/**
 * How a concession is written, in one place (#987).
 *
 * Two surfaces used to spell this differently — the live card said `(Retired)`, the match scoreline
 * said `(ret)` — and `LiveScoreCard`'s own comment claimed they matched. #954 extracted `scoreline`
 * precisely to stop three surfaces inventing three spellings; the constant it defined was never shared
 * with the card, so they drifted anyway. One export, consumed by both.
 *
 * `(R)` / `(D)` over the conventional `(ret)` / `(def)` is a deliberate call: compactness won, on the
 * grounds that the marker now binds tight to a digit (`0(R)-4`) rather than trailing the line.
 */
export const CONCESSION_MARK: Record<string, string> = {
  RETIRED: "(R)",
  DEFAULTED: "(D)",
};

/** The mark for [completionReason], or undefined when the match ended normally. */
export function concessionMark(
  completionReason?: string | null,
): string | undefined {
  return completionReason ? CONCESSION_MARK[completionReason] : undefined;
}

/**
 * Which side gave up, given who won (#987).
 *
 * Derived, never stored: a player who retires or defaults always loses, so the conceding side is
 * simply the one that did not win. The API declines to carry it as a field for the same reason —
 * *"carrying it as well would let the two disagree."*
 *
 * Undefined when nobody won, which is a match still in progress: there is no concession to place.
 */
export function concededSide(
  winner: string | null | undefined,
): "TEAM1" | "TEAM2" | undefined {
  if (winner === "TEAM1") return "TEAM2";
  if (winner === "TEAM2") return "TEAM1";
  return undefined;
}
