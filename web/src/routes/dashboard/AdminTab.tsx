import { PendingAssessmentSection } from './admin/PendingAssessmentSection'
import { PendingCalculationSection } from './admin/PendingCalculationSection'

export function AdminTab() {
  return (
    <div className="grid gap-4">
      <PendingAssessmentSection />
      <PendingCalculationSection />
    </div>
  )
}
