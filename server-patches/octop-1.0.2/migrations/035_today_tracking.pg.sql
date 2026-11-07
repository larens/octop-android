ALTER TABLE today_cards ADD COLUMN IF NOT EXISTS tracking_json TEXT;

UPDATE _schema_version SET version = 35;
