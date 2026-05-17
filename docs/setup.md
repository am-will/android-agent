# Setup

## PC

Requirements:

- Node.js 24+
- Codex CLI with `codex app-server`
- Same network reachability from phone to PC
- Gradle or Android Studio for Android builds

Install and start the bridge:

```bash
cd pc
npm install
export PHONE_AGENT_TOKEN=change-me
export PHONE_AGENT_DEFAULT_DEVICE=pixel
npm run bridge
```

The bridge exposes:

- `ws://0.0.0.0:8787/phone` for Android
- `http://127.0.0.1:8787/health` for local status
- `POST http://127.0.0.1:8787/api/phone/default/command` for the MCP server

## Android

Open `android/` in Android Studio or run Gradle from that directory. Install the app on the device, then:

1. Save the WebSocket URL, device ID, token, and OpenAI API key if you want realtime voice or composer transcription.
2. Grant overlay permission.
3. If Android shows **Restricted setting**, open **Settings > Apps > Android Agent**, use the three-dot menu, choose **Allow restricted settings**, and authenticate.
4. Enable **Settings > Accessibility > Installed apps > Android Agent**.
5. Confirm the switch still says **On** after leaving and returning to that page.
6. Start the foreground agent bubble.

While Android Agent is running, the foreground notification includes a **Stop Turn** action. Use it to stop the active phone-control turn from the notification shade, including moments when the floating bubble is temporarily hidden during taps, swipes, or screenshots.

For adb installs, build `android/app/build/outputs/apk/debug/app-debug.apk` and run:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

For USB testing, forward the bridge port so the phone can use the default loopback URL:

```bash
adb reverse tcp:8787 tcp:8787
```

Accessibility is intentionally user-controlled by Android. It is commonly disabled by the OS after reinstalling/updating a sideloaded APK, after uninstall/reinstall cycles, or if Android's restricted-settings gate has not been allowed. For the most stable testing loop: install once, allow restricted settings once, enable Accessibility manually once, then use normal app restarts without reinstalling.

If using adb to enable the service on a test device, preserve other enabled services by appending with `:` instead of replacing the whole setting.
