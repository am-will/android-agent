export interface BridgeConfig {
  host: string;
  port: number;
  token: string;
  defaultDeviceId: string;
  bridgeUrl: string;
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
    openAiApiKey: process.env.OPENAI_API_KEY,
    openAiRealtimeModel: process.env.OPENAI_REALTIME_MODEL ?? "gpt-realtime-2",
    openAiRealtimeVoice: process.env.OPENAI_REALTIME_VOICE ?? "marin",
    openAiWebSearchModel: process.env.OPENAI_WEB_SEARCH_MODEL ?? "gpt-5.5"
  };
}
