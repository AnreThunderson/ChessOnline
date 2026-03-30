# ChessOnline

An Android chess app with **local pass-and-play** and **online multiplayer** via a
Node.js WebSocket relay server deployable on [Render](https://render.com).

---

## Features

- Full chess rules (castling, en passant, promotion, draw conditions)
- Pass-and-play (two players, one device)
- **Online multiplayer** — host a room or join by code
- Relay server ready to deploy on Render (free tier)

---

## Repository Layout

```
ChessOnline/
├── app/                    Android application
│   └── src/main/java/com/example/passandplaychess/
│       ├── Chess.kt            Chess engine (rules, FEN, UCI)
│       ├── MainActivity.kt     App entry point & pass-and-play UI
│       └── multiplayer/
│           ├── RelayClient.kt       OkHttp WebSocket client
│           ├── MultiplayerViewModel.kt
│           └── MultiplayerScreen.kt
├── server/                 Node.js TypeScript relay server
│   ├── src/index.ts
│   ├── package.json
│   ├── tsconfig.json
│   └── README.md           Full server docs
├── render.yaml             Render one-click deploy config
└── README.md               ← you are here
```

---

## Server — Local Development

```bash
cd server
npm install
npm run dev       # starts on http://localhost:3000  (hot-reload)
```

Health check:
```bash
curl http://localhost:3000/health
# {"status":"ok","rooms":0}
```

## Server — Deploy to Render

1. Push this repo to GitHub (public or private with access granted).
2. Go to [Render Dashboard](https://dashboard.render.com) → **New Web Service**.
3. Connect this repo — Render auto-detects `render.yaml`.
4. Click **Deploy**.

After deploy, copy the service URL (e.g. `https://chess-relay.onrender.com`).

Then update `app/build.gradle.kts`:

```kotlin
release {
    buildConfigField("String", "WS_BASE_URL", "\"wss://chess-relay.onrender.com\"")
    // ...
}
```

Rebuild the app.

---

## Android App — Multiplayer Usage

### Hosting a game

1. Open the app → **Online Multiplayer**.
2. Select the **Host** tab — a random 5-character room code is generated.
3. Share the code with your opponent.
4. Tap **Create Room & Wait** — the app connects to the relay server.
5. When your opponent joins you see the board. You play **White**.

### Joining a game

1. Open the app → **Online Multiplayer**.
2. Select the **Join** tab.
3. Enter the room code the host shared.
4. Tap **Join Room**. You play **Black**.

---

## Testing with Two Emulators

1. Run the relay server locally:
   ```bash
   cd server && npm run dev
   ```
2. Launch two Android emulators (AVD Manager).
3. The debug build is pre-configured to use `ws://10.0.2.2:3000`
   (Android's alias for the host machine `localhost`).
4. On emulator A → **Online Multiplayer → Host** → note the code → **Create Room**.
5. On emulator B → **Online Multiplayer → Join** → enter the code → **Join Room**.
6. Make moves on emulator A (White's turn) — they appear on emulator B, and vice versa.

---

## Protocol Reference

See [server/README.md](server/README.md) for the full WebSocket protocol documentation.
