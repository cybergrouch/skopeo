import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MatchHistoryVisibilityForm } from "./MatchHistoryVisibilityForm";

const {
  useGetApiV1UsersId,
  usePutApiV1UsersIdMatchHistoryVisibility,
  saveMutate,
} = vi.hoisted(() => ({
  useGetApiV1UsersId: vi.fn(),
  usePutApiV1UsersIdMatchHistoryVisibility: vi.fn(),
  saveMutate: vi.fn(),
}));

vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersId,
  usePutApiV1UsersIdMatchHistoryVisibility,
  getGetApiV1UsersIdQueryKey: (id: string) => ["users", id],
  getGetApiV1UsersMeQueryKey: () => ["me"],
}));

function renderForm() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MatchHistoryVisibilityForm userId="u1" />
    </QueryClientProvider>,
  );
}

describe("MatchHistoryVisibilityForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1UsersId.mockReturnValue({
      data: { id: "u1", matchHistoryHidden: false },
      isLoading: false,
    });
    usePutApiV1UsersIdMatchHistoryVisibility.mockReturnValue({
      isPending: false,
      mutateAsync: async (vars: unknown) => saveMutate(vars),
    });
  });

  it("shows a loading state until the user resolves", () => {
    useGetApiV1UsersId.mockReturnValue({ data: undefined, isLoading: true });
    renderForm();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("prefills the checkbox from the current flag", () => {
    useGetApiV1UsersId.mockReturnValue({
      data: { id: "u1", matchHistoryHidden: true },
      isLoading: false,
    });
    renderForm();
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(
      true,
    );
  });

  it("saves the flag on toggle and confirms", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByRole("checkbox"));
    await waitFor(() =>
      expect(saveMutate).toHaveBeenCalledWith({
        id: "u1",
        data: { hidden: true },
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Saved");
  });

  it("shows a saving indicator while the save is in flight", () => {
    usePutApiV1UsersIdMatchHistoryVisibility.mockReturnValue({
      isPending: true,
      mutateAsync: async (vars: unknown) => saveMutate(vars),
    });
    renderForm();
    expect(screen.getByText("Saving…")).toBeInTheDocument();
  });

  it("surfaces an error and reverts the toggle when the save fails", async () => {
    const user = userEvent.setup();
    usePutApiV1UsersIdMatchHistoryVisibility.mockReturnValue({
      isPending: false,
      mutateAsync: async () => {
        throw new Error("network down");
      },
    });
    renderForm();
    await user.click(screen.getByRole("checkbox"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not save. Please try again.",
    );
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(
      false,
    );
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("renders the toggle once the user query resolves with data", () => {
    useGetApiV1UsersId.mockReturnValue({
      data: { id: "u1", matchHistoryHidden: true },
      isLoading: false,
    });
    renderForm();
    expect(screen.getByRole("checkbox")).toBeInTheDocument();
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(
      true,
    );
  });

  it("defaults to visible when matchHistoryHidden is undefined", () => {
    useGetApiV1UsersId.mockReturnValue({
      data: { id: "u1" },
      isLoading: false,
    });
    renderForm();
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(
      false,
    );
  });
});
