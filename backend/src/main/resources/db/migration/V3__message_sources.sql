ALTER TABLE messages ADD COLUMN sources jsonb NOT NULL DEFAULT '[]'::jsonb;
