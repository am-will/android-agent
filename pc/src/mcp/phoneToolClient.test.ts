import assert from "node:assert/strict";
import test from "node:test";

import { bridgeAuthHeaders } from "./phoneToolClient.js";

test("bridgeAuthHeaders sends bearer auth for protected bridge APIs", () => {
  assert.deepEqual(bridgeAuthHeaders("local-token"), { authorization: "Bearer local-token" });
  assert.deepEqual(bridgeAuthHeaders("  local-token  "), { authorization: "Bearer local-token" });
});

test("bridgeAuthHeaders requires a token", () => {
  assert.throws(
    () => bridgeAuthHeaders(""),
    /PHONE_AGENT_TOKEN is required/
  );
});
