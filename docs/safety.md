# Safety

The dispatcher prepends a safety policy to each phone-control task before it reaches the active desktop agent session. The copied implementation sends that policy to Codex app-server; the target implementation must send the same policy to Open Claw.

The active session adapter must call `phone_ask_user_confirmation` before:

- Purchases or final order placement
- Payments or money movement
- Crypto transactions
- Account or security changes
- App installs or deletions
- Sending messages or email

The Android app displays a confirmation overlay above the current app. If the user cancels, the command result has `ok: false` and `error: "User did not confirm"`.

Biometric, fingerprint, passkey, and credential prompts are always manual. The agent should stop and ask the user to handle them.
