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

## Agent Control

Android can ask the bridge to stop the active phone-control turn. The same message is used by the bubble stop button, realtime voice cancellation, and the foreground notification **Stop Turn** action.

```json
{
  "type": "agent_control",
  "deviceId": "pixel",
  "action": "stop",
  "reason": "Stopped from Android notification"
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

### Tool Calls

Realtime voice sessions expose one high-level OpenAI function tool, `run_phone_task`. Android parses function-call events from the WebRTC data channel and relays the completed call to the PC bridge:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "pixel",
  "callId": "call_abc",
  "itemId": "item_abc",
  "name": "run_phone_task",
  "arguments": {
    "instruction": "Open Facebook messages",
    "urgency": "normal"
  }
}
```

The bridge validates that `name` is `run_phone_task`, rejects empty or oversized instructions, and runs the instruction through the existing Codex phone-agent dispatcher. Only one task runs per device. Later calls queue FIFO up to the bridge limit; calls with `"urgency": "interrupt"` stop the active Codex turn before starting the new task.

Task status updates are sent whenever the active task or queue changes:

```json
{
  "type": "realtime.task_status",
  "deviceId": "pixel",
  "running": true,
  "queued": 1,
  "currentTask": "Open Facebook messages",
  "completed": 0,
  "failed": 0
}
```

When the task finishes, fails, times out, or is cancelled, the bridge sends a correlated result:

```json
{
  "type": "realtime.tool_result",
  "deviceId": "pixel",
  "callId": "call_abc",
  "ok": true,
  "status": "completed",
  "output": "Facebook messages are open."
}
```

Android sends that result back to OpenAI Realtime as a `function_call_output` conversation item, followed by `response.create`, so Realtime can speak the outcome while the WebRTC session remains connected.

Speech-start notifications are shown when the WebRTC data channel emits an OpenAI speech-start event:

```json
{
  "type": "realtime.speech_started",
  "deviceId": "pixel",
  "role": "user",
  "itemId": null
}
```

### Device Validation

Use these checks after starting the PC bridge and Android bubble:

1. Start realtime voice and say, “Open Facebook messages.”
2. Verify Realtime gives a short spoken acknowledgement and does not hang up.
3. Verify the Android voice panel shows the active task and queued count.
4. While the task is running, speak another actionable instruction and verify it queues, or use an explicit interrupt instruction and verify the active task is cancelled.
5. After the first task changes phone state, say a follow-up such as “Message Alice…” and verify Codex acts from the current screen.
6. Verify risky actions still trigger the existing Android/Codex confirmation flow before proceeding.

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
