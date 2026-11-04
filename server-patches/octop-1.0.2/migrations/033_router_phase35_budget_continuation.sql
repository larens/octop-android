ALTER TABLE dispatch_plans
  ADD COLUMN budget_request_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE dispatch_plans
  ADD COLUMN budget_extension_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE dispatch_plans
  ADD COLUMN budget_extended_tokens INTEGER NOT NULL DEFAULT 0;

UPDATE _schema_version SET version = 33;
