/**
 * The two lifecycle questions the match surfaces keep asking, answered in one place (#969/#970).
 *
 * Both were previously re-derived at each call site from whatever field happened to be nearby —
 * `sets.length`, `ratedAt` — and each of those proxies is wrong in a case that actually occurs. This
 * is the file to add the next one to, rather than a fourth inline expression.
 */

/** The subset of a match these predicates read. Structurally satisfied by every match DTO we have. */
export interface MatchLifecycle {
  status?: string | null;
  sets?: unknown[];
  /** Set once the rating calculation has consumed this match; it is frozen from then on. */
  ratedAt?: string | null;
}

/**
 * Does this match have a result (#969)?
 *
 * **Not `sets.length > 0`.** A finished match can legitimately have no sets: a walkover never has one,
 * and a retirement before the first game has none either. The server already answers this question —
 * its `results` filter selects on status — and the client disagreeing with it is what rendered a
 * finalized match as a blank entry form, still offering to record a result that already existed.
 *
 * **Not `ratedAt != null`.** Rating happens at event finalization, days later (#952); a match is
 * finished long before it is rated.
 */
export function hasResult(match: MatchLifecycle): boolean {
  return match.status === "COMPLETED";
}

/**
 * Has play begun (#970)? `IN_PROGRESS` or `COMPLETED`.
 *
 * The question behind the control that must not change a contest already under way — who is playing.
 * Mirrors the server's `Match.playHasBegun()`.
 *
 * **Not the question for deletion any more (#1052)** — use {@link isDeletable}.
 */
export function playHasBegun(match: MatchLifecycle): boolean {
  return match.status === "IN_PROGRESS" || match.status === "COMPLETED";
}

/**
 * May this fixture be deleted (#1052)? Mirrors the server's `ensureDeletable`.
 *
 * `playHasBegun` was the gate and answers wrongly for half of what it covers. A recorded result in an
 * unfinalized, unrated event MUST be deletable, because `DELETE /events/{id}` refuses an event holding
 * one and tells the organizer to delete those matches first — advice that led nowhere while this
 * predicate said no. A match being scored right now must still refuse: that is the whole of #970.
 *
 * Two of the server's four conditions are deliberately absent here, because the call site already
 * carries them: an event that is finalized (#403) or has ENDED for a plain HOST (#310) renders these
 * rows `readOnly`, which suppresses every data-entry control including this one.
 */
export function isDeletable(match: MatchLifecycle): boolean {
  return match.status !== "IN_PROGRESS" && match.ratedAt == null;
}
