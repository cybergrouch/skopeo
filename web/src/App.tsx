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
import { SignUpPage } from '@/routes/SignUpPage'
import { LoginPage } from '@/routes/LoginPage'
import { InviteAcceptPage } from '@/routes/InviteAcceptPage'
import { CompleteProfilePage } from '@/routes/CompleteProfilePage'
import { DashboardPage } from '@/routes/DashboardPage'
import { PlayerProfilePage } from '@/routes/PlayerProfilePage'
import { MatchPage } from '@/routes/MatchPage'
import { EventPage } from '@/routes/EventPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
})

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
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
            <Route
              path="/players/:code"
              element={
                <RequireAuth>
                  <RequireProfile>
                    <PlayerProfilePage />
                  </RequireProfile>
                </RequireAuth>
              }
            />
            <Route
              path="/matches/:code"
              element={
                <RequireAuth>
                  <RequireProfile>
                    <MatchPage />
                  </RequireProfile>
                </RequireAuth>
              }
            />
            <Route
              path="/events/:code"
              element={
                <RequireAuth>
                  <RequireProfile>
                    <EventPage />
                  </RequireProfile>
                </RequireAuth>
              }
            />
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}

export default App
