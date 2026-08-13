import { ApiClientsSection } from "./admin/ApiClientsSection";
import { BuildInfoSection } from "./admin/BuildInfoSection";
import { CircuitsSection } from "./admin/CircuitsSection";
import { FeatureFlagsSection } from "./admin/FeatureFlagsSection";
import { PendingCalculationSection } from "./admin/PendingCalculationSection";
import { StandingsSourceSection } from "./admin/StandingsSourceSection";
import { ThemeSection } from "./admin/ThemeSection";

// Player/account administration (manage player, deleted accounts, duplicates, duplicate candidates)
// moved to the Account Management tab (#648). Clubs moved to the Club Management tab (#698).
export function AdminTab() {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
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
