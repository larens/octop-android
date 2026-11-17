ALTER TABLE conversation_tasks ADD COLUMN IF NOT EXISTS worker_agent_id TEXT;
ALTER TABLE conversation_tasks ADD COLUMN IF NOT EXISTS worker_agent_name TEXT;

ALTER TABLE dispatch_plan_items ADD COLUMN IF NOT EXISTS worker_agent_id TEXT;
ALTER TABLE dispatch_plan_items ADD COLUMN IF NOT EXISTS worker_agent_name TEXT;

CREATE INDEX IF NOT EXISTS idx_conversation_tasks_worker_agent
  ON conversation_tasks(worker_agent_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_dispatch_plan_items_worker_agent
  ON dispatch_plan_items(worker_agent_id, updated_at);

UPDATE _schema_version SET version = 39;
