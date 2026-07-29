-- V36: stop treating RESEARCHER as a universal capability (#622).
--
-- Historically every sign-up was granted RESEARCHER (see TokenMapping), so the capability never
-- gated anything. New accounts now receive PLAYER only. This one-off cleanup revokes the legacy
-- RESEARCHER grant from accounts that do NOT hold an elevated role
-- (HOST / CLUB_OWNER / RATER / POINTS_MANAGER / ADMINISTRATOR); elevated accounts keep it so their
-- research access is unchanged. Soft revoke, matching the grant lifecycle (is_active / revoked_at);
-- revoked_by is NULL because this is a system action, not an administrator's.

UPDATE user_capabilities uc
SET is_active  = FALSE,
    revoked_at = now(),
    revoked_by = NULL
WHERE uc.capability = 'RESEARCHER'
  AND uc.is_active = TRUE
  AND NOT EXISTS (SELECT 1
                  FROM user_capabilities e
                  WHERE e.user_id = uc.user_id
                    AND e.is_active = TRUE
                    AND e.capability IN ('HOST', 'CLUB_OWNER', 'RATER', 'POINTS_MANAGER', 'ADMINISTRATOR'));
