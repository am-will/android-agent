import type { BridgeConfig } from "./config.js";

export interface OpenAiRealtimeStartOptions {
  deviceId: string;
  sdp: string;
  systemPrompt?: string;
  apiKey?: string;
}

export interface OpenAiRealtimeSession {
  deviceId: string;
  callId?: string;
}

const VOICE_PROMPT = `
You are the Android agent in a live voice conversation. Keep responses short and conversational.
When action on the phone is needed, tell the user you will do it after they hang up.
Confirm only when an action is risky or irreversible.
`.trim();

function callIdFromLocation(location: string | null): string | undefined {
  if (!location) {
    return undefined;
  }
  return location.split("/").filter(Boolean).at(-1);
}

export class OpenAiRealtimeClient {
  constructor(private readonly config: BridgeConfig) {}

  async start(options: OpenAiRealtimeStartOptions): Promise<{ answerSdp: string; session: OpenAiRealtimeSession }> {
    const apiKey = options.apiKey?.trim() || this.config.openAiApiKey?.trim();
    if (!apiKey) {
      throw new Error("OpenAI API key is required for realtime voice. Set it in the Android app settings or OPENAI_API_KEY on the PC bridge.");
    }

    const sessionConfig = {
      type: "realtime",
      model: this.config.openAiRealtimeModel,
      instructions: [options.systemPrompt?.trim(), VOICE_PROMPT].filter(Boolean).join("\n\n"),
      audio: {
        output: {
          voice: this.config.openAiRealtimeVoice
        }
      }
    };

    const formData = new FormData();
    formData.set("sdp", options.sdp);
    formData.set("session", JSON.stringify(sessionConfig));

    const response = await fetch("https://api.openai.com/v1/realtime/calls", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "OpenAI-Safety-Identifier": options.deviceId
      },
      body: formData
    });

    const body = await response.text();
    if (!response.ok) {
      throw new Error(`OpenAI realtime call failed: ${response.status} ${response.statusText}: ${body}`);
    }

    return {
      answerSdp: body,
      session: {
        deviceId: options.deviceId,
        callId: callIdFromLocation(response.headers.get("location"))
      }
    };
  }

  async stop(session: OpenAiRealtimeSession): Promise<void> {
    const apiKey = this.config.openAiApiKey?.trim();
    if (!apiKey || !session.callId) {
      return;
    }

    await fetch(`https://api.openai.com/v1/realtime/calls/${encodeURIComponent(session.callId)}`, {
      method: "DELETE",
      headers: {
        Authorization: `Bearer ${apiKey}`
      }
    }).catch(() => undefined);
  }
}
