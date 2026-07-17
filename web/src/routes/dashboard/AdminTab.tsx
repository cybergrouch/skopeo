import { BuildInfoSection } from "./admin/BuildInfoSection";
import { ClubsSection } from "./admin/ClubsSection";
import { DuplicateCandidatesSection } from "./admin/DuplicateCandidatesSection";
import { DuplicatesSection } from "./admin/DuplicatesSection";
import { ManagePlayerSection } from "./admin/ManagePlayerSection";
import { PendingCalculationSection } from "./admin/PendingCalculationSection";
import { PointsManagementSection } from "./admin/PointsManagementSection";
import { StandingsSourceSection } from "./admin/StandingsSourceSection";
import { ThemeSection } from "./admin/ThemeSection";

export function AdminTab() {
  return (
    <div className="grid gap-4">
      <ManagePlayerSection />
      <ClubsSection />
      <PointsManagementSection />
      <DuplicatesSection />
      <DuplicateCandidatesSection />
      <PendingCalculationSection />
      <StandingsSourceSection />
      <ThemeSection />
      <BuildInfoSection />
    </div>
  );
}
