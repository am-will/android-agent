# Android-to-Codex Phone Control Agent

Prototype control loop:

1. Android overlay bubble sends a text request to the PC bridge over WebSocket.
2. The bridge sends the request to Codex app-server.
3. Codex calls the local `android-phone` MCP server.
4. The MCP server forwards phone commands through the bridge to Android.
5. Android executes commands with `AccessibilityService` and returns observations.

## Quick Start

```bash
cd pc
npm install
export PHONE_AGENT_TOKEN=change-me
npm run bridge
```

In another terminal, configure Codex with the MCP server from `docs/codex-mcp.md`, then install the Android app from `android/` with Android Studio or Gradle. On the phone, set:

- WebSocket URL: `ws://<your-computer-lan-ip>:8787/phone`
- Device ID: `pixel`
- Token: `change-me`

Grant overlay and accessibility permissions, start the agent bubble, then send:

```text
Open Settings.
```

See `docs/setup.md`, `docs/pairing.md`, and `docs/demo.md` for details.
