import { BuildInfoSection } from "./admin/BuildInfoSection";
import { ClubsSection } from "./admin/ClubsSection";
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
      <ClubsSection />
      <DuplicatesSection />
      <DuplicateCandidatesSection />
      <PendingCalculationSection />
      <StandingsSourceSection />
      <ThemeSection />
      <BuildInfoSection />
    </div>
  );
}
