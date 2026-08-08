import { DeletedAccountsSection } from "./admin/DeletedAccountsSection";
import { DuplicateCandidatesSection } from "./admin/DuplicateCandidatesSection";
import { DuplicatesSection } from "./admin/DuplicatesSection";
import { ManagePlayerSection } from "./admin/ManagePlayerSection";
import { MergeAccountsSection } from "./admin/MergeAccountsSection";

/**
 * Account Management tab (#648): the player/account administration surface, split out of the Admin tab
 * to keep each tab focused. ADMINISTRATOR-gated (same gate as Admin, applied in DashboardPage). Groups
 * managing a player, restoring soft-deleted accounts, and duplicate rectification (mark/replace +
 * candidate review).
 */
export function AccountManagementTab() {
  return (
    <div className="grid gap-4">
      <ManagePlayerSection />
      <DeletedAccountsSection />
      <DuplicatesSection />
      <MergeAccountsSection />
      <DuplicateCandidatesSection />
    </div>
  );
}
