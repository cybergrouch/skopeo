import { PendingAssessmentSection } from './admin/PendingAssessmentSection'
import { RatingRequestSection } from './admin/RatingRequestSection'
import { RatingsSearchSection } from './admin/RatingsSearchSection'

/**
 * The Ratings tab (#106): rating work for a RATER (or ADMINISTRATOR), kept out of the Admin tab.
 * The pending initial-rating queue, re-rate-request triage (#140), and search-and-rate (#205).
 */
export function RatingsTab() {
  return (
    <div className="grid gap-4">
      <PendingAssessmentSection />
      <RatingRequestSection />
      <RatingsSearchSection />
    </div>
  )
}
