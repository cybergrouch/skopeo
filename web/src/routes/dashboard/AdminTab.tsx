import { ApiClientsSection } from "./admin/ApiClientsSection";
import { BuildInfoSection } from "./admin/BuildInfoSection";
import { CircuitsSection } from "./admin/CircuitsSection";
import { ClubsSection } from "./admin/ClubsSection";
import { FeatureFlagsSection } from "./admin/FeatureFlagsSection";
import { PendingCalculationSection } from "./admin/PendingCalculationSection";
import { StandingsSourceSection } from "./admin/StandingsSourceSection";
import { ThemeSection } from "./admin/ThemeSection";

// Player/account administration (manage player, deleted accounts, duplicates, duplicate candidates)
// moved to the Account Management tab (#648).
export function AdminTab() {
  return (
    <div className="grid gap-4">
      <ClubsSection />
      <CircuitsSection />
      <PendingCalculationSection />
      <StandingsSourceSection />
      <FeatureFlagsSection />
      <ThemeSection />
      <ApiClientsSection />
      <BuildInfoSection />
    </div>
  );
}
