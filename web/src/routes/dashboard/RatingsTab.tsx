import { PendingAssessmentSection } from './admin/PendingAssessmentSection'

/**
 * The Ratings tab (#106): rating work for a RATER (or ADMINISTRATOR), kept out of the Admin tab.
 * Today it holds the pending initial-rating queue; re-rate requests follow in Phase 2 (#140).
 */
export function RatingsTab() {
  return (
    <div className="grid gap-4">
      <PendingAssessmentSection />
    </div>
  )
}
