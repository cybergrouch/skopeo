import { DeletedAccountsSection } from "./admin/DeletedAccountsSection";
import { DuplicateCandidatesSection } from "./admin/DuplicateCandidatesSection";
import { DuplicatesSection } from "./admin/DuplicatesSection";
import { InvitesSection } from "./admin/InvitesSection";
import { ManagePlayerSection } from "./admin/ManagePlayerSection";
import { MergeAccountsSection } from "./admin/MergeAccountsSection";

/**
 * Account Management tab (#648): the player/account administration surface, split out of the Admin tab
 * to keep each tab focused. ADMINISTRATOR-gated (same gate as Admin, applied in DashboardPage). Groups
 * managing a player, restoring soft-deleted accounts, duplicate rectification (mark/replace +
 * candidate review), and onboarding-invite management (#725, folded back in from its own tab — the
 * InvitesSection duplicate-email guard from #132 is unchanged).
 */
export function AccountManagementTab() {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <ManagePlayerSection />
      <InvitesSection />
      <DeletedAccountsSection />
      <DuplicatesSection />
      <MergeAccountsSection />
      <DuplicateCandidatesSection />
    </div>
  );
}
