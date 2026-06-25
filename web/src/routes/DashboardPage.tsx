import { useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useAuth } from '@/auth/useAuth'
import { canManageMatches, isAdministrator } from '@/auth/capabilities'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import { ProfileTab } from './dashboard/ProfileTab'
import { AdminTab } from './dashboard/AdminTab'
import { MatchesTab } from './dashboard/MatchesTab'
import { ResearchTab } from './dashboard/ResearchTab'

export function DashboardPage() {
  const navigate = useNavigate()
  const { signOut } = useAuth()
  const meQuery = useGetApiV1UsersMe()

  const me = meQuery.data
  const capabilities = me?.capabilities ?? []
  const showMatches = canManageMatches(capabilities)
  const showAdmin = isAdministrator(capabilities)

  async function onSignOut() {
    await signOut()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-svh bg-muted/40">
      <header className="flex items-center justify-between border-b bg-background px-4 py-3">
        <span className="text-lg font-semibold">Skopeo</span>
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
              <TabsTrigger value="research">Research</TabsTrigger>
              {showMatches ? (
                <TabsTrigger value="matches">Matches</TabsTrigger>
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
              />
            </TabsContent>
            <TabsContent value="research">
              <ResearchTab />
            </TabsContent>
            {showMatches ? (
              <TabsContent value="matches">
                <MatchesTab />
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
