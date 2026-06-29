import { PendingAssessmentSection } from './admin/PendingAssessmentSection'
import { RatingRequestSection } from './admin/RatingRequestSection'

/**
 * The Ratings tab (#106): rating work for a RATER (or ADMINISTRATOR), kept out of the Admin tab.
 * The pending initial-rating queue plus the re-rate-request triage (#140).
 */
export function RatingsTab() {
  return (
    <div className="grid gap-4">
      <PendingAssessmentSection />
      <RatingRequestSection />
    </div>
  )
}
