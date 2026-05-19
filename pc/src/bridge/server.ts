import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { WebSocketServer } from "ws";
import { AuditLog } from "./AuditLog.js";
import { Dispatcher } from "../dispatcher/dispatcher.js";
import { OpenClawChatBridge } from "./OpenClawChatBridge.js";
import {
  getPetSpritesheet,
  listPets,
  openSpritesheetStream,
  resolvePetsDir,
  spritesheetEtag
} from "./PetCatalog.js";
import { OpenAiRealtimeClient, type OpenAiRealtimeSession } from "./OpenAiRealtimeClient.js";
import { OpenAiWebSearchClient } from "./OpenAiWebSearchClient.js";
import { RealtimeTaskManager } from "./RealtimeTaskManager.js";
import {
  inboundPhoneMessageSchema,
  phoneCommandSchema,
  type PhoneCommandRequest,
  type RealtimeOutboundMessage,
  type RealtimeStartMessage,
  type RealtimeStopMessage
} from "../protocol/messages.js";
import { getBridgeConfig } from "./config.js";
import { PhoneHub } from "./PhoneHub.js";

const config = getBridgeConfig();
const audit = new AuditLog();
const hub = new PhoneHub(config.defaultDeviceId, audit);
const dispatcher = new Dispatcher(hub, audit);
const chatBridge = new OpenClawChatBridge(config, hub, dispatcher, audit);
const realtimeClient = new OpenAiRealtimeClient(config);
const webSearchClient = new OpenAiWebSearchClient(config);
const realtimeTaskManager = new RealtimeTaskManager({
  taskDelegate: chatBridge,
  audit,
  sendRealtime,
  webSearch: webSearchClient,
  getRealtimeApiKey: (deviceId) => realtimeSessions.get(deviceId)?.apiKey,
  getRealtimeLocation: (deviceId) => realtimeSessions.get(deviceId)?.location
});

const realtimeSessions = new Map<string, OpenAiRealtimeSession>();

function json(res: ServerResponse, status: number, body: unknown): void {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(data)
  });
  res.end(data);
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  if (chunks.length === 0) {
    return {};
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function sendRealtime(deviceId: string, message: RealtimeOutboundMessage): void {
  try {
    hub.sendRealtime(deviceId, message);
  } catch (error) {
    console.warn(`[realtime] ${deviceId}: failed to send ${message.type}: ${error instanceof Error ? error.message : String(error)}`);
  }
}

function sendRealtimeError(deviceId: string, message: string): void {
  sendRealtime(deviceId, {
    type: "realtime.error",
    deviceId,
    message
  });
}

async function startRealtimeSession(message: RealtimeStartMessage, registeredDeviceId: string): Promise<void> {
  if (message.deviceId !== registeredDeviceId) {
    sendRealtimeError(registeredDeviceId, `realtime.start deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
    return;
  }

  const existing = realtimeSessions.get(message.deviceId);
  if (existing) {
    await stopRealtimeSession(message.deviceId, "Replaced by newer realtime.start");
  }

  try {
    audit.record("openai_realtime_starting", message.deviceId, {
      model: config.openAiRealtimeModel,
      voice: config.openAiRealtimeVoice,
      sdpLength: message.sdp.length
    });
    const { answerSdp, session } = await realtimeClient.start({
      deviceId: message.deviceId,
      sdp: message.sdp,
      systemPrompt: message.systemPrompt,
      apiKey: message.openAiApiKey,
      location: message.location
    });
    realtimeSessions.set(message.deviceId, session);
    audit.record("openai_realtime_started", message.deviceId, {
      callId: session.callId ?? null,
      answerSdpLength: answerSdp.length
    });
    sendRealtime(message.deviceId, { type: "realtime.sdp", deviceId: message.deviceId, sdp: answerSdp });
  } catch (error) {
    realtimeSessions.delete(message.deviceId);
    const errorMessage = error instanceof Error ? error.message : String(error);
    audit.record("openai_realtime_error", message.deviceId, { message: errorMessage });
    sendRealtimeError(message.deviceId, errorMessage);
  }
}

async function stopRealtimeSession(deviceId: string, reason = "Stopped by user"): Promise<void> {
  const session = realtimeSessions.get(deviceId);
  if (!session) {
    sendRealtime(deviceId, { type: "realtime.closed", deviceId, reason });
    return;
  }
  realtimeSessions.delete(deviceId);
  await realtimeClient.stop(session);
  sendRealtime(deviceId, { type: "realtime.closed", deviceId, reason });
}

async function handleRealtimeStop(message: RealtimeStopMessage, registeredDeviceId: string): Promise<void> {
  if (message.deviceId !== registeredDeviceId) {
    sendRealtimeError(registeredDeviceId, `realtime.stop deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
    return;
  }
  await stopRealtimeSession(message.deviceId, message.reason ?? "Stopped by Android");
}

async function handleRealtimeHangUpToolCall(
  message: { deviceId: string; arguments: Record<string, unknown> },
  registeredDeviceId: string
): Promise<void> {
  if (message.deviceId !== registeredDeviceId) {
    sendRealtimeError(registeredDeviceId, `hang_up_realtime deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
    return;
  }

  const reason = typeof message.arguments.reason === "string" && message.arguments.reason.trim()
    ? message.arguments.reason.trim()
    : "Realtime voice hung up";
  if (message.arguments.stopPhoneTask === true) {
    await stopAgentWork(message.deviceId, reason);
  }
  await stopRealtimeSession(message.deviceId, reason);
}

async function stopAgentWork(deviceId: string, reason: string): Promise<void> {
  hub.cancelPendingCommands(deviceId, reason);
  await realtimeTaskManager.cancelDevice(deviceId, reason);
}

async function handleHttp(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);

  if (req.method === "GET" && url.pathname === "/health") {
    json(res, 200, { ok: true, phones: hub.listPhones() });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/phones") {
    json(res, 200, { phones: hub.listPhones(), defaultDeviceId: config.defaultDeviceId });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/audit/recent") {
    const limit = Number.parseInt(url.searchParams.get("limit") ?? "100", 10);
    json(res, 200, { events: audit.recent(Number.isFinite(limit) ? limit : 100) });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/audit/active") {
    json(res, 200, { activeTurns: audit.active() });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/phone/default/command") {
    const body = (await readJson(req)) as Partial<PhoneCommandRequest>;
    const command = phoneCommandSchema.parse(body.command);
    console.log(`[command] ${body.deviceId ?? config.defaultDeviceId}: ${command}`);
    const result = await hub.sendCommand({
      deviceId: typeof body.deviceId === "string" ? body.deviceId : undefined,
      command,
      args: typeof body.args === "object" && body.args ? (body.args as Record<string, unknown>) : {},
      timeoutMs: typeof body.timeoutMs === "number" ? body.timeoutMs : undefined
    });
    console.log(`[result] ${result.deviceId}: ${command} ok=${result.ok}${result.error ? ` error=${result.error}` : ""}`);
    json(res, result.ok ? 200 : 502, result);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/user_request") {
    const body = (await readJson(req)) as { deviceId?: unknown; text?: unknown };
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : config.defaultDeviceId;
    const text = typeof body.text === "string" ? body.text.trim() : "";
    if (!text) {
      json(res, 400, { ok: false, error: "text is required" });
      return;
    }

    const result = await dispatcher.handleUserRequest({
      type: "user_request",
      inputType: "text",
      deviceId,
      text
    });
    json(res, result.error ? 502 : 200, { ok: !result.error, result });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/pets") {
    const pets = await listPets(resolvePetsDir());
    json(res, 200, { pets });
    return;
  }

  const petsSpritesheetMatch = url.pathname.match(/^\/api\/pets\/([^/]+)\/spritesheet$/);
  if (req.method === "GET" && petsSpritesheetMatch) {
    const petId = decodeURIComponent(petsSpritesheetMatch[1] ?? "");
    const info = await getPetSpritesheet(petId, resolvePetsDir());
    if (!info) {
      json(res, 404, { ok: false, error: "pet not found" });
      return;
    }
    const etag = spritesheetEtag(info);
    if (req.headers["if-none-match"] === etag) {
      res.writeHead(304, { etag });
      res.end();
      return;
    }
    res.writeHead(200, {
      "content-type": "image/webp",
      "content-length": info.sizeBytes,
      "cache-control": "no-cache",
      etag
    });
    const stream = openSpritesheetStream(info);
    stream.on("error", (error) => {
      console.warn(`[pets] failed to stream spritesheet for ${petId}: ${error instanceof Error ? error.message : String(error)}`);
      res.destroy(error instanceof Error ? error : new Error(String(error)));
    });
    stream.pipe(res);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/agent/stop") {
    const body = (await readJson(req)) as { deviceId?: unknown; reason?: unknown };
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : config.defaultDeviceId;
    const reason = typeof body.reason === "string" ? body.reason : "Stopped by user";
    await stopAgentWork(deviceId, reason);
    json(res, 200, { ok: true });
    return;
  }

  json(res, 404, { ok: false, error: "not found" });
}

const server = createServer((req, res) => {
  handleHttp(req, res).catch((error) => {
    json(res, 500, { ok: false, error: error instanceof Error ? error.message : String(error) });
  });
});

const wss = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
  if (url.pathname !== "/phone") {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
});

wss.on("connection", (socket) => {
  let deviceId: string | undefined;

  socket.on("message", (data) => {
    let rawMessage: unknown;
    try {
      rawMessage = JSON.parse(data.toString());
      const message = inboundPhoneMessageSchema.parse(rawMessage);
      if (message.type === "register") {
        if (message.token !== config.token) {
          socket.close(4001, "invalid token");
          return;
        }
        deviceId = message.deviceId;
        hub.register(message, socket);
        audit.record("phone_registered", deviceId, {
          capabilities: message.capabilities,
          connectedAt: Date.now()
        });
        hub.sendStatus(deviceId, { deviceId, status: "info", text: `Registered ${deviceId}` });
        return;
      }

      if (!deviceId) {
        socket.close(4002, "register first");
        return;
      }

      if (message.type === "result") {
        hub.handleResult(deviceId, message);
        return;
      }

      if (message.type === "user_request") {
        dispatcher.handleUserRequest(message).catch((error) => {
          hub.sendStatus(message.deviceId, {
            deviceId: message.deviceId,
            status: "error",
            text: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "agent_control") {
        if (message.action === "stop") {
          stopAgentWork(message.deviceId, message.reason ?? "Stopped from Android").catch((error) => {
            hub.sendStatus(message.deviceId, {
              deviceId: message.deviceId,
              status: "error",
              text: error instanceof Error ? error.message : String(error)
            });
          });
        }
        return;
      }

      if (message.type === "chat.open") {
        chatBridge.open(message).catch((error) => {
          hub.sendChat(message.deviceId, {
            type: "chat.error",
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            message: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "chat.send") {
        chatBridge.send(message).catch((error) => {
          hub.sendChat(message.deviceId, {
            type: "chat.error",
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            message: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "chat.stop") {
        chatBridge.stop(message).catch((error) => {
          hub.sendChat(message.deviceId, {
            type: "chat.error",
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            runId: message.runId,
            message: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "chat.select_session") {
        chatBridge.selectSession(message).catch((error) => {
          hub.sendChat(message.deviceId, {
            type: "chat.error",
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            message: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "chat.new_session") {
        chatBridge.newSession(message).catch((error) => {
          hub.sendChat(message.deviceId, {
            type: "chat.error",
            deviceId: message.deviceId,
            message: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "chat.set_model") {
        chatBridge.setModel(message).catch((error) => {
          hub.sendChat(message.deviceId, {
            type: "chat.error",
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            message: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "chat.set_reasoning") {
        chatBridge.setReasoning(message).catch((error) => {
          hub.sendChat(message.deviceId, {
            type: "chat.error",
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            message: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "chat.control_command") {
        chatBridge.controlCommand(message).catch((error) => {
          hub.sendChat(message.deviceId, {
            type: "chat.error",
            deviceId: message.deviceId,
            message: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "realtime.tool_call") {
        if (message.deviceId !== deviceId) {
          sendRealtimeError(deviceId, `realtime.tool_call deviceId ${message.deviceId} does not match registered device ${deviceId}`);
          return;
        }
        if (message.name === "hang_up_realtime") {
          handleRealtimeHangUpToolCall(message, deviceId).catch((error) => {
            sendRealtimeError(message.deviceId, error instanceof Error ? error.message : String(error));
          });
          return;
        }
        realtimeTaskManager.handleToolCall(message).catch((error) => {
          sendRealtimeError(message.deviceId, error instanceof Error ? error.message : String(error));
        });
        return;
      }

      if (message.type === "realtime.start") {
        startRealtimeSession(message, deviceId).catch((error) => {
          sendRealtimeError(message.deviceId, error instanceof Error ? error.message : String(error));
        });
        return;
      }

      if (message.type === "realtime.stop") {
        handleRealtimeStop(message, deviceId).catch((error) => {
          sendRealtimeError(message.deviceId, error instanceof Error ? error.message : String(error));
        });
      }
    } catch (error) {
      const parsedDeviceId = rawMessage && typeof rawMessage === "object" && typeof (rawMessage as { deviceId?: unknown }).deviceId === "string"
        ? (rawMessage as { deviceId: string }).deviceId
        : deviceId;
      const parsedType = rawMessage && typeof rawMessage === "object" && typeof (rawMessage as { type?: unknown }).type === "string"
        ? (rawMessage as { type: string }).type
        : undefined;
      if (parsedDeviceId && parsedType?.startsWith("realtime.")) {
        sendRealtimeError(parsedDeviceId, error instanceof Error ? error.message : String(error));
        return;
      }
      socket.send(JSON.stringify({
        type: "agent_status",
        status: "error",
        text: error instanceof Error ? error.message : String(error)
      }));
    }
  });

  socket.on("close", () => {
    const disconnectedDeviceId = deviceId;
    hub.unregister(socket);
    if (disconnectedDeviceId) {
      realtimeTaskManager.failDevice(disconnectedDeviceId, "Phone WebSocket disconnected");
      const session = realtimeSessions.get(disconnectedDeviceId);
      if (session) {
        realtimeSessions.delete(disconnectedDeviceId);
        realtimeClient.stop(session).catch((error) => {
          console.warn(`[realtime] ${disconnectedDeviceId}: failed to stop after disconnect: ${error instanceof Error ? error.message : String(error)}`);
        });
      }
    }
  });
});

server.listen(config.port, config.host, () => {
  console.log(`open-claw-agent bridge listening on ws://${config.host}:${config.port}/phone`);
  console.log(`HTTP API listening on ${config.bridgeUrl}`);
});
