ALTER TABLE today_card_evidence ADD COLUMN IF NOT EXISTS image_url TEXT;
ALTER TABLE today_web_evidence ADD COLUMN IF NOT EXISTS image_url TEXT;

UPDATE _schema_version SET version = 38;
