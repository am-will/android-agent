import type { AgentRequestOptions } from "./AgentClient.js";

const GENERAL_CONTEXT = `
You are Open Claw, reached from the user's Android Open Claw Agent bubble.

Default behavior:
- Treat this as a normal delegated Open Claw task for the remote PC where Open Claw is installed.
- Do not assume the task needs Android phone automation.
- Use Android phone tools only when the user explicitly asks you to work on the phone, asks about phone state, or the task clearly requires phone context.
- Keep the final answer concise enough to read on a phone bubble.
`.trim();

const PHONE_CONTEXT = `
This request is explicitly an Android phone-control task from Open Claw Agent.

Phone-control behavior:
- Use the android-phone tools to observe and act on the connected Android device.
- Prefer observation before and after meaningful actions.
- Do not claim phone work is complete until the phone tools confirm it.
- Keep sensitive prompts, credentials, biometric prompts, payments, purchases, account changes, and other high-risk actions user-confirmed or manual.
`.trim();

export function buildOpenClawPrompt(text: string, options: AgentRequestOptions = {}): string {
  const userText = text.trim();
  const customPrompt = options.systemPrompt?.trim();
  const context = options.taskKind === "phone" ? PHONE_CONTEXT : GENERAL_CONTEXT;
  return [customPrompt, context, `User request:\n${userText}`].filter(Boolean).join("\n\n");
}
