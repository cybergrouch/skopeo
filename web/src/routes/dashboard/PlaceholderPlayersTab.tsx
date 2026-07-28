import type { Capability } from "@/auth/capabilities";
import { CreatePlaceholderSection } from "./CreatePlaceholderSection";
import { PlaceholderPlayersSection } from "./PlaceholderPlayersSection";

/**
 * The Placeholder Players tab (#578): create login-less placeholder players and manage the existing
 * unclaimed ones (list + one-time claim codes). Gated to HOST/CLUB_OWNER/ADMINISTRATOR in
 * {@link DashboardPage}; promoted out of the Event Organizer tab where it used to live.
 */
export function PlaceholderPlayersTab({
  capabilities,
}: {
  capabilities: readonly Capability[] | undefined;
}) {
  return (
    <div className="grid gap-4">
      <CreatePlaceholderSection capabilities={capabilities} />
      <PlaceholderPlayersSection capabilities={capabilities} />
    </div>
  );
}
