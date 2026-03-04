import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabase } from "../../lib/supabase.js";

function unauthorized(res: VercelResponse) {
  return res.status(401).json({ error: "unauthorized" });
}

function requireAdmin(req: VercelRequest, res: VercelResponse): boolean {
  const expected = process.env.ADMIN_TOKEN;
  if (!expected) {
    res.status(500).json({ error: "ADMIN_TOKEN not configured" });
    return false;
  }

  const token = String(req.headers["x-admin-token"] ?? "");
  if (!token || token !== expected) {
    unauthorized(res);
    return false;
  }
  return true;
}

function parseJsonBody(req: VercelRequest): any {
  if (typeof req.body === "string") return JSON.parse(req.body || "{}");
  return req.body ?? {};
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (!requireAdmin(req, res)) return;

  if (req.method === "GET") {
    const limit = Math.min(Number(req.query.limit ?? 100), 500);
    const deviceToken = String(req.query.device_token ?? "").trim();
    const packageName = String(req.query.package_name ?? "").trim();

    let query = supabase
      .from("device_rules")
      .select("id, device_token, package_name, allowed, expires_at, note, updated_at, created_at")
      .order("updated_at", { ascending: false })
      .limit(limit);

    if (deviceToken) query = query.eq("device_token", deviceToken);
    if (packageName) query = query.eq("package_name", packageName);

    const { data, error } = await query;
    if (error) return res.status(500).json({ error: "list failed" });
    return res.status(200).json({ items: data ?? [] });
  }

  if (req.method === "POST") {
    let body: any;
    try {
      body = parseJsonBody(req);
    } catch {
      return res.status(400).json({ error: "invalid json body" });
    }
    const deviceToken = String(body.device_token ?? "").trim();
    const packageName = String(body.package_name ?? "").trim();
    const allowed = Boolean(body.allowed);
    const note = String(body.note ?? "").trim() || null;
    const expiresAtValue = body.expires_at;
    const expiresAt =
      expiresAtValue === null || expiresAtValue === undefined || expiresAtValue === ""
        ? null
        : Number(expiresAtValue);

    if (!deviceToken || !packageName) {
      return res.status(400).json({ error: "device_token and package_name are required" });
    }
    if (expiresAt !== null && !Number.isFinite(expiresAt)) {
      return res.status(400).json({ error: "expires_at must be number or null" });
    }

    const { data, error } = await supabase
      .from("device_rules")
      .upsert(
        {
          device_token: deviceToken,
          package_name: packageName,
          allowed,
          expires_at: expiresAt,
          note
        },
        { onConflict: "device_token,package_name" }
      )
      .select("id, device_token, package_name, allowed, expires_at, note, updated_at, created_at")
      .single();

    if (error) return res.status(500).json({ error: "upsert failed" });
    return res.status(200).json({ item: data });
  }

  if (req.method === "DELETE") {
    let body: any;
    try {
      body = parseJsonBody(req);
    } catch {
      return res.status(400).json({ error: "invalid json body" });
    }
    const deviceToken = String(body.device_token ?? "").trim();
    const packageName = String(body.package_name ?? "").trim();
    if (!deviceToken || !packageName) {
      return res.status(400).json({ error: "device_token and package_name are required" });
    }

    const { error } = await supabase
      .from("device_rules")
      .delete()
      .eq("device_token", deviceToken)
      .eq("package_name", packageName);

    if (error) return res.status(500).json({ error: "delete failed" });
    return res.status(200).json({ ok: true });
  }

  return res.status(405).json({ error: "Method not allowed" });
}
