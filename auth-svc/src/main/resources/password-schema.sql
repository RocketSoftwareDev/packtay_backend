CREATE TABLE IF NOT EXISTS password_pins (
 email varchar(320) NOT NULL,
 purpose varchar(10) NOT NULL,
 user_id varchar(64),
 pin_hash varchar(100),
 expires_at bigint NOT NULL DEFAULT 0,
 sent_at bigint NOT NULL DEFAULT 0,
 window_at bigint NOT NULL DEFAULT 0,
 requests integer NOT NULL DEFAULT 0,
 attempts integer NOT NULL DEFAULT 0,
 PRIMARY KEY(email, purpose)
);

ALTER TABLE password_pins ADD COLUMN IF NOT EXISTS token_hash varchar(64);
ALTER TABLE password_pins ADD COLUMN IF NOT EXISTS token_expires_at bigint NOT NULL DEFAULT 0;
