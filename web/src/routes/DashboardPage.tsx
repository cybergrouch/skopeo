import { Suspense, useState, type ReactNode } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { LogOut, Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import { BrandLogo } from "@/components/BrandLogo";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import { useAuth } from "@/auth/useAuth";
import {
  canManageMatches,
  canManagePointsBudget,
  canRate,
  canManageAccounts,
  isAdministrator,
  isPlayer,
  isResearcher,
} from "@/auth/capabilities";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import { useGetApiV1Clubs } from "@/api/generated/clubs/clubs";
import { ownedClubs } from "@/auth/clubAccess";
import { ProfileTab } from "./dashboard/ProfileTab";
import { PageContainer } from "@/components/PageContainer";
import { DESKTOP_QUERY, useMediaQuery } from "@/hooks/useMediaQuery";
import { SectionNav } from "./dashboard/SectionNav";
import { lazyWithPreload } from "@/lib/lazyWithPreload";

// Every section but Profile is its own chunk (#1092), so a signed-in user downloads only the sections
// they open — a PLAYER no longer carries the admin surface they can never see. Profile stays in this
// chunk: it is where everyone lands, and lazy-loading it would put a second round trip in front of the
// first screen. `sections[]` below still decides who gets which; this only decides when bytes arrive.
// Not a security boundary — every operation keeps its server-side rule.
const SettingsTab = lazyWithPreload(() =>
  import("./dashboard/SettingsTab").then((m) => m.SettingsTab),
);
const AdminTab = lazyWithPreload(() =>
  import("./dashboard/AdminTab").then((m) => m.AdminTab),
);
const AccountManagementTab = lazyWithPreload(() =>
  import("./dashboard/AccountManagementTab").then((m) => m.AccountManagementTab),
);
const ClubManagementTab = lazyWithPreload(() =>
  import("./dashboard/ClubManagementTab").then((m) => m.ClubManagementTab),
);
const PlaceholderPlayersTab = lazyWithPreload(() =>
  import("./dashboard/PlaceholderPlayersTab").then((m) => m.PlaceholderPlayersTab),
);
const SeedingTab = lazyWithPreload(() =>
  import("./dashboard/SeedingTab").then((m) => m.SeedingTab),
);
const RatingsTab = lazyWithPreload(() =>
  import("./dashboard/RatingsTab").then((m) => m.RatingsTab),
);
const ResearchTab = lazyWithPreload(() =>
  import("./dashboard/ResearchTab").then((m) => m.ResearchTab),
);
const StandingsTab = lazyWithPreload(() =>
  import("./dashboard/StandingsTab").then((m) => m.StandingsTab),
);
const ActivityTab = lazyWithPreload(() =>
  import("./dashboard/ActivityTab").then((m) => m.ActivityTab),
);
const ReportTab = lazyWithPreload(() =>
  import("./dashboard/ReportTab").then((m) => m.ReportTab),
);
const AboutTab = lazyWithPreload(() =>
  import("./dashboard/AboutTab").then((m) => m.AboutTab),
);
const PointsManagementSection = lazyWithPreload(() =>
  import("./dashboard/admin/PointsManagementSection").then((m) => m.PointsManagementSection),
);

interface Section {
  value: string;
  label: string;
  element: ReactNode;
  /** Starts fetching a lazy section's chunk; absent for Profile, which is always loaded. */
  preload?: () => unknown;
}

/** Shown only on a cold load of a lazy section (a deep link); a tab switch keeps the old one instead. */
function SectionFallback() {
  return <p className="text-sm text-muted-foreground">Loading…</p>;
}

export function DashboardPage() {
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const meQuery = useGetApiV1UsersMe();

  const me = meQuery.data;
  const capabilities = me?.capabilities ?? [];
  // The Event Organizer tab is gone (#794). Every club has its own organizer on its public page
  // (#780/#786), so a cross-club index and a second create form had no remaining purpose — including for
  // administrators, who reach any club from Club Management. Seeding still follows match-management.
  const showMatches = canManageMatches(capabilities);
  const showSeeding = canManageMatches(capabilities);
  const showRatings = canRate(capabilities);
  const showSettings = isPlayer(capabilities);
  const showResearch = isResearcher(capabilities);
  const showActivity = isAdministrator(capabilities);
  const showReport = isAdministrator(capabilities);
  const showAccountManagement = canManageAccounts(capabilities);
  // Club Management is a match-manager surface (#786), not admin-only: a HOST/CLUB_OWNER already reads
  // the club list to file events, so they can see it here too. Every WRITE inside keeps its own server
  // rule (create/rename/delete/owners are ADMINISTRATOR, sanctioning is CLUB_OWNER/ADMIN) and the section
  // renders per-operation — so widening visibility never offers a control the API would refuse.
  const showClubManagement = canManageMatches(capabilities);
  const showAdmin = isAdministrator(capabilities);
  // Points Management is always a standalone tab for anyone who can manage points budgets
  // (POINTS_MANAGER or ADMINISTRATOR); it's no longer embedded in the Admin tab.
  const showPointsManagement = canManagePointsBudget(capabilities);
  // "My clubs" (#1096): the clubs the viewer OWNS, as menu shortcuts to their public pages. The clubs
  // list is staff-readable and carries each club's owners, so ownership needs no new endpoint — and the
  // fetch is gated like ClubPage's, so a plain PLAYER sends no request and takes no 403. An
  // administrator owns nothing and so gets no entries, by decision: they reach every club through Club
  // Management. The entries are shortcuts, not permissions; the club page decides what the viewer may do.
  const showMyClubs = canManageMatches(capabilities);
  const clubsQuery = useGetApiV1Clubs({ query: { enabled: showMyClubs } });
  const myClubs = showMyClubs ? ownedClubs(clubsQuery.data ?? [], me?.id) : [];
  const [clubsOpen, setClubsOpen] = useState(true);

  // The selected section lives in the URL (?tab=…, #323) so it survives leaving and returning to the
  // dashboard — e.g. Back from a public page lands on the tab the user was on, not a reset to Profile.
  // The menu's open state stays local. There is one menu (#187) — one `sections[]`, one `SectionNav` —
  // rendered two ways (#1095): a persistent rail from `md:` up, so a section is one click away, and the
  // hamburger drawer below it, where a rail would eat the screen.
  const [searchParams, setSearchParams] = useSearchParams();
  const active = searchParams.get("tab") ?? "profile";
  const [navOpen, setNavOpen] = useState(false);
  const isDesktop = useMediaQuery(DESKTOP_QUERY);
  // Widening past `md:` with the drawer open swaps it for the rail; close it here, or narrowing again
  // would bring back a drawer nobody just opened.
  if (isDesktop && navOpen) setNavOpen(false);

  // One capability-gated list of sections: the single source of truth for the menu items and the
  // rendered content, so gating stays identical across both.
  const sections: Section[] = [
    {
      value: "profile",
      label: "Profile",
      element: (
        <ProfileTab
          userId={me?.id ?? ""}
          capabilities={capabilities}
          publicCode={me?.publicCode}
          photoUrl={me?.photoUrl}
        />
      ),
    },
    // Settings (#589): the owner's account actions, split out of Profile. Right after Profile since
    // they're the two personal tabs. PLAYER-gated → present for every signed-in user.
    ...(showSettings
      ? [
          {
            value: "settings",
            label: "Settings",
            preload: SettingsTab.preload,
            element: <SettingsTab userId={me?.id ?? ""} />,
          },
        ]
      : []),
    ...(showResearch
      ? [{ value: "research", label: "Research", preload: ResearchTab.preload, element: <ResearchTab /> }]
      : []),
    { value: "standings", label: "Standings", preload: StandingsTab.preload, element: <StandingsTab /> },
    // Claiming a placeholder account (#496) now lives conditionally on the Profile tab (#727), shown
    // only while the owner's account is still claim-eligible — no standalone Claim tab.
    ...(showSeeding
      ? [{ value: "seeding", label: "Seeding", preload: SeedingTab.preload, element: <SeedingTab /> }]
      : []),
    // Placeholder Players (#578): create + manage login-less players; HOST/CLUB_OWNER/ADMIN, like
    // the other match-management tabs. Promoted out of the Event Organizer tab.
    ...(showMatches
      ? [
          {
            value: "placeholders",
            label: "Placeholder Players",
            preload: PlaceholderPlayersTab.preload,
            element: <PlaceholderPlayersTab capabilities={capabilities} />,
          },
        ]
      : []),
    ...(showRatings
      ? [{ value: "ratings", label: "Ratings", preload: RatingsTab.preload, element: <RatingsTab /> }]
      : []),
    ...(showActivity
      ? [{ value: "activity", label: "Activity Log", preload: ActivityTab.preload, element: <ActivityTab /> }]
      : []),
    ...(showReport
      ? [{ value: "reports", label: "Reports", preload: ReportTab.preload, element: <ReportTab /> }]
      : []),
    ...(showPointsManagement
      ? [
          {
            value: "points",
            label: "Points Management",
            preload: PointsManagementSection.preload,
            element: <PointsManagementSection capabilities={capabilities} />,
          },
        ]
      : []),
    // Account Management (#648): player/account administration, split out of Admin. ADMINISTRATOR-gated,
    // placed just before Admin.
    ...(showAccountManagement
      ? [
          {
            value: "accounts",
            label: "Account Management",
            preload: AccountManagementTab.preload,
            element: <AccountManagementTab />,
          },
        ]
      : []),
    // Club Management (#698): clubs administration, split out of Admin. ADMINISTRATOR-gated, placed
    // just before Admin — mirrors the #648 Account Management split.
    ...(showClubManagement
      ? [
          {
            value: "club-management",
            label: "Club Management",
            preload: ClubManagementTab.preload,
            element: <ClubManagementTab />,
          },
        ]
      : []),
    ...(showAdmin
      ? [{ value: "admin", label: "Admin", preload: AdminTab.preload, element: <AdminTab /> }]
      : []),
    // About (#573): general info, available to every signed-in user; last so it never displaces the
    // working tabs. Same content as the public /about page, minus the sign-up / log-in call to action.
    { value: "about", label: "About", preload: AboutTab.preload, element: <AboutTab /> },
  ];

  // `active` comes from the URL, so it may name a section the viewer can't access (a hand-edited or
  // stale ?tab=…); sections[0] (Profile) is the fallback in that case.
  const activeSection: Section =
    sections.find((s) => s.value === active) ?? sections[0];

  const clubsGroup = {
    clubs: myClubs,
    open: clubsOpen,
    onOpenChange: setClubsOpen,
  };

  async function onSignOut() {
    await signOut();
    navigate("/login", { replace: true });
  }

  function selectSection(value: string) {
    // Sync the tab into the URL. Replace (not push) so switching tabs isn't itself a Back step;
    // Profile is the default, so it drops the param to keep the URL clean.
    const next = new URLSearchParams(searchParams);
    if (value === "profile") next.delete("tab");
    else next.set("tab", value);
    // React Router applies this in a transition, so switching to a section whose chunk is still loading
    // keeps the current one on screen instead of flashing the Suspense fallback (#1092) — pinned by
    // DashboardPage.lazy.test.tsx, so a router change that dropped it would fail there.
    setSearchParams(next, { replace: true });
    setNavOpen(false);
  }

  // Menu intent (pointer over, or focus on, an item) starts that section's download, so the click that
  // usually follows finds the chunk already loaded.
  function preloadSection(value: string) {
    sections.find((section) => section.value === value)?.preload?.();
  }

  // Scoped to the section, so the header and the menu stay on screen while a section loads.
  const content = (
    <Suspense fallback={<SectionFallback />}>{activeSection.element}</Suspense>
  );

  return (
    <div className="min-h-svh bg-muted/40">
      {/* The app's topmost element, so it owns the top safe-area inset (#1076): with
          `viewport-fit=cover` — and on an installed iOS app, a translucent status bar drawn over our
          own canvas — 12px of padding would put the logo and the sign-out button under the clock.
          `max()` keeps 12px as the floor, so nothing changes where there is no inset. */}
      <header className="border-b bg-background pb-3 pt-[max(0.75rem,env(safe-area-inset-top))]">
        <PageContainer className="flex items-center justify-between">
          <BrandLogo className="text-lg" />
          <Button
            variant="ghost"
            size="icon"
            aria-label="Sign out"
            onClick={onSignOut}
          >
            <LogOut />
          </Button>
        </PageContainer>
      </header>

      <main className="py-4">
        <PageContainer>
          {meQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">
              Loading your dashboard…
            </p>
          ) : (
            <>
              {isDesktop ? (
                // The rail (#1095): always visible, so it neither traps focus nor needs a trigger. The
                // heading stays — it is the page's only <h1>, however obvious the rail makes location.
                <div className="flex items-start gap-6">
                  <aside className="sticky top-4 w-60 shrink-0">
                    <SectionNav
                      sections={sections}
                      active={activeSection.value}
                      onSelect={selectSection}
                      onIntent={preloadSection}
                      clubs={clubsGroup}
                    />
                  </aside>
                  <div className="min-w-0 flex-1">
                    <h1 className="mb-4 text-lg font-semibold">
                      {activeSection.label}
                    </h1>
                    {content}
                  </div>
                </div>
              ) : (
                <>
                  {/* Below `md:` the menu is a drawer, and the current section's name is the page
                    header beside its trigger. */}
                  <div className="mb-4 flex items-center gap-3">
                    <Sheet open={navOpen} onOpenChange={setNavOpen}>
                      <SheetTrigger asChild>
                        <Button
                          variant="outline"
                          size="icon"
                          aria-label="Open navigation menu"
                        >
                          <Menu />
                        </Button>
                      </SheetTrigger>
                      <SheetContent
                        side="left"
                        className="w-72"
                        aria-describedby={undefined}
                      >
                        <SheetHeader>
                          <SheetTitle>Menu</SheetTitle>
                        </SheetHeader>
                        <SectionNav
                          sections={sections}
                          active={activeSection.value}
                          onSelect={selectSection}
                          onIntent={preloadSection}
                          clubs={clubsGroup}
                          className="mt-4"
                        />
                      </SheetContent>
                    </Sheet>
                    <h1 className="text-lg font-semibold">
                      {activeSection.label}
                    </h1>
                  </div>

                  {content}
                </>
              )}
            </>
          )}
        </PageContainer>
      </main>
    </div>
  );
}
