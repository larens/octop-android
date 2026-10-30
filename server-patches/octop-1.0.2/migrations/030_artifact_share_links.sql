CREATE TABLE artifact_share_links (
  code       TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL,
  thread_id  TEXT NOT NULL,
  agent_id   TEXT NOT NULL,
  path       TEXT NOT NULL,
  filename   TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX idx_artifact_share_links_expires
  ON artifact_share_links(expires_at);

UPDATE _schema_version SET version = 30;
