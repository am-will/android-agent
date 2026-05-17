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

Coordinate taps use full-screen pixels, including the status and navigation bars. When a caller chooses a point from a screenshot that may have been shown at a scaled size, prefer `tap_normalized`:

```json
{
  "id": "cmd_124",
  "type": "command",
  "command": "tap_normalized",
  "args": { "xPct": 0.5, "yPct": 0.25 }
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
  "screenshot": {
    "widthPx": 1080,
    "heightPx": 2340
  },
  "error": null
}
```

`take_screenshot` results include `screenshotBase64` plus `screenshot.widthPx` and `screenshot.heightPx`. Those dimensions are the source of truth for mapping visual screenshot positions back to phone coordinates.

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
  "text": "Agent started working"
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
  "location": {
    "latitude": 31.7619,
    "longitude": -106.485,
    "accuracyMeters": 100,
    "provider": "network",
    "capturedAtMs": 1779050000000
  },
  "openAiApiKey": "sk-..."
}
```

Optional fields: `systemPrompt`, `model`, `reasoningEffort`, `location`, and `openAiApiKey`. Android sends `location` only when the user has granted location permission and the device has a recent best-effort location available. The bridge uses it as context for localized realtime answers and web searches.

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

Realtime voice sessions expose high-level OpenAI function tools. Android parses function-call events from the WebRTC data channel and relays the completed call to the PC bridge.

Use `run_phone_task` for new actionable phone tasks:

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

The bridge validates that `name` is `run_phone_task`, rejects empty or oversized instructions, and runs the instruction through the active phone-task dispatcher. Only one task runs per device. Later calls queue FIFO up to the bridge limit; calls with `"urgency": "interrupt"` interrupt the active task before starting the new task. Today the dispatcher is the copied Codex app-server client; the target dispatcher is an Open Claw session adapter.

Use `steer_phone_task` when the user corrects or adds information while a phone task is running. The bridge injects the guidance into the active task using the current dispatcher adapter:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "pixel",
  "callId": "call_steer",
  "name": "steer_phone_task",
  "arguments": {
    "guidance": "Stop looking in settings; use the already open Messages app."
  }
}
```

Use `stop_phone_task` when the user says to stop, pause, cancel, or leave the phone as-is. The bridge cancels queued realtime tasks, rejects pending phone commands, and interrupts the active dispatcher task:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "pixel",
  "callId": "call_stop",
  "name": "stop_phone_task",
  "arguments": {
    "reason": "User said stop."
  }
}
```

Use `hang_up_realtime` when the user says to hang up, end the call, or stop listening. By default it closes only the realtime voice session and lets any running phone task continue. Set `stopPhoneTask` only when the user explicitly asks to stop the phone task and hang up:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "pixel",
  "callId": "call_hangup",
  "name": "hang_up_realtime",
  "arguments": {
    "reason": "User asked to hang up.",
    "stopPhoneTask": false
  }
}
```

Use `web_search` for current-information questions that do not require controlling the phone. The bridge answers through OpenAI Responses API web search and returns the text as the tool output:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "pixel",
  "callId": "call_search",
  "name": "web_search",
  "arguments": {
    "query": "El Paso TX weather today"
  }
}
```

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
5. After the first task changes phone state, say a follow-up such as “Message Alice…” and verify the active dispatcher acts from the current screen.
6. Verify risky actions still trigger the Android confirmation flow before proceeding.

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
