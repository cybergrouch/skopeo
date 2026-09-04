import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { MatchPage } from "./MatchPage";

const {
  useGetApiV1MatchesCodeCode,
  useGetApiV1MatchesCodeCodePoints,
  useGetApiV1UsersMe,
} = vi.hoisted(() => ({
  useGetApiV1MatchesCodeCode: vi.fn(),
  useGetApiV1MatchesCodeCodePoints: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
}));
// Both hooks the page reads from this module. A mock that omits one returns undefined for it, which
// fails inside the component rather than where the omission is — so every hook the page imports has to
// be listed here.
vi.mock("@/api/generated/matches/matches", () => ({
  useGetApiV1MatchesCodeCode,
  useGetApiV1MatchesCodeCodePoints,
}));
// The viewer lookup that gates the admin-only score correction (#776); anonymous by default.
vi.mock("@/api/generated/users/users", () => ({ useGetApiV1UsersMe }));
// The correction card is exercised in its own test; here we only assert the gating decision.
vi.mock("@/components/MatchScoreCorrectionCard", () => ({
  MatchScoreCorrectionCard: () => <div>Correct this score</div>,
}));
// The page renders PublicPageNav (#193), which reads auth; default to anonymous here.
vi.mock("@/auth/useAuth", () => ({ useAuth: () => ({ user: null }) }));

const match = {
  publicCode: "MTCH01",
  matchFormat: "SINGLES",
  matchType: "OPEN_PLAY",
  matchDate: "2026-02-03",
  status: "COMPLETED",
  team1: [{ displayName: "Ana", publicCode: "AAA111" }],
  team2: [{ displayName: "Bob", publicCode: "BBB222" }],
  winner: "TEAM1",
  sets: [
    { setNumber: 1, team1Games: 6, team2Games: 4 },
    { setNumber: 2, team1Games: 6, team2Games: 2 },
  ],
  venue: "Center Court",
};

function renderAt(code = "MTCH01") {
  return render(
    <MemoryRouter initialEntries={[`/matches/${code}`]}>
      <Routes>
        <Route path="/matches/:code" element={<MatchPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("MatchPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Anonymous by default: no capabilities, so no admin affordance.
    useGetApiV1UsersMe.mockReturnValue({ data: undefined });
    // No points awarded by default, so the card is absent unless a test says otherwise (#858).
    useGetApiV1MatchesCodeCodePoints.mockReturnValue({ data: undefined });
  });

  it("shows a loading state", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderAt();
    expect(screen.getByText("Loading match…")).toBeInTheDocument();
  });

  it("shows an error state when the match cannot be loaded", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: undefined,
      isError: true,
    });
    renderAt();
    expect(
      screen.getByText(/couldn’t find or load this match/i),
    ).toBeInTheDocument();
  });

  it("renders the summary: code, players linking to profiles, winner badge, and score", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: match,
      isLoading: false,
    });
    renderAt();

    expect(screen.getByText("MTCH01")).toBeInTheDocument();
    const anaLink = screen.getByRole("link", { name: "Ana" });
    expect(anaLink).toHaveAttribute("href", "/players/AAA111");
    // The player name link wears the themed content-link style (#451).
    expect(anaLink).toHaveClass("content-link");
    expect(screen.getByRole("link", { name: "Bob" })).toHaveAttribute(
      "href",
      "/players/BBB222",
    );
    // The Winner tag uses the AA-on-card `text-link` token, not the low-contrast text-primary (#491).
    const winner = screen.getByText("Winner");
    expect(winner).toBeInTheDocument(); // exactly one side won
    expect(winner).toHaveClass("text-link");
    expect(winner).not.toHaveClass("text-primary");
    expect(screen.getByText("6-4 6-2")).toBeInTheDocument();
    expect(screen.getByText(/Center Court/)).toBeInTheDocument();
    // The shareable QR card (#137) appears once the match loads.
    expect(screen.getByText("Share this match")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Copy link" }),
    ).toBeInTheDocument();
  });

  it("shows the applied per-side handicap transparently (#486)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, team2Handicap: "0.3" },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText(/Handicap applied:/i)).toBeInTheDocument();
    expect(screen.getByText(/−0\.3 to Side 2/)).toBeInTheDocument();
  });

  it("shows both per-side handicaps, comma-separated, when each side has one (#486)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, team1Handicap: "0.4", team2Handicap: "0.2" },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText(/Handicap applied:/i)).toBeInTheDocument();
    expect(screen.getByText(/−0\.4 to Side 1/)).toBeInTheDocument();
    expect(screen.getByText(/−0\.2 to Side 2/)).toBeInTheDocument();
  });

  it("shows only Side 1 when just that side is handicapped (#486)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, team1Handicap: "0.5" },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText(/Handicap applied:/i)).toBeInTheDocument();
    expect(screen.getByText(/0\.5 to Side 1/)).toBeInTheDocument();
    expect(screen.queryByText(/to Side 2/)).not.toBeInTheDocument();
  });

  it("shows no handicap notice when none is applied (#486)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: match,
      isLoading: false,
    });
    renderAt();
    expect(screen.queryByText(/Handicap applied:/i)).not.toBeInTheDocument();
  });

  it("flags a soft-deleted match but still renders it (#325)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, isActive: false },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText("MTCH01")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      /this match has been deleted/i,
    );
  });

  it("handles doubles, name/code fallbacks, and a match with no venue", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        venue: null,
        team1: [
          { displayName: "Ana", publicCode: "AAA111" },
          { displayName: null, publicCode: "CCC333" }, // name falls back to the code
        ],
        team2: [{ displayName: null, publicCode: null }], // both null → "Unknown", not a link
      },
      isLoading: false,
    });
    renderAt();

    // Multi-player side renders both, the second linking by its code.
    expect(screen.getByRole("link", { name: "Ana" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "CCC333" })).toHaveAttribute(
      "href",
      "/players/CCC333",
    );
    // A player with neither name nor code is plain text.
    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Unknown" }),
    ).not.toBeInTheDocument();
    // No venue → the date line omits it.
    expect(screen.queryByText(/Center Court/)).not.toBeInTheDocument();
  });

  it("shows NTRP band rating changes for a non-rater viewer (precise rates withheld)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        ratingChanges: [
          {
            displayName: "Ana",
            publicCode: "AAA111",
            previousLevel: "4.0",
            newLevel: "4.5",
            previousRating: null,
            newRating: null,
            ratingChange: null,
          },
          {
            displayName: "Bob",
            publicCode: "BBB222",
            previousLevel: "4.0",
            newLevel: "3.5",
            previousRating: null,
            newRating: null,
            ratingChange: null,
          },
        ],
      },
      isLoading: false,
    });
    renderAt();

    expect(screen.getByText("Rating changes")).toBeInTheDocument();
    expect(screen.getByText("4.0 → 4.5")).toBeInTheDocument();
    expect(screen.getByText("4.0 → 3.5")).toBeInTheDocument();
    // No precise rates leak to a non-rater.
    expect(screen.queryByText(/→ 4\.\d{6}/)).not.toBeInTheDocument();
  });

  it("shows precise 6-dp rates and a signed delta for a rater/admin viewer", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        ratingChanges: [
          {
            displayName: "Ana",
            publicCode: "AAA111",
            previousLevel: "4.0",
            newLevel: "4.5",
            previousRating: "4.000000",
            newRating: "4.123456",
            ratingChange: "0.123456",
          },
          {
            displayName: "Bob",
            publicCode: "BBB222",
            previousLevel: "4.0",
            newLevel: "3.5",
            previousRating: "4.000000",
            newRating: "3.876544",
            ratingChange: "-0.123456",
          },
        ],
      },
      isLoading: false,
    });
    renderAt();

    expect(screen.getByText("4.000000 → 4.123456")).toBeInTheDocument();
    expect(screen.getByText("(+0.123456)")).toBeInTheDocument(); // gain gets a + sign
    expect(screen.getByText("4.000000 → 3.876544")).toBeInTheDocument();
    expect(screen.getByText("(-0.123456)")).toBeInTheDocument(); // loss keeps its - sign
    // The NTRP-band-only form is not shown when precise rates are present.
    expect(screen.queryByText("4.0 → 4.5")).not.toBeInTheDocument();
  });

  it("shows each player's current rating confidence beside the change, in both the band and rate views (#343)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        ratingChanges: [
          // Band-only view (non-rater): confidence appended beside the band move.
          {
            displayName: "Ana",
            publicCode: "AAA111",
            previousLevel: "4.0",
            newLevel: "4.5",
            previousRating: null,
            newRating: null,
            ratingChange: null,
            confidence: "0.4",
          },
          // Precise-rate view (rater): confidence appended beside the rate move.
          {
            displayName: "Bob",
            publicCode: "BBB222",
            previousLevel: "4.0",
            newLevel: "4.0",
            previousRating: "4.000000",
            newRating: "4.010000",
            ratingChange: "0.010000",
            confidence: "1",
          },
        ],
      },
      isLoading: false,
    });
    renderAt();

    expect(
      screen.getByRole("button", { name: /rating confidence 40%/i }),
    ).toHaveTextContent("40%");
    expect(
      screen.getByRole("button", { name: /rating confidence 100%/i }),
    ).toHaveTextContent("100%");
  });

  it('falls back to code/"Unknown" names, an em-dash band, and omits an absent delta', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        ratingChanges: [
          // No display name → label falls back to the public code, still a profile link.
          {
            displayName: null,
            publicCode: "DDD444",
            previousLevel: null,
            newLevel: null,
            previousRating: null,
            newRating: null,
            ratingChange: null,
          },
          // Neither name nor code → "Unknown", plain text; precise rates present but no net change.
          {
            displayName: null,
            publicCode: null,
            previousLevel: "3.5",
            newLevel: "3.0",
            previousRating: "3.500000",
            newRating: "3.000000",
            ratingChange: null,
          },
        ],
      },
      isLoading: false,
    });
    renderAt();

    const codeLink = screen.getByRole("link", { name: "DDD444" });
    expect(codeLink).toHaveAttribute("href", "/players/DDD444");
    // The rating-change name link wears the themed content-link style (#451).
    expect(codeLink).toHaveClass("content-link");
    expect(screen.getByText("— → —")).toBeInTheDocument(); // both band levels missing
    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Unknown" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("3.500000 → 3.000000")).toBeInTheDocument();
    // ratingChange null → no parenthesised delta is rendered.
    expect(screen.queryByText(/\(/)).not.toBeInTheDocument();
  });

  it("omits the rating-changes block entirely for an unrated match", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, ratingChanges: null },
      isLoading: false,
    });
    renderAt();
    expect(screen.queryByText("Rating changes")).not.toBeInTheDocument();
  });

  it('shows "Not yet played" and no winner badge before a result, and a player without a code is not a link', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        status: "SCHEDULED",
        winner: "NONE",
        sets: [],
        team2: [{ displayName: "Guest", publicCode: null }],
      },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText("Not yet played")).toBeInTheDocument();
    expect(screen.queryByText("Winner")).not.toBeInTheDocument();
    expect(screen.getByText("Guest")).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Guest" }),
    ).not.toBeInTheDocument();
  });

  it("renders the head-to-head tally and prior meetings, each linking to its match page (#188)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        headToHead: {
          team1Wins: 1,
          team2Wins: 1,
          meetings: [
            {
              publicCode: "PREV02",
              matchDate: "2026-02-01",
              status: "COMPLETED",
              rated: true,
              matchFormat: "DOUBLES",
              sets: [{ setNumber: 1, team1Games: 4, team2Games: 6 }],
              winnerPublicCode: "BBB222", // Bob (team2) won
            },
            {
              publicCode: "PREV01",
              matchDate: "2026-01-01",
              status: "COMPLETED",
              rated: false,
              matchFormat: "SINGLES",
              sets: [{ setNumber: 1, team1Games: 6, team2Games: 3 }],
              winnerPublicCode: "AAA111", // Ana (team1) won
            },
          ],
        },
      },
      isLoading: false,
    });
    renderAt();

    expect(screen.getByText("Head-to-head")).toBeInTheDocument();
    expect(screen.getByText("1 – 1")).toBeInTheDocument();
    // Each meeting shows format · date · score · winner and links to its own public page (#285).
    expect(
      screen.getByText(/doubles · 2026-02-01 · 4-6 · Bob won/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/singles · 2026-01-01 · 6-3 · Ana won/),
    ).toBeInTheDocument();
    const links = screen.getAllByRole("link", { name: "Public page (QR)" });
    expect(links.map((l) => l.getAttribute("href"))).toEqual([
      "/matches/PREV02",
      "/matches/PREV01",
    ]);
  });

  it("hides the head-to-head section when the backend omits it (e.g. non-singles) (#366)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, headToHead: null },
      isLoading: false,
    });
    renderAt();
    expect(screen.queryByText("Head-to-head")).not.toBeInTheDocument();
  });

  it('shows head-to-head with the tally and a "No prior meetings" note for a first meeting (#366)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        headToHead: { team1Wins: 1, team2Wins: 0, meetings: [] },
      },
      isLoading: false,
    });
    renderAt();
    expect(screen.getByText("Head-to-head")).toBeInTheDocument();
    expect(screen.getByText("1 – 0")).toBeInTheDocument();
    expect(screen.getByText("No prior meetings.")).toBeInTheDocument();
  });

  it("head-to-head copes with missing names, scores, and undecided/unknown winners (#188)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        team1: [{ publicCode: "AAA111" }], // no displayName → falls back to the code
        team2: [{}], // no name or code → "Unknown"
        headToHead: {
          team1Wins: 0,
          team2Wins: 0,
          meetings: [
            // Undecided + no sets: no score, no "won" suffix.
            {
              publicCode: "PREVX",
              matchDate: "2026-02-01",
              status: "COMPLETED",
              rated: false,
              matchFormat: "SINGLES",
              sets: [],
              winnerPublicCode: null,
            },
            // Winner code matching neither player → no resolvable name.
            {
              publicCode: "PREVY",
              matchDate: "2026-01-01",
              status: "COMPLETED",
              rated: true,
              matchFormat: "SINGLES",
              sets: [{ setNumber: 1, team1Games: 6, team2Games: 0 }],
              winnerPublicCode: "ZZZ999",
            },
          ],
        },
      },
      isLoading: false,
    });
    renderAt();

    expect(screen.getByText("Head-to-head")).toBeInTheDocument();
    expect(
      screen.getByText(/Prior meetings between AAA111 and Unknown/),
    ).toBeInTheDocument();
    // Undecided meeting: format · date, no " · ... won".
    expect(screen.getByText("singles · 2026-02-01")).toBeInTheDocument();
    // Unknown winner code resolves to no name → score shown but no "won" suffix.
    expect(screen.getByText("singles · 2026-01-01 · 6-0")).toBeInTheDocument();
    expect(screen.queryByText(/won/)).not.toBeInTheDocument();
  });

  it("links to the owning event when the match belongs to one (#358)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, event: { publicCode: "EVT001", name: "Spring Open" } },
      isLoading: false,
    });
    renderAt();

    const link = screen.getByRole("link", { name: "Spring Open" });
    expect(link).toHaveAttribute("href", "/events/EVT001");
    // The event link wears the themed content-link style, not text-primary (#491).
    expect(link).toHaveClass("content-link");
    expect(link).not.toHaveClass("text-primary");
    expect(screen.getByText(/Part of event:/)).toBeInTheDocument();
  });

  it("omits the event link for an eventless (open-play) match (#358)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, event: null },
      isLoading: false,
    });
    renderAt();

    expect(screen.queryByText(/Part of event:/)).not.toBeInTheDocument();
  });

  describe("score correction (#776)", () => {
    const ratedMatch = { ...match, id: "m-1", rated: true };

    function showMatch(data: unknown) {
      useGetApiV1MatchesCodeCode.mockReturnValue({ data, isLoading: false });
    }

    function asAdmin() {
      useGetApiV1UsersMe.mockReturnValue({
        data: { capabilities: ["PLAYER", "ADMINISTRATOR"] },
      });
    }

    it("offers the correction to an administrator on a rated match", () => {
      asAdmin();
      showMatch(ratedMatch);
      renderAt();

      expect(screen.getByText("Correct this score")).toBeInTheDocument();
    });

    it("hides the correction from an anonymous visitor, leaving the page intact", () => {
      // The default `me` mock is anonymous — the page must still render normally.
      showMatch(ratedMatch);
      renderAt();

      expect(screen.queryByText("Correct this score")).not.toBeInTheDocument();
      expect(screen.getByText("MTCH01")).toBeInTheDocument();
    });

    it("hides the correction from a signed-in non-administrator", () => {
      useGetApiV1UsersMe.mockReturnValue({
        data: { capabilities: ["PLAYER", "HOST"] },
      });
      showMatch(ratedMatch);
      renderAt();

      expect(screen.queryByText("Correct this score")).not.toBeInTheDocument();
    });

    it("hides the correction on an unrated match — that is the normal edit path", () => {
      asAdmin();
      showMatch({ ...ratedMatch, rated: false });
      renderAt();

      expect(screen.queryByText("Correct this score")).not.toBeInTheDocument();
    });

    it("hides the correction on a deleted match", () => {
      asAdmin();
      showMatch({ ...ratedMatch, isActive: false });
      renderAt();

      expect(screen.queryByText("Correct this score")).not.toBeInTheDocument();
    });

    it("badges a corrected match as Re-rated for everyone, not just admins", () => {
      // Anonymous viewer: the badge is a public transparency signal.
      showMatch({ ...ratedMatch, reRated: true });
      renderAt();

      expect(screen.getByText("Re-rated")).toBeInTheDocument();
    });

    it("shows no Re-rated badge on a match that was never corrected", () => {
      showMatch(ratedMatch);
      renderAt();

      expect(screen.queryByText("Re-rated")).not.toBeInTheDocument();
    });
  });

  it("shows what the match awarded, with the point class named (#858)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({ data: match, isLoading: false });
    useGetApiV1MatchesCodeCodePoints.mockReturnValue({
      data: {
        rows: [
          {
            userId: "u1",
            displayName: "Ana",
            publicCode: "AAA111",
            points: "8.0000",
            awardId: "a1",
            pointClass: "OPEN_PLAY",
          },
          { userId: "u2", displayName: "Bob", points: "1.0000", awardId: "a2", pointClass: "OPEN_PLAY" },
        ],
        totalPoints: "9.0000",
      },
      isLoading: false,
    });
    renderAt();

    expect(screen.getByText("Ranking points awarded")).toBeInTheDocument();
    expect(screen.getByText("+8")).toBeInTheDocument();
    expect(screen.getByText("+1")).toBeInTheDocument();
    // The class is what stops a placement amount reading as a per-set one (#836/#837).
    expect(screen.getAllByText("per set")).toHaveLength(2);
  });

  it("renders no points card at all when the match awarded nothing (#858)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({ data: match, isLoading: false });
    useGetApiV1MatchesCodeCodePoints.mockReturnValue({ data: { rows: [], totalPoints: "0" }, isLoading: false });
    renderAt();

    // An unfinalized event, awarding switched off (#831), or a suppressed viewer (#865) all land here.
    // An empty card would read as a fault; absence reads as absence.
    expect(screen.queryByText("Ranking points awarded")).not.toBeInTheDocument();
  });

  it("shows the derivation only when the server sent one (#858)", () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({ data: match, isLoading: false });
    useGetApiV1MatchesCodeCodePoints.mockReturnValue({
      data: {
        rows: [
          {
            userId: "u1",
            displayName: "Ana",
            points: "8.0000",
            awardId: "a1",
            pointClass: "OPEN_PLAY",
            derivation: {
              awardId: "a1",
              points: "8.0000",
              pointClass: "OPEN_PLAY",
              scheduleVersion: 1,
              recorded: true,
              teamBand: "4.0",
              opponentBand: "4.0",
              sets: [
                {
                  setNumber: 1,
                  score: "6-4",
                  margin: 2,
                  relation: "EQUAL",
                  wonSet: true,
                  winnerPoints: 8,
                  loserPoints: 0,
                  pointsForThisPlayer: 8,
                },
              ],
            },
          },
          { userId: "u2", displayName: "Bob", points: "1.0000", awardId: "a2", pointClass: "OPEN_PLAY" },
        ],
        totalPoints: "9.0000",
      },
      isLoading: false,
    });
    renderAt();

    // Ana's row explains itself; Bob's does not, because the server omitted the field. The component
    // makes no access decision of its own — there is no client-side capability check here to drift from
    // the server's (#583/#654).
    expect(screen.getByText(/Set 1: 6-4/)).toBeInTheDocument();
    expect(screen.getByText(/margin 2/)).toBeInTheDocument();
    expect(screen.getAllByText(/Set 1/)).toHaveLength(1);
  });
});
