CREATE TABLE IF NOT EXISTS applications (
  id TEXT PRIMARY KEY,
  qq TEXT NOT NULL UNIQUE,
  concentration_score INTEGER NOT NULL,
  concentration_passed INTEGER NOT NULL DEFAULT 0 CHECK (concentration_passed IN (0, 1)),
  intro_score INTEGER,
  quiz_passed INTEGER NOT NULL DEFAULT 0 CHECK (quiz_passed IN (0, 1)),
  adult_passed INTEGER NOT NULL DEFAULT 0 CHECK (adult_passed IN (0, 1)),
  follow_passed INTEGER NOT NULL DEFAULT 0 CHECK (follow_passed IN (0, 1)),
  voice_passed INTEGER NOT NULL DEFAULT 0 CHECK (voice_passed IN (0, 1)),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  completed_at TEXT
);

CREATE INDEX IF NOT EXISTS applications_updated_at_idx ON applications(updated_at);
