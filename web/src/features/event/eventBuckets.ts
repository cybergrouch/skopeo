/**
 * The Upcoming / Unfinalized / Finalized split (#483), shared by every surface that lists events (#780).
 *
 * Typed **structurally** rather than against `EventResponse` on purpose: the Event Organizer feeds it the
 * authenticated event DTO while a club's public page feeds it the public one, and neither should have to
 * be widened to match the other. The generic parameter keeps each caller's concrete type on the way out,
 * so a consumer still sees its own fields on the bucketed events.
 */
export interface BucketableEvent {
  startDate: string;
  endDate: string;
  isFinalized?: boolean;
  finalizedAt?: string | null;
  /** Recorded results in this event (#483) — the "activity started" signal. */
  completedMatchCount?: number;
}

/** Today as a local `yyyy-MM-dd`, the boundary the buckets compare dates against. */
export function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/** Recorded results present (#483) — the "has results" signal for the Unfinalized bucket. */
export function hasResults(event: BucketableEvent): boolean {
  return (event.completedMatchCount ?? 0) > 0;
}

/**
 * Split events into three buckets (#483). Finalized status wins over everything: a finalized event is
 * always Finalized, even with a future end date or no results. Otherwise Unfinalized = the event ended
 * OR has recorded results (activity started, not concluded); Upcoming = future + untouched.
 *
 * Sort: Upcoming by start date asc, Unfinalized by end date desc, Finalized by finalizedAt desc (falling
 * back to end date desc when a finalized row somehow lacks the timestamp).
 */
export function splitByBucket<T extends BucketableEvent>(
  events: T[],
  today: string,
): { upcoming: T[]; unfinalized: T[]; finalized: T[] } {
  const finalized = events.filter((e) => e.isFinalized);
  const active = events.filter((e) => !e.isFinalized);
  const unfinalized = active.filter((e) => e.endDate < today || hasResults(e));
  const upcoming = active.filter(
    (e) => e.endDate >= today && !hasResults(e),
  );
  return {
    upcoming: [...upcoming].sort((a, b) =>
      a.startDate.localeCompare(b.startDate),
    ),
    unfinalized: [...unfinalized].sort((a, b) =>
      b.endDate.localeCompare(a.endDate),
    ),
    finalized: [...finalized].sort((a, b) =>
      (b.finalizedAt ?? b.endDate).localeCompare(a.finalizedAt ?? a.endDate),
    ),
  };
}
