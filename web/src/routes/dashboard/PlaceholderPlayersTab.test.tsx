import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { PlaceholderPlayersTab } from "./PlaceholderPlayersTab";

// The two sub-sections have their own API wiring + tests; stub them so this test just checks that the
// tab composes both, passing the capabilities through.
vi.mock("./CreatePlaceholderSection", () => ({
  CreatePlaceholderSection: ({
    capabilities,
  }: {
    capabilities: readonly string[] | undefined;
  }) => <div>create:{(capabilities ?? []).join(",")}</div>,
}));
vi.mock("./PlaceholderPlayersSection", () => ({
  PlaceholderPlayersSection: ({
    capabilities,
  }: {
    capabilities: readonly string[] | undefined;
  }) => <div>list:{(capabilities ?? []).join(",")}</div>,
}));

describe("PlaceholderPlayersTab", () => {
  it("renders the create + manage sections, passing capabilities through", () => {
    render(<PlaceholderPlayersTab capabilities={["HOST"] as never} />);
    expect(screen.getByText("create:HOST")).toBeInTheDocument();
    expect(screen.getByText("list:HOST")).toBeInTheDocument();
  });

  it("tolerates undefined capabilities", () => {
    render(<PlaceholderPlayersTab capabilities={undefined} />);
    expect(screen.getByText("create:")).toBeInTheDocument();
    expect(screen.getByText("list:")).toBeInTheDocument();
  });
});
