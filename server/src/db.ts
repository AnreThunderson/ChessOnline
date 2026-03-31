import { Pool } from "pg";

const connectionString = process.env.DATABASE_URL;
if (!connectionString) {
  throw new Error("DATABASE_URL is not set");
}

export const pool = new Pool({
  connectionString,
  // Works for most hosted Postgres providers (Render, Supabase, Neon, etc.)
  ssl: { rejectUnauthorized: false },
});

export type SavedGameRow = {
  room_code: string;
  fen: string;
  side_to_move: "w" | "b";
  initial_time_ms: number | null;
  turn_deadline_epoch_ms: number | null;
};

export async function migrate(): Promise<void> {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS games (
      room_code TEXT PRIMARY KEY,
      fen TEXT NOT NULL,
      side_to_move TEXT NOT NULL,
      initial_time_ms BIGINT,
      turn_deadline_epoch_ms BIGINT,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `);
}

export async function loadGame(roomCode: string): Promise<SavedGameRow | null> {
  const res = await pool.query(
    `SELECT room_code, fen, side_to_move, initial_time_ms, turn_deadline_epoch_ms
     FROM games WHERE room_code = $1`,
    [roomCode]
  );
  return (res.rows[0] as SavedGameRow | undefined) ?? null;
}

export async function upsertGame(row: SavedGameRow): Promise<void> {
  await pool.query(
    `INSERT INTO games (room_code, fen, side_to_move, initial_time_ms, turn_deadline_epoch_ms, updated_at)
     VALUES ($1,$2,$3,$4,$5, NOW())
     ON CONFLICT (room_code) DO UPDATE SET
       fen = EXCLUDED.fen,
       side_to_move = EXCLUDED.side_to_move,
       initial_time_ms = EXCLUDED.initial_time_ms,
       turn_deadline_epoch_ms = EXCLUDED.turn_deadline_epoch_ms,
       updated_at = NOW()`,
    [
      row.room_code,
      row.fen,
      row.side_to_move,
      row.initial_time_ms,
      row.turn_deadline_epoch_ms,
    ]
  );
}
