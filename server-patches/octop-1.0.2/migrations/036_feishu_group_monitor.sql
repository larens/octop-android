CREATE TABLE IF NOT EXISTS feishu_group_watch (
  chat_id        TEXT PRIMARY KEY,
  chat_name      TEXT NOT NULL,
  agent_id       TEXT NOT NULL,
  user_id        INTEGER NOT NULL,
  enabled        INTEGER NOT NULL DEFAULT 1,
  min_priority   TEXT NOT NULL DEFAULT 'p2',
  last_scan_at   INTEGER,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS feishu_group_message (
  message_id     TEXT PRIMARY KEY,
  chat_id        TEXT NOT NULL,
  thread_id      TEXT,
  event_key      TEXT NOT NULL,
  sender_id      TEXT,
  sender_name    TEXT,
  sender_type    TEXT,
  is_official    INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,
  observed_at    INTEGER NOT NULL,
  digest         TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feishu_group_message_event
  ON feishu_group_message(event_key, created_at DESC);

CREATE TABLE IF NOT EXISTS feishu_group_event (
  event_key          TEXT PRIMARY KEY,
  chat_id            TEXT NOT NULL,
  chat_name          TEXT NOT NULL,
  thread_id          TEXT,
  agent_id           TEXT NOT NULL,
  user_id            INTEGER NOT NULL,
  card_id            TEXT,
  title              TEXT NOT NULL,
  topic              TEXT NOT NULL,
  priority           TEXT NOT NULL,
  state              TEXT NOT NULL,
  summary            TEXT NOT NULL,
  why_now            TEXT NOT NULL,
  action             TEXT NOT NULL,
  evidence_url       TEXT,
  first_seen_at      INTEGER NOT NULL,
  last_seen_at       INTEGER NOT NULL,
  last_message_at    INTEGER NOT NULL,
  official_reply_at  INTEGER,
  resolved_at        INTEGER,
  closed             INTEGER NOT NULL DEFAULT 0,
  notified_at        INTEGER,
  notify_count       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_feishu_group_event_open
  ON feishu_group_event(agent_id, closed, last_message_at DESC);

CREATE TABLE IF NOT EXISTS feishu_group_push (
  push_id     INTEGER PRIMARY KEY AUTOINCREMENT,
  event_key   TEXT NOT NULL,
  priority    TEXT NOT NULL,
  channel     TEXT NOT NULL,
  reason      TEXT,
  sent_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feishu_group_push_recent
  ON feishu_group_push(event_key, sent_at DESC);

UPDATE _schema_version SET version = 36;
