import assert from "node:assert/strict";
import test from "node:test";

import type { PhoneHub } from "../bridge/PhoneHub.js";
import type { AgentClient, AgentStatusSink } from "./AgentClient.js";
import { Dispatcher } from "./dispatcher.js";

class ThrowingClient implements AgentClient {
  async submitUserRequest(): Promise<never> {
    throw new Error("adapter failed");
  }

  async close(): Promise<void> {}
}

class SuccessfulClient implements AgentClient {
  async submitUserRequest(text: string, sink: AgentStatusSink): Promise<{ finalMessage: string }> {
    sink.done("completed");
    return { finalMessage: `ok: ${text}` };
  }

  async close(): Promise<void> {}
}

function fakeHub(): PhoneHub {
  return {
    sendStatus() {},
    cancelPendingCommands() {}
  } as unknown as PhoneHub;
}

test("dispatcher returns adapter errors without implicit fallback", async () => {
  const dispatcher = new Dispatcher(fakeHub(), undefined, new ThrowingClient(), "openclaw");
  const result = await dispatcher.handleUserRequest({
    type: "user_request",
    inputType: "text",
    deviceId: "phone",
    text: "Open Settings"
  });

  assert.equal(result.error, "adapter failed");
  assert.equal(result.finalMessage, "adapter failed");
});

test("dispatcher still allows an explicitly configured client to handle requests", async () => {
  const dispatcher = new Dispatcher(fakeHub(), undefined, new SuccessfulClient(), "fallback");
  const result = await dispatcher.handleUserRequest({
    type: "user_request",
    inputType: "text",
    deviceId: "phone",
    text: "manual path"
  });

  assert.equal(result.error, undefined);
  assert.equal(result.finalMessage, "ok: manual path");
});
