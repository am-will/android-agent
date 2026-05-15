# Codex MCP Configuration

Run the PC bridge first:

```bash
cd pc
PHONE_AGENT_TOKEN=change-me npm run bridge
```

For project-scoped Codex config, create `.codex/config.toml` with paths for your checkout:

```toml
[mcp_servers.android-phone]
command = "<repo-root>/pc/node_modules/.bin/tsx"
args = ["<repo-root>/pc/src/mcp/androidPhoneServer.ts"]
cwd = "<repo-root>/pc"
enabled = true
startup_timeout_sec = 20
tool_timeout_sec = 60

[mcp_servers.android-phone.env]
PHONE_AGENT_BRIDGE_URL = "http://127.0.0.1:8787"
```

Codex only loads project `.codex/config.toml` for trusted projects. To make the server available immediately through the official CLI-managed config, run:

```bash
codex mcp add android-phone \
  --env PHONE_AGENT_BRIDGE_URL=http://127.0.0.1:8787 \
  -- "$(pwd)/pc/node_modules/.bin/tsx" "$(pwd)/pc/src/mcp/androidPhoneServer.ts"
```

Verify:

```bash
codex mcp list
codex mcp get android-phone
```

If using Codex app-server, reload MCP config or restart app-server after changing config. The dispatcher uses `turn/start` and expects Codex to discover the `android-phone` tools from config.

The server exposes:

- `phone_observe`
- `phone_open_app`
- `phone_tap_node`
- `phone_tap_xy`
- `phone_long_press_node`
- `phone_type_text`
- `phone_scroll`
- `phone_swipe`
- `phone_press_back`
- `phone_press_home`
- `phone_open_recents`
- `phone_take_screenshot`
- `phone_ask_user_confirmation`
- `phone_wait`
