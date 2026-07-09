import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PhotoSettingsForm } from "./PhotoSettingsForm";

const { useGetApiV1UsersId, usePutApiV1UsersIdPhoto, photoMutate } = vi.hoisted(
  () => ({
    useGetApiV1UsersId: vi.fn(),
    usePutApiV1UsersIdPhoto: vi.fn(),
    photoMutate: vi.fn(),
  }),
);

vi.mock("@/api/generated/users/users", () => ({
  useGetApiV1UsersId,
  usePutApiV1UsersIdPhoto,
  getGetApiV1UsersIdQueryKey: (id: string) => ["users", id],
  getGetApiV1UsersMeQueryKey: () => ["me"],
}));

function renderForm() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <PhotoSettingsForm userId="u1" />
    </QueryClientProvider>,
  );
}

describe("PhotoSettingsForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1UsersId.mockReturnValue({
      data: {
        id: "u1",
        customPhotoUrl: "https://old.example/me.jpg",
        photoHidden: false,
      },
      isLoading: false,
    });
    usePutApiV1UsersIdPhoto.mockReturnValue({
      isPending: false,
      mutateAsync: async (vars: unknown) => photoMutate(vars),
    });
  });

  it("shows a loading state until the user resolves", () => {
    useGetApiV1UsersId.mockReturnValue({ data: undefined, isLoading: true });
    renderForm();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("prefills the custom URL and hide flag from the current settings", () => {
    useGetApiV1UsersId.mockReturnValue({
      data: {
        id: "u1",
        customPhotoUrl: "https://old.example/me.jpg",
        photoHidden: true,
      },
      isLoading: false,
    });
    renderForm();
    expect(
      (screen.getByLabelText("Custom photo URL") as HTMLInputElement).value,
    ).toBe("https://old.example/me.jpg");
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(
      true,
    );
  });

  it("saves a new custom URL and the hide flag", async () => {
    const user = userEvent.setup();
    renderForm();
    const url = screen.getByLabelText("Custom photo URL");
    await user.clear(url);
    await user.type(url, "https://new.example/pic.png");
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: "Save photo" }));

    await waitFor(() =>
      expect(photoMutate).toHaveBeenCalledWith({
        id: "u1",
        data: { customPhotoUrl: "https://new.example/pic.png", hidden: true },
      }),
    );
    expect(screen.getByRole("status")).toHaveTextContent("Saved");
  });

  it("sends null to clear the custom URL when left blank", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.clear(screen.getByLabelText("Custom photo URL"));
    await user.click(screen.getByRole("button", { name: "Save photo" }));

    await waitFor(() =>
      expect(photoMutate).toHaveBeenCalledWith({
        id: "u1",
        data: { customPhotoUrl: null, hidden: false },
      }),
    );
  });

  it("rejects a non-http(s) URL without calling the API", async () => {
    const user = userEvent.setup();
    renderForm();
    const url = screen.getByLabelText("Custom photo URL");
    await user.clear(url);
    await user.type(url, "ftp://nope.example/x.jpg");
    await user.click(screen.getByRole("button", { name: "Save photo" }));

    expect(screen.getByRole("alert")).toHaveTextContent(/http\(s\) image URL/);
    expect(photoMutate).not.toHaveBeenCalled();
  });

  it("defaults to an empty URL and unchecked when the user has no photo settings", () => {
    useGetApiV1UsersId.mockReturnValue({
      data: { id: "u1" },
      isLoading: false,
    });
    renderForm();
    expect(
      (screen.getByLabelText("Custom photo URL") as HTMLInputElement).value,
    ).toBe("");
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(
      false,
    );
  });

  it("shows an error when saving fails", async () => {
    usePutApiV1UsersIdPhoto.mockReturnValue({
      isPending: false,
      mutateAsync: async () => {
        throw new Error("boom");
      },
    });
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByRole("button", { name: "Save photo" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /Could not save your photo settings/,
    );
  });

  it("disables the button and shows a saving label while the request is pending", () => {
    usePutApiV1UsersIdPhoto.mockReturnValue({
      isPending: true,
      mutateAsync: photoMutate,
    });
    renderForm();
    const button = screen.getByRole("button", { name: "Saving…" });
    expect(button).toBeDisabled();
  });
});
