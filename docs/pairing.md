# Pairing

Use a shared token for the prototype.

## Local Wi-Fi

This is the preferred mode once the app is installed. It does not rely on `adb reverse`, so the phone can be unplugged as long as it stays on the same LAN as the PC.

1. Start the bridge on the PC:

```bash
export PHONE_AGENT_TOKEN=change-me
npm run bridge
```

2. Find the PC LAN IP:

```bash
ipconfig getifaddr en0
```

If that is empty, inspect active interfaces:

```bash
ifconfig | rg "inet .*broadcast|status: active|^[a-z].*:"
```

3. On Android, set:

- WebSocket URL: `ws://<pc-lan-ip>:8787/phone`
- Device ID: `pixel`
- Token: `change-me`

For example:

- WebSocket URL: `ws://192.168.1.163:8787/phone`

4. Tap **Save**, then **Start Agent Bubble**.

The bridge `/health` endpoint shows connected phones:

```bash
curl http://127.0.0.1:8787/health
```

You should see the device under `phones`.

If the phone does not register:

- Confirm the phone and PC are on the same Wi-Fi/subnet.
- Keep the phone unlocked while starting the agent.
- Confirm the bridge is listening on all interfaces:

```bash
lsof -nP -iTCP:8787 -sTCP:LISTEN
```

The listener should show `*:8787`, not only `127.0.0.1:8787`.

- If macOS prompts for local-network/firewall access, allow it for the terminal or Node.js process running the bridge.

## USB Development Mode

USB mode is useful while installing or debugging, but it depends on `adb reverse`. If the cable is unplugged, the reverse port mapping disappears.

```bash
cd pc
npm run phone:usb
```

In this mode the Android app may use:

```text
ws://127.0.0.1:8787/phone
```

Run `npm run phone:usb` again after reconnecting USB.

## Mobile Data Later

Mobile data will need a public relay or tunnel because the phone cannot directly reach a private LAN IP like `192.168.x.x` from the cellular network. Good prototype options are:

- A secure tunnel from the Mac to the internet, such as Cloudflare Tunnel, Tailscale Funnel, or ngrok.
- A small hosted relay service that accepts the phone WebSocket and lets the local dispatcher connect outbound.
- Tailscale on both the Mac and Android, which gives the phone a stable private address path without exposing the bridge publicly.

For mobile data, use `wss://...` and keep token auth enabled.
