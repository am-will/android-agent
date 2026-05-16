# Codex App-Server Dispatcher

The dispatcher starts Codex app-server with:

```bash
codex app-server --listen stdio://
```

Override it with:

```bash
export CODEX_APP_SERVER_COMMAND="codex app-server --listen stdio://"
export CODEX_AGENT_CWD="$(pwd)"
```

The client performs:

1. `initialize`
2. `initialized`
3. `thread/start`
4. `turn/start` with the Android user request and safety instructions

If app-server integration fails at runtime, the dispatcher reports the exact error to the Android bubble and invokes the isolated fallback adapter. Set `PHONE_AGENT_USE_FALLBACK=1` to test that path deliberately.

`npm run codex:schemas` writes generated bindings under `pc/src/generated/codex-app-server`. They are intentionally excluded from the prototype `tsconfig.json` because the current Codex generator emits extensionless relative imports, while this package uses NodeNext ESM imports.
