import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PlayerPicker } from "./PlayerPicker";

const { createMutate, createPending } = vi.hoisted(() => ({
  createMutate: vi.fn(),
  createPending: { value: false },
}));

// The embedded search is exercised elsewhere; stub it to a button that emits a picked player, so this
// test focuses on the placeholder-create affordance.
vi.mock("@/components/UserSearchSelect", () => ({
  UserSearchSelect: ({
    label,
    onSelect,
  }: {
    label: string;
    onSelect: (u: { id: string; publicCode: string; displayName: string }) => void;
  }) => (
    <button
      type="button"
      onClick={() =>
        onSelect({ id: "u1", publicCode: "AAA111", displayName: "Ana" })
      }
    >
      search {label}
    </button>
  ),
}));

vi.mock("@/api/generated/users/users", () => ({
  usePostApiV1UsersPlaceholders: () => ({
    mutateAsync: createMutate,
    isPending: createPending.value,
  }),
}));

function renderPicker(onSelect = vi.fn(), canSetRating = false) {
  render(
    <PlayerPicker
      label="Add participant"
      canSetRating={canSetRating}
      onSelect={onSelect}
    />,
  );
  return onSelect;
}

describe("PlayerPicker", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createPending.value = false;
    createMutate.mockResolvedValue({
      id: "p1",
      publicCode: "PLH001",
      country: "PH",
      kycVerified: false,
      isActive: true,
      sex: "Female",
      names: [{ id: "n1", type: "DISPLAY", value: "New Player" }],
      contacts: [],
      identities: [],
      capabilities: [],
    });
  });

  it("emits a searched player through onSelect", async () => {
    const user = userEvent.setup();
    const onSelect = renderPicker();
    await user.click(screen.getByRole("button", { name: /search Add participant/ }));
    expect(onSelect).toHaveBeenCalledWith(
      expect.objectContaining({ id: "u1", publicCode: "AAA111" }),
    );
  });

  it("creates a placeholder and emits the created player", async () => {
    const user = userEvent.setup();
    const onSelect = renderPicker();

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );

    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({
        data: { displayName: "New Player", sex: "Female" },
      }),
    );
    // The full UserResponse is adapted to the slim shape the pickers emit.
    expect(onSelect).toHaveBeenCalledWith(
      expect.objectContaining({
        id: "p1",
        publicCode: "PLH001",
        displayName: "New Player",
        sex: "Female",
      }),
    );
  });

  it("maps a created placeholder with no name or sex to null/undefined", async () => {
    createMutate.mockResolvedValue({
      id: "p2",
      publicCode: "PLH002",
      country: "PH",
      kycVerified: false,
      isActive: true,
      sex: null,
      names: [],
      contacts: [],
      identities: [],
      capabilities: [],
    });
    const user = userEvent.setup();
    const onSelect = renderPicker();

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );

    await waitFor(() =>
      expect(onSelect).toHaveBeenCalledWith(
        expect.objectContaining({ id: "p2", displayName: null, sex: undefined }),
      ),
    );
  });

  it("disables the submit with a pending label while creating", async () => {
    createPending.value = true;
    const user = userEvent.setup();
    renderPicker();

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    expect(screen.getByRole("button", { name: "Creating…" })).toBeDisabled();
  });

  it("sends the optional date of birth when provided", async () => {
    const user = userEvent.setup();
    renderPicker();

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.selectOptions(screen.getByLabelText("Sex"), "Male");
    await user.type(
      screen.getByLabelText("Date of birth (optional)"),
      "1990-01-02",
    );
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );

    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({
        data: {
          displayName: "New Player",
          sex: "Male",
          dateOfBirth: "1990-01-02",
        },
      }),
    );
  });

  it("validates that a display name and sex are required", async () => {
    const user = userEvent.setup();
    renderPicker();
    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );

    // No name yet.
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );
    expect(
      await screen.findByText(/a display name is required/i),
    ).toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();

    // Name but no sex.
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );
    expect(await screen.findByText(/sex is required/i)).toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("cancels the form, resetting fields and hiding it", async () => {
    const user = userEvent.setup();
    renderPicker();

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "Typed Name");

    await user.click(screen.getByRole("button", { name: "Cancel" }));

    // The form is hidden and the create was never called.
    expect(
      screen.queryByRole("button", { name: "Create placeholder" }),
    ).not.toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();

    // Re-opening shows an empty display-name field (state was reset).
    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    expect(screen.getByLabelText("Display name")).toHaveValue("");
  });

  it("hides the initial-rating field from a non-RATER caller", async () => {
    const user = userEvent.setup();
    renderPicker(vi.fn(), false);

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    expect(
      screen.queryByLabelText("Initial rating (optional)"),
    ).not.toBeInTheDocument();
  });

  it("shows the initial-rating field for a RATER caller and includes it in the payload", async () => {
    const user = userEvent.setup();
    renderPicker(vi.fn(), true);

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.type(
      screen.getByLabelText("Initial rating (optional)"),
      "4.0",
    );
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );

    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({
        data: {
          displayName: "New Player",
          sex: "Female",
          initialRating: "4.0",
        },
      }),
    );
  });

  it("omits the initial rating when a RATER leaves it blank", async () => {
    const user = userEvent.setup();
    renderPicker(vi.fn(), true);

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );

    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({
        data: { displayName: "New Player", sex: "Female" },
      }),
    );
  });

  it("rejects an out-of-range initial rating before calling create", async () => {
    const user = userEvent.setup();
    renderPicker(vi.fn(), true);

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.type(
      screen.getByLabelText("Initial rating (optional)"),
      "9",
    );
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );

    expect(
      await screen.findByText(/between 1.0 and 7.0/i),
    ).toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("shows an inline error when the create fails", async () => {
    createMutate.mockRejectedValue(new Error("boom"));
    const user = userEvent.setup();
    renderPicker();
    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );
    expect(
      await screen.findByText(/could not create the placeholder player/i),
    ).toBeInTheDocument();
  });
});
