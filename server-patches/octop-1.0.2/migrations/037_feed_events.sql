CREATE TABLE IF NOT EXISTS feed_events (
  event_id      TEXT PRIMARY KEY,
  agent_id      TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source        TEXT NOT NULL,
  source_ref    TEXT NOT NULL,
  dedupe_key    TEXT NOT NULL,
  content_hash  TEXT NOT NULL,
  kind          TEXT NOT NULL,
  title         TEXT NOT NULL,
  summary       TEXT NOT NULL DEFAULT '',
  why_now       TEXT NOT NULL DEFAULT '',
  lifecycle     TEXT NOT NULL CHECK (
    lifecycle IN ('unread', 'snoozed', 'read', 'dismissed', 'expired')
  ),
  occurred_at   INTEGER NOT NULL,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  payload_json  TEXT NOT NULL DEFAULT '{}',
  UNIQUE (agent_id, dedupe_key)
);

CREATE INDEX IF NOT EXISTS idx_feed_events_agent_lifecycle
  ON feed_events(agent_id, user_id, lifecycle, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_feed_events_source
  ON feed_events(agent_id, source, updated_at DESC);

UPDATE _schema_version SET version = 37;
