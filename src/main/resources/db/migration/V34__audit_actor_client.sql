-- V34: record which API client (application) drove an audited action (#225, #599).
--
-- Adds a nullable actor_client_id alongside the existing actor_user_id on audit_log: a partner
-- integration acting through the API can now be attributed distinctly from the end user. Null for
-- ordinary user- or system-driven actions. SET NULL on client delete so history survives.

ALTER TABLE audit_log
    ADD COLUMN actor_client_id UUID REFERENCES api_clients(id) ON DELETE SET NULL;
