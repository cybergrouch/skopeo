import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import { isAdministrator } from "@/auth/capabilities";
import { DeletedAccountsSection } from "./admin/DeletedAccountsSection";
import { DuplicateCandidatesSection } from "./admin/DuplicateCandidatesSection";
import { DuplicatesSection } from "./admin/DuplicatesSection";
import { InvitesSection } from "./admin/InvitesSection";
import { ManagePlayerSection } from "./admin/ManagePlayerSection";
import { MergeAccountsSection } from "./admin/MergeAccountsSection";

/**
 * Account Management tab (#648): the player/account administration surface, split out of the Admin tab
 * to keep each tab focused. Groups managing a player, restoring soft-deleted accounts, duplicate
 * rectification (mark/replace + candidate review), and onboarding-invite management (#725, folded back
 * in from its own tab — the InvitesSection duplicate-email guard from #132 is unchanged).
 *
 * **ACCOUNT_MANAGER-gated since #1002** (the tab gate lives in DashboardPage), with one exception:
 * `ManagePlayerSection` stays ADMINISTRATOR-only.
 *
 * That card is the only one whose controls answer to rules other than this tab's — ratings to
 * RATING_ROLES, points adjustments to POINTS_MANAGEMENT_ROLES, and capability grant/revoke plus account
 * soft-delete to ADMINISTRATOR. Showing it to an account manager would offer four controls that answer
 * 403, which is the #867 shape. Hiding the whole card also makes capability grant/revoke *unreachable*
 * rather than merely refused, so the role cannot become a route to ADMINISTRATOR.
 */
export function AccountManagementTab() {
  // Same source DashboardPage gates the tab from; TanStack Query dedupes the request.
  const capabilities = useGetApiV1UsersMe().data?.capabilities ?? [];
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      {isAdministrator(capabilities) ? <ManagePlayerSection /> : null}
      <InvitesSection />
      <DeletedAccountsSection />
      <DuplicatesSection />
      <MergeAccountsSection />
      <DuplicateCandidatesSection />
    </div>
  );
}
