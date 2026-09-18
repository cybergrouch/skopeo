// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, it, expect, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";

// Mocked rather than imported for real: the generated client chains through `api/axios.ts` into
// `lib/firebase.ts`, which initialises Firebase at module load and throws without an API key. The mock
// reproduces the real builder EXACTLY — including omitting the params element when none is given, which
// is the behaviour the partial-key prefix match depends on.
vi.mock("@/api/generated/clubs/clubs", () => ({
  getGetApiV1ClubsCodeCodeEventsQueryKey: (
    code: string,
    params?: Record<string, unknown>,
  ) => [`/api/v1/clubs/code/${code}/events`, ...(params ? [params] : [])],
}));

import { invalidateClubEvents } from "./clubEventsCache";

/** The key shape the generated client produces for one bucket page of a club's events. */
function eventsKey(code: string, bucket: string, offset = 0) {
  return [
    `/api/v1/clubs/code/${code}/events`,
    { bucket, limit: 10, offset },
  ] as const;
}

describe("invalidateClubEvents", () => {
  it("invalidates all three buckets and every page offset for one club", () => {
    const client = new QueryClient();
    const spy = vi.spyOn(client, "invalidateQueries");
    // Seed the cache as the club page would: three buckets, one of them paged past the first page.
    client.setQueryData(eventsKey("ABC123", "UPCOMING"), { items: [], total: 0 });
    client.setQueryData(eventsKey("ABC123", "UNFINALIZED"), { items: [], total: 0 });
    client.setQueryData(eventsKey("ABC123", "FINALIZED", 20), { items: [], total: 37 });

    invalidateClubEvents(client, "ABC123");

    // One call with a partial key; TanStack's prefix matching reaches every bucket and offset.
    expect(spy).toHaveBeenCalledWith({
      queryKey: ["/api/v1/clubs/code/ABC123/events"],
    });
    const matched = client
      .getQueryCache()
      .findAll({ queryKey: ["/api/v1/clubs/code/ABC123/events"] });
    expect(matched).toHaveLength(3);
  });

  it("leaves another club's lists alone when given a code", () => {
    const client = new QueryClient();
    client.setQueryData(eventsKey("ABC123", "UPCOMING"), { items: [], total: 0 });
    client.setQueryData(eventsKey("ZZZ999", "UPCOMING"), { items: [], total: 0 });

    invalidateClubEvents(client, "ABC123");

    const other = client
      .getQueryCache()
      .find({ queryKey: eventsKey("ZZZ999", "UPCOMING") });
    expect(other?.state.isInvalidated).toBe(false);
  });

  it("invalidates every club's lists when no code is available", () => {
    const client = new QueryClient();
    client.setQueryData(eventsKey("ABC123", "UPCOMING"), { items: [], total: 0 });
    client.setQueryData(eventsKey("ZZZ999", "FINALIZED"), { items: [], total: 0 });

    // The event-side mutations know `clubId`, not the public code — and re-filing an event (#319)
    // changes both the club it left and the club it joined, so both must be reached.
    invalidateClubEvents(client);

    for (const key of [
      eventsKey("ABC123", "UPCOMING"),
      eventsKey("ZZZ999", "FINALIZED"),
    ]) {
      expect(
        client.getQueryCache().find({ queryKey: key })?.state.isInvalidated,
      ).toBe(true);
    }
  });

  it("does not invalidate the club DETAIL query, which is a different concern", () => {
    const client = new QueryClient();
    // The bug this guards: the detail query renders only the page header, and invalidating it was
    // what the code used to do INSTEAD of the events query (#1055).
    const detailKey = ["/api/v1/clubs/code/ABC123"] as const;
    client.setQueryData(detailKey, { publicCode: "ABC123" });
    client.setQueryData(eventsKey("ABC123", "UPCOMING"), { items: [], total: 0 });

    invalidateClubEvents(client);

    expect(
      client.getQueryCache().find({ queryKey: detailKey })?.state.isInvalidated,
    ).toBe(false);
  });

  it("does not invalidate unrelated queries that merely start with the clubs path", () => {
    const client = new QueryClient();
    const clubsList = ["/api/v1/clubs"] as const;
    client.setQueryData(clubsList, []);
    client.setQueryData(eventsKey("ABC123", "UPCOMING"), { items: [], total: 0 });

    invalidateClubEvents(client);

    expect(
      client.getQueryCache().find({ queryKey: clubsList })?.state.isInvalidated,
    ).toBe(false);
  });
});
