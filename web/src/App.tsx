import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
} from 'react-router-dom'
import { AuthProvider } from '@/auth/AuthProvider'
import { RequireAuth } from '@/auth/RequireAuth'
import { SignUpPage } from '@/routes/SignUpPage'
import { LoginPage } from '@/routes/LoginPage'
import { PendingAssessmentPage } from '@/routes/PendingAssessmentPage'

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
            <Route
              path="/pending"
              element={
                <RequireAuth>
                  <PendingAssessmentPage />
                </RequireAuth>
              }
            />
            <Route path="/" element={<Navigate to="/pending" replace />} />
            <Route path="*" element={<Navigate to="/pending" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}

export default App
