import { PublicPageNav } from "@/components/PublicPageNav";
import { AboutContent } from "@/components/AboutContent";
import { PageContainer } from "@/components/PageContainer";

/**
 * Public "About" page (#556) — reachable at `/about` without logging in. Explains what Skopeo is and
 * its capabilities, with dedicated sections on NTRP and the Rating-vs-Ranking distinction, plus the
 * build version and a sign-up / log-in call to action. The content is shared with the logged-in
 * dashboard's About tab (#573) via {@link AboutContent}; here the auth CTA is shown. Themed like the
 * rest of the app so it works in every seasonal theme.
 */
export function AboutPage() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <PageContainer size="prose" className="space-y-6 py-8">
        <PublicPageNav />
        <AboutContent showAuthActions />
      </PageContainer>
    </div>
  );
}
