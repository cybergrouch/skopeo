import { lazy, Suspense } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
} from 'react-router-dom'
import { AuthProvider } from '@/auth/AuthProvider'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireProfile } from '@/auth/RequireProfile'

// Route components are code-split (#277): each becomes its own chunk, loaded on demand behind the
// <Suspense> boundary below, so the initial bundle no longer carries every page. React.lazy needs a
// module with a `default` export, so the named page exports are adapted inline.
const SignUpPage = lazy(() => import('@/routes/SignUpPage').then((m) => ({ default: m.SignUpPage })))
const LoginPage = lazy(() => import('@/routes/LoginPage').then((m) => ({ default: m.LoginPage })))
const InviteAcceptPage = lazy(() => import('@/routes/InviteAcceptPage').then((m) => ({ default: m.InviteAcceptPage })))
const CompleteProfilePage = lazy(() => import('@/routes/CompleteProfilePage').then((m) => ({ default: m.CompleteProfilePage })))
const DashboardPage = lazy(() => import('@/routes/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const PlayerProfilePage = lazy(() => import('@/routes/PlayerProfilePage').then((m) => ({ default: m.PlayerProfilePage })))
const PlayerMatchesPage = lazy(() => import('@/routes/PlayerMatchesPage').then((m) => ({ default: m.PlayerMatchesPage })))
const MatchPage = lazy(() => import('@/routes/MatchPage').then((m) => ({ default: m.MatchPage })))
const EventPage = lazy(() => import('@/routes/EventPage').then((m) => ({ default: m.EventPage })))
const ClubPage = lazy(() => import('@/routes/ClubPage').then((m) => ({ default: m.ClubPage })))

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
})

/** Lightweight fallback shown while a route's chunk is being fetched. */
function PageFallback() {
  return (
    <div className="flex min-h-svh items-center justify-center p-4">
      <p className="text-sm text-muted-foreground">Loading…</p>
    </div>
  )
}

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Suspense fallback={<PageFallback />}>
            <Routes>
              <Route path="/signup" element={<SignUpPage />} />
              <Route path="/login" element={<LoginPage />} />
              <Route path="/invite" element={<InviteAcceptPage />} />
              <Route
                path="/complete-profile"
                element={
                  <RequireAuth>
                    <CompleteProfilePage />
                  </RequireAuth>
                }
              />
              <Route
                path="/dashboard"
                element={
                  <RequireAuth>
                    <RequireProfile>
                      <DashboardPage />
                    </RequireProfile>
                  </RequireAuth>
                }
              />
              {/* Public-by-code pages are viewable without login (#193); anonymous viewers get a
                  sign-up/login CTA and any account-requiring action prompts them to authenticate. */}
              <Route path="/players/:code" element={<PlayerProfilePage />} />
              <Route path="/players/:code/matches" element={<PlayerMatchesPage />} />
              <Route path="/matches/:code" element={<MatchPage />} />
              <Route path="/events/:code" element={<EventPage />} />
              <Route path="/clubs/:code" element={<ClubPage />} />
              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
          </Suspense>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}

export default App
