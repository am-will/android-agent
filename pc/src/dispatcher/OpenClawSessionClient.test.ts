import assert from "node:assert/strict";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import type { AgentStatusSink } from "./AgentClient.js";
import { buildOpenClawCommandConfig, OpenClawSessionClient } from "./OpenClawSessionClient.js";

function withEnv(values: Record<string, string | undefined>, run: () => void): void {
  const previous = new Map<string, string | undefined>();
  for (const [key, value] of Object.entries(values)) {
    previous.set(key, process.env[key]);
    if (value === undefined) {
      delete process.env[key];
    } else {
      process.env[key] = value;
    }
  }
  try {
    run();
  } finally {
    for (const [key, value] of previous.entries()) {
      if (value === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
    }
  }
}

function sink(): AgentStatusSink & { doneMessages: string[] } {
  const doneMessages: string[] = [];
  return {
    doneMessages,
    info() {},
    working() {},
    tool() {},
    done(text) {
      doneMessages.push(text);
    },
    error() {}
  };
}

test("buildOpenClawCommandConfig targets OpenClaw agent JSON mode", () => {
  withEnv({
    OPENCLAW_AGENT_COMMAND: "openclaw-test",
    OPENCLAW_AGENT_SESSION_ID: "phone-bubble",
    OPENCLAW_AGENT_MODEL: "gpt-5.5",
    OPENCLAW_AGENT_THINKING: "high",
    OPENCLAW_AGENT_LOCAL: "1",
    OPENCLAW_AGENT_TIMEOUT_SECONDS: "42"
  }, () => {
    const config = buildOpenClawCommandConfig("hello");

    assert.equal(config.command, "openclaw-test");
    assert.deepEqual(config.args, [
      "--no-color",
      "agent",
      "--json",
      "--timeout",
      "42",
      "--session-id",
      "phone-bubble",
      "--model",
      "gpt-5.5",
      "--thinking",
      "high",
      "--local",
      "--message",
      "hello"
    ]);
  });
});

test("OpenClawSessionClient returns final message from JSON output", async () => {
  const dir = await mkdtemp(join(tmpdir(), "openclaw-client-"));
  const command = join(dir, "fake-openclaw");
  await writeFile(command, "#!/usr/bin/env node\nconsole.log(JSON.stringify({ reply: 'Open Claw finished it.' }));\n");
  await chmod(command, 0o755);

  try {
    const status = sink();
    await new Promise<void>((resolve, reject) => {
      withEnv({ OPENCLAW_AGENT_COMMAND: command }, () => {
        const client = new OpenClawSessionClient();
        client.submitUserRequest("Do work", status)
          .then((result) => {
            assert.equal(result.finalMessage, "Open Claw finished it.");
            assert.deepEqual(status.doneMessages, ["Open Claw finished it."]);
            resolve();
          })
          .catch(reject);
      });
    });
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
