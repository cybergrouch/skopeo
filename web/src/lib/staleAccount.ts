/**
 * The pending list's countdown (#1122): how the stale-account sweep's removal date reads to a rater.
 *
 * The date comes from the server, which derives it from the same rule the sweep uses, so this only
 * phrases it. [scheduledRemovalOn] is a calendar date ("2026-11-08"), compared against today's LOCAL date:
 * `new Date("2026-11-08")` would parse as UTC midnight and shift a day west of Greenwich.
 */
export interface RemovalNotice {
  text: string;
  /** Within a week, or already due — worth drawing a rater's eye to. */
  urgent: boolean;
}

const MS_PER_DAY = 86_400_000;
const URGENT_DAYS = 7;

export function removalNotice(
  scheduledRemovalOn: string | null | undefined,
  today: Date = new Date(),
): RemovalNotice | null {
  if (!scheduledRemovalOn) return null;
  const [year, month, day] = scheduledRemovalOn.split("-").map(Number);
  const removal = Date.UTC(year, month - 1, day);
  const now = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  const days = Math.round((removal - now) / MS_PER_DAY);

  if (days <= 0) return { text: "Removed at the next account cleanup unless rated", urgent: true };
  if (days === 1) return { text: "Removed tomorrow unless rated", urgent: true };
  return { text: `Removed in ${days} days unless rated`, urgent: days <= URGENT_DAYS };
}
