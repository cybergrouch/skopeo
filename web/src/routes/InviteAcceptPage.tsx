import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { AuthLayout } from '@/components/AuthLayout'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAuth } from '@/auth/useAuth'
import {
  getGetApiV1UsersMeQueryKey,
  usePostApiV1Users,
} from '@/api/generated/users/users'
import type { CreateUserRequestSex } from '@/api/generated/model'
import { NtrpSelfRatingSelect } from '@/components/NtrpSelfRatingSelect'
import { authErrorMessage } from '@/lib/firebase-errors'

const SEXES = ['Male', 'Female'] as const

/**
 * Invite-acceptance landing page reached from a Firebase email-link (issue #74). It completes
 * email-link sign-in (which sets email_verified: true), then captures the required profile details
 * and a password before provisioning. The email comes from the link's `email` query param, so it
 * works cross-device without relying on localStorage.
 */
export function InviteAcceptPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { isSignInLink, completeSignInLink, setPassword } = useAuth()
  const provision = usePostApiV1Users()
  const [params] = useSearchParams()
  const email = params.get('email') ?? ''
  const href = window.location.href
  // Derived so the effect never sets state synchronously: a link is valid only with an email param.
  const linkValid = Boolean(email) && isSignInLink(href)

  // 'verifying' → completing the email-link sign-in; 'ready' → show the form; 'error' → completion failed.
  const [status, setStatus] = useState<'verifying' | 'ready' | 'error'>('verifying')
  const started = useRef(false)

  useEffect(() => {
    if (!linkValid || started.current) return // the email-link is single-use; complete it exactly once
    started.current = true
    completeSignInLink(email, href)
      .then(() => setStatus('ready'))
      .catch(() => setStatus('error'))
  }, [linkValid, email, href, completeSignInLink])

  const [name, setName] = useState('')
  const [sex, setSex] = useState('')
  const [dateOfBirth, setDateOfBirth] = useState('')
  const [proposedRating, setProposedRating] = useState('')
  const [password, setPasswordValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await setPassword(password)
      await provision.mutateAsync({
        data: { displayName: name.trim(), sex: sex as CreateUserRequestSex, dateOfBirth, proposedRating },
      })
      await queryClient.invalidateQueries({ queryKey: getGetApiV1UsersMeQueryKey() })
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(authErrorMessage(err))
      setBusy(false)
    }
  }

  if (!linkValid || status === 'error') {
    return (
      <AuthLayout
        title="Invite link invalid"
        description="This invite link is invalid or has expired."
      >
        <p className="text-sm text-muted-foreground">
          Ask an administrator to send you a fresh invitation.
        </p>
      </AuthLayout>
    )
  }

  if (status === 'verifying') {
    return (
      <div className="flex min-h-svh items-center justify-center text-muted-foreground">
        Verifying your invite…
      </div>
    )
  }

  return (
    <AuthLayout
      title="Complete your sign-up"
      description={`Finishing onboarding for ${email}`}
    >
      <form onSubmit={onSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="name">Display name</Label>
          <Input
            id="name"
            required
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
        <NtrpSelfRatingSelect value={proposedRating} onChange={setProposedRating} />
        <div className="space-y-2">
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            required
            value={password}
            onChange={(e) => setPasswordValue(e.target.value)}
            placeholder="At least 6 characters"
            autoComplete="new-password"
          />
        </div>
        {error ? (
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}
        <Button type="submit" className="w-full" disabled={busy}>
          {busy ? 'Finishing…' : 'Finish sign-up'}
        </Button>
      </form>
    </AuthLayout>
  )
}
