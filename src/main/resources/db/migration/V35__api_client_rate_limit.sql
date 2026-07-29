-- V35: per-client rate-limit override (#225, #603).
--
-- Adds an optional per-minute request limit to api_clients. NULL means "use the global default tier"
-- (#598); a positive value throttles that client at its own limit, independent of other clients.

ALTER TABLE api_clients
    ADD COLUMN rate_limit_per_min INTEGER;
