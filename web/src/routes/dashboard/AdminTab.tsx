import { InvitesSection } from './admin/InvitesSection'
import { PendingAssessmentSection } from './admin/PendingAssessmentSection'
import { PendingCalculationSection } from './admin/PendingCalculationSection'
import { RoleGrantsSection } from './admin/RoleGrantsSection'

export function AdminTab() {
  return (
    <div className="grid gap-4">
      <InvitesSection />
      <PendingAssessmentSection />
      <RoleGrantsSection />
      <PendingCalculationSection />
    </div>
  )
}
