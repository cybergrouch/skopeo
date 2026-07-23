import { BuildInfoSection } from "./admin/BuildInfoSection";
import { CircuitsSection } from "./admin/CircuitsSection";
import { ClubsSection } from "./admin/ClubsSection";
import { DeletedAccountsSection } from "./admin/DeletedAccountsSection";
import { DuplicateCandidatesSection } from "./admin/DuplicateCandidatesSection";
import { DuplicatesSection } from "./admin/DuplicatesSection";
import { ManagePlayerSection } from "./admin/ManagePlayerSection";
import { PendingCalculationSection } from "./admin/PendingCalculationSection";
import { StandingsSourceSection } from "./admin/StandingsSourceSection";
import { ThemeSection } from "./admin/ThemeSection";

export function AdminTab() {
  return (
    <div className="grid gap-4">
      <ManagePlayerSection />
      <DeletedAccountsSection />
      <ClubsSection />
      <CircuitsSection />
      <DuplicatesSection />
      <DuplicateCandidatesSection />
      <PendingCalculationSection />
      <StandingsSourceSection />
      <ThemeSection />
      <BuildInfoSection />
    </div>
  );
}
