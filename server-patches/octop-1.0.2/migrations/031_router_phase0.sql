CREATE TABLE IF NOT EXISTS main_conversations (
  conversation_id       TEXT PRIMARY KEY,
  agent_id              TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
  user_id               INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  router_thread_id      TEXT REFERENCES threads(thread_id) ON DELETE SET NULL,
  state_json            TEXT NOT NULL DEFAULT '{}',
  summary               TEXT NOT NULL DEFAULT '',
  last_conversation_seq INTEGER NOT NULL DEFAULT 0,
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL,
  archived_at           INTEGER,
  UNIQUE(agent_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_main_conversations_agent_user
  ON main_conversations(agent_id, user_id);

CREATE TABLE IF NOT EXISTS conversation_tasks (
  task_id          TEXT PRIMARY KEY,
  conversation_id  TEXT NOT NULL REFERENCES main_conversations(conversation_id) ON DELETE CASCADE,
  task_type        TEXT NOT NULL,
  title            TEXT NOT NULL,
  subject_key      TEXT NOT NULL,
  objective        TEXT NOT NULL,
  status           TEXT NOT NULL CHECK (
    status IN ('open', 'in_progress', 'needs_user', 'done', 'failed', 'cancelled', 'expired')
  ),
  requires_user    INTEGER NOT NULL DEFAULT 0,
  capsule_json     TEXT NOT NULL DEFAULT '{}',
  priority         INTEGER NOT NULL DEFAULT 0,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  expires_at       INTEGER
);

CREATE INDEX IF NOT EXISTS idx_conversation_tasks_conversation
  ON conversation_tasks(conversation_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversation_tasks_status
  ON conversation_tasks(conversation_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversation_tasks_subject
  ON conversation_tasks(conversation_id, subject_key);

CREATE TABLE IF NOT EXISTS task_runs (
  run_id           TEXT PRIMARY KEY,
  task_id          TEXT NOT NULL REFERENCES conversation_tasks(task_id) ON DELETE CASCADE,
  worker_thread_id TEXT REFERENCES threads(thread_id) ON DELETE SET NULL,
  status           TEXT NOT NULL CHECK (
    status IN ('queued', 'running', 'waiting_user', 'succeeded', 'failed', 'cancelled', 'interrupted')
  ),
  attempt_no       INTEGER NOT NULL,
  failure_class    TEXT,
  started_at       INTEGER,
  finished_at      INTEGER,
  last_progress_at INTEGER,
  lease_until      INTEGER,
  created_at       INTEGER NOT NULL,
  UNIQUE(task_id, attempt_no)
);

CREATE INDEX IF NOT EXISTS idx_task_runs_task
  ON task_runs(task_id, attempt_no DESC);
CREATE INDEX IF NOT EXISTS idx_task_runs_worker_thread
  ON task_runs(worker_thread_id);
CREATE INDEX IF NOT EXISTS idx_task_runs_lease
  ON task_runs(status, lease_until);

CREATE TABLE IF NOT EXISTS conversation_events (
  event_id         TEXT PRIMARY KEY,
  conversation_id  TEXT NOT NULL REFERENCES main_conversations(conversation_id) ON DELETE CASCADE,
  trace_id         TEXT NOT NULL,
  task_id          TEXT REFERENCES conversation_tasks(task_id) ON DELETE SET NULL,
  run_id           TEXT REFERENCES task_runs(run_id) ON DELETE SET NULL,
  parent_event_id  TEXT,
  seq              INTEGER NOT NULL,
  schema_version   INTEGER NOT NULL DEFAULT 1,
  event_type       TEXT NOT NULL,
  status           TEXT,
  summary          TEXT NOT NULL DEFAULT '',
  payload_ref      TEXT,
  payload_json     TEXT,
  usage_json       TEXT NOT NULL DEFAULT '{}',
  created_at       INTEGER NOT NULL,
  UNIQUE(conversation_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_conversation_events_trace
  ON conversation_events(trace_id, created_at);
CREATE INDEX IF NOT EXISTS idx_conversation_events_task
  ON conversation_events(task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_conversation_events_run
  ON conversation_events(run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_conversation_events_type
  ON conversation_events(conversation_id, event_type, created_at DESC);

CREATE TABLE IF NOT EXISTS budget_ledgers (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  scope_type           TEXT NOT NULL,
  scope_id             TEXT NOT NULL,
  period_start         INTEGER NOT NULL,
  period_end           INTEGER NOT NULL,
  reserved_tokens      INTEGER NOT NULL DEFAULT 0,
  spent_tokens         INTEGER NOT NULL DEFAULT 0,
  reserved_cost_micros INTEGER NOT NULL DEFAULT 0,
  spent_cost_micros    INTEGER NOT NULL DEFAULT 0,
  limit_tokens         INTEGER NOT NULL,
  limit_cost_micros    INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL,
  UNIQUE(scope_type, scope_id, period_start, period_end)
);

CREATE INDEX IF NOT EXISTS idx_budget_ledgers_scope
  ON budget_ledgers(scope_type, scope_id, period_start, period_end);

CREATE TABLE IF NOT EXISTS context_pack_manifests (
  pack_id          TEXT PRIMARY KEY,
  conversation_id  TEXT NOT NULL REFERENCES main_conversations(conversation_id) ON DELETE CASCADE,
  trace_id         TEXT NOT NULL,
  policy_version   TEXT NOT NULL,
  model            TEXT NOT NULL,
  used_tokens      INTEGER NOT NULL,
  sections_json    TEXT NOT NULL DEFAULT '[]',
  included_refs_json TEXT NOT NULL DEFAULT '[]',
  omitted_refs_json  TEXT NOT NULL DEFAULT '[]',
  created_at       INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_context_pack_manifests_conversation
  ON context_pack_manifests(conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_context_pack_manifests_trace
  ON context_pack_manifests(trace_id, created_at DESC);

ALTER TABLE usage_log ADD COLUMN trace_id TEXT;
ALTER TABLE usage_log ADD COLUMN task_id TEXT;
ALTER TABLE usage_log ADD COLUMN run_id TEXT;

CREATE INDEX IF NOT EXISTS idx_usage_log_trace
  ON usage_log(trace_id, ts);
CREATE INDEX IF NOT EXISTS idx_usage_log_task
  ON usage_log(task_id, ts);
CREATE INDEX IF NOT EXISTS idx_usage_log_run
  ON usage_log(run_id, ts);

-- Backfill one stable main conversation per existing dashboard agent/user pair.
INSERT INTO main_conversations(
  conversation_id,
  agent_id,
  user_id,
  router_thread_id,
  state_json,
  summary,
  last_conversation_seq,
  created_at,
  updated_at
)
SELECT
  'conv_' || lower(hex(randomblob(16))),
  t.agent_id,
  t.user_id,
  t.thread_id,
  '{}',
  '',
  0,
  t.created_at,
  CASE WHEN t.last_active > 0 THEN t.last_active ELSE t.created_at END
FROM threads t
WHERE t.channel_type = 'dashboard'
  AND NOT EXISTS (
    SELECT 1
    FROM main_conversations c
    WHERE c.agent_id = t.agent_id AND c.user_id = t.user_id
  )
  AND t.id = (
    SELECT t2.id
    FROM threads t2
    WHERE t2.agent_id = t.agent_id
      AND t2.user_id = t.user_id
      AND t2.channel_type = 'dashboard'
    ORDER BY
      CASE WHEN t2.last_active > 0 THEN t2.last_active ELSE t2.created_at END DESC,
      t2.id DESC
    LIMIT 1
  );

UPDATE _schema_version SET version = 31;
