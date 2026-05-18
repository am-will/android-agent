import assert from "node:assert/strict";
import test from "node:test";
import {
  chatMessagesFromHistory,
  extractGatewayText,
  mapGatewayChatEvent,
  normalizeGatewayReasoningEvent,
  normalizeGatewayToolEvent,
  normalizeModels,
  normalizeSessions,
  requestKeyFromSessionKey
} from "./OpenClawGatewayChatClient.js";

test("extractGatewayText handles OpenClaw content blocks", () => {
  assert.equal(
    extractGatewayText({
      content: [
        { type: "text", text: "Hello" },
        { type: "text", text: " world" }
      ]
    }),
    "Hello world"
  );
});

test("chatMessagesFromHistory projects Gateway history for Android", () => {
  const messages = chatMessagesFromHistory({
    messages: [
      {
        role: "user",
        content: "Open settings",
        timestamp: 123,
        __openclaw: { id: "u1" }
      },
      {
        role: "assistant",
        content: [{ type: "text", text: "Done" }],
        timestamp: 124,
        __openclaw: { id: "a1" }
      },
      { role: "assistant", content: "" }
    ]
  });

  assert.deepEqual(messages, [
    { id: "u1", role: "user", text: "Open settings", timestamp: 123 },
    { id: "a1", role: "assistant", text: "Done", timestamp: 124 }
  ]);
});

test("mapGatewayChatEvent maps delta, final, and error states", () => {
  assert.deepEqual(
    mapGatewayChatEvent("phone", {
      runId: "run1",
      sessionKey: "agent:main:main",
      state: "delta",
      message: { delta: "Hi" }
    }),
    {
      type: "chat.delta",
      deviceId: "phone",
      sessionKey: "agent:main:main",
      runId: "run1",
      delta: "Hi",
      replace: false
    }
  );

  assert.deepEqual(
    mapGatewayChatEvent("phone", {
      runId: "run1",
      sessionKey: "agent:main:main",
      state: "final",
      message: { content: [{ type: "text", text: "Finished" }] },
      usage: { totalTokens: 42 }
    }),
    {
      type: "chat.final",
      deviceId: "phone",
      sessionKey: "agent:main:main",
      runId: "run1",
      text: "Finished",
      usage: { totalTokens: 42 }
    }
  );

  assert.deepEqual(
    mapGatewayChatEvent("phone", {
      runId: "run1",
      sessionKey: "agent:main:main",
      state: "error",
      errorMessage: "Nope"
    }),
    {
      type: "chat.error",
      deviceId: "phone",
      sessionKey: "agent:main:main",
      runId: "run1",
      message: "Nope"
    }
  );
});

test("normalizers map Gateway model and session metadata", () => {
  assert.deepEqual(normalizeModels({
    models: [{
      key: "openai-codex/gpt-5.5",
      name: "gpt-5.5",
      contextWindow: 200000,
      available: true
    }]
  }), [{
    id: "openai-codex/gpt-5.5",
    label: "gpt-5.5 (openai-codex)",
    provider: "openai-codex",
    contextWindow: 200000,
    available: true
  }]);

  assert.deepEqual(normalizeSessions({
    sessions: [{
      key: "agent:main:main",
      sessionId: "abc",
      model: "gpt-5.5",
      totalTokens: 12,
      hasActiveRun: false
    }]
  }), [{
    key: "agent:main:main",
    sessionId: "abc",
    label: null,
    displayName: null,
    updatedAt: null,
    model: "gpt-5.5",
    modelProvider: null,
    contextTokens: null,
    inputTokens: null,
    outputTokens: null,
    totalTokens: 12,
    estimatedCostUsd: null,
    fastMode: null,
    hasActiveRun: false,
    thinkingLevel: null,
    reasoningLevel: null,
    verboseLevel: null
  }]);
});

test("normalizeGatewayReasoningEvent maps thinking deltas", () => {
  assert.deepEqual(
    normalizeGatewayReasoningEvent("phone", "agent:main:main", {
      runId: "run1",
      sessionKey: "agent:main:main",
      data: {
        type: "thinking.delta",
        delta: "Checking context"
      }
    }, "thinking.delta"),
    {
      type: "chat.reasoning_delta",
      deviceId: "phone",
      sessionKey: "agent:main:main",
      runId: "run1",
      delta: "Checking context",
      replace: false
    }
  );
});

test("normalizeGatewayToolEvent produces expandable tool rows", () => {
  const event = normalizeGatewayToolEvent("phone", "agent:main:main", {
    runId: "run1",
    seq: 2,
    stream: "tool",
    data: {
      toolName: "exec",
      status: "completed",
      summary: "Ran npm test",
      args: { command: "npm test" },
      output: "ok"
    }
  });

  assert.equal(event?.type, "chat.tool_event");
  assert.equal(event?.toolName, "exec");
  assert.equal(event?.status, "completed");
  assert.deepEqual(event?.args, { command: "npm test" });
  assert.equal(event?.output, "ok");
});

test("requestKeyFromSessionKey strips canonical explicit key prefix", () => {
  assert.equal(requestKeyFromSessionKey("agent:main:explicit:open-claw-agent", "main"), "open-claw-agent");
  assert.equal(requestKeyFromSessionKey("agent:main:main", "main"), "agent:main:main");
});
