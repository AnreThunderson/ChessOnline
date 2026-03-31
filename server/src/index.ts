import * as http from 'http';
import { WebSocketServer, WebSocket } from 'ws';

const PORT = parseInt(process.env.PORT ?? '3000', 10);
const ROOM_CODE_PATTERN = /^[A-Z0-9]{4,6}$/;
const HEARTBEAT_INTERVAL_MS = 30_000;

// ── Types ─────────────────────────────────────────────────────────────────────

interface Client {
  id: string;
  role: 'host' | 'guest';
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
  send(ws, { type: 'error', message });
}

function peer(room: Room, self: Client): Client | undefined {
  return room.clients.find((c) => c.id !== self.id);
}

// ── HTTP server ───────────────────────────────────────────────────────────────

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', rooms: rooms.size }));
    return;
  }
  res.writeHead(404);
  res.end();
});

// ── WebSocket server ──────────────────────────────────────────────────────────

const wss = new WebSocketServer({ server });

wss.on('connection', (rawWs: ExtWs) => {
  rawWs.alive = true; // used by heartbeat

  rawWs.on('message', (data) => {
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(data.toString()) as Record<string, unknown>;
    } catch {
      sendError(rawWs, 'Invalid JSON');
      return;
    }

    switch (msg.type) {
      case 'hello':
        handleHello(rawWs, msg);
        break;
      case 'move':
        handleRelay(rawWs, msg);
        break;
      case 'state_sync':
        handleRelay(rawWs, msg);
        break;
      case 'ping':
        send(rawWs, { type: 'pong' });
        break;
      case 'pong':
        if (rawWs.clientRef) rawWs.clientRef.alive = true;
        break;
      default:
        sendError(rawWs, `Unknown message type: ${String(msg.type)}`);
    }
  });

  rawWs.on('close', () => handleClose(rawWs));

  rawWs.on('error', () => {
    // Errors trigger close automatically; nothing extra needed.
  });
});

// ── Protocol handlers ─────────────────────────────────────────────────────────

function handleHello(
  ws: ExtWs,
  msg: Record<string, unknown>
): void {
  const room_code = String(msg.room ?? '').toUpperCase();
  const role = msg.role as string;

  if (!ROOM_CODE_PATTERN.test(room_code)) {
    sendError(ws, 'Room code must be 4–6 uppercase alphanumeric characters');
    return;
  }

  if (role !== 'host' && role !== 'guest') {
    sendError(ws, 'Role must be "host" or "guest"');
    return;
  }

  let room = rooms.get(room_code);

  if (role === 'host') {
    if (room) {
      sendError(ws, `Room ${room_code} already exists`);
      return;
    }
    room = { code: room_code, clients: [] };
    rooms.set(room_code, room);
  } else {
    // guest
    if (!room) {
      sendError(ws, `Room ${room_code} not found`);
      return;
    }
    if (room.clients.length >= 2) {
      sendError(ws, `Room ${room_code} is full`);
      return;
    }
  }

  const client: Client = {
    id: genId(),
    role: role as 'host' | 'guest',
    ws,
    alive: true,
  };

  room.clients.push(client);
  ws.clientRef = client;
  ws.roomRef = room;

  // Welcome the new client
  send(ws, {
    type: 'welcome',
    clientId: client.id,
    room: room_code,
    role: client.role,
    occupants: room.clients.length,
  });

  // Notify existing peer
  const p = peer(room, client);
  if (p) {
    send(p.ws, {
      type: 'peer_joined',
      role: client.role,
      occupants: room.clients.length,
    });
  }
}

function handleRelay(ws: ExtWs, msg: Record<string, unknown>): void {
  const client = ws.clientRef;
  const room = ws.roomRef;

  if (!client || !room) {
    sendError(ws, 'Not in a room. Send hello first.');
    return;
  }

  const p = peer(room, client);
  if (!p) {
    sendError(ws, 'No peer connected yet');
    return;
  }

  send(p.ws, msg);
}

function handleClose(ws: ExtWs): void {
  const client = ws.clientRef;
  const room = ws.roomRef;

  if (!client || !room) return;

  // Remove from room
  room.clients = room.clients.filter((c) => c.id !== client.id);

  // Notify remaining peer
  const p = room.clients[0];
  if (p) {
    send(p.ws, {
      type: 'peer_left',
      role: client.role,
      occupants: room.clients.length,
    });

    // If host left, close the room and kick the guest
    if (client.role === 'host') {
      rooms.delete(room.code);
      p.ws.close(1001, 'Host disconnected');
    }
  } else {
    // Room is empty – clean up
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
      send(ws, { type: 'ping' });
    }
  });
}, HEARTBEAT_INTERVAL_MS);

wss.on('close', () => clearInterval(heartbeatTimer));

// ── Start ─────────────────────────────────────────────────────────────────────

server.listen(PORT, () => {
  console.log(`Chess relay server listening on port ${PORT}`);
});
