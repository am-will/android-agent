# AGENTS.md

## Project Shape
- `pc/` is the TypeScript PC bridge and MCP server. It uses Node.js 24+, ESM, strict TypeScript, `zod`, and `ws`.
- `android/` is the Kotlin Android app. It connects to the PC bridge over WebSocket and executes commands through `AccessibilityService`.
- `docs/` contains the setup, protocol, pairing, safety, and demo notes. Check these before changing cross-device behavior.

## Commands
- PC install: `cd pc && npm install`
- PC type check: `cd pc && npm run check`
- PC bridge: `cd pc && PHONE_AGENT_TOKEN=change-me npm run bridge`
- Android build: open `android/` in Android Studio, or run Gradle from `android/` if available.

## Working Rules
- Keep WebSocket message shapes aligned across `pc/src/protocol/messages.ts`, Android JSON handling, and `docs/protocol.md`.
- Do not hand-edit `pc/src/generated/`; regenerate it with `cd pc && npm run codex:schemas`.
- Treat `PHONE_AGENT_TOKEN` and device-specific values as local config. Do not commit real tokens or machine-specific secrets.
- Keep changes narrowly scoped; this is a prototype, so prefer clear local code over broad abstractions.

## Safety
- Preserve user confirmation before purchases, payments, crypto transactions, account/security changes, app installs/deletions, or sending messages/email.
- Biometric, passkey, credential, and other sensitive OS prompts must remain manual; the agent should stop and ask the user to handle them.
