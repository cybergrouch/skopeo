import { ActivityLogSection } from './admin/ActivityLogSection'
import { InvitesSection } from './admin/InvitesSection'
import { ManagePlayerSection } from './admin/ManagePlayerSection'
import { PendingAssessmentSection } from './admin/PendingAssessmentSection'
import { PendingCalculationSection } from './admin/PendingCalculationSection'

export function AdminTab() {
  return (
    <div className="grid gap-4">
      <InvitesSection />
      <PendingAssessmentSection />
      <ManagePlayerSection />
      <PendingCalculationSection />
      <ActivityLogSection />
    </div>
  )
}
