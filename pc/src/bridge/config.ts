export interface BridgeConfig {
  host: string;
  port: number;
  token: string;
  defaultDeviceId: string;
  bridgeUrl: string;
}

export function getBridgeConfig(): BridgeConfig {
  const port = Number.parseInt(process.env.PHONE_AGENT_PORT ?? "8787", 10);
  return {
    host: process.env.PHONE_AGENT_HOST ?? "0.0.0.0",
    port,
    token: process.env.PHONE_AGENT_TOKEN ?? "change-me",
    defaultDeviceId: process.env.PHONE_AGENT_DEFAULT_DEVICE ?? "pixel",
    bridgeUrl: process.env.PHONE_AGENT_BRIDGE_URL ?? `http://127.0.0.1:${port}`
  };
}
