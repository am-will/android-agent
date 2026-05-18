import { join } from "node:path";
import type { PhoneCommand, PhoneCommandResult } from "../protocol/messages.js";

export class PhoneToolClient {
  constructor(private readonly bridgeUrl = process.env.PHONE_AGENT_BRIDGE_URL ?? "http://127.0.0.1:8788") {}

  async command(command: PhoneCommand, args: Record<string, unknown> = {}, timeoutMs = 30_000): Promise<PhoneCommandResult> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs + 5_000);
    const response = await fetch(`${this.bridgeUrl}/api/phone/default/command`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ command, args, timeoutMs }),
      signal: controller.signal
    }).finally(() => clearTimeout(timeout));
    try {
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        const error = typeof body.error === "string" ? body.error : `Bridge returned HTTP ${response.status}`;
        throw new Error(error);
      }
      return body as PhoneCommandResult;
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") {
        throw new Error(`Timed out waiting for bridge response to ${command}`);
      }
      throw error;
    }
  }
}

export function screenshotDirectory(): string {
  return process.env.PHONE_AGENT_SCREENSHOT_DIR ?? join(process.cwd(), "..", "captures");
}
