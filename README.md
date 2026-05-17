# Open Claw Agent

Open Claw Agent is an Android phone-control prototype with a PC bridge. The Android app keeps the realtime voice experience over OpenAI Realtime/WebRTC, while non-voice phone tasks are being migrated from the copied Codex app-server dispatcher to an installed Open Claw session on the user's remote PC.

Target control loop:

1. Android overlay bubble sends a text request to the PC bridge over WebSocket.
2. The bridge sends the request to the Open Claw session adapter.
3. Open Claw uses the local phone-control tools exposed by the PC bridge.
4. The local phone-tool layer forwards commands through the bridge to Android.
5. Android executes commands with `AccessibilityService` and returns observations.

Current prototype note: the copied PC dispatcher still contains a Codex app-server compatibility path. Treat it as legacy scaffolding for the Open Claw migration, not the product direction.

## Quick Start

```bash
cd pc
npm install
export PHONE_AGENT_TOKEN=change-me
npm run bridge
```

In another terminal, configure the current session adapter. Today that means the legacy Codex MCP setup in `docs/codex-mcp.md`; the intended replacement is the Open Claw adapter described in `docs/open-claw-migration-plan.md`. Then install the Android app from `android/` with Android Studio or Gradle. On the phone, set:

- WebSocket URL: `ws://<your-computer-lan-ip>:8787/phone`
- Device ID: `pixel`
- Token: `change-me`

Grant overlay and accessibility permissions, start the agent bubble, then send:

```text
Open Settings.
```

See `docs/setup.md`, `docs/pairing.md`, and `docs/demo.md` for details.
