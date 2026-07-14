import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ClubsSection } from "./ClubsSection";

const { useGetApiV1Clubs, createMutate, assignMutate, removeMutate, state } =
  vi.hoisted(() => ({
    useGetApiV1Clubs: vi.fn(),
    createMutate: vi.fn(),
    assignMutate: vi.fn(),
    removeMutate: vi.fn(),
    state: { createFail: false, createPending: false },
  }));

vi.mock("@/api/generated/clubs/clubs", () => ({
  useGetApiV1Clubs,
  getGetApiV1ClubsQueryKey: () => ["clubs"],
  usePostApiV1Clubs: () => ({
    isPending: state.createPending,
    mutateAsync: async (vars: unknown) => {
      createMutate(vars);
      if (state.createFail) throw new Error("boom");
    },
  }),
  usePostApiV1ClubsIdOwners: () => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      assignMutate(vars);
    },
  }),
  useDeleteApiV1ClubsIdOwnersUserId: () => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      removeMutate(vars);
    },
  }),
}));

// The player picker becomes a button that selects a fixed user when clicked.
vi.mock("@/components/UserSearchSelect", () => ({
  UserSearchSelect: ({
    label,
    onSelect,
  }: {
    label: string;
    onSelect: (u: {
      id: string;
      publicCode: string;
      displayName: string;
    }) => void;
  }) => (
    <button
      type="button"
      onClick={() =>
        onSelect({ id: "u-new", publicCode: "NEW", displayName: "New Owner" })
      }
    >
      pick:{label}
    </button>
  ),
}));

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ClubsSection />
    </QueryClientProvider>,
  );
}

describe("ClubsSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.createFail = false;
    state.createPending = false;
    useGetApiV1Clubs.mockReturnValue({ data: [], isLoading: false });
  });

  it("shows a loading state", () => {
    useGetApiV1Clubs.mockReturnValue({ data: undefined, isLoading: true });
    renderSection();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows an empty state when there are no clubs", () => {
    renderSection();
    expect(screen.getByText("No clubs yet.")).toBeInTheDocument();
  });

  it("lists clubs with their owners and a no-owners note", () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [
        {
          id: "c1",
          name: "Downtown TC",
          isActive: true,
          owners: [{ userId: "o1", displayName: "Ann", publicCode: "AAA" }],
        },
        { id: "c2", name: "West End", isActive: true, owners: [] },
      ],
      isLoading: false,
    });
    renderSection();
    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
    expect(screen.getByText("Ann")).toBeInTheDocument();
    expect(screen.getByText("West End")).toBeInTheDocument();
    expect(screen.getByText("No owners yet.")).toBeInTheDocument();
  });

  it("creates a club", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("New club"), "New Club");
    await user.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({ data: { name: "New Club" } }),
    );
  });

  it("rejects a blank club name", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Club name is required.",
    );
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("assigns an owner to a club", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();
    await user.click(
      screen.getByRole("button", { name: "pick:Assign an owner" }),
    );
    await waitFor(() =>
      expect(assignMutate).toHaveBeenCalledWith({
        id: "c1",
        data: { userId: "u-new" },
      }),
    );
  });

  it("removes an owner from a club", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [
        {
          id: "c1",
          name: "Downtown TC",
          isActive: true,
          owners: [{ userId: "o1", displayName: "Ann", publicCode: "AAA" }],
        },
      ],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Remove" }));
    await waitFor(() =>
      expect(removeMutate).toHaveBeenCalledWith({ id: "c1", userId: "o1" }),
    );
  });

  it("shows an error when club creation fails", async () => {
    state.createFail = true;
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("New club"), "Bad Club");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not create the club.",
    );
  });

  it("shows an owner without a display name by public code", () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [
        {
          id: "c1",
          name: "Downtown TC",
          isActive: true,
          owners: [{ userId: "o1", displayName: null, publicCode: "AAA" }],
        },
      ],
      isLoading: false,
    });
    renderSection();
    expect(screen.getByText("AAA")).toBeInTheDocument();
  });

  it("disables the create button while the request is pending", () => {
    state.createPending = true;
    renderSection();
    expect(screen.getByRole("button", { name: "Creating…" })).toBeDisabled();
  });
});
