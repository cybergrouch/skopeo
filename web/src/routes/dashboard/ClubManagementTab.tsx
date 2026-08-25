import { ClubsSection } from "./admin/ClubsSection";

/**
 * Club Management tab (#698): the clubs administration surface (create/rename/delete clubs, owners,
 * sanction toggle), split out of the Admin tab to keep each tab focused. Mirrors the #648 Account
 * Management split.
 *
 * Visible to HOST / CLUB_OWNER / ADMINISTRATOR (#786), applied in DashboardPage — it was ADMINISTRATOR-only.
 * Visibility and capability are deliberately separate here: the writes inside keep their existing server
 * rules, so a HOST sees the club list read-only rather than buttons that 403. [ClubsSection] owns that
 * per-operation gating.
 */
export function ClubManagementTab() {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <ClubsSection />
    </div>
  );
}
