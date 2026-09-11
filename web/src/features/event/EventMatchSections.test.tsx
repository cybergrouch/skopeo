import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { EventMatchSections } from "./EventMatchSections";
import type { MatchPublicResponse } from "@/api/generated/model";

function match(overrides: Partial<MatchPublicResponse>): MatchPublicResponse {
  return {
    publicCode: "MTCH01",
    matchFormat: "SINGLES",
    matchType: "OPEN_PLAY",
    matchDate: "2026-04-01",
    matchNumber: 1,
    status: "SCHEDULED",
    completionReason: "COMPLETED",
    rated: false,
    team1: [{ displayName: "Ana" }],
    team2: [{ displayName: "Bob" }],
    sets: [],
    ...overrides,
  } as MatchPublicResponse;
}

function renderSections(matches: MatchPublicResponse[]) {
  return render(
    <MemoryRouter>
      <EventMatchSections matches={matches} />
    </MemoryRouter>,
  );
}

/** The heading's sibling list, so a match is asserted to be in the RIGHT section rather than merely present. */
function section(title: string) {
  const heading = screen.getByText(title);
  return within(heading.parentElement as HTMLElement);
}

describe("EventMatchSections", () => {
  it("lists a match being scored, in its own section (#945)", () => {
    // The regression: a claimed match dropped out of "awaiting" (status changed) and was not in
    // "recorded" (no sets yet), so it vanished from the event entirely.
    renderSections([
      match({ publicCode: "LIVE01", status: "IN_PROGRESS" }),
      match({ publicCode: "SCHED1", status: "SCHEDULED" }),
    ]);

    expect(screen.getByText("In progress")).toBeInTheDocument();
    expect(section("In progress").getByText(/Ana/)).toBeInTheDocument();
  });

  it("does not describe a live match as Scheduled (#945)", () => {
    renderSections([match({ status: "IN_PROGRESS" })]);
    expect(screen.getByText("Live")).toBeInTheDocument();
    // The badge fell through to "Scheduled" before this, which actively misinformed.
    expect(screen.queryByText("Scheduled")).not.toBeInTheDocument();
  });

  it("keeps a live match out of the awaiting bucket, so it is listed once", () => {
    renderSections([match({ publicCode: "LIVE01", status: "IN_PROGRESS" })]);
    expect(
      section("Awaiting results").getByText("No fixtures awaiting results."),
    ).toBeInTheDocument();
  });

  it("hides the In progress section when nothing is being played", () => {
    // The normal state for almost all of an event's life; an always-present empty section would spend
    // the space on nothing.
    renderSections([match({ status: "SCHEDULED" })]);
    expect(screen.queryByText("In progress")).not.toBeInTheDocument();
  });

  it("still splits the rest by whether a result was recorded", () => {
    renderSections([
      match({ publicCode: "SCHED1", status: "SCHEDULED" }),
      match({
        publicCode: "DONE01",
        status: "COMPLETED",
        sets: [{ setNumber: 1, team1Games: 6, team2Games: 4 }],
      }),
    ]);

    expect(section("Awaiting results").getByText(/Ana/)).toBeInTheDocument();
    expect(section("Recorded results").getByText(/6-4/)).toBeInTheDocument();
  });

  it("badges a recorded but unrated match as awaiting rating, and a rated one as rated", () => {
    renderSections([
      match({
        publicCode: "DONE01",
        status: "COMPLETED",
        sets: [{ setNumber: 1, team1Games: 6, team2Games: 4 }],
      }),
      match({
        publicCode: "RATED1",
        status: "COMPLETED",
        rated: true,
        sets: [{ setNumber: 1, team1Games: 6, team2Games: 2 }],
      }),
    ]);

    expect(screen.getByText("Awaiting rating")).toBeInTheDocument();
    expect(screen.getByText("Rated")).toBeInTheDocument();
  });
  it('files a finished match with no sets under Recorded, not Awaiting (#969)', () => {
    // A walkover never has a set, so the sets-based split called it "awaiting" forever. The file's own
    // comment already warned about this shape for #945 — and then split on sets.length anyway.
    render(
      <MemoryRouter>
        <EventMatchSections
          matches={[match({ status: 'COMPLETED', completionReason: 'DEFAULTED', sets: [] })]}
        />
      </MemoryRouter>,
    )

    expect(screen.getByText('No fixtures awaiting results.')).toBeInTheDocument()
    expect(screen.queryByText('No recorded results yet.')).not.toBeInTheDocument()
  })

});
