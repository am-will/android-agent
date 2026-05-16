# WebSocket Protocol

Android connects outbound to the PC bridge at `/phone`. The bridge validates `token` during registration. Android never implements MCP directly.

## Register

```json
{
  "type": "register",
  "deviceId": "pixel",
  "token": "change-me",
  "capabilities": ["accessibility_tree", "gestures", "text_input", "screenshots", "app_launch"]
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
