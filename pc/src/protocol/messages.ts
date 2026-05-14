import { z } from "zod";

export const PHONE_COMMANDS = [
  "observe_screen",
  "open_app",
  "tap_node",
  "tap_xy",
  "long_press_node",
  "type_text",
  "scroll",
  "swipe",
  "press_back",
  "press_home",
  "open_recents",
  "take_screenshot",
  "ask_user_confirmation",
  "wait"
] as const;

export const phoneCommandSchema = z.enum(PHONE_COMMANDS);
export type PhoneCommand = z.infer<typeof phoneCommandSchema>;

export const registerMessageSchema = z.object({
  type: z.literal("register"),
  deviceId: z.string().min(1),
  token: z.string().min(1),
  capabilities: z.array(z.string())
});

export const commandMessageSchema = z.object({
  id: z.string().min(1),
  type: z.literal("command"),
  command: phoneCommandSchema,
  args: z.record(z.string(), z.unknown()).default({})
});

export const resultMessageSchema = z.object({
  id: z.string().min(1),
  type: z.literal("result"),
  ok: z.boolean(),
  observation: z.unknown().optional().nullable(),
  screenshotBase64: z.string().optional().nullable(),
  error: z.string().optional().nullable()
});

export const userRequestMessageSchema = z.object({
  type: z.literal("user_request"),
  deviceId: z.string().min(1),
  inputType: z.literal("text"),
  text: z.string().min(1),
  systemPrompt: z.string().optional(),
  model: z.enum(["gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.3-codex", "gpt-5.3-codex-spark", "gpt-5.2"]).optional(),
  reasoningEffort: z.enum(["low", "medium", "high", "xhigh"]).optional()
});

export const agentStatusMessageSchema = z.object({
  type: z.literal("agent_status"),
  deviceId: z.string().optional(),
  status: z.enum(["info", "working", "tool", "done", "error"]),
  text: z.string()
});

export const agentControlMessageSchema = z.object({
  type: z.literal("agent_control"),
  deviceId: z.string().min(1),
  action: z.literal("stop"),
  reason: z.string().optional()
});

export const inboundPhoneMessageSchema = z.discriminatedUnion("type", [
  registerMessageSchema,
  resultMessageSchema,
  userRequestMessageSchema,
  agentControlMessageSchema
]);

export type RegisterMessage = z.infer<typeof registerMessageSchema>;
export type CommandMessage = z.infer<typeof commandMessageSchema>;
export type ResultMessage = z.infer<typeof resultMessageSchema>;
export type UserRequestMessage = z.infer<typeof userRequestMessageSchema>;
export type AgentStatusMessage = z.infer<typeof agentStatusMessageSchema>;
export type AgentControlMessage = z.infer<typeof agentControlMessageSchema>;

export interface PhoneCommandRequest {
  deviceId?: string;
  command: PhoneCommand;
  args?: Record<string, unknown>;
  timeoutMs?: number;
}

export interface PhoneCommandResult {
  id: string;
  deviceId: string;
  ok: boolean;
  observation?: unknown;
  screenshotBase64?: string | null;
  error?: string | null;
}

export const DEFAULT_TIMEOUT_MS = 30_000;

export function newCommandId(): string {
  return `cmd_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}
