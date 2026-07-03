-- Credentials and roles for JWT auth (Phase 1).
-- password_hash is nullable: members without one simply cannot log in yet.
ALTER TABLE member
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN roles VARCHAR(255) NOT NULL DEFAULT 'MEMBER';