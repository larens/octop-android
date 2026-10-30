CREATE TABLE interest_monitors (
  monitor_id            TEXT PRIMARY KEY,
  agent_id              TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
  user_id               INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  query_text            TEXT NOT NULL,
  schedule              TEXT NOT NULL CHECK (schedule IN ('manual', 'morning', 'evening', 'twice_daily')),
  enabled               INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
  baseline_established  INTEGER NOT NULL DEFAULT 0 CHECK (baseline_established IN (0, 1)),
  last_run_at           INTEGER,
  last_status           TEXT,
  last_error            TEXT,
  last_result_count     INTEGER NOT NULL DEFAULT 0,
  next_run_at           INTEGER,
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL
);

CREATE TABLE interest_results (
  monitor_id    TEXT NOT NULL REFERENCES interest_monitors(monitor_id) ON DELETE CASCADE,
  fingerprint   TEXT NOT NULL,
  title         TEXT NOT NULL,
  url           TEXT NOT NULL,
  excerpt       TEXT NOT NULL,
  published_at  INTEGER,
  first_seen_at INTEGER NOT NULL,
  PRIMARY KEY (monitor_id, fingerprint)
);

CREATE TABLE today_web_evidence (
  agent_id      TEXT NOT NULL,
  card_id       TEXT NOT NULL,
  monitor_id    TEXT NOT NULL REFERENCES interest_monitors(monitor_id) ON DELETE CASCADE,
  fingerprint   TEXT NOT NULL,
  url           TEXT NOT NULL,
  title         TEXT NOT NULL,
  excerpt       TEXT NOT NULL,
  published_at  INTEGER,
  observed_at   INTEGER NOT NULL,
  PRIMARY KEY (agent_id, card_id, fingerprint),
  FOREIGN KEY (agent_id, card_id) REFERENCES today_cards(agent_id, card_id) ON DELETE CASCADE
);

CREATE INDEX idx_interest_monitors_due ON interest_monitors(enabled, next_run_at);
CREATE INDEX idx_today_web_evidence_monitor ON today_web_evidence(monitor_id, observed_at DESC);

UPDATE _schema_version SET version = 24;
