import assert from "node:assert/strict";
import test from "node:test";

import { getBridgeConfig } from "./config.js";

const originalToken = process.env.PHONE_AGENT_TOKEN;

test.afterEach(() => {
  if (originalToken === undefined) {
    delete process.env.PHONE_AGENT_TOKEN;
  } else {
    process.env.PHONE_AGENT_TOKEN = originalToken;
  }
});

test("getBridgeConfig requires an explicit phone agent token", () => {
  delete process.env.PHONE_AGENT_TOKEN;
  assert.throws(
    () => getBridgeConfig(),
    /PHONE_AGENT_TOKEN is required/
  );
});

test("getBridgeConfig rejects the known weak default token", () => {
  process.env.PHONE_AGENT_TOKEN = "12345678";
  assert.throws(
    () => getBridgeConfig(),
    /known weak default/
  );
});

test("getBridgeConfig accepts a non-default token", () => {
  process.env.PHONE_AGENT_TOKEN = "strong-local-test-token";
  assert.equal(getBridgeConfig().token, "strong-local-test-token");
});
