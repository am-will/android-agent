import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface, type Interface } from "node:readline";
import type { AuditLog } from "../bridge/AuditLog.js";
import type { AgentClient, AgentRunResult, AgentStatusSink } from "./AgentClient.js";
import { buildPhoneAgentPrompt } from "./safetyPrompt.js";

interface JsonRpcRequest {
  id?: number;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: unknown;
}

interface PendingRpc {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
}

interface PendingTurn {
  threadId: string;
  turnId: string;
  finalMessage: string[];
  resolve: (value: AgentRunResult) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

function commandParts(command: string): string[] {
  return command.match(/(?:[^\s"]+|"[^"]*")+/g)?.map((part) => part.replace(/^"|"$/g, "")) ?? [];
}

function isBlockedFinalMessage(message: string): boolean {
  const normalized = message.trim().toLowerCase();
  return normalized.startsWith("blocked:");
}

function isCompleteFinalMessage(message: string): boolean {
  const normalized = message.trim().toLowerCase();
  return normalized.startsWith("task_complete:");
}

export class CodexAppServerClient implements AgentClient {
  private child?: ChildProcessWithoutNullStreams;
  private lines?: Interface;
  private nextId = 1;
  private initialized = false;
  private readonly pending = new Map<number, PendingRpc>();
  private pendingTurn?: PendingTurn;
  private activeSink?: AgentStatusSink;

  constructor(
    private readonly audit?: AuditLog,
    private readonly command = process.env.CODEX_APP_SERVER_COMMAND ?? "codex app-server --listen stdio://",
    private readonly cwd = process.env.CODEX_AGENT_CWD ?? process.cwd()
  ) {}

  async submitUserRequest(
    text: string,
    sink: AgentStatusSink,
    options: { systemPrompt?: string; model?: string; reasoningEffort?: string } = {}
  ): Promise<AgentRunResult> {
    this.activeSink = sink;
    await this.ensureStarted();
    const threadId = await this.startThread(options.model);
    this.audit?.record("codex_turn_starting", undefined, { threadId, text, model: options.model, reasoningEffort: options.reasoningEffort });
    sink.working("Sending request to Codex app-server");
    const result = await this.request("turn/start", {
      threadId,
      input: [{ type: "text", text: buildPhoneAgentPrompt(text, options.systemPrompt) }],
      cwd: this.cwd,
      approvalPolicy: "never",
      model: options.model,
      effort: options.reasoningEffort,
      personality: "pragmatic"
    });
    const turnId = (result as { turn?: { id?: string } }).turn?.id;
    if (!turnId) {
      throw new Error("Codex app-server turn/start returned no turn id");
    }
    this.audit?.record("codex_turn_started", undefined, { threadId, turnId });
    return await this.waitForTurn(threadId, turnId);
  }

  async close(): Promise<void> {
    this.lines?.close();
    this.child?.kill();
  }

  async interrupt(reason = "Stopped by user"): Promise<void> {
    const pendingTurn = this.pendingTurn;
    if (pendingTurn) {
      clearTimeout(pendingTurn.timer);
      this.pendingTurn = undefined;
      pendingTurn.resolve({
        threadId: pendingTurn.threadId,
        turnId: pendingTurn.turnId,
        finalMessage: `BLOCKED: ${reason}`,
        error: reason
      });
    }
    for (const pending of this.pending.values()) {
      pending.reject(new Error(reason));
    }
    this.pending.clear();
    this.initialized = false;
    this.lines?.close();
    this.lines = undefined;
    this.child?.kill();
    this.child = undefined;
  }

  private async ensureStarted(): Promise<void> {
    if (this.initialized) {
      return;
    }

    const [bin, ...args] = commandParts(this.command);
    if (!bin) {
      throw new Error("CODEX_APP_SERVER_COMMAND is empty");
    }

    this.child = spawn(bin, args, {
      cwd: this.cwd,
      env: process.env
    });

    this.child.stderr.on("data", (chunk) => {
      this.activeSink?.info(chunk.toString().trim());
    });
    this.child.on("exit", (code, signal) => {
      const error = new Error(`Codex app-server exited with code ${code ?? "null"} signal ${signal ?? "null"}`);
      for (const pending of this.pending.values()) {
        pending.reject(error);
      }
      this.pending.clear();
      this.initialized = false;
      this.pendingTurn?.reject(error);
      this.pendingTurn = undefined;
      this.activeSink?.error(error.message);
    });

    this.lines = createInterface({ input: this.child.stdout });
    this.lines.on("line", (line) => this.handleLine(line));

    await this.request("initialize", {
      clientInfo: {
        name: "android_phone_agent",
        title: "Android Phone Agent Dispatcher",
        version: "0.1.0"
      },
      capabilities: { experimentalApi: true }
    });
    this.notify("initialized", {});
    this.initialized = true;
  }

  private async startThread(model?: string): Promise<string> {
    const result = await this.request("thread/start", {
      model,
      cwd: this.cwd,
      approvalPolicy: "never",
      sandbox: "workspace-write",
      personality: "pragmatic",
      serviceName: "android_phone_agent"
    });
    const thread = (result as { thread?: { id?: string } }).thread;
    if (!thread?.id) {
      throw new Error("Codex app-server thread/start returned no thread id");
    }
    return thread.id;
  }

  private request(method: string, params?: unknown): Promise<unknown> {
    const id = this.nextId++;
    const payload: JsonRpcRequest = { id, method, params };
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.audit?.record("codex_rpc_request", undefined, { id, method, params });
      this.write(payload);
    });
  }

  private waitForTurn(threadId: string, turnId: string): Promise<AgentRunResult> {
    if (this.pendingTurn) {
      throw new Error("A Codex turn is already running for this dispatcher");
    }

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingTurn = undefined;
        reject(new Error(`Timed out waiting for Codex turn ${turnId} to complete`));
      }, Number.parseInt(process.env.CODEX_TURN_TIMEOUT_MS ?? "600000", 10));

      this.pendingTurn = {
        threadId,
        turnId,
        finalMessage: [],
        resolve,
        reject,
        timer
      };
    });
  }

  private notify(method: string, params?: unknown): void {
    this.write({ method, params });
  }

  private write(payload: JsonRpcRequest): void {
    if (!this.child) {
      throw new Error("Codex app-server process is not started");
    }
    this.child.stdin.write(`${JSON.stringify(payload)}\n`);
  }

  private handleLine(line: string): void {
    if (!line.trim()) {
      return;
    }
    let message: any;
    try {
      message = JSON.parse(line);
    } catch {
      this.activeSink?.info(line);
      return;
    }

    if (typeof message.id === "number" && this.pending.has(message.id)) {
      const pending = this.pending.get(message.id)!;
      this.pending.delete(message.id);
      if (message.error) {
        this.audit?.record("codex_rpc_response", undefined, { id: message.id, error: message.error });
        pending.reject(new Error(message.error.message ?? JSON.stringify(message.error)));
      } else {
        this.audit?.record("codex_rpc_response", undefined, { id: message.id, result: message.result });
        pending.resolve(message.result);
      }
      return;
    }

    if (typeof message.id === "number" && typeof message.method === "string") {
      this.handleServerRequest(message);
      return;
    }

    this.audit?.record("codex_app_server_message", undefined, message);
    this.handleNotification(message);
  }

  private handleServerRequest(message: { id: number; method: string; params?: any }): void {
    if (message.method === "mcpServer/elicitation/request") {
      const serverName = message.params?.serverName;
      const approvalKind = message.params?._meta?.codex_approval_kind;
      const toolDescription = message.params?._meta?.tool_description ?? "MCP tool";
      if (serverName === "android-phone" && approvalKind === "mcp_tool_call") {
        this.activeSink?.tool(`approved android-phone tool: ${toolDescription}`);
        this.write({
          id: message.id,
          result: { action: "accept", content: {} }
        });
        return;
      }
    }

    this.activeSink?.error(`Declined unsupported app-server request: ${message.method}`);
    this.write({
      id: message.id,
      result: { action: "decline", content: null }
    });
  }

  private handleNotification(message: { method?: string; params?: any }): void {
    const sink = this.activeSink;
    if (!sink || !message.method) {
      return;
    }

    if (message.method === "item/agentMessage/delta") {
      const text = message.params?.delta ?? message.params?.textDelta;
      if (typeof text === "string" && text.trim()) {
        this.pendingTurn?.finalMessage.push(text);
        sink.info(text);
      }
      return;
    }

    if (message.method === "turn/started") {
      sink.working("Codex started working");
      return;
    }

    if (message.method === "turn/completed") {
      const turnId = message.params?.turn?.id ?? message.params?.turnId;
      const pendingTurn = this.pendingTurn;
      if (pendingTurn && (!turnId || turnId === pendingTurn.turnId)) {
        clearTimeout(pendingTurn.timer);
        this.pendingTurn = undefined;
        const finalMessage = pendingTurn.finalMessage.join("").trim();
        const blocked = isBlockedFinalMessage(finalMessage);
        const complete = isCompleteFinalMessage(finalMessage);
        pendingTurn.resolve({
          threadId: pendingTurn.threadId,
          turnId: pendingTurn.turnId,
          finalMessage,
          error: blocked ? finalMessage : undefined
        });
        if (blocked) {
          sink.error(finalMessage || "Codex reported the phone task is blocked");
        } else if (complete) {
          sink.done(finalMessage);
        } else {
          sink.done(finalMessage ? `Codex stopped: ${finalMessage}` : "Codex turn completed without a final task status");
        }
        return;
      }
      sink.done("Codex turn completed");
      return;
    }

    if (message.method === "item/started" || message.method === "item/completed") {
      const item = message.params?.item;
      if (item?.type === "mcpToolCall") {
        sink.tool(`${item.server ?? "mcp"}.${item.tool ?? "tool"} ${item.status ?? ""}`.trim());
      }
    }
  }
}
