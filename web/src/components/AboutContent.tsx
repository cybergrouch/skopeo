import { Link } from "react-router-dom";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { buttonVariants } from "@/components/ui/button";
import { webVersion, webCommit } from "@/lib/version";

interface AboutContentProps {
  /**
   * Whether to show the Sign up / Log in call to action (#573). True on the public `/about` page for
   * logged-out visitors; false when rendered inside the logged-in dashboard's About tab (the viewer is
   * already authenticated).
   */
  showAuthActions?: boolean;
}

/**
 * The shared "About" content (#556/#573, reworded #586): what Skopeo is, why NTRP, and Rating vs
 * Ranking, plus the build version. Reused by the public {@link AboutPage} (with the auth CTA) and the
 * dashboard About tab (without it). Presentational only — the page/tab wrapper supplies its own chrome.
 */
export function AboutContent({ showAuthActions = true }: AboutContentProps) {
  const commit = webCommit();
  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <h1 className="text-3xl font-bold tracking-tight">Skopeo</h1>
        <p className="text-lg text-muted-foreground">
          Match logging for recreational tennis players. Skopeo turns real
          results into an honest NTRP estimate — and a ranking race within your
          level.
        </p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle>What Skopeo is</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm text-muted-foreground">
          <p>
            At heart, Skopeo is a{" "}
            <strong>match logger for recreational tennis players</strong> —
            simply record the players and the scores, and it takes it from
            there. On top of that: performance-based{" "}
            <strong>NTRP ratings</strong>,{" "}
            <strong>rankings</strong> within each rating band, and every
            player&rsquo;s <strong>match history</strong> and{" "}
            <strong>head-to-head</strong> records.
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>NTRP</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm text-muted-foreground">
          <p>
            We rate on the <strong>NTRP scale (1.0–7.0)</strong>. Why NTRP, and
            not something else?
          </p>
          <ul className="ml-4 list-disc space-y-1">
            <li>
              <strong>Not UTR</strong> — its match-validation rules are strict.
              We keep it simple: hosts log the matches; players don&rsquo;t
              self-report.
            </li>
            <li>
              <strong>Not A/B/C/D classes</strong> — you can&rsquo;t do the math
              on letters.
            </li>
            <li>
              <strong>Not a homegrown scale</strong> — no need to reinvent the
              wheel. NTRP is proven, and the USTA shows how it&rsquo;s
              calculated.
            </li>
          </ul>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Rating vs Ranking</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm text-muted-foreground">
          <p>
            <strong className="text-foreground">Rating — your game&rsquo;s level.</strong>{" "}
            Skopeo&rsquo;s best <em>estimate</em> of your NTRP level from your
            results — a good predictor, not the final word. Players in the same
            band are well-matched, so games stay close, competitive, and fun —
            fewer lopsided blowouts.
          </p>
          <p>
            <strong className="text-foreground">Ranking — the game within the game.</strong>{" "}
            Where you stand <em>within</em> your rating cohort. Win matches, earn
            ranking points, and climb the race against players at your level —
            fair, and within reach.
          </p>
        </CardContent>
      </Card>

      {showAuthActions ? (
        <div className="flex flex-wrap items-center gap-3">
          <Link to="/signup" className={buttonVariants()}>
            Sign up
          </Link>
          <Link to="/login" className={buttonVariants({ variant: "outline" })}>
            Log in
          </Link>
        </div>
      ) : null}

      <footer className="border-t pt-4 text-xs text-muted-foreground">
        Skopeo build {webVersion()}
        {commit ? ` · ${commit.slice(0, 7)}` : ""}
      </footer>
    </div>
  );
}
