/**
 * A player's short display label: their display name, else their public code, else a sliced id.
 * Centralized so the fallback chain is tested once and call sites stay branch-free.
 */
export function playerLabel(
  displayName: string | null | undefined,
  publicCode: string | null | undefined,
  id: string,
): string {
  return displayName ?? publicCode ?? id.slice(0, 8)
}
