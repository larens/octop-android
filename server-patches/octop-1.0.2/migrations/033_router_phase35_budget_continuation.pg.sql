ALTER TABLE dispatch_plans
  ADD COLUMN IF NOT EXISTS budget_request_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE dispatch_plans
  ADD COLUMN IF NOT EXISTS budget_extension_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE dispatch_plans
  ADD COLUMN IF NOT EXISTS budget_extended_tokens BIGINT NOT NULL DEFAULT 0;

UPDATE _schema_version SET version = 33;
