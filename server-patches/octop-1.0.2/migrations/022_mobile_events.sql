CREATE TABLE mobile_devices (
  agent_id     TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id    TEXT NOT NULL,
  first_seen_at INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  PRIMARY KEY (agent_id, device_id)
);

CREATE TABLE mobile_events (
  agent_id        TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
  user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id       TEXT NOT NULL,
  event_id        TEXT NOT NULL,
  source_type     TEXT NOT NULL,
  source_record_id TEXT,
  event_type      TEXT NOT NULL,
  occurred_at     INTEGER NOT NULL,
  observed_at     INTEGER NOT NULL,
  payload_json    TEXT NOT NULL,
  privacy_class   TEXT NOT NULL,
  received_at     INTEGER NOT NULL,
  PRIMARY KEY (agent_id, event_id)
);

CREATE INDEX idx_mobile_events_agent_occurred
  ON mobile_events(agent_id, occurred_at DESC);
CREATE INDEX idx_mobile_events_agent_source_record
  ON mobile_events(agent_id, source_type, source_record_id);

UPDATE _schema_version SET version = 22;
