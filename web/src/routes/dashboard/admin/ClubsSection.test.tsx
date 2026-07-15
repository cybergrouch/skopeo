import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ClubsSection } from "./ClubsSection";

const {
  useGetApiV1Clubs,
  createMutate,
  assignMutate,
  removeMutate,
  renameMutate,
  deleteMutate,
  state,
} = vi.hoisted(() => ({
  useGetApiV1Clubs: vi.fn(),
  createMutate: vi.fn(),
  assignMutate: vi.fn(),
  removeMutate: vi.fn(),
  renameMutate: vi.fn(),
  deleteMutate: vi.fn(),
  state: {
    createFail: false,
    createPending: false,
    renameFail: false,
    renamePending: false,
    deleteFail: false,
    deletePending: false,
  },
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
  usePatchApiV1ClubsId: () => ({
    isPending: state.renamePending,
    mutateAsync: async (vars: unknown) => {
      renameMutate(vars);
      if (state.renameFail) throw new Error("boom");
    },
  }),
  useDeleteApiV1ClubsId: () => ({
    isPending: state.deletePending,
    mutateAsync: async (vars: unknown) => {
      deleteMutate(vars);
      if (state.deleteFail) throw new Error("boom");
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
    filters,
    onSelect,
  }: {
    label: string;
    filters?: { capability?: string };
    onSelect: (u: {
      id: string;
      publicCode: string;
      displayName: string;
    }) => void;
  }) => (
    <button
      type="button"
      data-capability={filters?.capability ?? ""}
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
      <MemoryRouter>
        <ClubsSection />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("ClubsSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.createFail = false;
    state.createPending = false;
    state.renameFail = false;
    state.renamePending = false;
    state.deleteFail = false;
    state.deletePending = false;
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

  it("links each club to its public page (#327)", () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [
        {
          id: "c1",
          name: "Downtown TC",
          publicCode: "CLB001",
          isActive: true,
          owners: [],
        },
      ],
      isLoading: false,
    });
    renderSection();
    expect(
      screen.getByRole("link", { name: "Public page (QR)" }),
    ).toHaveAttribute("href", "/clubs/CLB001");
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
    const picker = screen.getByRole("button", { name: "pick:Assign an owner" });
    // The picker is scoped to users with the CLUB_OWNER capability (#317).
    expect(picker).toHaveAttribute("data-capability", "CLUB_OWNER");
    await user.click(picker);
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

  it("renames a club (#325)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Edit" }));
    const input = screen.getByLabelText("Club name");
    await user.clear(input);
    await user.type(input, "Uptown TC");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(renameMutate).toHaveBeenCalledWith({
        id: "c1",
        data: { name: "Uptown TC" },
      }),
    );
  });

  it("rejects a blank rename and can be cancelled (#325)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Edit" }));
    await user.clear(screen.getByLabelText("Club name"));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Club name is required.");
    expect(renameMutate).not.toHaveBeenCalled();

    // Cancel restores the read-only name and hides the editor.
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByLabelText("Club name")).not.toBeInTheDocument();
    expect(screen.getByText("Downtown TC")).toBeInTheDocument();
  });

  it("surfaces an error when a rename fails (#325)", async () => {
    state.renameFail = true;
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Edit" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not rename the club.",
    );
  });

  it("deletes a club after a confirm step (#325)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Delete" }));
    // A confirm step guards the delete.
    expect(deleteMutate).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Confirm delete" }));
    await waitFor(() =>
      expect(deleteMutate).toHaveBeenCalledWith({ id: "c1" }),
    );
  });

  it("cancels a pending delete without calling the API (#325)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(deleteMutate).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
  });

  it("shows busy labels while a rename or delete is in flight (#325)", async () => {
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();

    state.renamePending = true;
    const { unmount } = renderSection();
    await user.click(screen.getByRole("button", { name: "Edit" }));
    expect(screen.getByRole("button", { name: "Saving…" })).toBeDisabled();
    unmount();

    state.renamePending = false;
    state.deletePending = true;
    renderSection();
    await user.click(screen.getByRole("button", { name: "Delete" }));
    expect(screen.getByRole("button", { name: "Deleting…" })).toBeDisabled();
  });

  it("surfaces an error when a delete fails (#325)", async () => {
    state.deleteFail = true;
    useGetApiV1Clubs.mockReturnValue({
      data: [{ id: "c1", name: "Downtown TC", isActive: true, owners: [] }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Confirm delete" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not delete the club.",
    );
  });
});
