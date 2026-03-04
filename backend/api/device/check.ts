import crypto from "node:crypto";
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabase } from "../../lib/supabase.js";

type DeviceRuleRow = {
  device_token: string;
  package_name: string;
  allowed: boolean;
  expires_at: number | null;
};

function badRequest(res: VercelResponse, message: string) {
  return res.status(400).json({ error: message });
}

function signHex(payload: string, key: string): string {
  return crypto.createHmac("sha256", key).update(payload, "utf8").digest("hex");
}

function buildPayload(deviceToken: string, packageName: string, allowed: boolean, expiresAt: number): string {
  const allowedBit = allowed ? "1" : "0";
  return `${deviceToken}|${packageName}|${allowedBit}|${expiresAt}`;
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
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
    body = typeof req.body === "string" ? JSON.parse(req.body || "{}") : req.body ?? {};
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
          note: "auto-created by check api"
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
  const expiresAtRaw = rule.expires_at ?? nowMs + defaultTtlMs;
  const expiresAt = Number.isFinite(expiresAtRaw) ? Number(expiresAtRaw) : nowMs + defaultTtlMs;

  const payload = buildPayload(deviceToken, packageName, allowed, expiresAt);
  const signature = signHex(payload, signingKey);

  return res.status(200).json({
    allowed,
    expires_at: expiresAt,
    signature
  });
}
