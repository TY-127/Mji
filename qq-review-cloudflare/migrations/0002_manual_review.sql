CREATE TABLE IF NOT EXISTS admins (
  email TEXT PRIMARY KEY,
  role TEXT NOT NULL CHECK (role IN ('owner', 'reviewer')),
  active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
  created_by TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS materials (
  id TEXT PRIMARY KEY,
  application_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('adult', 'follow', 'voice')),
  image_data BLOB,
  mime_type TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
  reason TEXT,
  submitted_at TEXT NOT NULL,
  reviewed_at TEXT,
  reviewed_by TEXT,
  UNIQUE (application_id, kind),
  FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS review_events (
  id TEXT PRIMARY KEY,
  material_id TEXT NOT NULL,
  application_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  decision TEXT NOT NULL CHECK (decision IN ('approved', 'rejected')),
  reason TEXT,
  reviewer_email TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS materials_status_submitted_idx ON materials(status, submitted_at);
CREATE INDEX IF NOT EXISTS review_events_application_idx ON review_events(application_id, created_at);
