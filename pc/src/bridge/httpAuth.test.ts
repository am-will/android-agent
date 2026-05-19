import assert from "node:assert/strict";
import test from "node:test";

import { PHONE_AGENT_TOKEN_HEADER, bearerToken, isAuthorizedHttpRequest } from "./httpAuth.js";

test("bearerToken extracts a single bearer credential", () => {
  assert.equal(bearerToken("Bearer secret-token"), "secret-token");
  assert.equal(bearerToken("bearer secret-token"), "secret-token");
  assert.equal(bearerToken("Basic secret-token"), undefined);
  assert.equal(bearerToken("Bearer too many parts"), undefined);
  assert.equal(bearerToken(undefined), undefined);
});

test("isAuthorizedHttpRequest accepts bearer or x-phone-agent-token", () => {
  assert.equal(isAuthorizedHttpRequest({ authorization: "Bearer secret-token" }, "secret-token"), true);
  assert.equal(isAuthorizedHttpRequest({ [PHONE_AGENT_TOKEN_HEADER]: "secret-token" }, "secret-token"), true);
});

test("isAuthorizedHttpRequest rejects missing, wrong, or malformed tokens", () => {
  assert.equal(isAuthorizedHttpRequest({}, "secret-token"), false);
  assert.equal(isAuthorizedHttpRequest({ authorization: "Bearer wrong-token" }, "secret-token"), false);
  assert.equal(isAuthorizedHttpRequest({ authorization: "secret-token" }, "secret-token"), false);
  assert.equal(isAuthorizedHttpRequest({ [PHONE_AGENT_TOKEN_HEADER]: "wrong-token" }, "secret-token"), false);
  assert.equal(isAuthorizedHttpRequest({ authorization: "Bearer secret-token" }, ""), false);
});
