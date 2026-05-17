# WebSocket Protocol

Android connects outbound to the PC bridge at `/phone`. The bridge validates `token` during registration. Android never implements MCP directly.

## Register

```json
{
  "type": "register",
  "deviceId": "pixel",
  "token": "change-me",
  "capabilities": ["accessibility_tree", "gestures", "text_input", "screenshots", "app_launch", "realtime_voice"]
}
```

## Command

```json
{
  "id": "cmd_123",
  "type": "command",
  "command": "tap_node",
  "args": { "nodeId": "n17" }
}
```

## Result

```json
{
  "id": "cmd_123",
  "type": "result",
  "ok": true,
  "observation": {
    "deviceId": "Galaxy",
    "package": "com.android.settings",
    "activity": "com.android.settings.Settings",
    "screenSummary": "Settings | Connections | Notifications",
    "nodes": []
  },
  "error": null
}
```

## User Request

```json
{
  "type": "user_request",
  "deviceId": "pixel",
  "inputType": "text",
  "text": "Open Settings"
}
```

## Agent Status

```json
{
  "type": "agent_status",
  "deviceId": "pixel",
  "status": "working",
  "text": "Codex started working"
}
```

## Realtime Voice

Realtime voice mode is Codex-backed. Android creates the WebRTC offer, sends it to the PC bridge, and the bridge forwards it to the Codex app-server experimental realtime API. Android message names use dotted `realtime.*` types.

### Start

Android sends:

```json
{
  "type": "realtime.start",
  "deviceId": "pixel",
  "sdp": "v=0\r\n..."
}
```

Optional fields: `systemPrompt`, `model`, and `reasoningEffort`.

The bridge replies with the remote SDP answer:

```json
{
  "type": "realtime.sdp",
  "deviceId": "pixel",
  "sdp": "v=0\r\n..."
}
```

### Events

Transcript deltas and final transcript text both use `realtime.transcript_delta`. Final text is marked with `isFinal: true`.

```json
{
  "type": "realtime.transcript_delta",
  "deviceId": "pixel",
  "role": "assistant",
  "delta": "Open",
  "isFinal": false
}
```

```json
{
  "type": "realtime.transcript_delta",
  "deviceId": "pixel",
  "role": "assistant",
  "delta": "",
  "text": "Opening Settings.",
  "isFinal": true
}
```

Raw non-audio realtime items are forwarded for Android-side normalization or debugging:

```json
{
  "type": "realtime.item_added",
  "deviceId": "pixel",
  "item": { "type": "message", "role": "assistant" }
}
```

Speech-start notifications are sent when the Codex app-server emits a speech-start notification or forwards a raw item whose type contains `speech_started`:

```json
{
  "type": "realtime.speech_started",
  "deviceId": "pixel",
  "role": "user",
  "itemId": null
}
```

### Stop, Errors, And Close

Android sends:

```json
{
  "type": "realtime.stop",
  "deviceId": "pixel",
  "reason": "User hung up"
}
```

The bridge sends `realtime.error` if Codex rejects startup, stop, or a runtime realtime event fails:

```json
{
  "type": "realtime.error",
  "deviceId": "pixel",
  "message": "Codex app-server rejected experimental thread/realtime/start: method not found"
}
```

The bridge sends `realtime.closed` when the realtime transport closes or the local session is cleaned up:

```json
{
  "type": "realtime.closed",
  "deviceId": "pixel",
  "reason": "User hung up"
}
```
