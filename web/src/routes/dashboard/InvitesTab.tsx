import { InvitesSection } from './admin/InvitesSection'

/**
 * The Invites tab (#135): onboarding-invite management, promoted out of the crowded Admin tab into
 * its own ADMINISTRATOR-only tab — the same access it had inside Admin. The duplicate-email guard
 * (#132) lives in InvitesSection itself.
 */
export function InvitesTab() {
  return (
    <div className="grid gap-4">
      <InvitesSection />
    </div>
  )
}
