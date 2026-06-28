import { useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { BrandLogo } from '@/components/BrandLogo'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
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
import { MatchesTab } from './dashboard/MatchesTab'
import { SeedingTab } from './dashboard/SeedingTab'
import { RatingsTab } from './dashboard/RatingsTab'
import { ResearchTab } from './dashboard/ResearchTab'

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
  const showAdmin = isAdministrator(capabilities)

  async function onSignOut() {
    await signOut()
    navigate('/login', { replace: true })
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
          <Tabs defaultValue="profile">
            <TabsList>
              <TabsTrigger value="profile">Profile</TabsTrigger>
              {showResearch ? (
                <TabsTrigger value="research">Research</TabsTrigger>
              ) : null}
              {showMatches ? (
                <TabsTrigger value="matches">Matches</TabsTrigger>
              ) : null}
              {showSeeding ? (
                <TabsTrigger value="seeding">Seeding</TabsTrigger>
              ) : null}
              {showRatings ? (
                <TabsTrigger value="ratings">Ratings</TabsTrigger>
              ) : null}
              {showAdmin ? (
                <TabsTrigger value="admin">Admin</TabsTrigger>
              ) : null}
            </TabsList>

            <TabsContent value="profile">
              <ProfileTab
                userId={me?.id ?? ''}
                capabilities={capabilities}
                publicCode={me?.publicCode}
                dateOfBirth={me?.dateOfBirth}
                sex={me?.sex}
              />
            </TabsContent>
            {showResearch ? (
              <TabsContent value="research">
                <ResearchTab />
              </TabsContent>
            ) : null}
            {showMatches ? (
              <TabsContent value="matches">
                <MatchesTab />
              </TabsContent>
            ) : null}
            {showSeeding ? (
              <TabsContent value="seeding">
                <SeedingTab />
              </TabsContent>
            ) : null}
            {showRatings ? (
              <TabsContent value="ratings">
                <RatingsTab />
              </TabsContent>
            ) : null}
            {showAdmin ? (
              <TabsContent value="admin">
                <AdminTab />
              </TabsContent>
            ) : null}
          </Tabs>
        )}
      </main>
    </div>
  )
}
