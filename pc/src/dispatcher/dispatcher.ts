import type { PhoneHub } from "../bridge/PhoneHub.js";
import type { AuditLog } from "../bridge/AuditLog.js";
import type { UserRequestMessage } from "../protocol/messages.js";
import type { AgentClient, AgentRunResult, AgentStatusSink } from "./AgentClient.js";
import { CodexAppServerClient } from "./CodexAppServerClient.js";
import { FallbackAgentClient } from "./FallbackAgentClient.js";

export class Dispatcher {
  private readonly client: AgentClient;

  constructor(
    private readonly hub: PhoneHub,
    private readonly audit?: AuditLog
  ) {
    this.client = process.env.PHONE_AGENT_USE_FALLBACK === "1"
      ? new FallbackAgentClient()
      : new CodexAppServerClient(audit);
  }

  async handleUserRequest(request: UserRequestMessage): Promise<AgentRunResult> {
    this.audit?.startTurn(request.deviceId, request.text);
    const sink = this.statusSink(request.deviceId);
    try {
      sink.working(`Received: ${request.text}`);
      const result = await this.client.submitUserRequest(request.text, sink, {
        systemPrompt: request.systemPrompt,
        model: request.model,
        reasoningEffort: request.reasoningEffort
      });
      this.audit?.endTurn(request.deviceId, { result });
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      sink.error(message);
      const fallback = await new FallbackAgentClient().submitUserRequest(request.text, sink);
      this.audit?.endTurn(request.deviceId, { error: message, fallback });
      return { ...fallback, error: message };
    }
  }

  async stopActiveTurn(deviceId: string, reason = "Stopped by user"): Promise<void> {
    const sink = this.statusSink(deviceId);
    this.audit?.record("turn_stop_requested", deviceId, { reason });
    sink.working("Stopping active Codex turn");
    if (this.client.interrupt) {
      await this.client.interrupt(reason);
      sink.done("Stopped active Codex turn");
      return;
    }
    await this.client.close();
    sink.done("Stopped agent client");
  }

  private statusSink(deviceId: string): AgentStatusSink {
    return {
      info: (text) => this.status(deviceId, "info", text),
      working: (text) => this.status(deviceId, "working", text),
      tool: (text) => this.status(deviceId, "tool", text),
      done: (text) => this.status(deviceId, "done", text),
      error: (text) => this.status(deviceId, "error", text)
    };
  }

  private status(deviceId: string, status: "info" | "working" | "tool" | "done" | "error", text: string): void {
    console.log(`[${status}] ${deviceId}: ${text}`);
    this.audit?.record("agent_status", deviceId, { status, text });
    this.hub.sendStatus(deviceId, { deviceId, status, text });
  }
}
