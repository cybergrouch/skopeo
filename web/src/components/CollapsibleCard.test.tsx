import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CollapsibleCard } from "./CollapsibleCard";

describe("CollapsibleCard", () => {
  it("renders a plain card with no toggle when not collapsible", () => {
    render(
      <CollapsibleCard title="Plain" description="desc">
        <p>body content</p>
      </CollapsibleCard>,
    );
    expect(screen.getByText("Plain")).toBeInTheDocument();
    expect(screen.getByText("desc")).toBeInTheDocument();
    expect(screen.getByText("body content")).toBeInTheDocument();
    // No expand/collapse affordance in the non-collapsible mode.
    expect(
      screen.queryByRole("button", { name: /show all|show less/i }),
    ).not.toBeInTheDocument();
  });

  it("starts collapsed with a 'Show all' toggle and the body still in the DOM", () => {
    render(
      <CollapsibleCard title="History" collapsible={true}>
        <p>row one</p>
      </CollapsibleCard>,
    );
    const toggle = screen.getByRole("button", { name: "Show all" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    // The clip is CSS-only, so all rows are rendered even while collapsed.
    expect(screen.getByText("row one")).toBeInTheDocument();
  });

  it("expands and collapses on toggle", async () => {
    const user = userEvent.setup();
    render(
      <CollapsibleCard title="History" collapsible={true}>
        <p>row one</p>
      </CollapsibleCard>,
    );
    await user.click(screen.getByRole("button", { name: "Show all" }));
    const expanded = screen.getByRole("button", { name: "Show less" });
    expect(expanded).toHaveAttribute("aria-expanded", "true");

    await user.click(expanded);
    expect(
      screen.getByRole("button", { name: "Show all" }),
    ).toHaveAttribute("aria-expanded", "false");
  });

  it("wires the toggle to the body via aria-controls", () => {
    render(
      <CollapsibleCard title="History" collapsible={true}>
        <p>row one</p>
      </CollapsibleCard>,
    );
    const toggle = screen.getByRole("button", { name: "Show all" });
    const controls = toggle.getAttribute("aria-controls");
    expect(controls).toBeTruthy();
    expect(document.getElementById(controls as string)).toHaveTextContent(
      "row one",
    );
  });
});
