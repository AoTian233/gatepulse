/**
 * GET /api/device/debug?device_token=XXX&package_name=YYY
 * Header: x-admin-token: <ADMIN_TOKEN>
 *
 * Returns the current DB record + what decision the check endpoint would make.
 * Useful for diagnosing "allowed in DB but still blocked on device" without device logs.
 */
import crypto from "node:crypto";
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabase } from "../../lib/supabase.js";

function signHex(payload: string, key: string): string {
  return crypto.createHmac("sha256", key).update(payload, "utf8").digest("hex");
}

function buildPayload(
  deviceToken: string,
  packageName: string,
  allowed: boolean,
  expiresAt: number
): string {
  return `${deviceToken}|${packageName}|${allowed ? "1" : "0"}|${expiresAt}`;
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== "GET") {
    return res.status(405).json({ error: "GET only" });
  }

  const expectedToken = process.env.ADMIN_TOKEN;
  const provided = String(req.headers["x-admin-token"] ?? "").trim();
  if (!expectedToken || provided !== expectedToken) {
    return res.status(401).json({ error: "unauthorized" });
  }

  const deviceToken = String(req.query.device_token ?? "").trim();
  const packageName = String(req.query.package_name ?? "").trim();
  if (!deviceToken || !packageName) {
    return res.status(400).json({ error: "device_token and package_name required" });
  }

  const signingKey = process.env.DEVICE_SIGNING_KEY ?? "";
  const defaultTtlMs = Number(process.env.DEFAULT_TTL_MS ?? "900000");
  const nowMs = Date.now();

  const { data, error } = await supabase
    .from("device_rules")
    .select("device_token, package_name, allowed, expires_at, updated_at, note, created_at")
    .eq("device_token", deviceToken)
    .eq("package_name", packageName)
    .maybeSingle();

  if (error) {
    return res.status(500).json({ error: "db error", detail: error.message });
  }

  if (!data) {
    return res.status(200).json({
      found: false,
      device_token: deviceToken,
      package_name: packageName,
      decision_source: "not_in_db",
      would_be_inserted_as: { allowed: false },
      now_ms: nowMs,
      now_iso: new Date(nowMs).toISOString(),
    });
  }

  const expiresAtRaw = data.expires_at;
  const expiresAt =
    expiresAtRaw !== null && expiresAtRaw !== undefined && Number.isFinite(Number(expiresAtRaw)) && Number(expiresAtRaw) > 0
      ? Number(expiresAtRaw)
      : nowMs + defaultTtlMs;

  const isExpired = nowMs >= expiresAt;

  // Simulate what the check endpoint would sign and return
  const payload = buildPayload(deviceToken, packageName, data.allowed, expiresAt);
  const simulatedSignature = signingKey ? signHex(payload, signingKey) : "(DEVICE_SIGNING_KEY missing)";

  return res.status(200).json({
    found: true,
    decision_source: "db",
    db_record: {
      device_token: data.device_token,
      package_name: data.package_name,
      allowed: data.allowed,
      expires_at_ms: expiresAt,
      expires_at_iso: new Date(expiresAt).toISOString(),
      is_expired: isExpired,
      updated_at: data.updated_at,
      created_at: data.created_at,
      note: data.note ?? null,
    },
    simulated_check_response: {
      allowed: data.allowed,
      expires_at: expiresAt,
      signature: simulatedSignature,
      payload_signed: payload,
    },
    cache_behavior: {
      will_device_revalidate_on_next_launch: !data.allowed,
      reason: !data.allowed
        ? "denied cache is NEVER persisted by client — every launch re-verifies"
        : isExpired
        ? "cache expired — async refresh triggered after serving stale true"
        : "cache valid — no revalidation until expiry",
    },
    now_ms: nowMs,
    now_iso: new Date(nowMs).toISOString(),
  });
}
