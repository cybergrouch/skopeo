import { AboutContent } from "@/components/AboutContent";

/**
 * The dashboard "About" tab (#573): the same {@link AboutContent} shown on the public `/about` page,
 * but without the Sign up / Log in call to action — the viewer is already logged in. Available to
 * every signed-in user (no capability gate).
 */
export function AboutTab() {
  return <AboutContent showAuthActions={false} />;
}
