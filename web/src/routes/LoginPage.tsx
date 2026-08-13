import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { AuthLayout } from "@/components/AuthLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/auth/useAuth";
import { useGetApiV1SettingsFacebookLogin } from "@/api/generated/settings/settings";
import { authErrorMessage } from "@/lib/firebase-errors";

interface LocationState {
  from?: { pathname?: string };
}

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { signInWithEmail, signInWithGoogle, signInWithFacebook } = useAuth();
  // Facebook login can be turned off app-wide (#647); show the button unless the flag is explicitly false.
  const facebookEnabled =
    useGetApiV1SettingsFacebookLogin({ query: { retry: false } }).data
      ?.enabled !== false;

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const from =
    (location.state as LocationState | null)?.from?.pathname ?? "/dashboard";

  async function run(action: () => Promise<unknown>) {
    setError(null);
    setBusy(true);
    try {
      await action();
      navigate(from, { replace: true });
    } catch (err) {
      setError(authErrorMessage(err));
      setBusy(false);
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    void run(() => signInWithEmail(email, password));
  }

  return (
    <AuthLayout
      title="Welcome back"
      description="Sign in to your Skopeo account"
      footer={
        <>
          Don&apos;t have an account?{" "}
          <Link
            to="/signup"
            className="font-medium text-primary hover:underline"
          >
            Sign up
          </Link>
          <span className="mt-1 block text-muted-foreground">
            New to Skopeo?{" "}
            <Link
              to="/about"
              className="font-medium text-primary hover:underline"
            >
              What is Skopeo?
            </Link>
          </span>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4">
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
            autoComplete="current-password"
          />
        </div>
        {error ? (
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}
        <Button type="submit" className="w-full" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
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
        onClick={() => void run(signInWithGoogle)}
        disabled={busy}
      >
        Continue with Google
      </Button>

      {facebookEnabled ? (
        <Button
          type="button"
          variant="outline"
          className="w-full"
          onClick={() => void run(signInWithFacebook)}
          disabled={busy}
        >
          Continue with Facebook
        </Button>
      ) : null}
    </AuthLayout>
  );
}
