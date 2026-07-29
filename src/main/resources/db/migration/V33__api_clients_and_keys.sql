-- V33: partner API clients and hashed API keys (#225, #596).
--
-- Adds an application/client identity layer distinct from end-user Firebase auth, so the API can be
-- opened to third-party integrations. A partner application (api_clients) holds one or more revocable
-- API keys (api_keys); only the SHA-256 hash of a key is stored, never the plaintext. Scopes are a
-- comma-separated subset of the Capability set (enforcement lands in a later phase). See
-- docs/engineering/architecture/CLIENT_API_AUTH.md.

CREATE TABLE api_clients (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_api_clients_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    client_id UUID NOT NULL REFERENCES api_clients(id) ON DELETE CASCADE,
    -- The non-secret leading segment (prefix + a few chars), shown for identification in the admin UI.
    key_prefix VARCHAR(32) NOT NULL,
    -- The SHA-256 hex of the plaintext key. Lookups are by this hash; the plaintext is never stored.
    key_hash VARCHAR(64) NOT NULL,
    -- Comma-separated Capability names the key is scoped to (least privilege); '' = none yet.
    scopes VARCHAR(600) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_api_keys_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP
);

-- Keys are looked up by hash on every client-authenticated request, so it must be unique + indexed.
CREATE UNIQUE INDEX uq_api_keys_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_client ON api_keys (client_id);
