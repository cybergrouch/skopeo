import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { formatElapsed } from "./useMatchClock";
import { MatchClock } from "./MatchClock";

describe("formatElapsed", () => {
  it("reads mm:ss under an hour", () => {
    expect(formatElapsed(0)).toBe("0:00");
    expect(formatElapsed(9)).toBe("0:09");
    expect(formatElapsed(600)).toBe("10:00");
    expect(formatElapsed(3599)).toBe("59:59");
  });

  it("grows to h:mm:ss past the hour, which tennis matches do", () => {
    expect(formatElapsed(3600)).toBe("1:00:00");
    expect(formatElapsed(7325)).toBe("2:02:05");
  });

  it("never shows a negative time", () => {
    // Clock skew between two Cloud Run instances should read as zero, not as a countdown.
    expect(formatElapsed(-30)).toBe("0:00");
  });
});

describe("MatchClock", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("renders nothing before the match has started", () => {
    // A 0:00 on a fixture nobody has begun would claim the clock is running when it is not.
    const { container } = render(
      <MatchClock elapsedSeconds={0} isRunning={false} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the server's figure and ticks while running", () => {
    render(<MatchClock elapsedSeconds={100} isRunning />);
    expect(screen.getByLabelText("Elapsed playing time")).toHaveTextContent("1:40");

    act(() => void vi.advanceTimersByTime(3000));
    expect(screen.getByLabelText("Elapsed playing time")).toHaveTextContent("1:43");
  });

  it("freezes while paused, however long the suspension lasts", () => {
    render(<MatchClock elapsedSeconds={300} isRunning={false} />);
    act(() => void vi.advanceTimersByTime(60_000));
    // A rain delay is not playing time.
    expect(screen.getByLabelText("Elapsed playing time")).toHaveTextContent("5:00");
  });

  it("re-syncs to the server rather than adding to its own count", () => {
    // The local tick is a display convenience; the server's figure is authoritative. Adding them would
    // make the clock run fast by however long the client had been counting.
    const { rerender } = render(<MatchClock elapsedSeconds={100} isRunning />);
    act(() => void vi.advanceTimersByTime(5000));
    expect(screen.getByLabelText("Elapsed playing time")).toHaveTextContent("1:45");

    rerender(<MatchClock elapsedSeconds={200} isRunning />);
    expect(screen.getByLabelText("Elapsed playing time")).toHaveTextContent("3:20");
  });

  it("stops ticking when the match ends", () => {
    const { rerender } = render(<MatchClock elapsedSeconds={100} isRunning />);
    rerender(<MatchClock elapsedSeconds={140} isRunning={false} />);
    act(() => void vi.advanceTimersByTime(10_000));
    // A finished match must not keep accruing minutes.
    expect(screen.getByLabelText("Elapsed playing time")).toHaveTextContent("2:20");
  });
});
