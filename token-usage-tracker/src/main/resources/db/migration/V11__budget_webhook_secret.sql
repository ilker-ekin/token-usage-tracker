-- Budget webhooks are signed (Standard Webhooks: HMAC-SHA256 over
-- "id.timestamp.body"), so receivers can tell a real alert from a forged or
-- replayed request. Each budget has its own secret, "whsec_" followed by the
-- base64 of 32 random bytes. HMAC needs the secret itself, so it is stored
-- as is; the API returns it only when it is created or rotated.
ALTER TABLE budget ADD COLUMN webhook_secret VARCHAR(100);

-- Existing budgets get a random secret. Two v4 UUIDs give 244 random bits
-- from PostgreSQL's strong random source; their 64 hex digits are 32 bytes.
-- Admins see it after rotating once.
UPDATE budget
SET webhook_secret = 'whsec_' || encode(
        decode(replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''), 'hex'),
        'base64');

ALTER TABLE budget ALTER COLUMN webhook_secret SET NOT NULL;
