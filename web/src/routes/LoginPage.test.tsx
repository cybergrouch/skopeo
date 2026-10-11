import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { FirebaseError } from "firebase/app";
import { LoginPage } from "./LoginPage";

const {
  signInWithEmail,
  signInWithGoogle,
  signInWithFacebook,
  navigateMock,
  useFbFlag, authState } = vi.hoisted(() => ({
  signInWithEmail: vi.fn(),
  signInWithGoogle: vi.fn(),
  signInWithFacebook: vi.fn(),
  navigateMock: vi.fn(),
  useFbFlag: vi.fn(),
  authState: { user: null as null | { uid: string }, initializing: false },
}));

vi.mock("@/auth/useAuth", () => ({
  useAuth: () => ({ ...authState, signInWithEmail, signInWithGoogle, signInWithFacebook }),
}));

vi.mock("@/api/generated/settings/settings", () => ({
  useGetApiV1SettingsFacebookLogin: useFbFlag,
}));

vi.mock("react-router-dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router-dom")>();
  return { ...actual, useNavigate: () => navigateMock };
});

function renderLogin() {
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe("LoginPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState.user = null;
    authState.initializing = false;
    // Facebook login enabled by default (#647).
    useFbFlag.mockReturnValue({ data: { enabled: true } });
  });

  it("hides the Facebook button when the feature flag is disabled (#647)", () => {
    useFbFlag.mockReturnValue({ data: { enabled: false } });
    renderLogin();
    expect(
      screen.queryByRole("button", { name: /continue with facebook/i }),
    ).not.toBeInTheDocument();
    // Google stays available.
    expect(
      screen.getByRole("button", { name: /continue with google/i }),
    ).toBeInTheDocument();
  });

  it("signs in and navigates to the default destination", async () => {
    signInWithEmail.mockResolvedValue({});
    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText("Email"), "roger@example.com");
    await user.type(screen.getByLabelText("Password"), "secret123");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));

    await waitFor(() =>
      expect(signInWithEmail).toHaveBeenCalledWith(
        "roger@example.com",
        "secret123",
      ),
    );
    expect(navigateMock).toHaveBeenCalledWith("/dashboard", { replace: true });
  });

  it("shows an error and does not navigate on bad credentials", async () => {
    signInWithEmail.mockRejectedValue(
      new FirebaseError("auth/invalid-credential", "raw"),
    );
    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText("Email"), "roger@example.com");
    await user.type(screen.getByLabelText("Password"), "wrong");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));

    expect(
      await screen.findByText("Incorrect email or password."),
    ).toBeInTheDocument();
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it("signs in with Google", async () => {
    signInWithGoogle.mockResolvedValue({});
    const user = userEvent.setup();
    renderLogin();

    await user.click(
      screen.getByRole("button", { name: /continue with google/i }),
    );

    await waitFor(() => expect(signInWithGoogle).toHaveBeenCalled());
    expect(navigateMock).toHaveBeenCalledWith("/dashboard", { replace: true });
  });

  it("signs in with Facebook", async () => {
    signInWithFacebook.mockResolvedValue({});
    const user = userEvent.setup();
    renderLogin();

    await user.click(
      screen.getByRole("button", { name: /continue with facebook/i }),
    );

    await waitFor(() => expect(signInWithFacebook).toHaveBeenCalled());
    expect(navigateMock).toHaveBeenCalledWith("/dashboard", { replace: true });
  });

  // #1141: the public pages' signed-out "← Back" lands here, and a visitor whose session that page could
  // not see is in fact signed in. They pass straight through instead of being asked to sign in again.
  it("forwards an already signed-in visitor to the dashboard instead of showing the form", () => {
    authState.user = { uid: "u1" };
    render(
      <MemoryRouter initialEntries={["/login"]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/dashboard" element={<p>dashboard</p>} />
          <Route path="/players/:code" element={<p>player page</p>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText("dashboard")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /log in/i })).not.toBeInTheDocument();
  });

  it("forwards a signed-in visitor to where they were heading, when the link said so", () => {
    authState.user = { uid: "u1" };
    render(
      <MemoryRouter initialEntries={[{ pathname: "/login", state: { from: { pathname: "/players/ABC123" } } }]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/dashboard" element={<p>dashboard</p>} />
          <Route path="/players/:code" element={<p>player page</p>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText("player page")).toBeInTheDocument();
  });

  it("shows nothing while auth is still resolving, rather than flashing the form", () => {
    authState.initializing = true;
    const { container } = renderLogin();
    expect(container).toBeEmptyDOMElement();
  });
});
