import { Link } from "react-router-dom";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { buttonVariants } from "@/components/ui/button";
import { PublicPageNav } from "@/components/PublicPageNav";
import { webVersion, webCommit } from "@/lib/version";

/**
 * Public "About" page (#556) — reachable at `/about` without logging in. Explains what Skopeo is and
 * its capabilities, with dedicated sections on NTRP and the Rating-vs-Ranking distinction, plus the
 * build version and a sign-up / log-in call to action. Themed like the rest of the app so it works in
 * every seasonal theme.
 */
export function AboutPage() {
  const commit = webCommit();
  return (
    <div className="min-h-screen bg-background text-foreground">
      <div className="mx-auto max-w-3xl space-y-6 px-4 py-8">
        <PublicPageNav />

        <header className="space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Skopeo</h1>
          <p className="text-lg text-muted-foreground">
            Performance-based tennis ratings, competitions, and per-band ranking
            standings — from real match results.
          </p>
        </header>

        <Card>
          <CardHeader>
            <CardTitle>What Skopeo is</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm text-muted-foreground">
            <p>
              Skopeo turns real match results into NTRP ratings, runs tournaments
              and circuits, and keeps ranking-point standings for each NTRP band —
              with shareable public pages for players, matches, and events.
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>NTRP</CardTitle>
            <CardDescription>The rating scale Skopeo uses.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2 text-sm text-muted-foreground">
            <p>
              The National Tennis Rating Program (NTRP) rates players on a{" "}
              <strong>1.0–7.0</strong> scale in half-point levels (2.5, 3.0, 3.5,
              …). Skopeo estimates your NTRP rating from your match results and
              groups players into <strong>0.5-wide bands</strong>, so you compete
              and are ranked against players of a similar level.
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Rating vs Ranking</CardTitle>
            <CardDescription>
              Two different numbers — easy to confuse, worth keeping straight.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm text-muted-foreground">
            <p>
              <strong className="text-foreground">Rating — how good you are.</strong>{" "}
              A performance-based NTRP estimate (1.0–7.0) computed from your match
              results with an Elo-style algorithm, plus a <em>confidence</em>{" "}
              measure that reflects how much recent play backs it up. It moves as
              you do better or worse than expected.
            </p>
            <p>
              <strong className="text-foreground">Ranking — how you&rsquo;re doing in the race.</strong>{" "}
              A standings position earned from <em>ranking points</em> you accrue
              in competitions (open play and tournaments), tallied{" "}
              <strong>per NTRP band</strong>. Points have a validity window and
              expire, so a ranking reflects recent results; ties break on rating
              confidence.
            </p>
            <p>
              They are <strong>independent</strong>: your rating can hold steady
              while your ranking climbs or slips as you earn and shed points — and
              a newly-rated player can sit low in the ranking until they compete.
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Competitions &amp; standings</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm text-muted-foreground">
            <p>
              Organizers run events — open play, leagues, and tournaments
              (sanctioned or unsanctioned) grouped into admin-defined circuits.
              Finalizing an event awards ranking points, and standings are
              computed per band. Seeding and event management are built in.
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Public &amp; shareable</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm text-muted-foreground">
            <p>
              Every player, match, and event has a shareable public page (with a
              QR code) that anyone can view without an account. Skopeo is for
              players tracking their level and results, hosts and clubs running
              events, and administrators managing ratings, circuits, and
              standings.
            </p>
          </CardContent>
        </Card>

        <div className="flex flex-wrap items-center gap-3">
          <Link to="/signup" className={buttonVariants()}>
            Sign up
          </Link>
          <Link to="/login" className={buttonVariants({ variant: "outline" })}>
            Log in
          </Link>
        </div>

        <footer className="border-t pt-4 text-xs text-muted-foreground">
          Skopeo build {webVersion()}
          {commit ? ` · ${commit.slice(0, 7)}` : ""}
        </footer>
      </div>
    </div>
  );
}
