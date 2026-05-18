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

export function getBridgeConfig(): BridgeConfig {
  const port = Number.parseInt(process.env.PHONE_AGENT_PORT ?? "8788", 10);
  return {
    host: process.env.PHONE_AGENT_HOST ?? "0.0.0.0",
    port,
    token: process.env.PHONE_AGENT_TOKEN ?? "12345678",
    defaultDeviceId: process.env.PHONE_AGENT_DEFAULT_DEVICE ?? "openclaw-agent",
    bridgeUrl: process.env.PHONE_AGENT_BRIDGE_URL ?? `http://127.0.0.1:${port}`,
    openClawGatewayUrl: process.env.OPENCLAW_GATEWAY_URL ?? "ws://127.0.0.1:18789",
    openClawGatewayToken: process.env.OPENCLAW_GATEWAY_TOKEN,
    openClawGatewayPassword: process.env.OPENCLAW_GATEWAY_PASSWORD,
    openClawChatAgentId: process.env.OPENCLAW_CHAT_AGENT_ID ?? "main",
    openClawChatSessionKey: process.env.OPENCLAW_CHAT_SESSION_KEY ?? "agent:main:explicit:open-claw-agent",
    openAiApiKey: process.env.OPENAI_API_KEY,
    openAiRealtimeModel: process.env.OPENAI_REALTIME_MODEL ?? "gpt-realtime-2",
    openAiRealtimeVoice: process.env.OPENAI_REALTIME_VOICE ?? "marin",
    openAiWebSearchModel: process.env.OPENAI_WEB_SEARCH_MODEL ?? "gpt-5.5"
  };
}
