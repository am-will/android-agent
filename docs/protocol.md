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

Realtime voice mode uses Android WebRTC for live audio and the PC bridge for OpenAI Realtime session creation. Android creates the WebRTC offer, sends it to the PC bridge, and the bridge posts it to OpenAI's `/v1/realtime/calls` endpoint. Android message names use dotted `realtime.*` types.

The OpenAI API key can be supplied either by setting `OPENAI_API_KEY` on the PC bridge or by saving it in the Android app settings. If the Android app sends an `openAiApiKey` in `realtime.start`, the bridge uses it only for that realtime call. The bridge defaults voice sessions to `gpt-realtime-2`; override with `OPENAI_REALTIME_MODEL` if needed.

### Start

Android sends:

```json
{
  "type": "realtime.start",
  "deviceId": "pixel",
  "sdp": "v=0\r\n...",
  "openAiApiKey": "sk-..."
}
```

Optional fields: `systemPrompt`, `model`, `reasoningEffort`, and `openAiApiKey`.

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

Speech-start notifications are shown when the WebRTC data channel emits an OpenAI speech-start event:

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

The bridge sends `realtime.error` if OpenAI rejects startup, stop, or a runtime realtime event fails:

```json
{
  "type": "realtime.error",
  "deviceId": "pixel",
  "message": "OpenAI realtime call failed: 401 Unauthorized"
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
