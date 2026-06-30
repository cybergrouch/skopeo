import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AuthLayout } from '@/components/AuthLayout'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAuth } from '@/auth/useAuth'
import { usePostApiV1Users } from '@/api/generated/users/users'
import type { CreateUserRequestSex } from '@/api/generated/model'
import { NtrpSelfRatingSelect } from '@/components/NtrpSelfRatingSelect'
import { authErrorMessage } from '@/lib/firebase-errors'

const SEXES = ['Male', 'Female'] as const

/**
 * Self-serve sign-up is OAuth-only (Google/Facebook), which arrives email-verified. Manual
 * (email/password) sign-up was retired in favour of admin invites (issue #74): invited members
 * onboard via the email-link on the /invite page, then sign in with email+password thereafter.
 */
export function SignUpPage() {
  const navigate = useNavigate()
  const { signInWithGoogle, signInWithFacebook } = useAuth()
  const provision = usePostApiV1Users()

  const [name, setName] = useState('')
  const [sex, setSex] = useState('')
  const [dateOfBirth, setDateOfBirth] = useState('')
  const [proposedRating, setProposedRating] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Sex + date of birth are required up front (the OAuth popup yields neither), then the profile
  // is provisioned from the verified token.
  async function onOAuth(signIn: () => Promise<unknown>, provider: string) {
    if (!sex || !dateOfBirth || !proposedRating) {
      setError(`Please enter your date of birth, sex, and NTRP self-rating before continuing with ${provider}.`)
      return
    }
    setError(null)
    setBusy(true)
    try {
      await signIn()
      await provision.mutateAsync({
        data: {
          displayName: name.trim() || null,
          sex: sex as CreateUserRequestSex,
          dateOfBirth,
          proposedRating,
        },
      })
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(authErrorMessage(err))
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      description="Sign up with Google or Facebook to track your tennis rating."
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-primary hover:underline">
            Sign in
          </Link>
        </>
      }
    >
      <div className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="name">Display name (optional)</Label>
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
        <NtrpSelfRatingSelect value={proposedRating} onChange={setProposedRating} />
        {error ? (
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}

        <Button
          type="button"
          variant="outline"
          className="w-full"
          onClick={() => void onOAuth(signInWithGoogle, 'Google')}
          disabled={busy}
        >
          Continue with Google
        </Button>
        <Button
          type="button"
          variant="outline"
          className="w-full"
          onClick={() => void onOAuth(signInWithFacebook, 'Facebook')}
          disabled={busy}
        >
          Continue with Facebook
        </Button>

        <p className="text-center text-xs text-muted-foreground">
          Invited by an administrator? Use the sign-in link in your email.
        </p>
      </div>
    </AuthLayout>
  )
}
