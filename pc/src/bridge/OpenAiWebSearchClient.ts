import type { BridgeConfig } from "./config.js";
import type { PhoneLocation } from "../protocol/messages.js";
import { formatLocationContext } from "./OpenAiRealtimeClient.js";

export interface OpenAiWebSearchOptions {
  query: string;
  deviceId: string;
  apiKey?: string;
  location?: PhoneLocation;
}

interface ResponsesApiTextContent {
  type?: string;
  text?: string;
}

interface ResponsesApiOutputItem {
  type?: string;
  role?: string;
  content?: ResponsesApiTextContent[];
}

interface ResponsesApiBody {
  output_text?: string;
  output?: ResponsesApiOutputItem[];
  error?: {
    message?: string;
  };
}

export class OpenAiWebSearchClient {
  constructor(private readonly config: BridgeConfig) {}

  async search(options: OpenAiWebSearchOptions): Promise<string> {
    const apiKey = options.apiKey?.trim() || this.config.openAiApiKey?.trim();
    if (!apiKey) {
      throw new Error("OpenAI API key is required for realtime web search. Set it in the Android app settings or OPENAI_API_KEY on the PC bridge.");
    }

    const locationContext = formatLocationContext(options.location);
    const response = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
        "OpenAI-Safety-Identifier": options.deviceId
      },
      body: JSON.stringify({
        model: this.config.openAiWebSearchModel,
        tools: [{ type: "web_search", search_context_size: "low" }],
        tool_choice: "auto",
        max_output_tokens: 800,
        input: [
          {
            role: "system",
            content: [
              "Answer the user's web lookup concisely. Include source URLs when available.",
              locationContext
            ].filter(Boolean).join("\n")
          },
          {
            role: "user",
            content: options.query
          }
        ]
      })
    });

    const bodyText = await response.text();
    let body: ResponsesApiBody | undefined;
    try {
      body = JSON.parse(bodyText) as ResponsesApiBody;
    } catch {
      body = undefined;
    }

    if (!response.ok) {
      throw new Error(`OpenAI web search failed: ${response.status} ${response.statusText}: ${body?.error?.message ?? bodyText}`);
    }

    const outputText = body?.output_text?.trim();
    if (outputText) {
      return outputText;
    }

    const messageText = body?.output
      ?.filter((item) => item.type === "message" && item.role === "assistant")
      .flatMap((item) => item.content ?? [])
      .map((content) => content.text)
      .filter((text): text is string => Boolean(text?.trim()))
      .join("\n")
      .trim();

    if (messageText) {
      return messageText;
    }

    throw new Error("OpenAI web search returned no answer text.");
  }
}
