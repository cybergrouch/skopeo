import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { LogOut, Menu } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { BrandLogo } from '@/components/BrandLogo'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import { useAuth } from '@/auth/useAuth'
import {
  canManageMatches,
  canRate,
  isAdministrator,
  isResearcher,
} from '@/auth/capabilities'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import { ProfileTab } from './dashboard/ProfileTab'
import { AdminTab } from './dashboard/AdminTab'
import { EventOrganizerTab } from './dashboard/EventOrganizerTab'
import { SeedingTab } from './dashboard/SeedingTab'
import { RatingsTab } from './dashboard/RatingsTab'
import { ResearchTab } from './dashboard/ResearchTab'
import { StandingsTab } from './dashboard/StandingsTab'
import { InvitesTab } from './dashboard/InvitesTab'
import { ActivityTab } from './dashboard/ActivityTab'
import { ReportTab } from './dashboard/ReportTab'

interface Section {
  value: string
  label: string
  element: ReactNode
}

export function DashboardPage() {
  const navigate = useNavigate()
  const { signOut } = useAuth()
  const meQuery = useGetApiV1UsersMe()

  const me = meQuery.data
  const capabilities = me?.capabilities ?? []
  const showMatches = canManageMatches(capabilities)
  const showSeeding = canManageMatches(capabilities)
  const showRatings = canRate(capabilities)
  const showResearch = isResearcher(capabilities)
  const showInvites = isAdministrator(capabilities)
  const showActivity = isAdministrator(capabilities)
  const showReport = isAdministrator(capabilities)
  const showAdmin = isAdministrator(capabilities)

  // The selected section + the menu's open state. One menu drives navigation at every breakpoint —
  // a hamburger drawer on mobile and desktop alike (#187) — so there's a single nav to reason about.
  const [active, setActive] = useState('profile')
  const [navOpen, setNavOpen] = useState(false)

  // One capability-gated list of sections: the single source of truth for the menu items and the
  // rendered content, so gating stays identical across both.
  const sections: Section[] = [
    {
      value: 'profile',
      label: 'Profile',
      element: (
        <ProfileTab
          userId={me?.id ?? ''}
          capabilities={capabilities}
          publicCode={me?.publicCode}
        />
      ),
    },
    ...(showResearch ? [{ value: 'research', label: 'Research', element: <ResearchTab /> }] : []),
    { value: 'standings', label: 'Standings', element: <StandingsTab /> },
    ...(showMatches
      ? [{ value: 'matches', label: 'Event Organizer', element: <EventOrganizerTab /> }]
      : []),
    ...(showSeeding ? [{ value: 'seeding', label: 'Seeding', element: <SeedingTab /> }] : []),
    ...(showRatings ? [{ value: 'ratings', label: 'Ratings', element: <RatingsTab /> }] : []),
    ...(showInvites ? [{ value: 'invites', label: 'Invites', element: <InvitesTab /> }] : []),
    ...(showActivity ? [{ value: 'activity', label: 'Activity Log', element: <ActivityTab /> }] : []),
    ...(showReport ? [{ value: 'reports', label: 'Reports', element: <ReportTab /> }] : []),
    ...(showAdmin ? [{ value: 'admin', label: 'Admin', element: <AdminTab /> }] : []),
  ]

  // `active` always names a section (default 'profile', and the menu only sets values from this
  // list), so the find resolves; sections[0] (Profile) is a typed, never-taken safety net.
  /* v8 ignore next */
  const activeSection: Section = sections.find((s) => s.value === active) ?? sections[0]

  async function onSignOut() {
    await signOut()
    navigate('/login', { replace: true })
  }

  function selectSection(value: string) {
    setActive(value)
    setNavOpen(false)
  }

  return (
    <div className="min-h-svh bg-muted/40">
      <header className="flex items-center justify-between border-b bg-background px-4 py-3">
        <BrandLogo className="text-lg" />
        <Button
          variant="ghost"
          size="icon"
          aria-label="Sign out"
          onClick={onSignOut}
        >
          <LogOut />
        </Button>
      </header>

      <main className="mx-auto max-w-3xl p-4">
        {meQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading your dashboard…</p>
        ) : (
          <>
            {/* A single hamburger menu drives navigation everywhere; the current section's name is
                the page header in place of a tab strip. */}
            <div className="mb-4 flex items-center gap-3">
              <Sheet open={navOpen} onOpenChange={setNavOpen}>
                <SheetTrigger asChild>
                  <Button variant="outline" size="icon" aria-label="Open navigation menu">
                    <Menu />
                  </Button>
                </SheetTrigger>
                <SheetContent side="left" className="w-72" aria-describedby={undefined}>
                  <SheetHeader>
                    <SheetTitle>Menu</SheetTitle>
                  </SheetHeader>
                  <nav className="mt-4 flex flex-col gap-1">
                    {sections.map((section) => (
                      <button
                        key={section.value}
                        type="button"
                        onClick={() => selectSection(section.value)}
                        aria-current={section.value === active ? 'page' : undefined}
                        className={cn(
                          'rounded-md px-3 py-2 text-left text-sm font-medium transition-colors hover:bg-muted',
                          section.value === active && 'bg-muted',
                        )}
                      >
                        {section.label}
                      </button>
                    ))}
                  </nav>
                </SheetContent>
              </Sheet>
              <h1 className="text-lg font-semibold">{activeSection.label}</h1>
            </div>

            {activeSection.element}
          </>
        )}
      </main>
    </div>
  )
}
