import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PlaceholderPlayersSection } from "./PlaceholderPlayersSection";

const { useGetApiV1UsersPlaceholders, generateMutate } = vi.hoisted(() => ({
  useGetApiV1UsersPlaceholders: vi.fn(),
  generateMutate: vi.fn(),
}));

vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersPlaceholders,
  getGetApiV1UsersPlaceholdersQueryKey: () => ["placeholders"],
  usePostApiV1UsersIdClaimCode: () => ({
    mutateAsync: generateMutate,
    isPending: false,
  }),
}));

function renderSection(capabilities: string[] = ["ADMINISTRATOR"]) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <PlaceholderPlayersSection
        capabilities={capabilities as never}
      />
    </QueryClientProvider>,
  );
}

const placeholder = {
  id: "p1",
  publicCode: "PLH001",
  displayName: "Alex P.",
  sex: "Female",
  age: 30,
  capabilities: [],
};

describe("PlaceholderPlayersSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1UsersPlaceholders.mockReturnValue({
      data: [placeholder],
      isLoading: false,
      isFetching: false,
    });
    generateMutate.mockResolvedValue({
      code: "SECRET-1234",
      expiresAt: "2026-07-27T00:00:00",
      placeholderPublicCode: "PLH001",
    });
  });

  it("lists unclaimed placeholders", () => {
    renderSection();
    expect(screen.getByText("Alex P.")).toBeInTheDocument();
    expect(screen.getByText(/PLH001 · Female · 30/)).toBeInTheDocument();
  });

  it("shows the plaintext claim code once after generating (admin)", async () => {
    const user = userEvent.setup();
    renderSection(["ADMINISTRATOR"]);

    // The code is not present before generating.
    expect(screen.queryByTestId("claim-code")).not.toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "Generate claim code" }),
    );

    await waitFor(() =>
      expect(generateMutate).toHaveBeenCalledWith({ id: "p1" }),
    );
    expect(screen.getByTestId("claim-code")).toHaveTextContent("SECRET-1234");
    expect(screen.getByText(/won.t be shown again/i)).toBeInTheDocument();
    expect(screen.getByText(/Expires 2026-07-27/)).toBeInTheDocument();
  });

  it("does not offer code generation to non-admin match managers", () => {
    renderSection(["HOST"]);
    expect(screen.getByText("Alex P.")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Generate claim code" }),
    ).not.toBeInTheDocument();
  });

  it("shows an inline error when generation fails", async () => {
    generateMutate.mockRejectedValue(new Error("boom"));
    const user = userEvent.setup();
    renderSection(["ADMINISTRATOR"]);
    await user.click(
      screen.getByRole("button", { name: "Generate claim code" }),
    );
    expect(
      await screen.findByText(/could not generate a claim code/i),
    ).toBeInTheDocument();
  });

  it("shows an empty state when there are no placeholders", () => {
    useGetApiV1UsersPlaceholders.mockReturnValue({
      data: [],
      isLoading: false,
      isFetching: false,
    });
    renderSection();
    expect(
      screen.getByText("No unclaimed placeholder players."),
    ).toBeInTheDocument();
  });
});
