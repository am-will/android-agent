export interface AgentStatusSink {
  info(text: string): void;
  working(text: string): void;
  tool(text: string): void;
  done(text: string): void;
  error(text: string): void;
}

export interface AgentRunResult {
  threadId?: string;
  turnId?: string;
  finalMessage?: string;
  error?: string;
}

export interface AgentClient {
  submitUserRequest(
    text: string,
    sink: AgentStatusSink,
    options?: { systemPrompt?: string; model?: string; reasoningEffort?: string }
  ): Promise<AgentRunResult>;
  interrupt?(reason?: string): Promise<void>;
  close(): Promise<void>;
}
