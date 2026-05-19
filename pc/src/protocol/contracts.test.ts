import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";

import { AGENT_MODEL_IDS, PHONE_COMMANDS, REASONING_EFFORTS } from "./messages.js";

const repoRoot = resolve(import.meta.dirname, "../../..");

function readRepoFile(path: string): string {
  return readFileSync(resolve(repoRoot, path), "utf8");
}

test("Android command executor handles every protocol phone command", () => {
  const source = readRepoFile("android/app/src/main/java/dev/androidagent/accessibility/AccessibilityCommandExecutor.kt");
  const start = source.indexOf("return when (command) {");
  const end = source.indexOf("            else ->", start);
  assert.ok(start >= 0 && end > start, "Could not find command dispatch block");
  const commandBlock = source.slice(start, end);
  const androidCommands = Array.from(commandBlock.matchAll(/^\s*"([^"]+)"\s*->/gm), (match) => match[1]).sort();
  assert.deepEqual(androidCommands, [...PHONE_COMMANDS].sort());
});

test("Android model and reasoning options match protocol enums", () => {
  const source = readRepoFile("android/app/src/main/java/dev/androidagent/AgentModelOptions.kt");
  const androidModels = Array.from(source.matchAll(/ModelOption\("([^"]+)"/g), (match) => match[1]);
  const androidReasoning = Array.from(source.matchAll(/ReasoningOption\("([^"]+)"/g), (match) => match[1]);
  assert.deepEqual(androidModels, [...AGENT_MODEL_IDS]);
  assert.deepEqual(androidReasoning, [...REASONING_EFFORTS]);
});
