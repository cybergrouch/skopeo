import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { stubMatchMedia } from "@/test/matchMedia";
import { setupUser } from "@/test/user";
import { MemoryRouter, useLocation } from "react-router-dom";
import { DashboardPage } from "./DashboardPage";

const { useGetApiV1UsersMe, useGetApiV1Clubs, signOut, navigateMock } =
  vi.hoisted(() => ({
    useGetApiV1UsersMe: vi.fn(),
    useGetApiV1Clubs: vi.fn(),
    signOut: vi.fn(),
    navigateMock: vi.fn(),
  }));

vi.mock("@/api/generated/users/users", () => ({ useGetApiV1UsersMe }));
// The staff clubs list behind "My clubs" (#1096); empty unless a test says otherwise.
vi.mock("@/api/generated/clubs/clubs", () => ({ useGetApiV1Clubs }));
vi.mock("@/auth/useAuth", () => ({ useAuth: () => ({ signOut }) }));
vi.mock("react-router-dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router-dom")>();
  return { ...actual, useNavigate: () => navigateMock };
});
// Stub the section bodies so this test focuses on the shell (nav gating + sign-out).
vi.mock("./dashboard/ProfileTab", () => ({
  ProfileTab: () => <div>profile content</div>,
}));
vi.mock("./dashboard/SettingsTab", () => ({
  SettingsTab: () => <div>settings content</div>,
}));
vi.mock("./dashboard/AccountManagementTab", () => ({
  AccountManagementTab: () => <div>account management content</div>,
}));
vi.mock("./dashboard/ClubManagementTab", () => ({
  ClubManagementTab: () => <div>club management content</div>,
}));
vi.mock("./dashboard/AdminTab", () => ({
  AdminTab: () => <div>admin content</div>,
}));
vi.mock("./dashboard/SeedingTab", () => ({
  SeedingTab: () => <div>seeding content</div>,
}));
vi.mock("./dashboard/PlaceholderPlayersTab", () => ({
  PlaceholderPlayersTab: () => <div>placeholder players content</div>,
}));
vi.mock("./dashboard/RatingsTab", () => ({
  RatingsTab: () => <div>ratings content</div>,
}));
vi.mock("./dashboard/ResearchTab", () => ({
  ResearchTab: () => <div>research content</div>,
}));
vi.mock("./dashboard/StandingsTab", () => ({
  StandingsTab: () => <div>standings content</div>,
}));
vi.mock("./dashboard/ActivityTab", () => ({
  ActivityTab: () => <div>activity content</div>,
}));
vi.mock("./dashboard/ReportTab", () => ({
  ReportTab: () => <div>report content</div>,
}));
vi.mock("./dashboard/admin/PointsManagementSection", () => ({
  PointsManagementSection: () => <div>points management content</div>,
}));

/** Surfaces the current query string so a test can assert the tab is synced into the URL (#323). */
function SearchProbe() {
  const location = useLocation();
  return <div data-testid="search">{location.search}</div>;
}

function renderDashboard(initialEntries: string[] = ["/"]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <DashboardPage />
      <SearchProbe />
    </MemoryRouter>,
  );
}

/** Open the hamburger menu so its section items (role=button) are queryable. */
async function openMenu(user: ReturnType<typeof setupUser>) {
  await user.click(
    screen.getByRole("button", { name: "Open navigation menu" }),
  );
}

/** A club in the staff clubs list, owned by [ownerIds] (#789). */
function clubOwnedBy(name: string, publicCode: string, ...ownerIds: string[]) {
  return {
    id: `id-${publicCode}`,
    name,
    publicCode,
    isActive: true,
    owners: ownerIds.map((userId) => ({ userId, publicCode: `P-${userId}` })),
  };
}

describe("DashboardPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1Clubs.mockReturnValue({ data: undefined });
  });

  it("shows a loading state while the profile resolves", () => {
    useGetApiV1UsersMe.mockReturnValue({ data: undefined, isLoading: true });
    renderDashboard();
    expect(screen.getByText("Loading your dashboard…")).toBeInTheDocument();
  });

  it("shows Profile and Research (but not Matches/Ratings/Admin) for a default player", async () => {
    // Every sign-up is PLAYER + RESEARCHER (#107), so Research is visible by default.
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "RESEARCHER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);
    expect(screen.getByRole("button", { name: "Profile" })).toBeInTheDocument();
    // Settings (#589) is PLAYER-gated, so it's present for every signed-in user.
    expect(
      screen.getByRole("button", { name: "Settings" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Research" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Standings" }),
    ).toBeInTheDocument();
    // Claiming a placeholder account now lives conditionally on Profile (#727) — no standalone tab.
    expect(
      screen.queryByRole("button", { name: "Claim account" }),
    ).not.toBeInTheDocument();
    // The Event Organizer tab was removed entirely (#794) — every club organizes from its own page.
    expect(
      screen.queryByRole("button", { name: "Event Organizer" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Seeding" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Placeholder Players" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Ratings" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Activity Log" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Reports" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Points Management" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Account Management" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Club Management" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Admin" }),
    ).not.toBeInTheDocument();
  });

  it("opens the Settings tab content when selected (#589)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);
    await user.click(screen.getByRole("button", { name: "Settings" }));
    expect(await screen.findByText("settings content")).toBeInTheDocument();
  });

  it("hides the Research item from a player without RESEARCHER (#107)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);
    expect(screen.getByRole("button", { name: "Profile" })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Research" }),
    ).not.toBeInTheDocument();
  });

  it("shows the Ratings item for a rater (no Matches/Admin) (#106)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "RATER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);
    expect(screen.getByRole("button", { name: "Ratings" })).toBeInTheDocument();
    // The Event Organizer tab was removed entirely (#794) — every club organizes from its own page.
    expect(
      screen.queryByRole("button", { name: "Event Organizer" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Activity Log" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Admin" }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Ratings" }));
    expect(await screen.findByText("ratings content")).toBeInTheDocument();
  });

  it("shows Account Management for an account manager, and nothing else staff-only (#1002)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "ACCOUNT_MANAGER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);

    expect(
      screen.getByRole("button", { name: "Account Management" }),
    ).toBeInTheDocument();
    // The role is deliberately narrow: it is a VIEW right over people plus the account surfaces, and
    // grants none of the other staff jobs (#1002's view-set/action-set split).
    [
      "Admin",
      "Activity Log",
      "Reports",
      "Matches",
      "Ratings",
      "Points Management",
    ].forEach((tab) =>
      expect(
        screen.queryByRole("button", { name: tab }),
      ).not.toBeInTheDocument(),
    );
  });

  it("shows the Matches items for a host (plus Profile/Research, no Admin)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "RESEARCHER", "HOST"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);
    // The Event Organizer tab is ADMINISTRATOR-only since #794 — a host organizes from their club's
    // own public page instead.
    // The Event Organizer tab was removed entirely (#794) — every club organizes from its own page.
    expect(
      screen.queryByRole("button", { name: "Event Organizer" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Seeding" })).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Placeholder Players" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Research" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Standings" }),
    ).toBeInTheDocument();
    // Club Management is a match-manager surface now (#786) — a HOST sees it, though the writes inside
    // keep their own server rules, so they get the club list read-only.
    expect(
      screen.getByRole("button", { name: "Club Management" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Admin" }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Seeding" }));
    expect(await screen.findByText("seeding content")).toBeInTheDocument();

    // Selecting a tab closes the menu, so re-open it before switching tabs again.
    await openMenu(user);
    await user.click(
      screen.getByRole("button", { name: "Placeholder Players" }),
    );
    expect(await screen.findByText("placeholder players content")).toBeInTheDocument();
  });

  it("shows the Matches items for a club owner (same as a host, no Admin)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "RESEARCHER", "CLUB_OWNER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);
    // The Event Organizer tab is ADMINISTRATOR-only since #794 — a host organizes from their club's
    // own public page instead.
    // The Event Organizer tab was removed entirely (#794) — every club organizes from its own page.
    expect(
      screen.queryByRole("button", { name: "Event Organizer" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Research" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Admin" }),
    ).not.toBeInTheDocument();
  });

  it("shows every section for an administrator and switches between them", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "ADMINISTRATOR"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);
    expect(screen.getByRole("button", { name: "Ratings" })).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Standings" }),
    ).toBeInTheDocument();
    // Invites no longer has its own tab (#725) — it lives under Account Management now.
    expect(
      screen.queryByRole("button", { name: "Invites" }),
    ).not.toBeInTheDocument();
    // Claim account no longer has its own tab (#727) — it lives conditionally on Profile now.
    expect(
      screen.queryByRole("button", { name: "Claim account" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Activity Log" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reports" })).toBeInTheDocument();
    // Points Management is now a standalone tab administrators see too (#444).
    expect(
      screen.getByRole("button", { name: "Points Management" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Admin" })).toBeInTheDocument();
    // Account Management was split out of Admin (#648); ACCOUNT_MANAGER-gated since #1002, and an
    // administrator is implicitly one.
    expect(
      screen.getByRole("button", { name: "Account Management" }),
    ).toBeInTheDocument();
    // Club Management was split out of Admin (#698) and is now a match-manager tab (#786).
    expect(
      screen.getByRole("button", { name: "Club Management" }),
    ).toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "Account Management" }),
    );
    expect(await screen.findByText("account management content")).toBeInTheDocument();

    await openMenu(user);
    await user.click(screen.getByRole("button", { name: "Club Management" }));
    expect(await screen.findByText("club management content")).toBeInTheDocument();

    // The menu closes on select, so re-open it to navigate again.
    await openMenu(user);
    await user.click(screen.getByRole("button", { name: "Points Management" }));
    expect(await screen.findByText("points management content")).toBeInTheDocument();

    await openMenu(user);
    await user.click(screen.getByRole("button", { name: "Admin" }));
    expect(await screen.findByText("admin content")).toBeInTheDocument();
  });

  it("shows a standalone Points Management tab for a non-admin points manager (#444)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER", "POINTS_MANAGER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    await openMenu(user);
    expect(
      screen.getByRole("button", { name: "Points Management" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Admin" }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Points Management" }));
    expect(await screen.findByText("points management content")).toBeInTheDocument();
  });

  it("reflects the selected section as the page header, closing the menu on select (#187)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    // The header doubles as the page title in place of a tab strip.
    expect(
      screen.getByRole("heading", { name: "Profile" }),
    ).toBeInTheDocument();

    await openMenu(user);
    await user.click(screen.getByRole("button", { name: "Standings" }));
    expect(
      await screen.findByRole("heading", { name: "Standings" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("standings content")).toBeInTheDocument();
    // Selecting closed the drawer — its items are no longer rendered.
    expect(
      screen.queryByRole("button", { name: "Profile" }),
    ).not.toBeInTheDocument();
  });

  it("restores the active tab from the URL so returning to the dashboard keeps it (#323)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
      isLoading: false,
    });
    renderDashboard(["/?tab=standings"]);
    // No menu interaction: the tab is read straight from the URL on mount.
    expect(
      await screen.findByRole("heading", { name: "Standings" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("standings content")).toBeInTheDocument();
  });

  it("syncs the selected tab into the URL (#323)", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
      isLoading: false,
    });
    const user = setupUser();
    renderDashboard();
    expect(screen.getByTestId("search")).toHaveTextContent("");

    await openMenu(user);
    await user.click(screen.getByRole("button", { name: "Standings" }));
    expect(screen.getByTestId("search")).toHaveTextContent("tab=standings");

    // Returning to Profile (the default) drops the param again for a clean URL.
    await openMenu(user);
    await user.click(screen.getByRole("button", { name: "Profile" }));
    expect(screen.getByTestId("search")).toHaveTextContent("");
  });

  it("falls back to Profile when the URL names a tab the viewer cannot access (#323)", () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
      isLoading: false,
    });
    renderDashboard(["/?tab=admin"]);
    expect(
      screen.getByRole("heading", { name: "Profile" }),
    ).toBeInTheDocument();
    expect(screen.getByText("profile content")).toBeInTheDocument();
  });

  it("still renders the profile section when the id is missing", () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { capabilities: ["PLAYER"] },
      isLoading: false,
    });
    renderDashboard();
    expect(screen.getByText("profile content")).toBeInTheDocument();
  });

  it("signs out and returns to /login", async () => {
    useGetApiV1UsersMe.mockReturnValue({
      data: { id: "u1", capabilities: ["PLAYER"] },
      isLoading: false,
    });
    signOut.mockResolvedValue(undefined);
    const user = setupUser();
    renderDashboard();

    await user.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(signOut).toHaveBeenCalled());
    expect(navigateMock).toHaveBeenCalledWith("/login", { replace: true });
  });

  // From `md:` up the menu is a persistent rail (#1095). jsdom has no matchMedia, so every test above
  // runs the small-screen drawer; these stub a wide viewport.
  describe("at desktop width", () => {
    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it("shows the sections in a rail, one click away, with no drawer trigger", async () => {
      stubMatchMedia(true);
      useGetApiV1UsersMe.mockReturnValue({
        data: { id: "u1", capabilities: ["PLAYER"] },
        isLoading: false,
      });
      const user = setupUser();
      renderDashboard();

      expect(
        screen.queryByRole("button", { name: "Open navigation menu" }),
      ).not.toBeInTheDocument();
      const rail = screen.getByRole("navigation", { name: "Dashboard" });
      expect(
        within(rail)
          .getAllByRole("button")
          .map((item) => item.textContent),
      ).toEqual(["Profile", "Settings", "Standings", "About"]);
      expect(within(rail).getByRole("button", { name: "Profile" })).toHaveAttribute(
        "aria-current",
        "page",
      );

      // One click, straight from the landing — no menu to open first.
      await user.click(within(rail).getByRole("button", { name: "Standings" }));
      expect(
        await screen.findByRole("heading", { name: "Standings" }),
      ).toBeInTheDocument();
      expect(await screen.findByText("standings content")).toBeInTheDocument();
      expect(screen.getByTestId("search")).toHaveTextContent("?tab=standings");
      expect(within(rail).getByRole("button", { name: "Standings" })).toHaveAttribute(
        "aria-current",
        "page",
      );
      // The rail stays put: it is not a dialog, so nothing traps focus or closes it.
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("keeps the rail gated like the drawer", () => {
      stubMatchMedia(true);
      useGetApiV1UsersMe.mockReturnValue({
        data: { id: "u1", capabilities: ["PLAYER", "HOST"] },
        isLoading: false,
      });
      renderDashboard();
      const rail = screen.getByRole("navigation", { name: "Dashboard" });
      expect(
        within(rail).getByRole("button", { name: "Club Management" }),
      ).toBeInTheDocument();
      expect(
        within(rail).queryByRole("button", { name: "Admin" }),
      ).not.toBeInTheDocument();
    });

    it("marks Profile current when the URL names a tab the viewer cannot access", () => {
      stubMatchMedia(true);
      useGetApiV1UsersMe.mockReturnValue({
        data: { id: "u1", capabilities: ["PLAYER"] },
        isLoading: false,
      });
      renderDashboard(["/?tab=admin"]);
      expect(screen.getByRole("heading", { name: "Profile" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Profile" })).toHaveAttribute(
        "aria-current",
        "page",
      );
    });

    it("swaps an open drawer for the rail on widening, and does not reopen it on narrowing", async () => {
      const viewport = stubMatchMedia(false);
      useGetApiV1UsersMe.mockReturnValue({
        data: { id: "u1", capabilities: ["PLAYER"] },
        isLoading: false,
      });
      const user = setupUser();
      renderDashboard();
      await openMenu(user);
      expect(screen.getByRole("dialog")).toBeInTheDocument();

      act(() => viewport.set(true));
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(
        screen.getByRole("navigation", { name: "Dashboard" }),
      ).toBeInTheDocument();

      act(() => viewport.set(false));
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "Open navigation menu" }),
      ).toBeInTheDocument();
    });
  });

  // "My clubs" (#1096): the clubs the viewer owns, as menu shortcuts to their public pages.
  describe("My clubs", () => {
    afterEach(() => {
      vi.unstubAllGlobals();
    });

    function asOwner(capabilities: string[] = ["PLAYER", "CLUB_OWNER"]) {
      useGetApiV1UsersMe.mockReturnValue({
        data: { id: "u1", capabilities },
        isLoading: false,
      });
      useGetApiV1Clubs.mockReturnValue({
        data: [
          clubOwnedBy("Downtown TC", "CLB001", "u1"),
          clubOwnedBy("Riverside", "CLB002", "someone-else"),
          clubOwnedBy("Hilltop", "CLB003", "someone-else", "u1"),
        ],
      });
    }

    it("lists only the clubs the viewer owns, expanded, linking to each club page", async () => {
      asOwner();
      const user = setupUser();
      renderDashboard();
      await openMenu(user);

      expect(screen.getByRole("button", { name: "My clubs" })).toHaveAttribute(
        "aria-expanded",
        "true",
      );
      expect(screen.getByRole("link", { name: "Downtown TC" })).toHaveAttribute(
        "href",
        "/clubs/CLB001",
      );
      expect(screen.getByRole("link", { name: "Hilltop" })).toHaveAttribute(
        "href",
        "/clubs/CLB003",
      );
      expect(
        screen.queryByRole("link", { name: "Riverside" }),
      ).not.toBeInTheDocument();
    });

    it("collapses and expands in place, navigating nowhere", async () => {
      asOwner();
      stubMatchMedia(true);
      const user = setupUser();
      renderDashboard(["/?tab=standings"]);
      const heading = screen.getByRole("button", { name: "My clubs" });
      const list = document.getElementById(
        heading.getAttribute("aria-controls") ?? "",
      );
      expect(list).toContainElement(
        screen.getByRole("link", { name: "Downtown TC" }),
      );

      await user.click(heading);
      expect(heading).toHaveAttribute("aria-expanded", "false");
      expect(list).not.toBeVisible();
      expect(
        screen.queryByRole("link", { name: "Downtown TC" }),
      ).not.toBeInTheDocument();
      // Still on the same section: the heading is a disclosure, not a destination.
      expect(screen.getByTestId("search")).toHaveTextContent("?tab=standings");
      expect(navigateMock).not.toHaveBeenCalled();

      await user.click(heading);
      expect(heading).toHaveAttribute("aria-expanded", "true");
      expect(screen.getByRole("link", { name: "Downtown TC" })).toBeVisible();
    });

    it("keeps its open state across the drawer closing and reopening", async () => {
      asOwner();
      const user = setupUser();
      renderDashboard();
      await openMenu(user);
      await user.click(screen.getByRole("button", { name: "My clubs" }));
      // Picking a section closes the drawer, unmounting its content.
      await user.click(screen.getByRole("button", { name: "Standings" }));
      await openMenu(user);
      expect(screen.getByRole("button", { name: "My clubs" })).toHaveAttribute(
        "aria-expanded",
        "false",
      );
    });

    it("renders no group at all for a manager who owns no club", async () => {
      useGetApiV1UsersMe.mockReturnValue({
        data: { id: "u1", capabilities: ["PLAYER", "HOST"] },
        isLoading: false,
      });
      useGetApiV1Clubs.mockReturnValue({
        data: [clubOwnedBy("Riverside", "CLB002", "someone-else")],
      });
      const user = setupUser();
      renderDashboard();
      await openMenu(user);
      expect(screen.getByRole("button", { name: "Club Management" })).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "My clubs" }),
      ).not.toBeInTheDocument();
    });

    it("gives an administrator who owns no club no entries — they use Club Management", async () => {
      useGetApiV1UsersMe.mockReturnValue({
        data: { id: "u1", capabilities: ["PLAYER", "ADMINISTRATOR"] },
        isLoading: false,
      });
      useGetApiV1Clubs.mockReturnValue({
        data: [clubOwnedBy("Riverside", "CLB002", "someone-else")],
      });
      const user = setupUser();
      renderDashboard();
      await openMenu(user);
      expect(
        screen.queryByRole("button", { name: "My clubs" }),
      ).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Club Management" })).toBeInTheDocument();
    });

    it("sends a plain PLAYER no clubs request, and shows no group", async () => {
      useGetApiV1UsersMe.mockReturnValue({
        data: { id: "u1", capabilities: ["PLAYER"] },
        isLoading: false,
      });
      // Even if a cached list somehow named them an owner, the gate — not the data — decides.
      useGetApiV1Clubs.mockReturnValue({
        data: [clubOwnedBy("Downtown TC", "CLB001", "u1")],
      });
      const user = setupUser();
      renderDashboard();
      await openMenu(user);
      expect(useGetApiV1Clubs).toHaveBeenCalledWith({ query: { enabled: false } });
      expect(
        screen.queryByRole("button", { name: "My clubs" }),
      ).not.toBeInTheDocument();
    });
  });
});
