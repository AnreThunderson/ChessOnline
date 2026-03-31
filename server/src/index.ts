import * as http from "http";
import { WebSocketServer, WebSocket } from "ws";
import { loadGame, migrate, upsertGame } from "./db";

const PORT = parseInt(process.env.PORT ?? "3000", 10);
const ROOM_CODE_PATTERN = /^[A-Z0-9]{4,6}$/;
const HEARTBEAT_INTERVAL_MS = 30_000;

const ONE_DAY_MS = 86_400_000;

// ── Types ─────────────────────────────────────────────────────────────────────

interface Client {
  id: string;
  role: "host" | "guest";
  ws: ExtWs;
  alive: boolean;
}

interface Room {
  code: string;
  clients: Client[];
}

interface ExtWs extends WebSocket {
  clientRef?: Client;
  roomRef?: Room;
  alive?: boolean;
}

// ── State ─────────────────────────────────────────────────────────────────────

/**
 * Live socket tracking only. Canonical game state lives in Postgres.
 */
const rooms = new Map<string, Room>();

// ── Helpers ───────────────────────────────────────────────────────────────────

function genId(): string {
  return Math.random().toString(36).substring(2, 10);
}

function send(ws: WebSocket, payload: object): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function sendError(ws: WebSocket, message: string): void {
  send(ws, { type: "error", message });
}

function peer(room: Room, self: Client): Client | undefined {
  return room.clients.find((c) => c.id !== self.id);
}

function nowMs(): number {
  return Date.now();
}

function toSideToMove(value: unknown): "w" | "b" | null {
  const s = String(value ?? "").toLowerCase();
  if (s === "w" || s === "b") return s;
  return null;
}

function isSocketAlive(ws: WebSocket): boolean {
  return ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING;
}

function pruneDeadClients(room: Room): void {
  room.clients = room.clients.filter((c) => isSocketAlive(c.ws));
}

function kickRoleIfConnected(room: Room, role: "host" | "guest"): void {
  const existing = room.clients.find((c) => c.role === role);
  if (!existing) return;

  // Terminate stale/duplicate role so reconnect can proceed.
  try {
    existing.ws.terminate();
  } catch {
    // ignore
  }
  room.clients = room.clients.filter((c) => c !== existing);
}

// ── HTTP server ───────────────────────────────────────────────────────────────

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "ok", rooms: rooms.size }));
    return;
  }
  res.writeHead(404);
  res.end();
});

// ── WebSocket server ──────────────────────────────────────────────────────────

const wss = new WebSocketServer({ server });

wss.on("connection", (rawWs: ExtWs) => {
  rawWs.alive = true;

  rawWs.on("message", (data) => {
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(data.toString()) as Record<string, unknown>;
    } catch {
      sendError(rawWs, "Invalid JSON");
      return;
    }

    switch (msg.type) {
      case "hello":
        void handleHello(rawWs, msg);
        break;

      case "move":
        // Best-effort relay. Opponent may be offline.
        handleRelay(rawWs, msg);
        break;

      case "state_sync":
        // Canonical commit to DB.
        void handleStateSync(rawWs, msg);
        break;

      case "ping":
        send(rawWs, { type: "pong" });
        break;

      case "pong":
        if (rawWs.clientRef) rawWs.clientRef.alive = true;
        break;

      default:
        sendError(rawWs, `Unknown message type: ${String(msg.type)}`);
    }
  });

  rawWs.on("close", () => handleClose(rawWs));
  rawWs.on("error", () => {
    // close will happen; nothing else needed
  });
});

// ── Protocol handlers ─────────────────────────────────────────────────────────

async function handleHello(ws: ExtWs, msg: Record<string, unknown>): Promise<void> {
  const room_code = String(msg.room ?? "").toUpperCase();
  const role = msg.role as string;

  if (!ROOM_CODE_PATTERN.test(room_code)) {
    sendError(ws, "Room code must be 4–6 uppercase alphanumeric characters");
    return;
  }

  if (role !== "host" && role !== "guest") {
    sendError(ws, 'Role must be "host" or "guest"');
    return;
  }

  let room = rooms.get(room_code);
  if (!room) {
    room = { code: room_code, clients: [] };
    rooms.set(room_code, room);
  }

  // IMPORTANT: prevent "Room is full" due to stale sockets.
  pruneDeadClients(room);

  // Allow reconnect: if same role is already connected, kick the old one.
  kickRoleIfConnected(room, role as "host" | "guest");

  // Prune again (kick removed it, but safe)
  pruneDeadClients(room);

  // Enforce max 2 live sockets in the room
  if (room.clients.length >= 2) {
    sendError(ws, `Room ${room_code} is full`);
    return;
  }

  const client: Client = {
    id: genId(),
    role: role as "host" | "guest",
    ws,
    alive: true,
  };

  room.clients.push(client);
  ws.clientRef = client;
  ws.roomRef = room;

  send(ws, {
    type: "welcome",
    clientId: client.id,
    room: room_code,
    role: client.role,
    occupants: room.clients.length,
  });

  // Load saved state and send to connecting client
  try {
    const saved = await loadGame(room_code);
    if (saved) {
      const isDaily = saved.initial_time_ms === ONE_DAY_MS;
      const deadline = saved.turn_deadline_epoch_ms ?? null;

      if (isDaily && deadline != null && nowMs() > deadline) {
        const loser = saved.side_to_move;
        const winner = loser === "w" ? "b" : "w";
        send(ws, {
          type: "time_forfeit",
          room: room_code,
          winner,
          loser,
          deadlineEpochMs: deadline,
        });
      }

      send(ws, {
        type: "state_sync",
        fen: saved.fen,
        sideToMove: saved.side_to_move,
        moveHistory: [],
        initialTimeMs: saved.initial_time_ms,
        turnDeadlineEpochMs: saved.turn_deadline_epoch_ms,
      });
    }
  } catch (e) {
    console.error("DB loadGame failed:", e);
  }

  // Notify existing peer (if any)
  const p = peer(room, client);
  if (p) {
    send(p.ws, {
      type: "peer_joined",
      role: client.role,
      occupants: room.clients.length,
    });
  }
}

function handleRelay(ws: ExtWs, msg: Record<string, unknown>): void {
  const client = ws.clientRef;
  const room = ws.roomRef;

  if (!client || !room) {
    sendError(ws, "Not in a room. Send hello first.");
    return;
  }

  const p = peer(room, client);
  if (!p) {
    // Opponent offline is normal for daily games
    return;
  }

  send(p.ws, msg);
}

async function handleStateSync(ws: ExtWs, msg: Record<string, unknown>): Promise<void> {
  const client = ws.clientRef;
  const room = ws.roomRef;

  if (!client || !room) {
    sendError(ws, "Not in a room. Send hello first.");
    return;
  }

  const fen = String(msg.fen ?? "");
  const stm = toSideToMove(msg.sideToMove);

  if (!fen) {
    sendError(ws, "state_sync missing fen");
    return;
  }
  if (!stm) {
    sendError(ws, "state_sync sideToMove must be 'w' or 'b'");
    return;
  }

  const initialTimeMsRaw = msg.initialTimeMs;
  const initialTimeMs =
    typeof initialTimeMsRaw === "number" && Number.isFinite(initialTimeMsRaw)
      ? Math.max(0, Math.floor(initialTimeMsRaw))
      : null;

  const isDaily = initialTimeMs === ONE_DAY_MS;
  const turnDeadlineEpochMs = isDaily ? nowMs() + ONE_DAY_MS : null;

  try {
    await upsertGame({
      room_code: room.code,
      fen,
      side_to_move: stm,
      initial_time_ms: initialTimeMs,
      turn_deadline_epoch_ms: turnDeadlineEpochMs,
    });
  } catch (e) {
    console.error("DB upsertGame failed:", e);
  }

  // Relay best-effort
  const p = peer(room, client);
  if (!p) return;

  send(p.ws, {
    ...msg,
    turnDeadlineEpochMs,
  });
}

function handleClose(ws: ExtWs): void {
  const client = ws.clientRef;
  const room = ws.roomRef;

  if (!client || !room) return;

  room.clients = room.clients.filter((c) => c.id !== client.id);

  const p = room.clients[0];
  if (p) {
    send(p.ws, {
      type: "peer_left",
      role: client.role,
      occupants: room.clients.length,
    });
  }

  if (room.clients.length === 0) {
    rooms.delete(room.code);
  }
}

// ── Heartbeat ─────────────────────────────────────────────────────────────────

const heartbeatTimer = setInterval(() => {
  wss.clients.forEach((rawWs) => {
    const ws = rawWs as ExtWs;
    const client = ws.clientRef;
    if (client && !client.alive) {
      ws.terminate();
      return;
    }
    if (client) client.alive = false;
    if (ws.readyState === WebSocket.OPEN) {
      send(ws, { type: "ping" });
    }
  });
}, HEARTBEAT_INTERVAL_MS);

wss.on("close", () => clearInterval(heartbeatTimer));

// ── Start ─────────────────────────────────────────────────────────────────────

migrate()
  .then(() => {
    console.log("DB migration complete");
    server.listen(PORT, () => {
      console.log(`Chess relay server listening on port ${PORT}`);
    });
  })
  .catch((e) => {
    console.error("DB migrate failed:", e);
    process.exit(1);
  });
