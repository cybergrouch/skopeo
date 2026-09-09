import { describe, it, expect, beforeEach, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useLiveScore } from "./useLiveScore";

const { onSnapshot, doc, getFirestore } = vi.hoisted(() => ({
  onSnapshot: vi.fn(),
  doc: vi.fn(),
  getFirestore: vi.fn(),
}));
vi.mock("firebase/firestore", () => ({ onSnapshot, doc, getFirestore }));
// Never initialize the real SDK: CI has no Firebase config, and this hook's job is the subscription.
vi.mock("@/lib/firebase", () => ({ firebaseApp: {} }));

type SnapshotHandler = (snapshot: {
  exists: () => boolean;
  data: () => unknown;
}) => void;

const score = {
  publicCode: "MTCH01",
  sequence: 4,
  pointsTeam1: "40",
  pointsTeam2: "15",
  gamesTeam1: 2,
  gamesTeam2: 1,
  sets: [],
  isTiebreak: false,
  isPaused: false,
  hasStarted: true,
  serverId: null,
  outcomeKind: null,
  outcomeWinner: null,
};

describe("useLiveScore", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getFirestore.mockReturnValue({});
    doc.mockReturnValue({ path: "liveScores/MTCH01" });
  });

  it("does not subscribe without a code", () => {
    onSnapshot.mockReturnValue(vi.fn());
    const { result } = renderHook(() => useLiveScore(undefined));
    expect(result.current).toBeNull();
    expect(onSnapshot).not.toHaveBeenCalled();
  });

  it("subscribes by PUBLIC CODE, not by internal id", () => {
    onSnapshot.mockReturnValue(vi.fn());
    renderHook(() => useLiveScore("MTCH01"));
    // The document is world-readable, so it is keyed by the identifier a spectator actually has.
    expect(doc).toHaveBeenCalledWith(expect.anything(), "liveScores", "MTCH01");
  });

  it("returns the pushed score", () => {
    let handler: SnapshotHandler = () => {};
    onSnapshot.mockImplementation((_ref, next: SnapshotHandler) => {
      handler = next;
      return vi.fn();
    });

    const { result } = renderHook(() => useLiveScore("MTCH01"));
    act(() => handler({ exists: () => true, data: () => score }));
    expect(result.current?.pointsTeam1).toBe("40");
  });

  it("returns null when no live document exists", () => {
    let handler: SnapshotHandler = () => {};
    onSnapshot.mockImplementation((_ref, next: SnapshotHandler) => {
      handler = next;
      return vi.fn();
    });

    const { result } = renderHook(() => useLiveScore("MTCH01"));
    act(() => handler({ exists: () => false, data: () => undefined }));
    // The normal state for almost every match — the caller renders nothing.
    expect(result.current).toBeNull();
  });

  it("returns null when the subscription errors, rather than breaking the page", () => {
    let onError: () => void = () => {};
    onSnapshot.mockImplementation(
      (_ref, _next: SnapshotHandler, error: () => void) => {
        onError = error;
        return vi.fn();
      },
    );

    const { result } = renderHook(() => useLiveScore("MTCH01"));
    act(() => onError());
    expect(result.current).toBeNull();
  });

  it("survives Firestore being unavailable entirely", () => {
    // Optional infrastructure: a deployment without it still serves the public match page.
    onSnapshot.mockImplementation(() => {
      throw new Error("no firestore");
    });
    const { result } = renderHook(() => useLiveScore("MTCH01"));
    expect(result.current).toBeNull();
  });

  it("never shows one match's score under another match's code", () => {
    let handler: SnapshotHandler = () => {};
    onSnapshot.mockImplementation((_ref, next: SnapshotHandler) => {
      handler = next;
      return vi.fn();
    });

    const { result, rerender } = renderHook(
      ({ code }) => useLiveScore(code),
      { initialProps: { code: "MTCH01" } },
    );
    act(() => handler({ exists: () => true, data: () => score }));
    expect(result.current?.pointsTeam1).toBe("40");

    // Switching match must not leave the previous score on screen under the new name, even for the
    // moment before the new snapshot arrives.
    rerender({ code: "MTCH02" });
    expect(result.current).toBeNull();
  });

  it("unsubscribes on unmount", () => {
    const unsubscribe = vi.fn();
    onSnapshot.mockReturnValue(unsubscribe);
    const { unmount } = renderHook(() => useLiveScore("MTCH01"));
    unmount();
    expect(unsubscribe).toHaveBeenCalled();
  });
});
