import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { PageContainer } from "./PageContainer";
import { PublicPageShell } from "./PublicPageShell";

// The nav inside the shell reads auth (#193); these cases are about layout, not the nav.
vi.mock("@/auth/useAuth", () => ({ useAuth: () => ({ user: { uid: "u1" } }) }));

// Width is a policy, not a per-page decision (#742) — these lock the policy in one place so a future
// page can't quietly reintroduce its own cap.
describe("PageContainer", () => {
  it("is fluid, centred and gutter-padded at every size", () => {
    render(<PageContainer>content</PageContainer>);
    const el = screen.getByText("content");
    expect(el.className).toContain("mx-auto");
    expect(el.className).toContain("w-full");
    expect(el.className).toContain("px-4");
  });

  it("caps data pages well beyond the old 384px column", () => {
    render(<PageContainer>wide</PageContainer>);
    expect(screen.getByText("wide").className).toContain("max-w-[1400px]");
  });

  it("keeps a reading measure for prose and a narrow card for forms", () => {
    render(
      <>
        <PageContainer size="prose">prose</PageContainer>
        <PageContainer size="form">form</PageContainer>
      </>,
    );
    expect(screen.getByText("prose").className).toContain("max-w-3xl");
    expect(screen.getByText("form").className).toContain("max-w-sm");
  });

  it("merges a caller's own classes without dropping the width", () => {
    render(<PageContainer className="space-y-6">merged</PageContainer>);
    const el = screen.getByText("merged");
    expect(el.className).toContain("space-y-6");
    expect(el.className).toContain("max-w-[1400px]");
  });
});

describe("PublicPageShell", () => {
  const shell = (columns?: boolean) =>
    render(
      <MemoryRouter>
        <PublicPageShell columns={columns}>
          <div>card</div>
        </PublicPageShell>
      </MemoryRouter>,
    );

  it("lays cards out in two columns on a wide viewport by default", () => {
    shell();
    expect(screen.getByText("card").parentElement?.className).toContain(
      "lg:grid-cols-2",
    );
  });

  it("keeps one wide block when the page opts out", () => {
    // A table or the event page's organizer surface: splitting it would only narrow it.
    shell(false);
    expect(screen.getByText("card").parentElement?.className).not.toContain(
      "lg:grid-cols-2",
    );
  });
});
