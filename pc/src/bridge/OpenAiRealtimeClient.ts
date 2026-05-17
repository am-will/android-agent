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
When the user asks for an actionable phone task, briefly acknowledge that you will work on it and call the run_phone_task tool.
Do not claim a phone task is complete until tool output is returned.
Ask a short clarification question when the instruction is ambiguous.
Confirm only when an action is risky or irreversible, and never bypass Android or Codex safety confirmations.
`.trim();

const RUN_PHONE_TASK_TOOL = {
  type: "function",
  name: "run_phone_task",
  description: "Execute an actionable instruction on the connected Android phone using the phone automation agent.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      instruction: {
        type: "string",
        description: "The concise phone task to execute."
      },
      urgency: {
        type: "string",
        enum: ["normal", "interrupt"],
        description: "Use interrupt only when the user explicitly wants to stop the current phone task."
      }
    },
    required: ["instruction"]
  }
} as const;

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
      tools: [RUN_PHONE_TASK_TOOL],
      tool_choice: "auto",
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
