import { describe, it, expect, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { setupUser } from "@/test/user";
import { DashboardPage } from "./DashboardPage";

// Lazy-loaded sections (#1092). Its own file so the module cache starts empty: each section chunk
// below is held back until the test releases it, which is the only way to see what the page does
// while a chunk is in flight — in the main DashboardPage test a mocked module resolves at once.

/** A promise the test resolves by hand: a section chunk "arriving". */
function deferred() {
  let release = () => {};
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

const { chunks, requested } = vi.hoisted(() => ({
  chunks: {} as Record<string, { promise: Promise<void>; release: () => void }>,
  requested: new Set<string>(),
}));
chunks.standings = deferred();
chunks.settings = deferred();
chunks.about = deferred();

vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersMe: () => ({
    data: { id: "u1", capabilities: ["PLAYER"] },
    isLoading: false,
  }),
}));
vi.mock("@/api/generated/clubs/clubs", () => ({
  useGetApiV1Clubs: () => ({ data: undefined }),
}));
vi.mock("@/auth/useAuth", () => ({ useAuth: () => ({ signOut: vi.fn() }) }));
vi.mock("./dashboard/ProfileTab", () => ({
  ProfileTab: () => <div>profile content</div>,
}));
vi.mock("./dashboard/StandingsTab", async () => {
  requested.add("standings");
  await chunks.standings.promise;
  return { StandingsTab: () => <div>standings content</div> };
});
vi.mock("./dashboard/SettingsTab", async () => {
  requested.add("settings");
  await chunks.settings.promise;
  return { SettingsTab: () => <div>settings content</div> };
});
vi.mock("./dashboard/AboutTab", async () => {
  requested.add("about");
  await chunks.about.promise;
  return { AboutTab: () => <div>about content</div> };
});

function SearchProbe() {
  return <div data-testid="search">{useLocation().search}</div>;
}

function renderDashboard(initialEntries: string[] = ["/"]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <DashboardPage />
      <SearchProbe />
    </MemoryRouter>,
  );
}

describe("DashboardPage lazy sections", () => {
  it("keeps the header and menu on screen while a deep-linked section loads", async () => {
    renderDashboard(["/?tab=standings"]);

    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Standings" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open navigation menu" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();

    await act(async () => chunks.standings.release());
    expect(await screen.findByText("standings content")).toBeInTheDocument();
    expect(screen.queryByText("Loading…")).not.toBeInTheDocument();
  });

  it("keeps the current section on screen while the next one loads, rather than flashing a fallback", async () => {
    const user = setupUser();
    renderDashboard();
    expect(screen.getByText("profile content")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Open navigation menu" }));
    await user.click(screen.getByRole("button", { name: "Settings" }));

    // The chunk is still in flight: Profile stays, and no fallback replaces it.
    expect(requested.has("settings")).toBe(true);
    expect(screen.getByText("profile content")).toBeInTheDocument();
    expect(screen.queryByText("Loading…")).not.toBeInTheDocument();

    await act(async () => chunks.settings.release());
    expect(await screen.findByText("settings content")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Settings" })).toBeInTheDocument();
    expect(screen.getByTestId("search")).toHaveTextContent("?tab=settings");
  });

  it("starts loading a section when the pointer reaches its menu item, before any click", async () => {
    const user = setupUser();
    renderDashboard();
    await user.click(screen.getByRole("button", { name: "Open navigation menu" }));
    expect(requested.has("about")).toBe(false);

    await user.hover(screen.getByRole("button", { name: "About" }));

    expect(requested.has("about")).toBe(true);
    // Intent alone navigates nowhere.
    expect(screen.getByTestId("search")).toHaveTextContent("");
    expect(screen.getByText("profile content")).toBeInTheDocument();
    await act(async () => chunks.about.release());
  });
});
