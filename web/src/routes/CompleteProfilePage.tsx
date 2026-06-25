import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { AuthLayout } from '@/components/AuthLayout'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAuth } from '@/auth/useAuth'
import {
  useGetApiV1UsersMe,
  getGetApiV1UsersMeQueryKey,
  usePostApiV1Users,
} from '@/api/generated/users/users'
import type { CreateUserRequestSex } from '@/api/generated/model'
import { authErrorMessage } from '@/lib/firebase-errors'

const SEXES = ['Male', 'Female'] as const

/**
 * Profile-completion step for an authenticated user with no Skopeo profile yet
 * (an OAuth login that never provisioned, or a failed provision). Collects the
 * required sex + date of birth (and an optional display name, prefilled from the
 * provider) and runs the idempotent provision, then continues to the dashboard.
 * `RequireProfile` routes unprovisioned users here.
 */
export function CompleteProfilePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const meQuery = useGetApiV1UsersMe()
  const provision = usePostApiV1Users()

  const [name, setName] = useState(user?.displayName ?? '')
  const [sex, setSex] = useState('')
  const [dateOfBirth, setDateOfBirth] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await provision.mutateAsync({
        data: {
          displayName: name.trim() || null,
          sex: sex as CreateUserRequestSex,
          dateOfBirth,
        },
      })
      await queryClient.invalidateQueries({ queryKey: getGetApiV1UsersMeQueryKey() })
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(authErrorMessage(err))
      setBusy(false)
    }
  }

  if (meQuery.isLoading) {
    return (
      <div className="flex min-h-svh items-center justify-center text-muted-foreground">
        Loading…
      </div>
    )
  }
  // Already provisioned (e.g. navigated here directly) — nothing to complete.
  if (meQuery.isSuccess) {
    return <Navigate to="/dashboard" replace />
  }

  return (
    <AuthLayout
      title="Complete your profile"
      description="A couple more details before you start playing."
    >
      <form onSubmit={onSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="name">Display name</Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Roger F."
            autoComplete="name"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="dateOfBirth">Date of birth</Label>
          <Input
            id="dateOfBirth"
            type="date"
            required
            value={dateOfBirth}
            onChange={(e) => setDateOfBirth(e.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="sex">Sex</Label>
          <select
            id="sex"
            required
            value={sex}
            onChange={(e) => setSex(e.target.value)}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
          >
            <option value="" disabled>
              Select…
            </option>
            {SEXES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        {error ? (
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}
        <Button type="submit" className="w-full" disabled={busy}>
          {busy ? 'Saving…' : 'Save and continue'}
        </Button>
      </form>
    </AuthLayout>
  )
}
