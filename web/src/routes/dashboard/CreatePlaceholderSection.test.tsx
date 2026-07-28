import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CreatePlaceholderSection } from "./CreatePlaceholderSection";

const { createMutate, createPending } = vi.hoisted(() => ({
  createMutate: vi.fn(),
  createPending: { value: false },
}));

vi.mock("@/api/generated/users/users", () => ({
  getGetApiV1UsersPlaceholdersQueryKey: () => ["placeholders"],
  usePostApiV1UsersPlaceholders: () => ({
    mutateAsync: createMutate,
    isPending: createPending.value,
  }),
}));

function renderSection(capabilities: string[] = ["ADMINISTRATOR"]) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <CreatePlaceholderSection capabilities={capabilities as never} />
    </QueryClientProvider>,
  );
}

describe("CreatePlaceholderSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createPending.value = false;
    createMutate.mockResolvedValue({ id: "p1", publicCode: "PLH001" });
  });

  it("creates a placeholder with name and sex, invalidating the list", async () => {
    const queryClient = new QueryClient();
    const invalidate = vi
      .spyOn(queryClient, "invalidateQueries")
      .mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <CreatePlaceholderSection capabilities={["HOST"] as never} />
      </QueryClientProvider>,
    );

    await user.type(screen.getByLabelText("Display name"), "Alex P.");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.click(screen.getByRole("button", { name: "Create placeholder" }));

    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({
        data: { displayName: "Alex P.", sex: "Female" },
      }),
    );
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["placeholders"] });
    expect(screen.getByRole("status")).toHaveTextContent("Created Alex P.");
  });

  it("includes date of birth and initial rating when provided (rater)", async () => {
    const user = userEvent.setup();
    renderSection(["ADMINISTRATOR"]);

    await user.type(screen.getByLabelText("Display name"), "Sam Q.");
    await user.selectOptions(screen.getByLabelText("Sex"), "Male");
    await user.type(screen.getByLabelText("Date of birth (optional)"), "1990-05-01");
    await user.selectOptions(
      screen.getByLabelText("Initial rating (optional)"),
      "3.5",
    );
    await user.click(screen.getByRole("button", { name: "Create placeholder" }));

    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({
        data: {
          displayName: "Sam Q.",
          sex: "Male",
          dateOfBirth: "1990-05-01",
          initialRating: "3.5",
        },
      }),
    );
  });

  it("hides the initial-rating field from a non-rater match manager", () => {
    renderSection(["HOST"]);
    expect(
      screen.queryByLabelText("Initial rating (optional)"),
    ).not.toBeInTheDocument();
  });

  it("requires a display name", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Create placeholder" }));
    expect(screen.getByRole("alert")).toHaveTextContent(/display name is required/i);
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("requires a sex", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("Display name"), "Nameless");
    await user.click(screen.getByRole("button", { name: "Create placeholder" }));
    expect(screen.getByRole("alert")).toHaveTextContent(/sex is required/i);
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("shows a pending label while creating", () => {
    createPending.value = true;
    renderSection();
    expect(screen.getByRole("button", { name: "Creating…" })).toBeDisabled();
  });

  it("shows an inline error when creation fails", async () => {
    createMutate.mockRejectedValue(new Error("boom"));
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("Display name"), "Alex P.");
    await user.selectOptions(screen.getByLabelText("Sex"), "Female");
    await user.click(screen.getByRole("button", { name: "Create placeholder" }));
    expect(
      await screen.findByText(/could not create the placeholder player/i),
    ).toBeInTheDocument();
  });
});
