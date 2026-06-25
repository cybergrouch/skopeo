import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { FirebaseError } from 'firebase/app'
import { AuthLayout } from '@/components/AuthLayout'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAuth } from '@/auth/useAuth'
import { usePostApiV1Users } from '@/api/generated/users/users'
import type { CreateUserRequestSex } from '@/api/generated/model'
import { authErrorMessage } from '@/lib/firebase-errors'

const SEXES = ['Male', 'Female'] as const

export function SignUpPage() {
  const navigate = useNavigate()
  const { signUpWithEmail, signInWithEmail, signInWithGoogle, signInWithFacebook } =
    useAuth()
  const provision = usePostApiV1Users()

  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [sex, setSex] = useState('')
  const [dateOfBirth, setDateOfBirth] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Provisioning the Skopeo profile is the final, idempotent step of sign-up
  // (POST /api/v1/users). Sex and date of birth are required — they drive
  // tournament-category eligibility — so they ride along from the form.
  async function provisionAndContinue(displayName: string | null) {
    await provision.mutateAsync({
      data: { displayName, sex: sex as CreateUserRequestSex, dateOfBirth },
    })
    navigate('/dashboard', { replace: true })
  }

  async function onEmailSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await signUpWithEmail(email, password)
      // Manual sign-up requires a display name (the field is `required`), so no null fallback.
      await provisionAndContinue(name.trim())
    } catch (err) {
      // A Firebase user already exists for this email. If the password is correct,
      // this is either a returning user or an orphaned auth user whose profile never
      // provisioned (e.g. provisioning failed after the Firebase account was created).
      // Sign in and re-run the idempotent provision to recover instead of dead-ending.
      if (err instanceof FirebaseError && err.code === 'auth/email-already-in-use') {
        try {
          await signInWithEmail(email, password)
        } catch {
          // Wrong password (or another account) — point them at sign-in.
          setError(authErrorMessage(err))
          setBusy(false)
          return
        }
        try {
          await provisionAndContinue(name.trim())
        } catch (provisionErr) {
          setError(authErrorMessage(provisionErr))
          setBusy(false)
        }
        return
      }
      setError(authErrorMessage(err))
      setBusy(false)
    }
  }

  // Google and Facebook share the flow: sex + date of birth are required up front
  // (the popup yields no such fields), then provision the profile from the token.
  async function onOAuth(signIn: () => Promise<unknown>, provider: string) {
    if (!sex || !dateOfBirth) {
      setError(`Please enter your date of birth and sex before continuing with ${provider}.`)
      return
    }
    setError(null)
    setBusy(true)
    try {
      await signIn()
      await provisionAndContinue(name.trim() || null)
    } catch (err) {
      setError(authErrorMessage(err))
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      description="Join Skopeo to track your tennis rating"
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-primary hover:underline">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={onEmailSubmit} className="space-y-4">
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
        <div className="space-y-2">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            autoComplete="email"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
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
          {busy ? 'Creating account…' : 'Create account'}
        </Button>
      </form>

      <div className="relative">
        <div className="absolute inset-0 flex items-center">
          <span className="w-full border-t" />
        </div>
        <div className="relative flex justify-center text-xs uppercase">
          <span className="bg-card px-2 text-muted-foreground">or</span>
        </div>
      </div>

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
    </AuthLayout>
  )
}
