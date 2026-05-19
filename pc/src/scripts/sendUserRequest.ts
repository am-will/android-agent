import { bridgeAuthHeaders } from "../mcp/phoneToolClient.js";

const text = process.argv.slice(2).join(" ").trim();

if (!text) {
  throw new Error("Usage: npm run demo:agent -- \"Open Settings\"");
}

const bridgeUrl = process.env.PHONE_AGENT_BRIDGE_URL ?? "http://127.0.0.1:8788";
const deviceId = process.env.PHONE_AGENT_DEFAULT_DEVICE ?? "openclaw-agent";

const response = await fetch(`${bridgeUrl}/api/user_request`, {
  method: "POST",
  headers: { "content-type": "application/json", ...bridgeAuthHeaders() },
  body: JSON.stringify({ deviceId, text })
});

const body = await response.json().catch(() => ({}));
if (!response.ok) {
  throw new Error(typeof body.error === "string" ? body.error : `Bridge returned HTTP ${response.status}`);
}

console.log(JSON.stringify(body, null, 2));
