import { randomUUID } from "node:crypto";
import type { AgentRunResult, AgentTaskKind } from "../dispatcher/AgentClient.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import type {
  ChatControlCommandMessage,
  ChatHistoryMessage,
  ChatNewSessionMessage,
  ChatOpenMessage,
  ChatOutboundMessage,
  ChatSelectSessionMessage,
  ChatSendMessage,
  ChatSetModelMessage,
  ChatSetReasoningMessage,
  ChatStopMessage,
  UserRequestMessage
} from "../protocol/messages.js";
import type { AuditLog } from "./AuditLog.js";
import type { BridgeConfig } from "./config.js";
import { PhoneHub } from "./PhoneHub.js";
import {
  chatMessagesFromHistory,
  mapGatewayChatEvent,
  normalizeCommands,
  normalizeGatewayReasoningEvent,
  normalizeGatewayToolEvent,
  normalizeModels,
  normalizeReasoningOptions,
  normalizeSessions,
  normalizeTools,
  OpenClawGatewayChatClient,
  requestKeyFromSessionKey,
  usageFromSession
} from "./OpenClawGatewayChatClient.js";
import type { GatewayChatSendResult, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";

interface DeviceChatState {
  sessionKey: string;
  sessionId?: string | null;
  runId?: string | null;
  model?: string | null;
  reasoningEffort?: string | null;
  reasoningStream?: boolean | null;
  fastMode?: boolean | null;
  verboseLevel?: string | null;
  lastRealtimeRequestAt?: number | null;
}

interface GatewayChatClient {
  addEventListener(handler: GatewayEventHandler): () => void;
  history(sessionKey: string): Promise<unknown>;
  sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult>;
  abort(sessionKey: string, runId?: string): Promise<unknown>;
  listModels(): Promise<unknown>;
  listSessions(limit?: number): Promise<unknown>;
  createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown>;
  patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown>;
  listCommands(): Promise<unknown>;
  effectiveTools(sessionKey: string): Promise<unknown>;
  close(): void;
}

interface RunWaiter {
  deviceId: string;
  sessionKey: string;
  runId: string;
  resolve: (result: AgentRunResult) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

const REALTIME_CHAT_REUSE_WINDOW_MS = 15 * 60 * 1000;
const REALTIME_CHAT_RUN_TIMEOUT_MS = 10 * 60 * 1000;

export class OpenClawChatBridge {
  private readonly client: GatewayChatClient;
  private readonly devices = new Map<string, DeviceChatState>();
  private readonly runWaiters = new Map<string, RunWaiter>();

  constructor(
    private readonly config: BridgeConfig,
    private readonly hub: PhoneHub,
    private readonly dispatcher: Pick<Dispatcher, "handleUserRequest" | "stopActiveTurn">,
    private readonly audit?: AuditLog,
    client?: GatewayChatClient
  ) {
    this.client = client ?? new OpenClawGatewayChatClient(config);
    this.client.addEventListener((event) => {
      const eventName = event.event.toLowerCase();
      if (event.event === "chat") {
        this.handleGatewayChatEvent(event.payload);
      } else if (event.event === "agent") {
        this.handleGatewayAgentEvent(event.payload, event.event);
      } else if (eventName.includes("thinking") || eventName.includes("reasoning")) {
        this.handleGatewayReasoningEvent(event.payload, event.event);
      }
    });
  }

  async open(message: ChatOpenMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    if (message.sessionKey) {
      state.sessionKey = message.sessionKey;
    }
    this.sendState(message.deviceId, "Loading OpenClaw chat");
    await this.refreshDevice(message.deviceId);
  }

  async send(message: ChatSendMessage): Promise<void> {
    const text = message.text.trim();
    if (!text) {
      return;
    }
    const state = this.stateFor(message.deviceId);
    if (message.sessionKey) {
      state.sessionKey = message.sessionKey;
    }

    const idempotencyKey = message.idempotencyKey ?? randomUUID();
    if (isExplicitPhoneTask(text)) {
      await this.fallbackSend(message, idempotencyKey, "phone");
      return;
    }

    try {
      const result = await this.client.sendChat({
        sessionKey: state.sessionKey,
        sessionId: message.sessionId,
        message: text,
        thinking: state.reasoningEffort ?? undefined,
        idempotencyKey
      });
      state.sessionKey = result.sessionKey;
      state.runId = result.runId;
      this.audit?.record("openclaw_chat_send", message.deviceId, {
        sessionKey: result.sessionKey,
        runId: result.runId,
        length: text.length
      });
      this.sendState(message.deviceId, "OpenClaw is working");
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.sendChat(message.deviceId, {
        type: "chat.error",
        deviceId: message.deviceId,
        sessionKey: state.sessionKey,
        runId: idempotencyKey,
        message: errorMessage
      });
      await this.fallbackSend(message, idempotencyKey);
    }
  }

  async stop(message: ChatStopMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionKey = message.sessionKey ?? state.sessionKey;
    const runId = message.runId ?? state.runId ?? undefined;
    try {
      await this.client.abort(sessionKey, runId);
    } catch (error) {
      await this.dispatcher.stopActiveTurn(message.deviceId, message.reason ?? "Stopped from Android chat");
      this.sendChat(message.deviceId, {
        type: "chat.error",
        deviceId: message.deviceId,
        sessionKey,
        runId,
        message: error instanceof Error ? error.message : String(error)
      });
    } finally {
      state.runId = null;
      this.sendState(message.deviceId, "Stop requested");
    }
  }

  async handleRealtimeRequest(
    request: UserRequestMessage,
    options: { taskKind: AgentTaskKind; callId?: string } = { taskKind: "general" }
  ): Promise<AgentRunResult> {
    const text = request.text.trim();
    if (!text) {
      throw new Error("Realtime request text is required");
    }

    await this.ensureFreshRealtimeSession(request.deviceId);
    const state = this.stateFor(request.deviceId);
    const idempotencyKey = options.callId ? `realtime_${options.callId}` : `realtime_${randomUUID()}`;
    this.appendUserMessage(request.deviceId, text, `user_${idempotencyKey}`);

    try {
      const result = await this.client.sendChat({
        sessionKey: state.sessionKey,
        sessionId: state.sessionId ?? undefined,
        message: text,
        thinking: state.reasoningEffort ?? undefined,
        idempotencyKey
      });
      state.sessionKey = result.sessionKey;
      state.runId = result.runId;
      state.lastRealtimeRequestAt = Date.now();
      this.audit?.record("realtime_chat_send", request.deviceId, {
        sessionKey: result.sessionKey,
        runId: result.runId,
        taskKind: options.taskKind,
        length: text.length
      });
      this.sendState(request.deviceId, options.taskKind === "phone" ? "OpenClaw is working on phone task" : "OpenClaw is working");
      return await this.waitForRun(request.deviceId, state.sessionKey, result.runId);
    } catch (error) {
      this.sendChatError(request.deviceId, state.sessionKey, error);
      throw error;
    }
  }

  async steerRealtimeTurn(deviceId: string, guidance: string, options?: { taskKind: AgentTaskKind; callId: string }): Promise<void> {
    const text = guidance.trim();
    if (!text) {
      throw new Error("Realtime steering guidance is required");
    }

    const state = this.stateFor(deviceId);
    const idempotencyKey = options?.callId ? `realtime_steer_${options.callId}` : `realtime_steer_${randomUUID()}`;
    this.appendUserMessage(deviceId, text, `user_${idempotencyKey}`);
    const result = await this.client.sendChat({
      sessionKey: state.sessionKey,
      sessionId: state.sessionId ?? undefined,
      message: text,
      thinking: state.reasoningEffort ?? undefined,
      idempotencyKey
    });
    state.sessionKey = result.sessionKey;
    state.runId = state.runId ?? result.runId;
    state.lastRealtimeRequestAt = Date.now();
    this.audit?.record("realtime_chat_steer", deviceId, {
      sessionKey: result.sessionKey,
      runId: result.runId,
      length: text.length
    });
    this.sendState(deviceId, "Steered OpenClaw from realtime chat");
  }

  async stopRealtimeTurn(deviceId: string, reason = "Stopped by realtime chat"): Promise<void> {
    const state = this.stateFor(deviceId);
    const runId = state.runId ?? undefined;
    const text = reason.trim() || "Stopped by realtime chat";
    this.appendUserMessage(deviceId, text, `user_realtime_stop_${randomUUID()}`);
    state.lastRealtimeRequestAt = Date.now();
    await this.client.abort(state.sessionKey, runId);
    this.rejectWaitersForDevice(deviceId, new Error(text));
    state.runId = null;
    this.sendState(deviceId, "Stop requested");
  }

  async selectSession(message: ChatSelectSessionMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    state.sessionKey = message.sessionKey;
    state.runId = null;
    state.lastRealtimeRequestAt = null;
    this.sendState(message.deviceId, "Switched session");
    await this.refreshDevice(message.deviceId);
  }

  async newSession(message: ChatNewSessionMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const requestKey = `phone-${message.deviceId}-${randomUUID()}`;
    const created = await this.client.createSession({
      key: requestKey,
      label: message.label ?? "Open Claw Agent",
      model: message.model ?? state.model ?? undefined
    });
    const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
    const key = typeof record.key === "string" && record.key.trim() ? record.key.trim() : undefined;
    state.sessionKey = key ?? `agent:${this.config.openClawChatAgentId}:explicit:${requestKey}`;
    state.sessionId = typeof record.sessionId === "string" ? record.sessionId : null;
    state.runId = null;
    state.lastRealtimeRequestAt = null;
    this.sendState(message.deviceId, "Started a new chat");
    await this.refreshDevice(message.deviceId);
  }

  async setModel(message: ChatSetModelMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionKey = message.sessionKey ?? state.sessionKey;
    state.model = message.model;
    await this.sendSlashCommand(message.deviceId, `/model ${message.model}`, sessionKey, `Model: ${message.model}`);
  }

  async setReasoning(message: ChatSetReasoningMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const sessionKey = message.sessionKey ?? state.sessionKey;
    state.reasoningEffort = message.reasoningEffort;
    await this.sendSlashCommand(message.deviceId, `/think ${message.reasoningEffort}`, sessionKey, `Reasoning: ${message.reasoningEffort}`);
  }

  async controlCommand(message: ChatControlCommandMessage): Promise<void> {
    const state = this.stateFor(message.deviceId);
    const command = message.command.trim();
    if (!command) {
      return;
    }
    const normalized = command.startsWith("/") ? command.slice(1).trim() : command;
    const [name = "", ...parts] = normalized.split(/\s+/);
    const firstArg = parts[0];

    if (name === "new") {
      await this.newSession({
        type: "chat.new_session",
        deviceId: message.deviceId
      });
      return;
    }

    if (name === "status") {
      this.sendState(message.deviceId, "Refreshing status");
      await this.refreshDevice(message.deviceId);
      this.sendState(message.deviceId, "Status refreshed");
      return;
    }

    if (name === "fast") {
      const enabled = typeof message.args.enabled === "boolean"
        ? message.args.enabled
        : firstArg === "off"
          ? false
          : firstArg === "on"
            ? true
            : undefined;
      await this.sendSlashCommand(message.deviceId, `/fast ${enabled === false ? "off" : "on"}`, state.sessionKey, "Updating fast mode");
      return;
    }

    if (name === "verbose") {
      const level = typeof message.args.level === "string" && message.args.level.trim()
        ? message.args.level.trim()
        : firstArg && ["on", "off", "full"].includes(firstArg)
          ? firstArg
          : "on";
      await this.sendSlashCommand(message.deviceId, `/verbose ${level}`, state.sessionKey, "Updating verbosity");
      return;
    }

    if (name === "reasoning") {
      const level = typeof message.args.level === "string" && message.args.level.trim() === "stream"
        ? "stream"
        : firstArg === "stream"
          ? "stream"
          : "off";
      state.reasoningStream = level === "stream";
      await this.sendSlashCommand(
        message.deviceId,
        `/reasoning ${level}`,
        state.sessionKey,
        `Reasoning Stream: ${state.reasoningStream ? "On" : "Off"}`
      );
      return;
    }

    const slashText = command.startsWith("/") ? command : `/${command}`;
    await this.send({
      type: "chat.send",
      deviceId: message.deviceId,
      text: slashText,
      sessionKey: state.sessionKey
    });
  }

  close(): void {
    for (const [key, waiter] of this.runWaiters) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error("OpenClaw chat bridge closed"));
      this.runWaiters.delete(key);
    }
    this.client.close();
  }

  private stateFor(deviceId: string): DeviceChatState {
    const existing = this.devices.get(deviceId);
    if (existing) {
      return existing;
    }
    const created: DeviceChatState = {
      sessionKey: this.config.openClawChatSessionKey,
      runId: null,
      model: null,
      reasoningEffort: null,
      reasoningStream: null,
      fastMode: null,
      verboseLevel: null,
      lastRealtimeRequestAt: null
    };
    this.devices.set(deviceId, created);
    return created;
  }

  private async ensureFreshRealtimeSession(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const lastRealtimeRequestAt = state.lastRealtimeRequestAt ?? 0;
    if (lastRealtimeRequestAt > 0 && Date.now() - lastRealtimeRequestAt <= REALTIME_CHAT_REUSE_WINDOW_MS) {
      return;
    }

    const requestKey = `realtime-${deviceId}-${randomUUID()}`;
    const created = await this.client.createSession({
      key: requestKey,
      label: "Realtime voice",
      model: state.model ?? undefined
    });
    const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
    const key = typeof record.key === "string" && record.key.trim() ? record.key.trim() : undefined;
    state.sessionKey = key ?? `agent:${this.config.openClawChatAgentId}:explicit:${requestKey}`;
    state.sessionId = typeof record.sessionId === "string" ? record.sessionId : null;
    state.runId = null;
    this.sendState(deviceId, "Started a new realtime chat");
    await this.refreshDevice(deviceId);
  }

  private async refreshDevice(deviceId: string): Promise<void> {
    await this.ensureSession(deviceId);
    await Promise.allSettled([
      this.refreshMetadata(deviceId),
      this.sendHistory(deviceId),
      this.sendCommands(deviceId),
      this.sendTools(deviceId)
    ]);
  }

  private async refreshMetadata(deviceId: string): Promise<void> {
    await Promise.allSettled([
      this.sendModels(deviceId),
      this.sendSessions(deviceId)
    ]);
  }

  private async ensureSession(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    try {
      await this.client.history(state.sessionKey);
    } catch {
      const key = requestKeyFromSessionKey(state.sessionKey, this.config.openClawChatAgentId);
      const created = await this.client.createSession({ key, label: "Open Claw Agent" });
      const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
      if (typeof record.key === "string" && record.key.trim()) {
        state.sessionKey = record.key;
      }
    }
  }

  private async sendModels(deviceId: string): Promise<void> {
    const [modelsPayload, sessionsPayload] = await Promise.all([
      this.client.listModels(),
      this.client.listSessions(1).catch(() => undefined)
    ]);
    this.sendChat(deviceId, {
      type: "chat.models",
      deviceId,
      models: normalizeModels(modelsPayload),
      reasoningOptions: normalizeReasoningOptions(sessionsPayload)
    });
  }

  private async sendSessions(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const payload = await this.client.listSessions(50);
    const sessions = normalizeSessions(payload);
    const selected = sessions.find((session) => session.key === state.sessionKey);
    if (selected) {
      state.sessionId = selected.sessionId ?? null;
      state.model = selected.model ?? state.model ?? null;
      state.reasoningEffort = selected.thinkingLevel ?? state.reasoningEffort ?? null;
      state.reasoningStream = reasoningStreamEnabled(selected.reasoningLevel) ?? state.reasoningStream ?? null;
      state.fastMode = selected.fastMode ?? null;
      state.verboseLevel = selected.verboseLevel ?? null;
    }
    this.sendChat(deviceId, {
      type: "chat.sessions",
      deviceId,
      sessions,
      selectedSessionKey: state.sessionKey
    });
    this.sendChat(deviceId, {
      type: "chat.usage",
      deviceId,
      sessionKey: state.sessionKey,
      usage: usageFromSession(selected)
    });
    this.sendState(deviceId);
  }

  private async sendHistory(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const payload = await this.client.history(state.sessionKey);
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    state.sessionId = typeof record.sessionId === "string" ? record.sessionId : state.sessionId ?? null;
    state.reasoningEffort = typeof record.thinkingLevel === "string" ? record.thinkingLevel : state.reasoningEffort ?? null;
    state.reasoningStream = typeof record.reasoningLevel === "string" ? reasoningStreamEnabled(record.reasoningLevel) : state.reasoningStream ?? null;
    state.fastMode = typeof record.fastMode === "boolean" ? record.fastMode : state.fastMode ?? null;
    state.verboseLevel = typeof record.verboseLevel === "string" ? record.verboseLevel : state.verboseLevel ?? null;
    this.sendChat(deviceId, {
      type: "chat.history",
      deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      messages: chatMessagesFromHistory(payload)
    });
    this.sendState(deviceId);
  }

  private async sendCommands(deviceId: string): Promise<void> {
    const payload = await this.client.listCommands();
    this.sendChat(deviceId, {
      type: "chat.commands",
      deviceId,
      commands: normalizeCommands(payload)
    });
  }

  private async sendTools(deviceId: string): Promise<void> {
    const state = this.stateFor(deviceId);
    const payload = await this.client.effectiveTools(state.sessionKey);
    this.sendChat(deviceId, {
      type: "chat.tools",
      deviceId,
      sessionKey: state.sessionKey,
      tools: normalizeTools(payload)
    });
  }

  private async patchSession(deviceId: string, sessionKey: string, patch: Record<string, unknown>): Promise<void> {
    try {
      await this.client.patchSession(sessionKey, patch);
      this.sendState(deviceId, "Updated session");
    } catch (error) {
      this.sendChatError(deviceId, sessionKey, error);
    }
  }

  private async sendSlashCommand(deviceId: string, text: string, sessionKey: string, status: string): Promise<void> {
    try {
      const result = await this.client.sendChat({
        sessionKey,
        message: text,
        idempotencyKey: randomUUID()
      });
      const state = this.stateFor(deviceId);
      state.sessionKey = result.sessionKey;
      state.runId = result.runId;
      this.sendState(deviceId, status);
    } catch (error) {
      this.sendChatError(deviceId, sessionKey, error);
    }
  }

  private handleGatewayChatEvent(payload: unknown): void {
    for (const [deviceId, state] of this.devices) {
      const message = mapGatewayChatEvent(deviceId, payload);
      if (!message || ("sessionKey" in message && message.sessionKey !== state.sessionKey)) {
        continue;
      }
      if (message.type === "chat.delta" || message.type === "chat.final" || message.type === "chat.error") {
        this.sendReasoningClear(deviceId, state.sessionKey, "runId" in message ? message.runId : state.runId ?? null);
      }
      this.sendChat(deviceId, message);
      if (message.type === "chat.final" || message.type === "chat.error") {
        this.settleRun(message);
        state.runId = null;
        this.sendState(deviceId, message.type === "chat.final" ? "OpenClaw finished" : "OpenClaw failed");
        void this.refreshMetadata(deviceId);
        void this.sendHistory(deviceId);
      }
    }
  }

  private handleGatewayReasoningEvent(payload: unknown, eventName?: string): void {
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    const runId = typeof record.runId === "string" ? record.runId : undefined;
    const sessionKey = typeof record.sessionKey === "string" ? record.sessionKey : undefined;
    for (const [deviceId, state] of this.devices) {
      if (runId && state.runId && runId !== state.runId) {
        continue;
      }
      if (sessionKey && sessionKey !== state.sessionKey) {
        continue;
      }
      const reasoningEvent = normalizeGatewayReasoningEvent(deviceId, state.sessionKey, payload, eventName);
      if (reasoningEvent && reasoningEvent.sessionKey === state.sessionKey) {
        this.sendChat(deviceId, reasoningEvent);
      }
    }
  }

  private handleGatewayAgentEvent(payload: unknown, eventName?: string): void {
    const record = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    const runId = typeof record.runId === "string" ? record.runId : undefined;
    const sessionKey = typeof record.sessionKey === "string" ? record.sessionKey : undefined;
    for (const [deviceId, state] of this.devices) {
      if (runId && state.runId && runId !== state.runId) {
        continue;
      }
      if (sessionKey && sessionKey !== state.sessionKey) {
        continue;
      }
      const reasoningEvent = normalizeGatewayReasoningEvent(deviceId, state.sessionKey, payload, eventName);
      if (reasoningEvent && reasoningEvent.sessionKey === state.sessionKey) {
        this.sendChat(deviceId, reasoningEvent);
        continue;
      }
      const chatMessage = mapGatewayChatEvent(deviceId, payload);
      if (chatMessage && (!("sessionKey" in chatMessage) || chatMessage.sessionKey === state.sessionKey)) {
        if (chatMessage.type === "chat.delta" || chatMessage.type === "chat.final" || chatMessage.type === "chat.error") {
          this.sendReasoningClear(deviceId, state.sessionKey, "runId" in chatMessage ? chatMessage.runId : state.runId ?? null);
        }
        this.sendChat(deviceId, chatMessage);
        if (chatMessage.type === "chat.final" || chatMessage.type === "chat.error") {
          this.settleRun(chatMessage);
          state.runId = null;
          this.sendState(deviceId, chatMessage.type === "chat.final" ? "OpenClaw finished" : "OpenClaw failed");
          void this.refreshMetadata(deviceId);
          void this.sendHistory(deviceId);
        }
        continue;
      }
      const toolEvent = normalizeGatewayToolEvent(deviceId, state.sessionKey, payload);
      if (toolEvent) {
        this.sendChat(deviceId, toolEvent);
      }
      if (record.type === "run.completed") {
        state.runId = null;
        this.sendState(deviceId, "OpenClaw finished");
        void this.refreshMetadata(deviceId);
        void this.sendHistory(deviceId);
      }
    }
  }

  private sendState(deviceId: string, status?: string): void {
    const state = this.stateFor(deviceId);
    this.sendChat(deviceId, {
      type: "chat.state",
      deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      runId: state.runId ?? null,
      isRunning: Boolean(state.runId),
      status: status ?? null,
      model: state.model ?? null,
      reasoningEffort: state.reasoningEffort ?? null,
      reasoningStream: state.reasoningStream ?? null,
      fastMode: state.fastMode ?? null,
      verboseLevel: state.verboseLevel ?? null
    });
  }

  private sendReasoningClear(deviceId: string, sessionKey: string, runId?: string | null): void {
    this.sendChat(deviceId, {
      type: "chat.reasoning_clear",
      deviceId,
      sessionKey,
      runId: runId ?? null
    });
  }

  private appendUserMessage(deviceId: string, text: string, id: string): void {
    const state = this.stateFor(deviceId);
    const message: ChatHistoryMessage = {
      id,
      role: "user",
      text,
      timestamp: Date.now()
    };
    this.sendChat(deviceId, {
      type: "chat.message",
      deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      message
    });
  }

  private waitForRun(deviceId: string, sessionKey: string, runId: string): Promise<AgentRunResult> {
    return new Promise<AgentRunResult>((resolve, reject) => {
      const key = this.runWaiterKey(deviceId, runId);
      const timer = setTimeout(() => {
        this.runWaiters.delete(key);
        reject(new Error(`OpenClaw chat run ${runId} timed out`));
      }, REALTIME_CHAT_RUN_TIMEOUT_MS);
      this.runWaiters.set(key, {
        deviceId,
        sessionKey,
        runId,
        resolve,
        reject,
        timer
      });
    });
  }

  private settleRun(message: Extract<ChatOutboundMessage, { type: "chat.final" | "chat.error" }>): void {
    const runId = message.runId;
    if (!runId) {
      return;
    }
    const waiter = this.runWaiters.get(this.runWaiterKey(message.deviceId, runId));
    if (!waiter || ("sessionKey" in message && message.sessionKey && message.sessionKey !== waiter.sessionKey)) {
      return;
    }

    clearTimeout(waiter.timer);
    this.runWaiters.delete(this.runWaiterKey(message.deviceId, runId));
    if (message.type === "chat.final") {
      waiter.resolve({ finalMessage: message.text });
    } else {
      waiter.reject(new Error(message.message));
    }
  }

  private rejectWaitersForDevice(deviceId: string, error: Error): void {
    for (const [key, waiter] of this.runWaiters) {
      if (waiter.deviceId !== deviceId) {
        continue;
      }
      clearTimeout(waiter.timer);
      this.runWaiters.delete(key);
      waiter.reject(error);
    }
  }

  private runWaiterKey(deviceId: string, runId: string): string {
    return `${deviceId}:${runId}`;
  }

  private sendChat(deviceId: string, message: ChatOutboundMessage): void {
    try {
      this.hub.sendChat(deviceId, message);
    } catch (error) {
      console.warn(`[chat] ${deviceId}: failed to send ${message.type}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private sendChatError(deviceId: string, sessionKey: string, error: unknown): void {
    this.sendChat(deviceId, {
      type: "chat.error",
      deviceId,
      sessionKey,
      message: error instanceof Error ? error.message : String(error)
    });
  }

  private async fallbackSend(message: ChatSendMessage, runId: string, taskKind: "general" | "phone" = "general"): Promise<void> {
    const state = this.stateFor(message.deviceId);
    this.sendChat(message.deviceId, {
      type: "chat.history",
      deviceId: message.deviceId,
      sessionKey: state.sessionKey,
      sessionId: state.sessionId,
      messages: [
        {
          id: `user_${runId}`,
          role: "user",
          text: message.text,
          timestamp: Date.now()
        }
      ]
    });
    this.sendState(message.deviceId, taskKind === "phone" ? "Using Android phone tools" : "Using OpenClaw fallback");
    try {
      const legacyRequest: UserRequestMessage = {
        type: "user_request",
        inputType: "text",
        deviceId: message.deviceId,
        text: message.text,
        model: undefined,
        reasoningEffort: undefined
      };
      const result = await this.dispatcher.handleUserRequest(legacyRequest, { taskKind });
      this.sendChat(message.deviceId, {
        type: "chat.final",
        deviceId: message.deviceId,
        sessionKey: state.sessionKey,
        runId,
        text: result.finalMessage ?? "OpenClaw task completed."
      });
    } catch (error) {
      this.sendChatError(message.deviceId, state.sessionKey, error);
    } finally {
      state.runId = null;
      this.sendState(message.deviceId, "OpenClaw finished");
    }
  }
}

function isExplicitPhoneTask(text: string): boolean {
  const normalized = text.toLowerCase();
  return /\b(android|phone|device|screen|app|tap|swipe|scroll|keyboard|notification|settings app|facebook app|instagram app|messages app|sms)\b/.test(normalized)
    && !/\b(mac|desktop|pc|laptop|browser|terminal|repo|codebase)\b/.test(normalized);
}

function reasoningStreamEnabled(level: string | null | undefined): boolean | null {
  if (!level) {
    return null;
  }
  const normalized = level.toLowerCase();
  if (normalized === "stream") {
    return true;
  }
  if (normalized === "off" || normalized === "false") {
    return false;
  }
  return null;
}
