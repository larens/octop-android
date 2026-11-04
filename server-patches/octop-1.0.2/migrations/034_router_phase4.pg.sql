ALTER TABLE conversation_tasks ADD COLUMN IF NOT EXISTS accepted_at BIGINT;
ALTER TABLE conversation_tasks ADD COLUMN IF NOT EXISTS acceptance_source TEXT;
ALTER TABLE conversation_tasks ADD COLUMN IF NOT EXISTS outcome_ref TEXT;

CREATE TABLE IF NOT EXISTS task_approvals (
  approval_id             TEXT PRIMARY KEY,
  conversation_id         TEXT NOT NULL REFERENCES main_conversations(conversation_id) ON DELETE CASCADE,
  task_id                 TEXT NOT NULL REFERENCES conversation_tasks(task_id) ON DELETE CASCADE,
  run_id                  TEXT REFERENCES task_runs(run_id) ON DELETE SET NULL,
  trace_id                TEXT NOT NULL,
  attempt_no              INTEGER,
  action_name             TEXT NOT NULL,
  action_json             TEXT NOT NULL,
  action_hash             TEXT NOT NULL,
  idempotency_key         TEXT NOT NULL UNIQUE,
  status                  TEXT NOT NULL CHECK (
    status IN ('pending', 'approved', 'rejected', 'expired', 'consumed')
  ),
  version                 INTEGER NOT NULL DEFAULT 1,
  token_limit             BIGINT NOT NULL,
  budget_json             TEXT NOT NULL DEFAULT '{}',
  expires_at              BIGINT NOT NULL,
  execution_expires_at    BIGINT,
  decided_at              BIGINT,
  decided_by_user_id      INTEGER,
  consumed_at             BIGINT,
  created_at              BIGINT NOT NULL,
  updated_at              BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_approvals_task
  ON task_approvals(task_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_approvals_status
  ON task_approvals(conversation_id, status, expires_at);

CREATE TABLE IF NOT EXISTS write_receipts (
  receipt_id          TEXT PRIMARY KEY,
  conversation_id     TEXT NOT NULL REFERENCES main_conversations(conversation_id) ON DELETE CASCADE,
  task_id             TEXT NOT NULL REFERENCES conversation_tasks(task_id) ON DELETE CASCADE,
  run_id              TEXT NOT NULL REFERENCES task_runs(run_id) ON DELETE CASCADE,
  trace_id            TEXT NOT NULL,
  attempt_no          INTEGER NOT NULL,
  approval_id         TEXT NOT NULL UNIQUE REFERENCES task_approvals(approval_id) ON DELETE CASCADE,
  action_name         TEXT NOT NULL,
  action_hash         TEXT NOT NULL,
  idempotency_key     TEXT NOT NULL UNIQUE,
  request_json        TEXT NOT NULL DEFAULT '{}',
  status              TEXT NOT NULL CHECK (
    status IN ('processing', 'succeeded', 'failed', 'result_uncertain')
  ),
  outcome_json        TEXT NOT NULL DEFAULT '{}',
  outcome_ref         TEXT,
  actual_tokens       BIGINT NOT NULL DEFAULT 0,
  accepted_at         BIGINT,
  error               TEXT,
  created_at          BIGINT NOT NULL,
  updated_at          BIGINT NOT NULL,
  finished_at         BIGINT
);

CREATE INDEX IF NOT EXISTS idx_write_receipts_task
  ON write_receipts(task_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_write_receipts_status
  ON write_receipts(conversation_id, status, updated_at);

UPDATE _schema_version SET version = 34;
