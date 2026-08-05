import { describe, it, expect, beforeEach, vi } from "vitest";
import type { FormEvent } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PlayerPicker } from "./PlayerPicker";

const { createMutate, createPending, toastSuccess, toastError } = vi.hoisted(() => ({
  createMutate: vi.fn(),
  createPending: { value: false },
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}));

vi.mock("sonner", () => ({
  toast: { success: toastSuccess, error: toastError },
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

  it("does not submit an enclosing form when creating a placeholder (#580)", async () => {
    // Regression: the placeholder-create UI used to be a nested <form>, which (being invalid HTML)
    // made its submit button submit the OUTER form — redirecting the host out of event creation.
    const user = userEvent.setup();
    const onSelect = vi.fn();
    const outerSubmit = vi.fn((e: FormEvent) => e.preventDefault());
    render(
      <form onSubmit={outerSubmit}>
        <PlayerPicker label="Add participant" onSelect={onSelect} />
        <button type="submit">Create event</button>
      </form>,
    );

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    await user.type(screen.getByLabelText("Display name"), "New Player");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.click(
      screen.getByRole("button", { name: "Create placeholder" }),
    );

    await waitFor(() => expect(createMutate).toHaveBeenCalled());
    // The placeholder was created, but the surrounding event form was NEVER submitted.
    expect(outerSubmit).not.toHaveBeenCalled();
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
    // Success is surfaced as a toast here too (#661), naming the player.
    await waitFor(() =>
      expect(toastSuccess).toHaveBeenCalledWith(
        expect.stringContaining("New Player"),
      ),
    );
  });

  it("shows an error toast when placeholder creation fails (#661)", async () => {
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

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith(
        expect.stringMatching(/could not create the placeholder player/i),
        expect.objectContaining({ duration: expect.any(Number) }),
      ),
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
    // The initial rating is now a band dropdown (#579).
    await user.selectOptions(
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

  it("offers the NTRP bands plus a 'No initial rating' option (#579)", async () => {
    const user = userEvent.setup();
    renderPicker(vi.fn(), true);

    await user.click(
      screen.getByRole("button", { name: "Add placeholder player" }),
    );
    const select = screen.getByLabelText("Initial rating (optional)");
    // The blank option is selected by default; the band options are offered.
    expect((select as HTMLSelectElement).value).toBe("");
    expect(screen.getByRole("option", { name: "No initial rating" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "NTRP 4.0" })).toBeInTheDocument();
  });

});
