-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Issue #111: host seeding generator. A host curates named player lists and generates a timestamped,
-- rating-sorted seeding (one current seeding per list; regenerate overwrites). Seedings are frozen
-- snapshots so the CSV export is reproducible.

CREATE TABLE player_lists (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   UUID        NOT NULL,
    name       VARCHAR(120) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT fk_player_lists_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_player_lists_owner ON player_lists(owner_id);

CREATE TABLE player_list_members (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    list_id  UUID      NOT NULL,
    user_id  UUID      NOT NULL,
    added_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_player_list_members_list FOREIGN KEY (list_id) REFERENCES player_lists(id) ON DELETE CASCADE,
    CONSTRAINT fk_player_list_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_player_list_members UNIQUE (list_id, user_id)
);

CREATE INDEX idx_player_list_members_list ON player_list_members(list_id);

CREATE TABLE seedings (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    list_id      UUID      NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT now(),
    generated_by UUID,
    CONSTRAINT fk_seedings_list FOREIGN KEY (list_id) REFERENCES player_lists(id) ON DELETE CASCADE,
    CONSTRAINT fk_seedings_generated_by FOREIGN KEY (generated_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_seedings_list UNIQUE (list_id)
);

CREATE TABLE seeding_entries (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seeding_id   UUID         NOT NULL,
    seed         INTEGER,
    position     INTEGER      NOT NULL,
    user_id      UUID,
    display_name VARCHAR(255),
    public_code  VARCHAR(16)  NOT NULL,
    ntrp_band    VARCHAR(8),
    rating       VARCHAR(32)  NOT NULL,
    sex          VARCHAR(16),
    age          INTEGER,
    CONSTRAINT fk_seeding_entries_seeding FOREIGN KEY (seeding_id) REFERENCES seedings(id) ON DELETE CASCADE,
    CONSTRAINT fk_seeding_entries_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_seeding_entries_seeding ON seeding_entries(seeding_id);
