ALTER TABLE today_cards ADD COLUMN tracking_json TEXT;

UPDATE _schema_version SET version = 35;
