import type { Dispatcher } from "../dispatcher/dispatcher.js";
import type { RealtimeOutboundMessage, RealtimeToolCallMessage, RealtimeToolResultMessage } from "../protocol/messages.js";
import type { AuditLog } from "./AuditLog.js";

interface RealtimeTaskManagerOptions {
  dispatcher: Pick<Dispatcher, "handleUserRequest" | "stopActiveTurn" | "steerActiveTurn">;
  sendRealtime: (deviceId: string, message: RealtimeOutboundMessage) => void;
  webSearch?: {
    search(options: { deviceId: string; query: string; apiKey?: string }): Promise<string>;
  };
  getRealtimeApiKey?: (deviceId: string) => string | undefined;
  audit?: AuditLog;
  maxQueueSize?: number;
  taskTimeoutMs?: number;
}

interface QueuedTask {
  deviceId: string;
  callId: string;
  instruction: string;
  urgency: "normal" | "interrupt";
}

interface DeviceTaskState {
  active?: QueuedTask;
  queue: QueuedTask[];
  completed: number;
  failed: number;
  completedResults: Map<string, RealtimeToolResultMessage>;
}

const DEFAULT_MAX_QUEUE_SIZE = 3;
const DEFAULT_TASK_TIMEOUT_MS = 120_000;
const MAX_INSTRUCTION_LENGTH = 4_000;
const MAX_WEB_SEARCH_QUERY_LENGTH = 1_000;

export class RealtimeTaskManager {
  private readonly states = new Map<string, DeviceTaskState>();
  private readonly maxQueueSize: number;
  private readonly taskTimeoutMs: number;

  constructor(private readonly options: RealtimeTaskManagerOptions) {
    this.maxQueueSize = options.maxQueueSize ?? DEFAULT_MAX_QUEUE_SIZE;
    this.taskTimeoutMs = options.taskTimeoutMs ?? DEFAULT_TASK_TIMEOUT_MS;
  }

  async handleToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const duplicate = this.findAcceptedCall(message.deviceId, message.callId);
    if (duplicate === "active" || duplicate === "queued") {
      this.sendStatus(message.deviceId);
      return;
    }
    if (duplicate && typeof duplicate !== "string") {
      this.options.sendRealtime(message.deviceId, duplicate);
      return;
    }

    if (message.name === "stop_phone_task") {
      await this.handleStopToolCall(message);
      return;
    }

    if (message.name === "steer_phone_task") {
      await this.handleSteerToolCall(message);
      return;
    }

    if (message.name === "web_search") {
      await this.handleWebSearchToolCall(message);
      return;
    }

    const validated = this.validate(message);
    if (!validated.ok) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: validated.error
      });
      return;
    }

    const task: QueuedTask = {
      deviceId: message.deviceId,
      callId: message.callId,
      instruction: validated.instruction,
      urgency: validated.urgency
    };

    const state = this.stateFor(message.deviceId);
    if (task.urgency === "interrupt") {
      await this.interruptActiveTask(state, task);
      state.queue.unshift(task);
      this.sendStatus(message.deviceId);
      this.processNext(message.deviceId);
      return;
    }

    if (state.active) {
      if (state.queue.length >= this.maxQueueSize) {
        this.sendResult(message.deviceId, {
          callId: message.callId,
          ok: false,
          status: "failed",
          error: `Realtime phone task queue is full (${this.maxQueueSize}).`
        });
        return;
      }
      state.queue.push(task);
      this.options.audit?.record("realtime_task_queued", message.deviceId, {
        callId: message.callId,
        queued: state.queue.length
      });
      this.sendStatus(message.deviceId);
      return;
    }

    state.queue.push(task);
    this.sendStatus(message.deviceId);
    this.processNext(message.deviceId);
  }

  private async handleStopToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const reason = typeof message.arguments.reason === "string" && message.arguments.reason.trim()
      ? message.arguments.reason.trim()
      : "Stopped by realtime voice";
    await this.cancelDevice(message.deviceId, reason);
    this.sendResult(message.deviceId, {
      callId: message.callId,
      ok: true,
      status: "completed",
      output: "Stopped the active phone task and cleared queued realtime phone tasks."
    });
  }

  private async handleSteerToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const guidance = typeof message.arguments.guidance === "string"
      ? message.arguments.guidance.trim()
      : "";
    if (!guidance) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: "steer_phone_task requires non-empty guidance."
      });
      return;
    }

    try {
      await this.options.dispatcher.steerActiveTurn(message.deviceId, guidance);
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: true,
        status: "completed",
        output: "Steered the active phone task."
      });
      this.sendStatus(message.deviceId);
    } catch (error) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  private async handleWebSearchToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const query = typeof message.arguments.query === "string"
      ? message.arguments.query.trim()
      : "";
    if (!query) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: "web_search requires a non-empty query."
      });
      return;
    }
    if (query.length > MAX_WEB_SEARCH_QUERY_LENGTH) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: `web_search query is too long (${query.length}/${MAX_WEB_SEARCH_QUERY_LENGTH}).`
      });
      return;
    }
    if (!this.options.webSearch) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: "Realtime web search is not configured."
      });
      return;
    }

    try {
      const output = await this.options.webSearch.search({
        deviceId: message.deviceId,
        query,
        apiKey: this.options.getRealtimeApiKey?.(message.deviceId)
      });
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: true,
        status: "completed",
        output
      });
    } catch (error) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  async cancelDevice(deviceId: string, reason = "Realtime phone task cancelled"): Promise<void> {
    const state = this.states.get(deviceId);
    if (!state?.active && (!state || state.queue.length === 0)) {
      await this.options.dispatcher.stopActiveTurn(deviceId, reason);
      return;
    }

    for (const task of state.queue.splice(0)) {
      this.sendResult(deviceId, {
        callId: task.callId,
        ok: false,
        status: "cancelled",
        error: reason
      });
    }

    const active = state.active;
    if (active) {
      state.active = undefined;
      await this.options.dispatcher.stopActiveTurn(deviceId, reason);
      this.sendResult(deviceId, {
        callId: active.callId,
        ok: false,
        status: "cancelled",
        error: reason
      });
    }
    this.sendStatus(deviceId);
  }

  failDevice(deviceId: string, reason: string): void {
    const state = this.states.get(deviceId);
    if (!state) {
      return;
    }
    const tasks = [...(state.active ? [state.active] : []), ...state.queue];
    state.active = undefined;
    state.queue = [];
    for (const task of tasks) {
      this.options.audit?.record("realtime_task_failed", deviceId, {
        callId: task.callId,
        reason
      });
    }
    this.states.delete(deviceId);
  }

  private async interruptActiveTask(state: DeviceTaskState, nextTask: QueuedTask): Promise<void> {
    const active = state.active;
    if (!active) {
      return;
    }
    state.active = undefined;
    await this.options.dispatcher.stopActiveTurn(nextTask.deviceId, "Interrupted by newer realtime phone task");
    this.sendResult(nextTask.deviceId, {
      callId: active.callId,
      ok: false,
      status: "cancelled",
      error: "Interrupted by newer realtime phone task."
    });
  }

  private processNext(deviceId: string): void {
    const state = this.stateFor(deviceId);
    if (state.active) {
      return;
    }
    const task = state.queue.shift();
    if (!task) {
      this.sendStatus(deviceId);
      return;
    }
    state.active = task;
    this.sendStatus(deviceId);
    void this.runTask(task);
  }

  private async runTask(task: QueuedTask): Promise<void> {
    const state = this.stateFor(task.deviceId);
    this.options.audit?.record("realtime_task_started", task.deviceId, {
      callId: task.callId,
      instruction: task.instruction
    });

    try {
      const result = await this.withTimeout(
        this.options.dispatcher.handleUserRequest({
          type: "user_request",
          deviceId: task.deviceId,
          inputType: "text",
          text: task.instruction
        }),
        this.taskTimeoutMs
      );
      const error = result.error?.trim();
      this.sendResult(task.deviceId, {
        callId: task.callId,
        ok: !error,
        status: error ? "failed" : "completed",
        output: result.finalMessage?.trim() || (error ? undefined : "Phone task completed."),
        error
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (message === "realtime task timeout") {
        await this.options.dispatcher.stopActiveTurn(task.deviceId, `Realtime phone task timed out after ${this.taskTimeoutMs}ms`);
        this.sendResult(task.deviceId, {
          callId: task.callId,
          ok: false,
          status: "timeout",
          error: `Phone task timed out after ${Math.round(this.taskTimeoutMs / 1000)} seconds.`
        });
      } else {
        this.sendResult(task.deviceId, {
          callId: task.callId,
          ok: false,
          status: "failed",
          error: message
        });
      }
    } finally {
      if (state.active?.callId === task.callId) {
        state.active = undefined;
      }
      this.processNext(task.deviceId);
    }
  }

  private validate(message: RealtimeToolCallMessage): { ok: true; instruction: string; urgency: "normal" | "interrupt" } | { ok: false; error: string } {
    if (message.name !== "run_phone_task") {
      return { ok: false, error: `Unsupported realtime tool ${message.name}.` };
    }

    const instruction = typeof message.arguments.instruction === "string"
      ? message.arguments.instruction.trim()
      : "";
    if (!instruction) {
      return { ok: false, error: "run_phone_task requires a non-empty instruction." };
    }
    if (instruction.length > MAX_INSTRUCTION_LENGTH) {
      return { ok: false, error: `run_phone_task instruction is too long (${instruction.length}/${MAX_INSTRUCTION_LENGTH}).` };
    }

    const urgency = message.arguments.urgency === "interrupt" ? "interrupt" : "normal";
    return { ok: true, instruction, urgency };
  }

  private findAcceptedCall(deviceId: string, callId: string): "active" | "queued" | RealtimeToolResultMessage | undefined {
    const state = this.states.get(deviceId);
    if (!state) {
      return undefined;
    }
    if (state.active?.callId === callId) {
      return "active";
    }
    if (state.queue.some((task) => task.callId === callId)) {
      return "queued";
    }
    return state.completedResults.get(callId);
  }

  private sendResult(deviceId: string, result: Omit<RealtimeToolResultMessage, "type" | "deviceId">): void {
    const state = this.stateFor(deviceId);
    if (result.ok) {
      state.completed += 1;
    } else {
      state.failed += 1;
    }
    const message: RealtimeToolResultMessage = {
      type: "realtime.tool_result",
      deviceId,
      ...result
    };
    state.completedResults.set(result.callId, message);
    this.options.audit?.record("realtime_task_result", deviceId, message);
    this.options.sendRealtime(deviceId, message);
    this.sendStatus(deviceId);
  }

  private sendStatus(deviceId: string): void {
    const state = this.stateFor(deviceId);
    this.options.sendRealtime(deviceId, {
      type: "realtime.task_status",
      deviceId,
      running: Boolean(state.active),
      queued: state.queue.length,
      currentTask: state.active?.instruction ?? null,
      completed: state.completed,
      failed: state.failed
    });
  }

  private stateFor(deviceId: string): DeviceTaskState {
    let state = this.states.get(deviceId);
    if (!state) {
      state = {
        queue: [],
        completed: 0,
        failed: 0,
        completedResults: new Map()
      };
      this.states.set(deviceId, state);
    }
    return state;
  }

  private async withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        promise,
        new Promise<T>((_, reject) => {
          timer = setTimeout(() => reject(new Error("realtime task timeout")), timeoutMs);
        })
      ]);
    } finally {
      if (timer) {
        clearTimeout(timer);
      }
    }
  }
}
