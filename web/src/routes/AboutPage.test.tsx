import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AboutPage } from "./AboutPage";
import { webCommit } from "@/lib/version";

// Logged-out visitor — PublicPageNav reads useAuth.
vi.mock("@/auth/useAuth", () => ({ useAuth: () => ({ user: null }) }));

// Build-version source stubbed so the footer's commit branch is exercisable.
vi.mock("@/lib/version", () => ({
  webVersion: vi.fn(() => "dev"),
  webCommit: vi.fn(() => ""),
}));

function renderAbout() {
  return render(
    <MemoryRouter>
      <AboutPage />
    </MemoryRouter>,
  );
}

describe("AboutPage", () => {
  it("renders for a logged-out visitor with the Skopeo overview", () => {
    renderAbout();
    expect(screen.getByRole("heading", { name: "Skopeo", level: 1 })).toBeInTheDocument();
    // Logged-out CTA from PublicPageNav is present (no auth redirect).
    expect(screen.getByText(/Sign up to track your own ratings/i)).toBeInTheDocument();
  });

  it("has the NTRP and Rating-vs-Ranking sections", () => {
    renderAbout();
    expect(screen.getByText("NTRP")).toBeInTheDocument();
    expect(screen.getByText(/National Tennis Rating Program/)).toBeInTheDocument();
    expect(screen.getByText("Rating vs Ranking")).toBeInTheDocument();
    expect(screen.getByText(/how good you are/i)).toBeInTheDocument();
    expect(screen.getByText(/how you.re doing in the race/i)).toBeInTheDocument();
  });

  it("shows the build version", () => {
    renderAbout();
    // webVersion() → "dev" and webCommit() → "" (stubbed), so no commit suffix.
    expect(screen.getByText(/Skopeo build dev/)).toBeInTheDocument();
  });

  it("appends the short commit sha when present", () => {
    vi.mocked(webCommit).mockReturnValue("abcdef1234567");
    renderAbout();
    expect(screen.getByText(/Skopeo build dev · abcdef1/)).toBeInTheDocument();
  });

  it("links to sign-up and log-in", () => {
    renderAbout();
    const signup = screen.getAllByRole("link", { name: "Sign up" });
    expect(signup.some((a) => a.getAttribute("href") === "/signup")).toBe(true);
    const login = screen.getAllByRole("link", { name: "Log in" });
    expect(login.some((a) => a.getAttribute("href") === "/login")).toBe(true);
  });
});
