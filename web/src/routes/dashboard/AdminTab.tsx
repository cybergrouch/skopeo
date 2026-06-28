import { ActivityLogSection } from './admin/ActivityLogSection'
import { DuplicateCandidatesSection } from './admin/DuplicateCandidatesSection'
import { DuplicatesSection } from './admin/DuplicatesSection'
import { ManagePlayerSection } from './admin/ManagePlayerSection'
import { PendingCalculationSection } from './admin/PendingCalculationSection'

export function AdminTab() {
  return (
    <div className="grid gap-4">
      <ManagePlayerSection />
      <DuplicatesSection />
      <DuplicateCandidatesSection />
      <PendingCalculationSection />
      <ActivityLogSection />
    </div>
  )
}
