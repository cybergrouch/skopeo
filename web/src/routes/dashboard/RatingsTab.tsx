import { CalibrationListSection } from './admin/CalibrationListSection'
import { PendingAssessmentSection } from './admin/PendingAssessmentSection'
import { RatingRequestSection } from './admin/RatingRequestSection'
import { RatingsSearchSection } from './admin/RatingsSearchSection'

/**
 * The Ratings tab (#106): rating work for a RATER (or ADMINISTRATOR), kept out of the Admin tab.
 * The pending initial-rating queue, re-rate-request triage (#140), search-and-rate (#205), and the
 * players still in calibration with their per-player overrides (#1126).
 */
export function RatingsTab() {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <PendingAssessmentSection />
      <RatingRequestSection />
      <RatingsSearchSection />
      <CalibrationListSection />
    </div>
  )
}
