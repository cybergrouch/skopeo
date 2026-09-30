import { describe, expect, it } from "vitest";
import { Capability } from "@/auth/capabilities";
import {
  clicksTo,
  isVisible,
  routes,
  sections,
  unresolvedLinks,
  type Breakpoint,
  type ClickQuery,
  type ClickTarget,
} from "./navGraph";

// Click budgets (#1094): how many clicks from the post-login landing (the dashboard's Profile tab) a
// flow takes, measured from source so a change that buries a flow one level deeper fails here instead
// of passing review silently. What the model can and cannot see is written up at the top of
// navGraph.ts — read it before trusting a number: it measures reachability, not behaviour, and stops
// at the page a flow happens on.
//
// The budgets are exact, not ceilings. A flow getting LONGER fails as a regression; a flow getting
// SHORTER fails too, asking for the budget to be lowered, so the numbers here stay what a user
// actually pays rather than drifting into slack.

const PLAYER = [Capability.PLAYER];
const SCORER = [Capability.PLAYER, Capability.SCORER];
// Assumed to own at least one club, so its "My clubs" menu group (#1096) is not empty.
const CLUB_OWNER = [Capability.PLAYER, Capability.CLUB_OWNER];
const ADMINISTRATOR = [Capability.PLAYER, Capability.ADMINISTRATOR];

const BREAKPOINTS: Breakpoint[] = ["mobile", "desktop"];

// The cards listing the viewer's OWN matches, events and awards. They link to events and matches only
// if the viewer has played in some, so a flow about work the viewer does for others (a host running
// an event they never played in) must not count them as a route.
const OWN_HISTORY = [
  "/src/components/UpcomingMatchesCard.tsx",
  "/src/components/EventsHistoryCard.tsx",
  "/src/components/MatchHistoryCard.tsx",
  "/src/components/PointsAuditCard.tsx",
];

// The menu, whose only links are the "My clubs" shortcuts — which an administrator never has.
const MY_CLUBS = "/src/routes/dashboard/SectionNav.tsx";

interface Budget {
  flow: string;
  as: readonly Capability[];
  to: ClickTarget;
  excluding?: readonly string[];
  clicks: Record<Breakpoint, number>;
}

const BUDGETS: Budget[] = [
  // Profile → a match in Upcoming matches → "Score this match".
  {
    flow: "Score a live match",
    as: SCORER,
    to: "/matches/:code/score",
    clicks: { mobile: 2, desktop: 2 },
  },
  // (Menu →) My clubs → the club (#1096). The New Event form is on the club page (#794), in-page.
  {
    flow: "Create an event",
    as: CLUB_OWNER,
    to: "/clubs/:code",
    clicks: { mobile: 2, desktop: 1 },
  },
  // (Menu →) My clubs → the club → the event, where EventManagerView renders in place.
  {
    flow: "Manage an event",
    as: CLUB_OWNER,
    to: "/events/:code",
    excluding: OWN_HISTORY,
    clicks: { mobile: 3, desktop: 2 },
  },
  // An administrator owns no club, so has no "My clubs" entries (#1096, by decision) and reaches a
  // club the way everyone did before: (Menu →) Club Management → the club.
  {
    flow: "Reach a club as an administrator",
    as: ADMINISTRATOR,
    to: "/clubs/:code",
    excluding: [MY_CLUBS],
    clicks: { mobile: 3, desktop: 2 },
  },
];

// Menu → the section; from `md:` up the rail is always open, so just the section (#1095). Every section
// a persona has costs the same, so one number covers them all.
const SECTION_CLICKS: Record<Breakpoint, number> = { mobile: 2, desktop: 1 };

/** Asserts the flow's click count, naming its route in the failure message. */
function expectClicks(flow: string, target: ClickTarget, query: ClickQuery, budget: number) {
  const { clicks, path } = clicksTo(target, query);
  const route = path.join(" → ");
  expect(
    clicks,
    clicks > budget
      ? `${flow} now takes ${clicks} clicks, over its budget of ${budget}: ${route}`
      : `${flow} now takes ${clicks} clicks, under its budget of ${budget} — lower the budget: ${route}`,
  ).toBe(budget);
}

describe("the navigation graph is read from source", () => {
  it("finds the page routes in App.tsx", () => {
    expect(routes().map((route) => route.path)).toEqual(
      expect.arrayContaining([
        "/dashboard",
        "/players/:code",
        "/matches/:code",
        "/matches/:code/score",
        "/events/:code",
        "/clubs/:code",
      ]),
    );
  });

  // These check the gate evaluation against what the product promises, not against the extractor.
  it("shows a plain PLAYER only Profile, Settings, Standings and About", () => {
    const visible = sections().filter((section) => isVisible(section, PLAYER));
    expect(visible.map((section) => section.value)).toEqual([
      "profile",
      "settings",
      "standings",
      "about",
    ]);
  });

  it("shows an ADMINISTRATOR every section", () => {
    const all = sections();
    expect(all.length).toBeGreaterThan(4);
    expect(all.filter((section) => isVisible(section, ADMINISTRATOR))).toEqual(all);
  });

  // A link written in a form the extractor cannot resolve builds no edge, silently. This list makes
  // that visible: a new entry means either the link is broken or navGraph needs to learn its form.
  it("resolves every link target except the known computed ones", () => {
    const file = "/src/routes/dashboard/admin/PointsManagementSection.tsx";
    expect(unresolvedLinks()).toEqual([
      // AwardSourceCell takes a route prefix as a prop and builds `${to}/${code}` itself; the events and
      // matches it links to are reachable by other routes.
      { file, target: "/events" },
      { file, target: "/matches" },
      { file, target: ":param/:param" },
    ]);
  });
});

describe.each(BREAKPOINTS)("click budgets at %s", (breakpoint) => {
  it.each([
    ["PLAYER", PLAYER],
    ["ADMINISTRATOR", ADMINISTRATOR],
  ] as const)("reaches every dashboard section as %s", (_, as) => {
    for (const section of sections().filter((s) => isVisible(s, as))) {
      if (section.value === "profile") continue; // the landing itself
      expectClicks(
        `The ${section.label} section`,
        { tab: section.value },
        { as, breakpoint },
        SECTION_CLICKS[breakpoint],
      );
    }
  });

  it.each(BUDGETS)("$flow", ({ flow, as, to, excluding, clicks }) => {
    expectClicks(flow, to, { as, breakpoint, excluding }, clicks[breakpoint]);
  });

  it("fails loudly when a flow is unreachable, rather than reporting Infinity", () => {
    // A plain PLAYER has no Club Management tab and no other way to a club page.
    expect(() => clicksTo("/clubs/:code", { as: PLAYER, breakpoint })).toThrow(/unreachable/);
    expect(() => clicksTo({ tab: "admin" }, { as: PLAYER, breakpoint })).toThrow(
      /not a page or a section this persona has/,
    );
  });
});
