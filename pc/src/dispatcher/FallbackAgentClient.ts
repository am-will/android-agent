import type { AgentClient, AgentRunResult, AgentStatusSink } from "./AgentClient.js";

export class FallbackAgentClient implements AgentClient {
  async submitUserRequest(text: string, sink: AgentStatusSink): Promise<AgentRunResult> {
    const error =
      `No desktop agent adapter is available. Request queued for manual fallback: ${text}. ` +
      "Start the Open Claw adapter, choose the legacy Codex adapter, or wire a custom adapter.";
    sink.error(error);
    return { error };
  }

  async interrupt(): Promise<void> {
    return;
  }

  async steer(): Promise<void> {
    return;
  }

  async close(): Promise<void> {
    return;
  }
}
