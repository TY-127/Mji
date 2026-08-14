ALTER TABLE admins ADD COLUMN password_salt BLOB;
ALTER TABLE admins ADD COLUMN password_hash BLOB;
ALTER TABLE admins ADD COLUMN password_iterations INTEGER;

CREATE TABLE IF NOT EXISTS admin_sessions (
  token_hash TEXT PRIMARY KEY,
  admin_email TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  FOREIGN KEY (admin_email) REFERENCES admins(email) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS admin_sessions_expiry_idx ON admin_sessions(expires_at);
