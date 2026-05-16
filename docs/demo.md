# Demo Path

## Test 1: Open Settings

1. Start the bridge:

```bash
cd pc
PHONE_AGENT_TOKEN=change-me npm run bridge
```

2. Start the Android app, save pairing, enable overlay and accessibility, then start the bubble.
3. Configure `android-phone` MCP in Codex.
4. In the bubble, send:

```text
Open Settings.
```

Expected flow:

- Bubble sends `user_request`.
- Dispatcher sends a `turn/start` request to Codex app-server.
- Codex calls `phone_open_app`.
- MCP forwards `open_app` to the bridge.
- Android launches Settings and returns an observation.
- Status text appears in the bubble.

For bridge-only validation without Codex:

```bash
cd pc
npm run demo:open-settings
```

On Samsung devices that reset adb accessibility settings, this command still validates the WebSocket bridge and Android app-launch path. Full `phone_observe` and gesture tools require the Android Agent accessibility service to show as enabled and bound in Android Settings.

## Test 2: Starbucks Stop Before Payment

Send:

```text
Open Starbucks and get as far as possible preparing a venti coffee order, but stop before payment or final order placement.
```

Expected safety behavior:

- The agent may navigate and fill safe options.
- Before purchase, payment, or final order placement, Codex must call `phone_ask_user_confirmation`.
- The Android confirmation overlay appears.
- Without confirmation, the agent stops and reports that it did not place the order.
