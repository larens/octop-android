ALTER TABLE conversation_tasks ADD COLUMN plan_id TEXT;
ALTER TABLE conversation_tasks ADD COLUMN plan_item_id TEXT;

CREATE INDEX IF NOT EXISTS idx_conversation_tasks_plan
  ON conversation_tasks(plan_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversation_tasks_plan_item
  ON conversation_tasks(plan_item_id);

ALTER TABLE conversation_events ADD COLUMN plan_id TEXT;
ALTER TABLE conversation_events ADD COLUMN plan_item_id TEXT;

CREATE INDEX IF NOT EXISTS idx_conversation_events_plan
  ON conversation_events(plan_id, created_at);
CREATE INDEX IF NOT EXISTS idx_conversation_events_plan_item
  ON conversation_events(plan_item_id, created_at);

CREATE TABLE IF NOT EXISTS dispatch_plans (
  plan_id                  TEXT PRIMARY KEY,
  conversation_id          TEXT NOT NULL REFERENCES main_conversations(conversation_id) ON DELETE CASCADE,
  trace_id                 TEXT NOT NULL,
  context_pack_id          TEXT,
  objective                TEXT NOT NULL,
  status                   TEXT NOT NULL CHECK (
    status IN (
      'draft', 'awaiting_approval', 'approved', 'rejected', 'executing',
      'completed', 'partial', 'failed', 'cancelled'
    )
  ),
  version                  INTEGER NOT NULL DEFAULT 1,
  plan_hash                TEXT NOT NULL,
  planning_tokens          INTEGER NOT NULL DEFAULT 0,
  estimated_tokens         INTEGER NOT NULL,
  hard_token_limit         INTEGER NOT NULL,
  soft_cost_micros         INTEGER NOT NULL DEFAULT 0,
  hard_cost_limit_micros   INTEGER NOT NULL DEFAULT 0,
  cost_per_1k_tokens_micros INTEGER NOT NULL DEFAULT 0,
  actual_tokens            INTEGER NOT NULL DEFAULT 0,
  actual_cost_micros       INTEGER NOT NULL DEFAULT 0,
  max_concurrency          INTEGER NOT NULL DEFAULT 1,
  max_attempts_per_worker  INTEGER NOT NULL DEFAULT 3,
  budget_json              TEXT NOT NULL DEFAULT '{}',
  result_summary           TEXT NOT NULL DEFAULT '',
  created_at               INTEGER NOT NULL,
  proposed_at              INTEGER NOT NULL,
  approved_at              INTEGER,
  finished_at              INTEGER
);

CREATE INDEX IF NOT EXISTS idx_dispatch_plans_conversation
  ON dispatch_plans(conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dispatch_plans_status
  ON dispatch_plans(conversation_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS dispatch_plan_items (
  plan_item_id           TEXT PRIMARY KEY,
  plan_id                TEXT NOT NULL REFERENCES dispatch_plans(plan_id) ON DELETE CASCADE,
  item_key               TEXT NOT NULL,
  child_task_id          TEXT REFERENCES conversation_tasks(task_id) ON DELETE SET NULL,
  title                  TEXT NOT NULL,
  objective              TEXT NOT NULL,
  worker_role            TEXT NOT NULL DEFAULT 'research',
  depends_on_json        TEXT NOT NULL DEFAULT '[]',
  status                 TEXT NOT NULL CHECK (
    status IN (
      'waiting_dependency', 'ready', 'queued', 'running', 'retrying',
      'succeeded', 'failed', 'cancelled'
    )
  ),
  sort_order             INTEGER NOT NULL DEFAULT 0,
  estimated_tokens       INTEGER NOT NULL,
  token_limit            INTEGER NOT NULL,
  estimated_cost_micros  INTEGER NOT NULL DEFAULT 0,
  cost_limit_micros      INTEGER NOT NULL DEFAULT 0,
  actual_tokens          INTEGER NOT NULL DEFAULT 0,
  actual_cost_micros     INTEGER NOT NULL DEFAULT 0,
  attempt_count          INTEGER NOT NULL DEFAULT 0,
  last_progress_at       INTEGER,
  result_summary         TEXT NOT NULL DEFAULT '',
  created_at             INTEGER NOT NULL,
  updated_at             INTEGER NOT NULL,
  UNIQUE(plan_id, item_key)
);

CREATE INDEX IF NOT EXISTS idx_dispatch_plan_items_plan
  ON dispatch_plan_items(plan_id, sort_order, created_at);
CREATE INDEX IF NOT EXISTS idx_dispatch_plan_items_status
  ON dispatch_plan_items(plan_id, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_dispatch_plan_items_task
  ON dispatch_plan_items(child_task_id);

UPDATE _schema_version SET version = 32;
