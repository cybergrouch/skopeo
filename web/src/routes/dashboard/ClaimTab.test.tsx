import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ClaimTab } from "./ClaimTab";

const { claimMutate, claimState } = vi.hoisted(() => ({
  claimMutate: vi.fn(),
  claimState: { isPending: false },
}));

vi.mock("@/api/generated/users/users", () => ({
  usePostApiV1UsersClaim: () => ({
    mutateAsync: claimMutate,
    isPending: claimState.isPending,
  }),
  getGetApiV1UsersMeQueryKey: () => ["me"],
}));

function renderTab() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <ClaimTab />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("ClaimTab", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    claimState.isPending = false;
    claimMutate.mockResolvedValue({
      id: "me",
      publicCode: "MYCODE",
      country: "PH",
      kycVerified: false,
      isActive: true,
      names: [],
      contacts: [],
      identities: [],
      capabilities: [],
    });
  });

  it("claims an account and shows the success state with a profile link", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Claim code"), "SECRET-1234");
    await user.click(screen.getByRole("button", { name: "Claim account" }));

    await waitFor(() =>
      expect(claimMutate).toHaveBeenCalledWith({
        data: { code: "SECRET-1234" },
      }),
    );
    expect(await screen.findByText("Account claimed")).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /view your profile/i }),
    ).toHaveAttribute("href", "/players/MYCODE");
  });

  it("surfaces the server error message inline on a failed claim", async () => {
    claimMutate.mockRejectedValue({
      response: { data: { message: "This claim code has expired." } },
    });
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Claim code"), "OLD-CODE");
    await user.click(screen.getByRole("button", { name: "Claim account" }));

    expect(
      await screen.findByText("This claim code has expired."),
    ).toBeInTheDocument();
    // Still on the form, not the success view.
    expect(screen.queryByText("Account claimed")).not.toBeInTheDocument();
  });

  it("falls back to a generic message when the server sends none", async () => {
    claimMutate.mockRejectedValue(new Error("boom"));
    const user = userEvent.setup();
    renderTab();
    await user.type(screen.getByLabelText("Claim code"), "BAD");
    await user.click(screen.getByRole("button", { name: "Claim account" }));
    expect(
      await screen.findByText(/that code could not be used/i),
    ).toBeInTheDocument();
  });

  it("shows a pending label and disables the button while claiming", () => {
    claimState.isPending = true;
    renderTab();
    const button = screen.getByRole("button", { name: "Claiming…" });
    expect(button).toBeDisabled();
  });

  it("validates that a code is entered", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.click(screen.getByRole("button", { name: "Claim account" }));
    expect(
      await screen.findByText(/enter the claim code you were given/i),
    ).toBeInTheDocument();
    expect(claimMutate).not.toHaveBeenCalled();
  });
});
