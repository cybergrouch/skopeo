import { ActivityLogSection } from './admin/ActivityLogSection'

/**
 * The Activity Log tab (#134): the audit log, promoted out of the crowded Admin tab into its own
 * ADMINISTRATOR-only tab so it has room to breathe (it also paginates 25 rows/page).
 */
export function ActivityTab() {
  return (
    <div className="grid gap-4">
      <ActivityLogSection />
    </div>
  )
}
