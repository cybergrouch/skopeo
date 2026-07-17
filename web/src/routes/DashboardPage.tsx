import { useState, type ReactNode } from "react";
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
import { cn } from "@/lib/utils";
import { useAuth } from "@/auth/useAuth";
import {
  canManageMatches,
  canManagePointsBudget,
  canRate,
  isAdministrator,
  isResearcher,
} from "@/auth/capabilities";
import { useGetApiV1UsersMe } from "@/api/generated/users/users";
import { ProfileTab } from "./dashboard/ProfileTab";
import { AdminTab } from "./dashboard/AdminTab";
import { PointsManagementSection } from "./dashboard/admin/PointsManagementSection";
import { EventOrganizerTab } from "./dashboard/EventOrganizerTab";
import { SeedingTab } from "./dashboard/SeedingTab";
import { RatingsTab } from "./dashboard/RatingsTab";
import { ResearchTab } from "./dashboard/ResearchTab";
import { StandingsTab } from "./dashboard/StandingsTab";
import { InvitesTab } from "./dashboard/InvitesTab";
import { ActivityTab } from "./dashboard/ActivityTab";
import { ReportTab } from "./dashboard/ReportTab";

interface Section {
  value: string;
  label: string;
  element: ReactNode;
}

export function DashboardPage() {
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const meQuery = useGetApiV1UsersMe();

  const me = meQuery.data;
  const capabilities = me?.capabilities ?? [];
  const showMatches = canManageMatches(capabilities);
  const showSeeding = canManageMatches(capabilities);
  const showRatings = canRate(capabilities);
  const showResearch = isResearcher(capabilities);
  const showInvites = isAdministrator(capabilities);
  const showActivity = isAdministrator(capabilities);
  const showReport = isAdministrator(capabilities);
  const showAdmin = isAdministrator(capabilities);
  // Points Management is always a standalone tab for anyone who can manage points budgets
  // (POINTS_MANAGER or ADMINISTRATOR); it's no longer embedded in the Admin tab.
  const showPointsManagement = canManagePointsBudget(capabilities);

  // The selected section lives in the URL (?tab=…, #323) so it survives leaving and returning to the
  // dashboard — e.g. Back from a public page lands on the tab the user was on, not a reset to Profile.
  // The menu's open state stays local. One menu drives navigation at every breakpoint — a hamburger
  // drawer on mobile and desktop alike (#187) — so there's a single nav to reason about.
  const [searchParams, setSearchParams] = useSearchParams();
  const active = searchParams.get("tab") ?? "profile";
  const [navOpen, setNavOpen] = useState(false);

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
    ...(showResearch
      ? [{ value: "research", label: "Research", element: <ResearchTab /> }]
      : []),
    { value: "standings", label: "Standings", element: <StandingsTab /> },
    ...(showMatches
      ? [
          {
            value: "matches",
            label: "Event Organizer",
            element: <EventOrganizerTab />,
          },
        ]
      : []),
    ...(showSeeding
      ? [{ value: "seeding", label: "Seeding", element: <SeedingTab /> }]
      : []),
    ...(showRatings
      ? [{ value: "ratings", label: "Ratings", element: <RatingsTab /> }]
      : []),
    ...(showInvites
      ? [{ value: "invites", label: "Invites", element: <InvitesTab /> }]
      : []),
    ...(showActivity
      ? [{ value: "activity", label: "Activity Log", element: <ActivityTab /> }]
      : []),
    ...(showReport
      ? [{ value: "reports", label: "Reports", element: <ReportTab /> }]
      : []),
    ...(showPointsManagement
      ? [
          {
            value: "points",
            label: "Points Management",
            element: <PointsManagementSection capabilities={capabilities} />,
          },
        ]
      : []),
    ...(showAdmin
      ? [{ value: "admin", label: "Admin", element: <AdminTab /> }]
      : []),
  ];

  // `active` comes from the URL, so it may name a section the viewer can't access (a hand-edited or
  // stale ?tab=…); sections[0] (Profile) is the fallback in that case.
  const activeSection: Section =
    sections.find((s) => s.value === active) ?? sections[0];

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
    setSearchParams(next, { replace: true });
    setNavOpen(false);
  }

  return (
    <div className="min-h-svh bg-muted/40">
      <header className="flex items-center justify-between border-b bg-background px-4 py-3">
        <BrandLogo className="text-lg" />
        <Button
          variant="ghost"
          size="icon"
          aria-label="Sign out"
          onClick={onSignOut}
        >
          <LogOut />
        </Button>
      </header>

      <main className="mx-auto max-w-3xl p-4">
        {meQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">
            Loading your dashboard…
          </p>
        ) : (
          <>
            {/* A single hamburger menu drives navigation everywhere; the current section's name is
                the page header in place of a tab strip. */}
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
                  <nav className="mt-4 flex flex-col gap-1">
                    {sections.map((section) => (
                      <button
                        key={section.value}
                        type="button"
                        onClick={() => selectSection(section.value)}
                        aria-current={
                          section.value === active ? "page" : undefined
                        }
                        className={cn(
                          "rounded-md px-3 py-2 text-left text-sm font-medium transition-colors hover:bg-muted",
                          section.value === active && "bg-muted",
                        )}
                      >
                        {section.label}
                      </button>
                    ))}
                  </nav>
                </SheetContent>
              </Sheet>
              <h1 className="text-lg font-semibold">{activeSection.label}</h1>
            </div>

            {activeSection.element}
          </>
        )}
      </main>
    </div>
  );
}
