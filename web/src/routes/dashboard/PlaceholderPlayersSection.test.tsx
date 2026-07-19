import { describe, it, expect, beforeEach, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PlaceholderPlayersSection } from "./PlaceholderPlayersSection";

const { useGetApiV1UsersPlaceholders, generateMutate, generatePending } = vi.hoisted(() => ({
  useGetApiV1UsersPlaceholders: vi.fn(),
  generateMutate: vi.fn(),
  generatePending: { value: false },
}));

vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersPlaceholders,
  getGetApiV1UsersPlaceholdersQueryKey: () => ["placeholders"],
  usePostApiV1UsersIdClaimCode: () => ({
    mutateAsync: generateMutate,
    isPending: generatePending.value,
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
    generatePending.value = false;
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

  it("omits the meta suffix when a placeholder has no sex or age", () => {
    useGetApiV1UsersPlaceholders.mockReturnValue({
      data: [{ id: "p9", publicCode: "PLH009", displayName: "No Meta", capabilities: [] }],
      isLoading: false,
      isFetching: false,
    });
    renderSection();
    // publicCode shows without the " · …" meta suffix.
    expect(screen.getByText("PLH009")).toBeInTheDocument();
    expect(screen.queryByText(/PLH009 ·/)).not.toBeInTheDocument();
  });

  it("shows a pending label while a claim code is generating (admin)", async () => {
    generatePending.value = true;
    renderSection(["ADMINISTRATOR"]);
    expect(screen.getByRole("button", { name: "Generating…" })).toBeDisabled();
  });

  it("shows a loading state", () => {
    useGetApiV1UsersPlaceholders.mockReturnValue({
      data: undefined,
      isLoading: true,
      isFetching: true,
    });
    renderSection();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows a refreshing label while refetching", () => {
    useGetApiV1UsersPlaceholders.mockReturnValue({
      data: [placeholder],
      isLoading: false,
      isFetching: true,
    });
    renderSection();
    expect(screen.getByRole("button", { name: "Refreshing…" })).toBeInTheDocument();
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

  it("copies the code to the clipboard and confirms, then dismisses the panel", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    // Define the clipboard AFTER setup so it wins over userEvent's own clipboard stub.
    const user = userEvent.setup();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    renderSection(["ADMINISTRATOR"]);

    await user.click(
      screen.getByRole("button", { name: "Generate claim code" }),
    );
    await waitFor(() => expect(screen.getByTestId("claim-code")).toBeInTheDocument());

    // fireEvent (not userEvent) so the component's navigator.clipboard mock is used, not userEvent's stub.
    fireEvent.click(screen.getByRole("button", { name: "Copy code" }));
    expect(writeText).toHaveBeenCalledWith("SECRET-1234");
    expect(await screen.findByRole("button", { name: "Copied" })).toBeInTheDocument();

    // Dismiss clears the panel from the screen.
    await user.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByTestId("claim-code")).not.toBeInTheDocument();
  });

  it("does not claim a successful copy when the clipboard is unavailable", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("blocked"));
    const user = userEvent.setup();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    renderSection(["ADMINISTRATOR"]);

    await user.click(
      screen.getByRole("button", { name: "Generate claim code" }),
    );
    await waitFor(() => expect(screen.getByTestId("claim-code")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "Copy code" }));
    await waitFor(() => expect(writeText).toHaveBeenCalled());
    // The button label stays "Copy code" — no false "Copied" confirmation.
    expect(
      await screen.findByRole("button", { name: "Copy code" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Copied" })).not.toBeInTheDocument();
  });

  it("refreshes the list on demand", async () => {
    const queryClient = new QueryClient();
    const invalidate = vi
      .spyOn(queryClient, "invalidateQueries")
      .mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <PlaceholderPlayersSection capabilities={["ADMINISTRATOR"] as never} />
      </QueryClientProvider>,
    );

    await user.click(screen.getByRole("button", { name: "Refresh" }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["placeholders"] });
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
