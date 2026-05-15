import type { AgentClient, AgentRunResult, AgentStatusSink } from "./AgentClient.js";

export class FallbackAgentClient implements AgentClient {
  async submitUserRequest(text: string, sink: AgentStatusSink): Promise<AgentRunResult> {
    const error =
      `Codex app-server is unavailable. Request queued for manual fallback: ${text}. ` +
      "Start `codex app-server --listen stdio://` support or wire a CLI/custom-agent adapter.";
    sink.error(error);
    return { error };
  }

  async interrupt(): Promise<void> {
    return;
  }

  async close(): Promise<void> {
    return;
  }
}
