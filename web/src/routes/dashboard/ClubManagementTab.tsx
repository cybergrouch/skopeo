import { ClubsSection } from "./admin/ClubsSection";

/**
 * Club Management tab (#698): the clubs administration surface (create/rename/delete clubs, owners,
 * sanction toggle), split out of the Admin tab to keep each tab focused. ADMINISTRATOR-gated (same
 * gate as Admin, applied in DashboardPage). Mirrors the #648 Account Management split.
 */
export function ClubManagementTab() {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <ClubsSection />
    </div>
  );
}
