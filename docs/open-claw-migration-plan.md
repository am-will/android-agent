# Open Claw Dispatcher Migration Plan

**Generated**: 2026-05-17  
**Estimated Complexity**: Medium to High

## Overview

Open Claw Agent should keep the Android bridge, Android accessibility executor, and OpenAI Realtime voice flow. The main migration is the PC dispatcher: replace the copied Codex app-server client with an adapter that can send phone-control tasks to an already installed Open Claw session on the user's remote PC.

The best path is to keep `pc/src/dispatcher/dispatcher.ts` as the stable boundary and add an `OpenClawSessionClient` behind the existing `AgentClient` interface. Realtime `run_phone_task`, bubble text requests, stop, steer, status updates, and Android confirmation should continue to use the same bridge-facing contracts while the desktop-agent implementation changes underneath.

## Target Architecture

1. Android keeps connecting to the PC bridge over `/phone`.
2. Android voice keeps using OpenAI Realtime through the PC bridge.
3. The PC bridge queues phone tasks per device exactly as it does today.
4. The dispatcher sends task instructions, safety policy, model options, and device context to Open Claw.
5. Open Claw uses local phone-control tools exposed by this repo, either through MCP or a direct HTTP/tool bridge.
6. Tool calls continue to flow through the PC bridge to Android, and observations/results return to Open Claw.
7. The Codex app-server client remains temporarily as a compatibility adapter until Open Claw parity is verified.

## Open Questions

- What stable control surface does the installed Open Claw session expose: MCP client config, local HTTP API, websocket, CLI command, or a session/socket protocol?
- Can Open Claw accept mid-turn steering and interruption, or do we need to emulate those with cancellation plus a new task?
- Does Open Claw already support MCP tools, or should `android-phone` be exposed through a native Open Claw tool registry?
- How should the bridge select a remote PC/session when multiple Open Claw sessions are available?
- What auth boundary is required between this bridge and the remote PC session beyond the local prototype token?

## Sprint 1: Stabilize The Adapter Boundary

**Goal**: Make the current dispatcher explicitly multi-adapter without changing behavior.

**Demo/Validation**:

- `PHONE_AGENT_DISPATCHER=codex npm run bridge` behaves like today.
- `PHONE_AGENT_USE_FALLBACK=1 npm run bridge` still exercises fallback.
- Existing queue, stop, steer, and realtime task tests continue to pass.

### Task 1.1: Rename Intent Without Breaking Code

- **Location**: `pc/src/dispatcher/`, `docs/app-server.md`
- **Description**: Keep `CodexAppServerClient` as a legacy implementation, but document it as one `AgentClient` adapter.
- **Dependencies**: None
- **Acceptance Criteria**:
  - Dispatcher terminology in docs and logs no longer implies Codex is the product.
  - Codex-specific env vars remain supported while the adapter exists.
- **Validation**:
  - `cd pc && npm run check`
  - `cd pc && npm test`

### Task 1.2: Add Dispatcher Selection Config

- **Location**: `pc/src/dispatcher/dispatcher.ts`, `pc/src/bridge/server.ts`
- **Description**: Introduce `PHONE_AGENT_DISPATCHER=codex|openclaw|fallback`, defaulting to the safest currently working path until Open Claw is implemented.
- **Dependencies**: Task 1.1
- **Acceptance Criteria**:
  - Unknown dispatcher values fail fast with a clear bridge startup error.
  - Fallback path remains available for local bridge testing.
- **Validation**:
  - Unit tests for config parsing.
  - Manual startup with each supported value.

## Sprint 2: Define The Open Claw Session Contract

**Goal**: Decide and document exactly how the bridge talks to Open Claw before wiring production behavior.

**Demo/Validation**:

- A local test script can create an Open Claw task session, send a prompt, receive status, and terminate the task.
- A contract document names every required operation and expected error.

### Task 2.1: Discover Open Claw Control APIs

- **Location**: Open Claw install docs/source, `docs/open-claw-migration-plan.md`
- **Description**: Identify whether Open Claw is best controlled through MCP, HTTP, websocket, CLI subprocess, or an existing app session protocol.
- **Dependencies**: None
- **Acceptance Criteria**:
  - The selected control surface supports task start, tool calls, output streaming, and cancellation, or the plan documents gaps.
  - The integration path works with an installed remote-PC session instead of spawning a separate unrelated agent.
- **Validation**:
  - Manual proof of concept against the installed Open Claw session.

### Task 2.2: Specify Required Adapter Methods

- **Location**: `pc/src/dispatcher/AgentClient.ts` or equivalent, `docs/protocol.md`
- **Description**: Map current behavior to Open Claw operations: start task, stream status, call phone tools, steer task, interrupt task, close session.
- **Dependencies**: Task 2.1
- **Acceptance Criteria**:
  - Every existing bridge feature has an Open Claw equivalent or explicit fallback behavior.
  - Realtime task queueing does not depend on Codex `turn/start` or `turn/steer` terms.
- **Validation**:
  - Contract tests with a fake Open Claw client.

## Sprint 3: Implement The Open Claw Adapter

**Goal**: Add `OpenClawSessionClient` while leaving Android and realtime protocol contracts unchanged.

**Demo/Validation**:

- Bubble text request opens Settings through Open Claw.
- Realtime voice can say “Open Settings,” receive spoken acknowledgement, and report completion.
- Stop and interrupt behave correctly from the Android bubble, notification, and realtime voice.

### Task 3.1: Build `OpenClawSessionClient`

- **Location**: `pc/src/dispatcher/`
- **Description**: Implement the selected Open Claw control protocol behind the existing `AgentClient` shape.
- **Dependencies**: Sprint 2
- **Acceptance Criteria**:
  - Sends safety prompt plus user task to Open Claw.
  - Streams meaningful working/done/error status to Android.
  - Surfaces Open Claw startup/session errors without silently falling through.
- **Validation**:
  - Unit tests with mocked Open Claw responses.
  - Manual task execution against the installed session.

### Task 3.2: Expose Phone Tools To Open Claw

- **Location**: `pc/src/mcp/tools.ts`, `pc/src/mcp/androidPhoneServer.ts`, Open Claw tool config
- **Description**: Reuse the existing phone command implementation so Open Claw can observe, tap, type, launch apps, screenshot, wait, and ask for confirmation.
- **Dependencies**: Task 3.1
- **Acceptance Criteria**:
  - Tool names and arguments stay aligned with Android command validation.
  - Screenshot width/height metadata remains available for normalized tapping.
  - `phone_ask_user_confirmation` remains mandatory for high-risk actions.
- **Validation**:
  - Tool-level tests.
  - Manual observe/open/tap/type/screenshot confirmation flow.

### Task 3.3: Preserve Steering, Interrupts, And Queueing

- **Location**: `pc/src/bridge/RealtimeTaskManager.ts`, `pc/src/dispatcher/`
- **Description**: Keep the bridge-owned FIFO queue and map realtime `steer_phone_task` and `stop_phone_task` onto Open Claw session capabilities.
- **Dependencies**: Task 3.1
- **Acceptance Criteria**:
  - Normal tasks queue while a device task is running.
  - Interrupt urgency cancels or supersedes the active Open Claw task.
  - Steering reaches the active task or returns a clear unsupported error.
- **Validation**:
  - Focused tests for queueing, interrupt, steer, and cancellation.

## Sprint 4: Flip Defaults And Retire Codex Language

**Goal**: Make Open Claw the default and demote Codex to optional legacy support or remove it.

**Demo/Validation**:

- Fresh setup docs lead to an Open Claw-powered phone-control demo without mentioning Codex as a required dependency.
- Legacy Codex tests either remain explicitly labeled or are deleted with the old adapter.

### Task 4.1: Update Defaults

- **Location**: `pc/src/dispatcher/dispatcher.ts`, `pc/package.json`, `README.md`, `docs/setup.md`
- **Description**: Default to `PHONE_AGENT_DISPATCHER=openclaw` once parity is proven.
- **Dependencies**: Sprint 3
- **Acceptance Criteria**:
  - Quick Start works with Open Claw.
  - Codex-specific setup is no longer in the primary path.
- **Validation**:
  - End-to-end Android demo from a clean checkout.

### Task 4.2: Remove Or Archive Codex Artifacts

- **Location**: `pc/src/dispatcher/CodexAppServerClient.ts`, `pc/src/generated/codex-app-server/`, `docs/app-server.md`, `docs/codex-mcp.md`
- **Description**: Decide whether to keep Codex as a legacy adapter or remove generated schemas and docs.
- **Dependencies**: Task 4.1
- **Acceptance Criteria**:
  - No primary docs or agent guidance describe Codex as required.
  - Any retained Codex files are clearly marked legacy.
- **Validation**:
  - `rg "Codex|codex|app-server" *.md docs pc/src` returns only intentional legacy references.

## Testing Strategy

- Run PC static checks and tests after each dispatcher change: `cd pc && npm run check && npm test`.
- Add fake-client unit tests for Open Claw start, status streaming, tool call routing, cancellation, and errors.
- Add realtime tests for `run_phone_task`, `steer_phone_task`, `stop_phone_task`, FIFO queueing, interrupt urgency, and tool result delivery.
- Manually test Android pairing, overlay permission, accessibility execution, screenshot coordinate metadata, and confirmation overlays.
- Run a full voice demo after adapter parity: start realtime, request a phone action, interrupt it, steer it, and complete a risky-action confirmation test.

## Risks And Mitigations

- **Open Claw may not expose a steerable session API**: keep queueing in the bridge and model steering as cancellation plus a new task if needed.
- **Tool schemas may not map cleanly from MCP**: keep `pc/src/mcp/tools.ts` as the canonical command list and generate or adapt schemas from that source.
- **Remote PC auth may be underspecified**: keep local prototype token support, then add per-session credentials before exposing beyond a trusted LAN.
- **Status text may leak legacy naming**: audit Android UI strings, dispatcher logs, and docs during the default flip.
- **Realtime voice can outpace task execution**: keep the existing bridge queue and tool-result correlation so OpenAI Realtime receives deterministic completion or failure output.

## Rollback Plan

- Keep the Codex app-server adapter behind `PHONE_AGENT_DISPATCHER=codex` until Open Claw parity is proven.
- Keep `PHONE_AGENT_USE_FALLBACK=1` for bridge-only demos and Android command validation.
- If Open Claw integration fails during testing, switch the dispatcher env var back to `codex` or `fallback` without changing Android builds.
