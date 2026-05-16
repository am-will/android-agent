# Known Limitations

- Overlay and accessibility permissions must be granted manually.
- On the tested Samsung Galaxy device, adb writes to `enabled_accessibility_services` were reset by the OS after process restarts. Enabling the service through Settings worked, but bridge-only `open_app` is also supported so the MVP can still launch apps before Accessibility is bound.
- Screenshot capture requires Android API 30+ and may be rate-limited by the OS.
- Android `take_screenshot` captures through `AccessibilityService`, so it does not require adb after the app is paired and accessibility is enabled. The MCP tool saves the PNG under `captures/` and returns `screenshotPath`; the raw WebSocket result still carries base64 internally.
- Node IDs are per-observation and should be refreshed with `phone_observe` after navigation.
- Text input relies on `ACTION_SET_TEXT`, which some custom controls may reject.
- App-name launch matching is fuzzy. Prefer `packageName` for reliable automation.
- The bridge uses a shared token suitable for a local prototype, not production auth.
- Codex app-server protocol may change by installed version. Generate schemas with `npm run codex:schemas` when needed.
