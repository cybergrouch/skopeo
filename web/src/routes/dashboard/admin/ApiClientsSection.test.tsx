import { describe, it, expect, beforeEach, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiClientsSection } from "./ApiClientsSection";

const { useGetApiV1ApiClients, createMutate, issueMutate, revokeMutate, pending } =
  vi.hoisted(() => ({
    useGetApiV1ApiClients: vi.fn(),
    createMutate: vi.fn(),
    issueMutate: vi.fn(),
    revokeMutate: vi.fn(),
    pending: { value: false },
  }));

vi.mock("@/api/generated/client-api/client-api", () => ({
  useGetApiV1ApiClients,
  getGetApiV1ApiClientsQueryKey: () => ["api-clients"],
  usePostApiV1ApiClients: () => ({ mutateAsync: createMutate, isPending: pending.value }),
  usePostApiV1ApiClientsIdKeys: () => ({ mutateAsync: issueMutate, isPending: pending.value }),
  useDeleteApiV1ApiClientsClientIdKeysKeyId: () => ({
    mutateAsync: revokeMutate,
    isPending: pending.value,
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
    pending.value = false;
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

  it("shows expiry and no Revoke for a revoked key", () => {
    useGetApiV1ApiClients.mockReturnValue({
      data: [
        {
          ...client,
          keys: [
            {
              id: "k9",
              keyPrefix: "skopeo_live_OLD9",
              scopes: [],
              status: "REVOKED",
              createdAt: "2026-01-01T00:00:00",
              expiresAt: "2026-12-31T00:00:00",
            },
          ],
        },
      ],
      isLoading: false,
    });
    renderSection();
    expect(screen.getByText(/expires 2026-12-31/)).toBeInTheDocument();
    // A revoked key exposes no Revoke action.
    expect(screen.queryByRole("button", { name: "Revoke" })).not.toBeInTheDocument();
  });

  it("shows a loading state", () => {
    useGetApiV1ApiClients.mockReturnValue({ data: undefined, isLoading: true });
    renderSection();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows a no-keys state for a client without keys", () => {
    useGetApiV1ApiClients.mockReturnValue({
      data: [{ ...client, keys: [] }],
      isLoading: false,
    });
    renderSection();
    expect(screen.getByText("No keys yet.")).toBeInTheDocument();
  });

  it("reflects pending state on the create and issue buttons", () => {
    pending.value = true;
    renderSection();
    expect(screen.getByRole("button", { name: "Creating…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Issuing…" })).toBeDisabled();
  });

  it("surfaces an error when creating a client fails", async () => {
    createMutate.mockRejectedValue(new Error("boom"));
    const user = userEvent.setup();
    renderSection();
    await user.type(screen.getByLabelText("New client"), "Partner B");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(
      await screen.findByText(/could not create the client/i),
    ).toBeInTheDocument();
  });

  it("surfaces an error when issuing a key fails", async () => {
    issueMutate.mockRejectedValue(new Error("boom"));
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Issue key" }));
    expect(
      await screen.findByText(/could not issue the key/i),
    ).toBeInTheDocument();
  });

  it("surfaces an error when revoking a key fails", async () => {
    revokeMutate.mockRejectedValue(new Error("boom"));
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Revoke" }));
    expect(
      await screen.findByText(/could not revoke the key/i),
    ).toBeInTheDocument();
  });

  it("deselects a scope when toggled twice, omitting scopes from the request", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByLabelText("Scope RESEARCHER"));
    await user.click(screen.getByLabelText("Scope RESEARCHER"));
    await user.click(screen.getByRole("button", { name: "Issue key" }));
    await waitFor(() =>
      expect(issueMutate).toHaveBeenCalledWith({
        id: "c1",
        data: { environment: "LIVE" },
      }),
    );
  });

  it("keeps the secret visible when the clipboard is unavailable", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("blocked"));
    const user = userEvent.setup();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    renderSection();
    await user.click(screen.getByRole("button", { name: "Issue key" }));
    await screen.findByTestId("issued-secret");

    fireEvent.click(screen.getByRole("button", { name: "Copy key" }));
    await waitFor(() => expect(writeText).toHaveBeenCalled());
    // No false "Copied" confirmation; the secret stays on screen to copy manually.
    expect(screen.getByRole("button", { name: "Copy key" })).toBeInTheDocument();
    expect(screen.getByTestId("issued-secret")).toBeInTheDocument();
  });

  it("dismisses the revealed secret with Done", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole("button", { name: "Issue key" }));
    await screen.findByTestId("issued-secret");
    await user.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByTestId("issued-secret")).not.toBeInTheDocument();
  });
});
