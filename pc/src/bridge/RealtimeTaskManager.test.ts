import assert from "node:assert/strict";
import test from "node:test";
import { RealtimeTaskManager } from "./RealtimeTaskManager.js";
import type { AgentRunResult } from "../dispatcher/AgentClient.js";
import type { RealtimeOutboundMessage, RealtimeToolCallMessage } from "../protocol/messages.js";

class Deferred<T> {
  readonly promise: Promise<T>;
  resolve!: (value: T) => void;
  reject!: (error: Error) => void;

  constructor() {
    this.promise = new Promise<T>((resolve, reject) => {
      this.resolve = resolve;
      this.reject = reject;
    });
  }
}

class FakeDispatcher {
  readonly requests: string[] = [];
  readonly stopReasons: string[] = [];
  readonly steers: string[] = [];
  results: Array<Promise<AgentRunResult>> = [];

  async handleUserRequest(request: { text: string }): Promise<AgentRunResult> {
    this.requests.push(request.text);
    return await (this.results.shift() ?? Promise.resolve({ finalMessage: `done ${request.text}` }));
  }

  async stopActiveTurn(_deviceId: string, reason?: string): Promise<void> {
    this.stopReasons.push(reason ?? "");
  }

  async steerActiveTurn(_deviceId: string, guidance: string): Promise<void> {
    this.steers.push(guidance);
  }
}

function toolCall(callId: string, instruction: string, extraArgs: Record<string, unknown> = {}): RealtimeToolCallMessage {
  return {
    type: "realtime.tool_call",
    deviceId: "pixel",
    callId,
    name: "run_phone_task",
    arguments: {
      instruction,
      ...extraArgs
    }
  };
}

function namedToolCall(callId: string, name: string, args: Record<string, unknown> = {}): RealtimeToolCallMessage {
  return {
    type: "realtime.tool_call",
    deviceId: "pixel",
    callId,
    name,
    arguments: args
  };
}

function createHarness(options: { taskTimeoutMs?: number } = {}) {
  const dispatcher = new FakeDispatcher();
  const messages: RealtimeOutboundMessage[] = [];
  const manager = new RealtimeTaskManager({
    dispatcher,
    sendRealtime: (_deviceId, message) => messages.push(message),
    taskTimeoutMs: options.taskTimeoutMs
  });
  return { dispatcher, manager, messages };
}

function createHarnessWithSearch(output = "search result") {
  const dispatcher = new FakeDispatcher();
  const messages: RealtimeOutboundMessage[] = [];
  const queries: string[] = [];
  const manager = new RealtimeTaskManager({
    dispatcher,
    sendRealtime: (_deviceId, message) => messages.push(message),
    webSearch: {
      async search({ query }) {
        queries.push(query);
        return output;
      }
    },
    getRealtimeApiKey: () => "test-key"
  });
  return { dispatcher, manager, messages, queries };
}

function results(messages: RealtimeOutboundMessage[]) {
  return messages.filter((message) => message.type === "realtime.tool_result");
}

async function waitFor(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
  assert.ok(predicate());
}

test("rejects invalid run_phone_task arguments", async () => {
  const { manager, messages } = createHarness();

  await manager.handleToolCall(toolCall("call_1", ""));

  assert.equal(results(messages).at(-1)?.ok, false);
  assert.match(results(messages).at(-1)?.error ?? "", /non-empty instruction/);
});

test("queues one active task at a time", async () => {
  const first = new Deferred<AgentRunResult>();
  const second = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise, second.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Messages"));
  await manager.handleToolCall(toolCall("call_2", "Open Alice"));

  assert.deepEqual(dispatcher.requests, ["Open Messages"]);
  assert.equal(messages.filter((message) => message.type === "realtime.task_status").at(-1)?.queued, 1);

  first.resolve({ finalMessage: "Messages opened" });
  await waitFor(() => dispatcher.requests.length === 2);
  second.resolve({ finalMessage: "Alice opened" });
  await waitFor(() => results(messages).length === 2);

  assert.deepEqual(dispatcher.requests, ["Open Messages", "Open Alice"]);
  assert.equal(results(messages)[0]?.status, "completed");
  assert.equal(results(messages)[1]?.status, "completed");
});

test("ignores duplicate call IDs while active", async () => {
  const first = new Deferred<AgentRunResult>();
  const { dispatcher, manager } = createHarness();
  dispatcher.results = [first.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(toolCall("call_1", "Open Settings"));

  assert.deepEqual(dispatcher.requests, ["Open Settings"]);
  first.resolve({ finalMessage: "Settings opened" });
});

test("times out and stops a hung task", async () => {
  const never = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness({ taskTimeoutMs: 1 });
  dispatcher.results = [never.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await waitFor(() => results(messages).some((message) => message.status === "timeout"));

  assert.equal(dispatcher.stopReasons.length, 1);
  assert.equal(results(messages).at(-1)?.status, "timeout");
});

test("interrupt cancels the active task before starting the next one", async () => {
  const first = new Deferred<AgentRunResult>();
  const second = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise, second.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(toolCall("call_2", "Open Messages", { urgency: "interrupt" }));
  await waitFor(() => dispatcher.requests.length === 2);

  assert.equal(dispatcher.stopReasons.length, 1);
  assert.equal(results(messages).find((message) => message.callId === "call_1")?.status, "cancelled");
  assert.deepEqual(dispatcher.requests, ["Open Settings", "Open Messages"]);

  second.resolve({ finalMessage: "Messages opened" });
  first.resolve({ finalMessage: "Settings opened late" });
});

test("stop_phone_task cancels active and queued realtime phone tasks", async () => {
  const first = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(toolCall("call_2", "Open Messages"));
  await manager.handleToolCall(namedToolCall("call_stop", "stop_phone_task", { reason: "User said stop" }));

  assert.deepEqual(dispatcher.stopReasons, ["User said stop"]);
  assert.equal(results(messages).find((message) => message.callId === "call_1")?.status, "cancelled");
  assert.equal(results(messages).find((message) => message.callId === "call_2")?.status, "cancelled");
  assert.equal(results(messages).find((message) => message.callId === "call_stop")?.status, "completed");
  assert.equal(results(messages).find((message) => message.callId === "call_stop")?.createResponse, false);

  first.resolve({ finalMessage: "Settings opened late" });
});

test("steer_phone_task injects guidance into active turn", async () => {
  const first = new Deferred<AgentRunResult>();
  const { dispatcher, manager, messages } = createHarness();
  dispatcher.results = [first.promise];

  await manager.handleToolCall(toolCall("call_1", "Open Settings"));
  await manager.handleToolCall(namedToolCall("call_steer", "steer_phone_task", { guidance: "Actually open Bluetooth settings." }));

  assert.deepEqual(dispatcher.steers, ["Actually open Bluetooth settings."]);
  assert.equal(results(messages).find((message) => message.callId === "call_steer")?.status, "completed");
  assert.equal(results(messages).find((message) => message.callId === "call_steer")?.createResponse, false);

  first.resolve({ finalMessage: "Settings opened" });
});

test("steer_phone_task becomes a normal phone task when no turn is active", async () => {
  const { dispatcher, manager, messages } = createHarness();

  await manager.handleToolCall(namedToolCall("call_steer", "steer_phone_task", { guidance: "Open the first video on this screen." }));
  await waitFor(() => results(messages).some((message) => message.callId === "call_steer"));

  assert.deepEqual(dispatcher.requests, ["Open the first video on this screen."]);
  assert.deepEqual(dispatcher.steers, []);
  assert.equal(results(messages).find((message) => message.callId === "call_steer")?.status, "completed");
});

test("web_search returns search output without starting a phone task", async () => {
  const { dispatcher, manager, messages, queries } = createHarnessWithSearch("It is sunny.");

  await manager.handleToolCall(namedToolCall("call_search", "web_search", { query: "El Paso weather today" }));

  assert.deepEqual(queries, ["El Paso weather today"]);
  assert.deepEqual(dispatcher.requests, []);
  assert.equal(results(messages).find((message) => message.callId === "call_search")?.output, "It is sunny.");
});
