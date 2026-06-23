import { PendingAssessmentSection } from './admin/PendingAssessmentSection'
import { PendingCalculationSection } from './admin/PendingCalculationSection'
import { RoleGrantsSection } from './admin/RoleGrantsSection'

export function AdminTab() {
  return (
    <div className="grid gap-4">
      <PendingAssessmentSection />
      <RoleGrantsSection />
      <PendingCalculationSection />
    </div>
  )
}
