import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CircuitsSection } from "./CircuitsSection";

const {
  useGetApiV1Circuits,
  createMutate,
  renameMutate,
  deleteMutate,
  state,
} = vi.hoisted(() => ({
  useGetApiV1Circuits: vi.fn(),
  createMutate: vi.fn(),
  renameMutate: vi.fn(),
  deleteMutate: vi.fn(),
  state: {
    createFail: false,
    createPending: false,
    renameFail: false,
    deleteFail: false,
  },
}));

vi.mock("@/api/generated/circuits/circuits", () => ({
  useGetApiV1Circuits,
  getGetApiV1CircuitsQueryKey: () => ["circuits"],
  usePostApiV1Circuits: () => ({
    isPending: state.createPending,
    mutateAsync: async (vars: unknown) => {
      createMutate(vars);
      if (state.createFail) throw new Error("boom");
    },
  }),
  usePatchApiV1CircuitsId: () => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      renameMutate(vars);
      if (state.renameFail) throw new Error("boom");
    },
  }),
  useDeleteApiV1CircuitsId: () => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      deleteMutate(vars);
      if (state.deleteFail) throw new Error("boom");
    },
  }),
}));

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <CircuitsSection />
    </QueryClientProvider>,
  );
}

describe("CircuitsSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.createFail = false;
    state.createPending = false;
    state.renameFail = false;
    state.deleteFail = false;
    useGetApiV1Circuits.mockReturnValue({ data: [], isLoading: false });
  });

  it("shows a loading state", () => {
    useGetApiV1Circuits.mockReturnValue({ data: undefined, isLoading: true });
    renderSection();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows an empty state when there are no circuits", () => {
    renderSection();
    expect(screen.getByText("No circuits yet.")).toBeInTheDocument();
  });

  it("lists circuits", () => {
    useGetApiV1Circuits.mockReturnValue({
      data: [
        { id: "c1", name: "NORTH", isActive: true },
        { id: "c2", name: "SOUTH", isActive: true },
      ],
      isLoading: false,
    });
    renderSection();
    expect(screen.getByText("NORTH")).toBeInTheDocument();
    expect(screen.getByText("SOUTH")).toBeInTheDocument();
  });

  it("creates a circuit", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("New circuit"), "EAST");
    await user.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({ data: { name: "EAST" } }),
    );
  });

  it("rejects a blank circuit name", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Circuit name is required.",
    );
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("shows an error when creation fails", async () => {
    state.createFail = true;
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("New circuit"), "BAD");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not create the circuit.",
    );
  });

  it("renames a circuit", async () => {
    useGetApiV1Circuits.mockReturnValue({
      data: [{ id: "c1", name: "NORTH", isActive: true }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Rename" }));
    const input = screen.getByLabelText("Circuit name");
    await user.clear(input);
    await user.type(input, "NORTH REGION");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(renameMutate).toHaveBeenCalledWith({
        id: "c1",
        data: { name: "NORTH REGION" },
      }),
    );
  });

  it("disables the create button while the request is pending", () => {
    state.createPending = true;
    renderSection();
    expect(screen.getByRole("button", { name: "Creating…" })).toBeDisabled();
  });

  it("rejects a blank rename and can be cancelled", async () => {
    useGetApiV1Circuits.mockReturnValue({
      data: [{ id: "c1", name: "NORTH", isActive: true }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Rename" }));
    await user.clear(screen.getByLabelText("Circuit name"));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Circuit name is required.",
    );
    expect(renameMutate).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByLabelText("Circuit name")).not.toBeInTheDocument();
    expect(screen.getByText("NORTH")).toBeInTheDocument();
  });

  it("surfaces an error when a rename fails", async () => {
    state.renameFail = true;
    useGetApiV1Circuits.mockReturnValue({
      data: [{ id: "c1", name: "NORTH", isActive: true }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Rename" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not rename the circuit.",
    );
  });

  it("deletes a circuit after a confirm step", async () => {
    useGetApiV1Circuits.mockReturnValue({
      data: [{ id: "c1", name: "NORTH", isActive: true }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Delete" }));
    expect(deleteMutate).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() =>
      expect(deleteMutate).toHaveBeenCalledWith({ id: "c1" }),
    );
  });

  it("cancels a pending delete without calling the API", async () => {
    useGetApiV1Circuits.mockReturnValue({
      data: [{ id: "c1", name: "NORTH", isActive: true }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(deleteMutate).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
  });

  it("surfaces an error when a delete fails", async () => {
    state.deleteFail = true;
    useGetApiV1Circuits.mockReturnValue({
      data: [{ id: "c1", name: "NORTH", isActive: true }],
      isLoading: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Confirm" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not delete the circuit.",
    );
  });
});
