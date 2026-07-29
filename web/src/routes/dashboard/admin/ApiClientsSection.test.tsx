import { describe, it, expect, beforeEach, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiClientsSection } from "./ApiClientsSection";

const { useGetApiV1ApiClients, createMutate, issueMutate, revokeMutate } =
  vi.hoisted(() => ({
    useGetApiV1ApiClients: vi.fn(),
    createMutate: vi.fn(),
    issueMutate: vi.fn(),
    revokeMutate: vi.fn(),
  }));

vi.mock("@/api/generated/client-api/client-api", () => ({
  useGetApiV1ApiClients,
  getGetApiV1ApiClientsQueryKey: () => ["api-clients"],
  usePostApiV1ApiClients: () => ({ mutateAsync: createMutate, isPending: false }),
  usePostApiV1ApiClientsIdKeys: () => ({ mutateAsync: issueMutate, isPending: false }),
  useDeleteApiV1ApiClientsClientIdKeysKeyId: () => ({
    mutateAsync: revokeMutate,
    isPending: false,
  }),
}));

const client = {
  id: "c1",
  name: "Partner A",
  status: "ACTIVE",
  createdAt: "2026-07-29T00:00:00",
  keys: [
    {
      id: "k1",
      keyPrefix: "skopeo_live_ABCD",
      scopes: ["RESEARCHER"],
      status: "ACTIVE",
      createdAt: "2026-07-29T00:00:00",
    },
  ],
};

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ApiClientsSection />
    </QueryClientProvider>,
  );
}

describe("ApiClientsSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGetApiV1ApiClients.mockReturnValue({ data: [client], isLoading: false });
    issueMutate.mockResolvedValue({
      apiKey: "skopeo_live_SECRETVALUE",
      key: { id: "k2", keyPrefix: "skopeo_live_SECR", scopes: [], status: "ACTIVE", createdAt: "x" },
    });
    createMutate.mockResolvedValue({ id: "c2" });
    revokeMutate.mockResolvedValue(undefined);
  });

  it("lists clients with their keys", () => {
    renderSection();
    expect(screen.getByText("Partner A")).toBeInTheDocument();
    expect(screen.getByText(/skopeo_live_ABCD/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Revoke" })).toBeInTheDocument();
  });

  it("shows an empty state when there are no clients", () => {
    useGetApiV1ApiClients.mockReturnValue({ data: [], isLoading: false });
    renderSection();
    expect(screen.getByText("No API clients yet.")).toBeInTheDocument();
  });

  it("creates a client from the name form", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("New client"), "Partner B");
    await user.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({ data: { name: "Partner B" } }),
    );
  });

  it("requires a client name", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(screen.getByRole("alert")).toHaveTextContent(/client name is required/i);
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("issues a key with the chosen scope and reveals the secret once", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Scope RESEARCHER"));
    await user.click(screen.getByRole("button", { name: "Issue key" }));

    await waitFor(() =>
      expect(issueMutate).toHaveBeenCalledWith({
        id: "c1",
        data: { scopes: ["RESEARCHER"], environment: "LIVE" },
      }),
    );
    // The plaintext secret is revealed once.
    expect(screen.getByTestId("issued-secret")).toHaveTextContent(
      "skopeo_live_SECRETVALUE",
    );
  });

  it("includes an expiry when provided", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("Expires in days (optional)"), "30");
    await user.click(screen.getByRole("button", { name: "Issue key" }));
    await waitFor(() =>
      expect(issueMutate).toHaveBeenCalledWith({
        id: "c1",
        data: { environment: "LIVE", expiresInDays: 30 },
      }),
    );
  });

  it("copies the revealed secret to the clipboard", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    // Define the clipboard AFTER setup so it wins over userEvent's own clipboard stub.
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    renderSection();
    await user.click(screen.getByRole("button", { name: "Issue key" }));
    await screen.findByTestId("issued-secret");

    // fireEvent (not userEvent) so the component's navigator.clipboard mock is used.
    fireEvent.click(screen.getByRole("button", { name: "Copy key" }));
    expect(writeText).toHaveBeenCalledWith("skopeo_live_SECRETVALUE");
    expect(
      await screen.findByRole("button", { name: "Copied" }),
    ).toBeInTheDocument();
  });

  it("revokes an active key", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Revoke" }));
    await waitFor(() =>
      expect(revokeMutate).toHaveBeenCalledWith({ clientId: "c1", keyId: "k1" }),
    );
  });
});
