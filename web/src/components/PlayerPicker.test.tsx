import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PlayerPicker } from "./PlayerPicker";

const { createMutate } = vi.hoisted(() => ({
  createMutate: vi.fn(),
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
    isPending: false,
  }),
}));

function renderPicker(onSelect = vi.fn()) {
  render(<PlayerPicker label="Add participant" onSelect={onSelect} />);
  return onSelect;
}

describe("PlayerPicker", () => {
  beforeEach(() => {
    vi.clearAllMocks();
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
