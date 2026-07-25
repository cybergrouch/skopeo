import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AboutTab } from "./AboutTab";

// Build-version source stubbed so the footer renders deterministically.
vi.mock("@/lib/version", () => ({
  webVersion: vi.fn(() => "dev"),
  webCommit: vi.fn(() => ""),
}));

function renderTab() {
  return render(
    <MemoryRouter>
      <AboutTab />
    </MemoryRouter>,
  );
}

describe("AboutTab", () => {
  it("shows the same About content as the public page", () => {
    renderTab();
    expect(
      screen.getByRole("heading", { name: "Skopeo", level: 1 }),
    ).toBeInTheDocument();
    expect(screen.getByText("NTRP")).toBeInTheDocument();
    expect(screen.getByText("Rating vs Ranking")).toBeInTheDocument();
    expect(screen.getByText(/Skopeo build dev/)).toBeInTheDocument();
  });

  it("omits the sign-up / log-in call to action (#573)", () => {
    renderTab();
    // The viewer is already logged in inside the dashboard, so no auth buttons.
    expect(
      screen.queryByRole("link", { name: "Sign up" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Log in" }),
    ).not.toBeInTheDocument();
  });
});
