import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export interface BridgeConfig {
  host: string;
  port: number;
  token: string;
  defaultDeviceId: string;
  bridgeUrl: string;
  openClawGatewayUrl: string;
  openClawGatewayToken?: string;
  openClawGatewayPassword?: string;
  openClawChatAgentId: string;
  openClawChatSessionKey: string;
  openAiApiKey?: string;
  openAiRealtimeModel: string;
  openAiRealtimeVoice: string;
  openAiWebSearchModel: string;
}

function readOpenClawConfig(): unknown {
  const path = process.env.OPENCLAW_CONFIG_PATH?.trim() || join(homedir(), ".openclaw", "openclaw.json");
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return undefined;
  }
}

function nestedString(value: unknown, path: string[]): string | undefined {
  let current = value;
  for (const segment of path) {
    if (!current || typeof current !== "object") {
      return undefined;
    }
    current = (current as Record<string, unknown>)[segment];
  }
  return typeof current === "string" && current.trim() ? current.trim() : undefined;
}

export function getBridgeConfig(): BridgeConfig {
  const port = Number.parseInt(process.env.PHONE_AGENT_PORT ?? "8788", 10);
  const openClawConfig = readOpenClawConfig();
  return {
    host: process.env.PHONE_AGENT_HOST ?? "0.0.0.0",
    port,
    token: process.env.PHONE_AGENT_TOKEN ?? "12345678",
    defaultDeviceId: process.env.PHONE_AGENT_DEFAULT_DEVICE ?? "openclaw-agent",
    bridgeUrl: process.env.PHONE_AGENT_BRIDGE_URL ?? `http://127.0.0.1:${port}`,
    openClawGatewayUrl: process.env.OPENCLAW_GATEWAY_URL ?? "ws://127.0.0.1:18789",
    openClawGatewayToken: process.env.OPENCLAW_GATEWAY_TOKEN ?? nestedString(openClawConfig, ["gateway", "auth", "token"]) ?? nestedString(openClawConfig, ["gateway", "remote", "token"]),
    openClawGatewayPassword: process.env.OPENCLAW_GATEWAY_PASSWORD ?? nestedString(openClawConfig, ["gateway", "auth", "password"]) ?? nestedString(openClawConfig, ["gateway", "remote", "password"]),
    openClawChatAgentId: process.env.OPENCLAW_CHAT_AGENT_ID ?? "main",
    openClawChatSessionKey: process.env.OPENCLAW_CHAT_SESSION_KEY ?? "agent:main:explicit:open-claw-agent",
    openAiApiKey: process.env.OPENAI_API_KEY,
    openAiRealtimeModel: process.env.OPENAI_REALTIME_MODEL ?? "gpt-realtime-2",
    openAiRealtimeVoice: process.env.OPENAI_REALTIME_VOICE ?? "marin",
    openAiWebSearchModel: process.env.OPENAI_WEB_SEARCH_MODEL ?? "gpt-5.5"
  };
}
