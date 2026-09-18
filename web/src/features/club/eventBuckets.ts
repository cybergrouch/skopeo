import type { GetApiV1ClubsCodeCodeEventsBucket } from "@/api/generated/model";

/**
 * A club's three event groupings, in the order they appear down the club page (#483/#786) — one
 * separately-paginated {@link ClubEventsCard} each.
 *
 * Data rather than JSX so the page and its tests compose the same three cards: the trio is exactly
 * what forces per-instance namespacing on URL-backed page state (#1056), and a test that hand-rolled
 * its own list could drift from the page it is meant to be checking.
 */
export const BUCKETS: ReadonlyArray<{
  bucket: GetApiV1ClubsCodeCodeEventsBucket;
  title: string;
  emptyLabel: string;
}> = [
  {
    bucket: "UPCOMING",
    title: "Upcoming events",
    emptyLabel: "No upcoming events.",
  },
  {
    bucket: "UNFINALIZED",
    title: "Unfinalized events",
    emptyLabel: "No unfinalized events.",
  },
  {
    bucket: "FINALIZED",
    title: "Finalized events",
    emptyLabel: "No finalized events.",
  },
];
