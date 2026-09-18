import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  MemoryRouter,
  Routes,
  Route,
  useLocation,
  useNavigationType,
} from "react-router-dom";
import { PlayerMatchesPage } from "./PlayerMatchesPage";
import type { PlayerMatchHistoryEntry } from "@/api/generated/model";

const { useGetApiV1PlayersCodeMatchHistory } = vi.hoisted(() => ({
  useGetApiV1PlayersCodeMatchHistory: vi.fn(),
}));
vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1PlayersCodeMatchHistory,
}));
// Make the search debounce a pass-through so typing flows straight to the query in tests.
vi.mock("@/hooks/useDebouncedValue", () => ({
  useDebouncedValue: (v: string) => v,
}));
// PublicPageNav reads auth; stub it so Firebase never initializes.
vi.mock("@/components/PublicPageNav", () => ({
  PublicPageNav: () => <nav>nav</nav>,
}));

function match(id: string, opponent: string): PlayerMatchHistoryEntry {
  return {
    matchId: id,
    publicCode: id.toUpperCase(),
    matchDate: "2026-01-01",
    status: "COMPLETED",
    rated: false,
    result: "WIN",
    setScores: ["6-4"],
    partners: [],
    opponents: [
      {
        publicCode: `${opponent}1`,
        displayName: opponent,
        photoUrl: null,
        levelAtMatch: null,
      },
    ],
    playerLevelAtMatch: null,
  };
}

/** Surfaces the query string page, search and band live in (#1056), and how it got there. */
function UrlProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <>
      <div data-testid="url">{decodeURIComponent(location.search)}</div>
      <div data-testid="nav-type">{navigationType}</div>
    </>
  );
}

function renderPage(entry = "/players/K7Q2MX/matches") {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/players/:code/matches" element={<PlayerMatchesPage />} />
      </Routes>
      <UrlProbe />
    </MemoryRouter>,
  );
}

describe("PlayerMatchesPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [match("a", "Ben"), match("b", "Cara")], total: 45 },
      isLoading: false,
    });
  });

  it("renders a page of matches with the pager and requests the first page", () => {
    renderPage();
    expect(screen.getByText("Ben")).toBeInTheDocument();
    expect(screen.getByText("Cara")).toBeInTheDocument();
    expect(screen.getByText("Showing 1–20 of 45")).toBeInTheDocument();
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
      "K7Q2MX",
      { limit: 20, offset: 0, search: undefined },
      { query: { enabled: true } },
    );
  });

  it("requests the next page by offset", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
      "K7Q2MX",
      { limit: 20, offset: 20, search: undefined },
      { query: { enabled: true } },
    );
  });

  it("searches server-side and resets to the first page", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("button", { name: "Next" })); // move off page 0 first
    await user.type(screen.getByPlaceholderText("Search opponent…"), "ben");
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
      "K7Q2MX",
      { limit: 20, offset: 0, search: "ben" },
      { query: { enabled: true } },
    );
  });

  it("filters by opponent NTRP band server-side and resets to the first page (#563)", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("button", { name: "Next" })); // move off page 0 first
    await user.selectOptions(
      screen.getByLabelText("Filter by opponent NTRP band"),
      "4.0",
    );
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
      "K7Q2MX",
      { limit: 20, offset: 0, search: undefined, opponentBand: "4.0" },
      { query: { enabled: true } },
    );
  });

  it("shows a loading state", () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderPage();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows a search-specific empty state", async () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [], total: 0 },
      isLoading: false,
    });
    const user = userEvent.setup();
    renderPage();
    expect(screen.getByText("No matches yet.")).toBeInTheDocument();
    await user.type(screen.getByPlaceholderText("Search opponent…"), "zzz");
    expect(screen.getByText("No matches for that filter.")).toBeInTheDocument();
  });

  describe("view state in the URL (#1056)", () => {
    it("opens on the page, search and band the link names", () => {
      renderPage("/players/K7Q2MX/matches?page=2&search=ben&band=4.0");
      // Reload-proof and shareable: "this player's matches vs 4.0 opponents, page 2" is the link.
      expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
        "K7Q2MX",
        { limit: 20, offset: 20, search: "ben", opponentBand: "4.0" },
        { query: { enabled: true } },
      );
      // The search box describes the results rather than sitting blank above them.
      expect(screen.getByPlaceholderText("Search opponent…")).toHaveValue("ben");
      expect(screen.getByLabelText("Filter by opponent NTRP band")).toHaveValue(
        "4.0",
      );
      expect(screen.getByText("Showing 21–40 of 45")).toBeInTheDocument();
    });

    it("writes the page by replace, un-namespaced", async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(screen.getByRole("button", { name: "Next" }));
      expect(screen.getByTestId("url")).toHaveTextContent("?page=2");
      // A page step is not its own Back step (#323).
      expect(screen.getByTestId("nav-type")).toHaveTextContent("REPLACE");
    });

    it("writes the settled search term and returns to the first page", async () => {
      const user = userEvent.setup();
      renderPage("/players/K7Q2MX/matches?page=3");
      await user.type(screen.getByPlaceholderText("Search opponent…"), "ben");
      // The page is absent from the URL because it is back at its default, which is omitted.
      expect(screen.getByTestId("url").textContent).toBe("?search=ben");
    });

    it("writes the band and returns to the first page in one step", async () => {
      const user = userEvent.setup();
      renderPage("/players/K7Q2MX/matches?page=3");
      await user.selectOptions(
        screen.getByLabelText("Filter by opponent NTRP band"),
        "4.0",
      );
      expect(screen.getByTestId("url").textContent).toBe("?band=4.0");
    });

    it("says so and shows the unfiltered first page for an unreadable link", () => {
      renderPage("/players/K7Q2MX/matches?page=nope&band=9.9");
      expect(screen.getByRole("alert")).toHaveTextContent(
        "This link had 2 unreadable view settings (page, band)",
      );
      // Visible fallback, not an empty page: page one, unfiltered, is on screen.
      expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
        "K7Q2MX",
        { limit: 20, offset: 0, search: undefined, opponentBand: undefined },
        { query: { enabled: true } },
      );
      expect(screen.getByText("Ben")).toBeInTheDocument();
    });
  });

  it("shows a privacy notice when the owner has hidden their match history (#622)", () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: {
        items: [match("a", "Ben"), match("b", "Cara")],
        total: 45,
        hidden: true,
      },
      isLoading: false,
    });
    renderPage();
    expect(
      screen.getByText("This player has hidden their match history."),
    ).toBeInTheDocument();
    // The match rows and pager are suppressed even though items are present.
    expect(screen.queryByText("Ben")).not.toBeInTheDocument();
    expect(screen.queryByText("Cara")).not.toBeInTheDocument();
    expect(screen.queryByText("Showing 1–20 of 45")).not.toBeInTheDocument();
  });
});
