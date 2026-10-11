import { describe, it, expect } from "vitest";
import {
  OLD_FORMAT_REPLAY,
  RETIRED_REPLAY,
  replayOf,
} from "@/test/replayFixtures";
import { hasReplayFeature, type ReplayFeature } from "./replayFeatures";

describe("hasReplayFeature", () => {
  const features: ReplayFeature[] = ["serve", "breaks", "breakPoints", "timing"];

  it.each(features)("offers %s when the replay carries its field", (feature) => {
    expect(hasReplayFeature(RETIRED_REPLAY, feature)).toBe(true);
  });

  it("withholds what an older replay lacks the data for, and keeps the rest", () => {
    expect(hasReplayFeature(OLD_FORMAT_REPLAY, "serve")).toBe(false);
    expect(hasReplayFeature(OLD_FORMAT_REPLAY, "breaks")).toBe(false);
    expect(hasReplayFeature(OLD_FORMAT_REPLAY, "breakPoints")).toBe(false);
    expect(hasReplayFeature(OLD_FORMAT_REPLAY, "timing")).toBe(true);
  });

  it("treats a null field as absent", () => {
    const replay = {
      ...RETIRED_REPLAY,
      points: RETIRED_REPLAY.points.map((point) => ({
        ...point,
        server: null as unknown as undefined,
      })),
    };
    expect(hasReplayFeature(replay, "serve")).toBe(false);
  });

  it.each(features)("offers nothing for a replay with no steps (%s)", (feature) => {
    expect(hasReplayFeature(replayOf([]), feature)).toBe(false);
  });
});
