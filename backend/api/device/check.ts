import crypto from "node:crypto";
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabase } from "../../lib/supabase.js";

type DeviceRuleRow = {
  device_token: string;
  package_name: string;
  allowed: boolean;
  expires_at: number | null;
  updated_at?: string;
};

function badRequest(res: VercelResponse, message: string) {
  return res.status(400).json({ error: message });
}

function signHex(payload: string, key: string): string {
  return crypto.createHmac("sha256", key).update(payload, "utf8").digest("hex");
}

function buildPayload(
  deviceToken: string,
  packageName: string,
  allowed: boolean,
  expiresAt: number
): string {
  const allowedBit = allowed ? "1" : "0";
  return `${deviceToken}|${packageName}|${allowedBit}|${expiresAt}`;
}

/**
 * Resolves expires_at to a guaranteed-finite Unix-ms number.
 * DB column is stored as bigint (Unix ms). Supabase JS client may return it
 * as a number or string depending on the value size.
 */
function resolveExpiresAt(raw: unknown, fallback: number): number {
  if (raw === null || raw === undefined) return fallback;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  // ── Debug endpoint ────────────────────────────────────────────────────────
  // GET /api/device/check?debug=1&device_token=...&package_name=...
  if (req.method === "GET") {
    const adminToken = process.env.ADMIN_TOKEN;
    const reqToken = String(req.headers["x-admin-token"] ?? "").trim();
    if (!adminToken || reqToken !== adminToken) {
      return res.status(401).json({ error: "unauthorized: x-admin-token required for debug" });
    }

    const deviceToken = String(req.query.device_token ?? "").trim();
    const packageName = String(req.query.package_name ?? "").trim();
    if (!deviceToken || !packageName) {
      return badRequest(res, "device_token and package_name query params required");
    }

    const { data, error } = await supabase
      .from("device_rules")
      .select("device_token, package_name, allowed, expires_at, updated_at, note")
      .eq("device_token", deviceToken)
      .eq("package_name", packageName)
      .maybeSingle<DeviceRuleRow & { note?: string }>();

    if (error) return res.status(500).json({ error: "db query failed", detail: error.message });

    const nowMs = Date.now();
    const defaultTtlMs = Number(process.env.DEFAULT_TTL_MS ?? "900000");

    if (!data) {
      return res.status(200).json({
        found: false,
        device_token: deviceToken,
        package_name: packageName,
        decision_source: "default",
        would_allow: process.env.DEFAULT_ALLOW === "true",
        now_ms: nowMs,
      });
    }

    const expiresAt = resolveExpiresAt(data.expires_at, nowMs + defaultTtlMs);
    const isExpired = nowMs >= expiresAt;

    return res.status(200).json({
      found: true,
      device_token: data.device_token,
      package_name: data.package_name,
      allowed: data.allowed,
      expires_at_ms: expiresAt,
      expires_at_iso: new Date(expiresAt).toISOString(),
      is_expired: isExpired,
      updated_at: data.updated_at,
      note: (data as any).note ?? null,
      decision_source: "db",
      now_ms: nowMs,
    });
  }

  // ── Check endpoint (POST) ─────────────────────────────────────────────────
  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method not allowed" });
  }

  const signingKey = process.env.DEVICE_SIGNING_KEY;
  if (!signingKey) {
    return res.status(500).json({ error: "DEVICE_SIGNING_KEY not configured" });
  }

  const defaultTtlMs = Number(process.env.DEFAULT_TTL_MS ?? "900000");
  const nowMs = Date.now();

  let body: any = {};
  try {
    body =
      typeof req.body === "string" ? JSON.parse(req.body || "{}") : req.body ?? {};
  } catch {
    return badRequest(res, "invalid json body");
  }

  const deviceToken = String(body.device_token ?? "").trim();
  const packageName = String(body.package_name ?? "").trim();

  if (!deviceToken) return badRequest(res, "device_token is required");
  if (!packageName) return badRequest(res, "package_name is required");
  if (deviceToken.length > 128 || packageName.length > 200) {
    return badRequest(res, "invalid parameter length");
  }

  const { data, error } = await supabase
    .from("device_rules")
    .select("device_token, package_name, allowed, expires_at")
    .eq("device_token", deviceToken)
    .eq("package_name", packageName)
    .maybeSingle<DeviceRuleRow>();

  if (error) {
    return res.status(500).json({ error: "db query failed" });
  }

  let rule = data;
  if (!rule) {
    const { data: inserted, error: insertError } = await supabase
      .from("device_rules")
      .upsert(
        {
          device_token: deviceToken,
          package_name: packageName,
          allowed: false,
          note: "auto-created by check api",
        },
        { onConflict: "device_token,package_name" }
      )
      .select("device_token, package_name, allowed, expires_at")
      .single<DeviceRuleRow>();

    if (insertError) {
      return res.status(500).json({ error: "auto insert failed" });
    }
    rule = inserted;
  }

  const allowed = rule.allowed;
  // Always resolve to a stable finite ms value; DB stores bigint epoch ms.
  const expiresAt = resolveExpiresAt(rule.expires_at, nowMs + defaultTtlMs);

  const payload = buildPayload(deviceToken, packageName, allowed, expiresAt);
  const signature = signHex(payload, signingKey);

  return res.status(200).json({
    allowed,
    expires_at: expiresAt,
    signature,
  });
}
