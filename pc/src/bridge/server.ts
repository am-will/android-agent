import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { WebSocketServer } from "ws";
import { AuditLog } from "./AuditLog.js";
import { Dispatcher } from "../dispatcher/dispatcher.js";
import {
  inboundPhoneMessageSchema,
  phoneCommandSchema,
  type PhoneCommandRequest
} from "../protocol/messages.js";
import { getBridgeConfig } from "./config.js";
import { PhoneHub } from "./PhoneHub.js";

const config = getBridgeConfig();
const audit = new AuditLog();
const hub = new PhoneHub(config.defaultDeviceId, audit);
const dispatcher = new Dispatcher(hub, audit);

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

  if (req.method === "POST" && url.pathname === "/api/agent/stop") {
    const body = (await readJson(req)) as { deviceId?: unknown; reason?: unknown };
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : config.defaultDeviceId;
    const reason = typeof body.reason === "string" ? body.reason : "Stopped by user";
    await dispatcher.stopActiveTurn(deviceId, reason);
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
    try {
      const message = inboundPhoneMessageSchema.parse(JSON.parse(data.toString()));
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
          dispatcher.stopActiveTurn(message.deviceId, message.reason ?? "Stopped from Android").catch((error) => {
            hub.sendStatus(message.deviceId, {
              deviceId: message.deviceId,
              status: "error",
              text: error instanceof Error ? error.message : String(error)
            });
          });
        }
      }
    } catch (error) {
      socket.send(JSON.stringify({
        type: "agent_status",
        status: "error",
        text: error instanceof Error ? error.message : String(error)
      }));
    }
  });

  socket.on("close", () => hub.unregister(socket));
});

server.listen(config.port, config.host, () => {
  console.log(`android-agent bridge listening on ws://${config.host}:${config.port}/phone`);
  console.log(`HTTP API listening on ${config.bridgeUrl}`);
});
