-- V13: the ranking-points ledger backing points-based standings (#146, phase 1).
--
-- An append-only ledger of ranking-point awards that admins grant/revoke manually (including for
-- external / ad-hoc sources), fully traceable via the audit log. Points are BigDecimal (NUMERIC) to
-- allow fractional open-play awards later. Each award is band-tagged (recorded at award time, #146
-- decision B in the design doc) and sex-tagged so each (band, sex) runs its own race (#212).
--
-- Revocation model: revoking flips the original row's status to REVOKED AND appends a REVOKED marker
-- row (revokes_award_id -> original, zero points) for a truthful append-only trail. The as-of total is
-- therefore a simple status = 'ACTIVE' AND asOf in [valid_from, valid_until) sum (phase 2 recompute).
--
-- No tournaments model here (that is #390, which gates auto-awards); no snapshot/recompute here (that
-- is #146 phase 2, which reuses #220's snapshot).

CREATE TABLE ranking_point_awards (
    id               UUID PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    points           NUMERIC(10,4) NOT NULL,
    point_class      VARCHAR(32)   NOT NULL,
    source_type      VARCHAR(16)   NOT NULL,
    source_id        VARCHAR(64),
    band             VARCHAR(8)    NOT NULL,
    sex              VARCHAR(16)   NOT NULL,
    reason           TEXT,
    valid_from       TIMESTAMP     NOT NULL,
    valid_until      TIMESTAMP     NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    revokes_award_id UUID          REFERENCES ranking_point_awards(id),
    granted_by       UUID          REFERENCES users(id) ON DELETE SET NULL,
    awarded_at       TIMESTAMP     NOT NULL
);

CREATE INDEX idx_ranking_point_awards_user ON ranking_point_awards (user_id);
CREATE INDEX idx_ranking_point_awards_validity ON ranking_point_awards (status, valid_from, valid_until);
CREATE INDEX idx_ranking_point_awards_race ON ranking_point_awards (band, sex, status);
