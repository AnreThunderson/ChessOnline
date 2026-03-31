# Chess Relay Server

A lightweight Node.js + TypeScript WebSocket relay server that connects two
players for online chess. Designed to run on [Render](https://render.com).

## Protocol

All messages are JSON frames.

| Direction        | Frame                                                            | Description                    |
|------------------|------------------------------------------------------------------|--------------------------------|
| client → server  | `{type:"hello", room:"ABCD", role:"host"\|"guest"}`              | Join/create a room             |
| server → client  | `{type:"welcome", clientId, room, role, occupants}`              | Confirmed join                 |
| server → client  | `{type:"peer_joined", role, occupants}`                          | The other player connected     |
| server → client  | `{type:"peer_left", role, occupants}`                            | The other player disconnected  |
| client → server  | `{type:"move", uci:"e2e4", seq:number}`                          | Relay a chess move             |
| client → server  | `{type:"state_sync", fen, sideToMove:"w"\|"b", moveHistory?:[]}` | Sync full board state          |
| server → client  | `{type:"error", message}`                                        | Protocol or room error         |
| server ↔ client  | `{type:"ping"}` / `{type:"pong"}`                                | Heartbeat                      |

**Room codes** must be 4–6 uppercase alphanumeric characters (`A-Z`, `0-9`).

## Running Locally

```bash
cd server
npm install
npm run dev        # hot-reload via ts-node-dev on port 3000
```

Health check: `curl http://localhost:3000/health`

## Building

```bash
cd server
npm run build      # compiles TypeScript → dist/
npm start          # runs dist/index.js
```

## Deploying to Render

### Option A — Using render.yaml (recommended)

1. Push this repo to GitHub.
2. Go to [Render Dashboard](https://dashboard.render.com) → **New Web Service**.
3. Connect your GitHub repo. Render will detect `render.yaml` automatically.
4. Click **Deploy**. Render sets `PORT` for you.

### Option B — Manual

1. **New Web Service** → connect repo.
2. **Build Command:** `cd server && npm ci && npm run build`
3. **Start Command:** `cd server && npm start`
4. **Environment:** Node 18+
5. Render injects `PORT` automatically — no extra env var needed.

After deploy, copy the `https://your-service.onrender.com` hostname.  
Edit `app/build.gradle.kts` and replace the `release` build config value:

```kotlin
release {
    buildConfigField("String", "WS_BASE_URL", "\"wss://your-service.onrender.com\"")
    // ...
}
```

Rebuild the Android app and install on device.

## Environment Variables

| Variable | Default | Description                              |
|----------|---------|------------------------------------------|
| `PORT`   | `3000`  | Injected by Render; set manually locally |
