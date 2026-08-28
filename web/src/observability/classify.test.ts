import { describe, expect, it } from "vitest";
import { FirebaseError } from "firebase/app";
import { isUnexpected } from "./classify";

const httpError = (status: number) => ({ response: { status } });

describe("isUnexpected", () => {
  it("treats 5xx as a fault, matching #751's outage definition", () => {
    expect(isUnexpected(httpError(500))).toBe(true);
    expect(isUnexpected(httpError(503))).toBe(true);
  });

  it("does not treat deliberate 4xx as faults", () => {
    // These are this API's normal contract: 409 "match is not rated yet", 404 for a mistyped public
    // code (those pages are shared by QR, so stale links are expected traffic), 400 form validation.
    expect(isUnexpected(httpError(400))).toBe(false);
    expect(isUnexpected(httpError(403))).toBe(false);
    expect(isUnexpected(httpError(404))).toBe(false);
    expect(isUnexpected(httpError(409))).toBe(false);
  });

  it("does not treat 401 as a fault, because public pages generate them by design", () => {
    // ClubPage/MatchPage/PlayerProfilePage all call useGetApiV1UsersMe() unguarded so they can spot a
    // manager while still rendering for a logged-out visitor. Every anonymous visit produces a 401.
    expect(isUnexpected(httpError(401))).toBe(false);
  });

  it("treats a request that never got a response as a fault", () => {
    // Network down, CORS rejection, DNS, dead revision — indistinguishable here, all worth knowing.
    expect(isUnexpected(new Error("Network Error"))).toBe(true);
    expect(isUnexpected({ message: "timeout" })).toBe(true);
  });

  it("treats an unrecognised thrown value as a fault, by default", () => {
    // An unknown shape is exactly the case we have not thought about, so it should surface.
    expect(isUnexpected(new TypeError("x is not a function"))).toBe(true);
    expect(isUnexpected("some string")).toBe(true);
  });

  it("reports Firebase configuration and provider faults", () => {
    // The #647 shape: a sign-in provider disabled in the console. This is the only place such a
    // failure is observable — a federated sign-in never reaches our API, so no 401 is ever recorded.
    expect(
      isUnexpected(new FirebaseError("auth/operation-not-allowed", "disabled")),
    ).toBe(true);
    expect(
      isUnexpected(new FirebaseError("auth/unauthorized-domain", "bad domain")),
    ).toBe(true);
    expect(
      isUnexpected(new FirebaseError("auth/network-request-failed", "offline")),
    ).toBe(true);
  });

  it("does not report Firebase user outcomes", () => {
    // A steady drip of wrong-password events would exhaust a quota and bury the codes above.
    expect(
      isUnexpected(new FirebaseError("auth/wrong-password", "nope")),
    ).toBe(false);
    expect(
      isUnexpected(new FirebaseError("auth/popup-closed-by-user", "cancelled")),
    ).toBe(false);
    expect(
      isUnexpected(new FirebaseError("auth/email-already-in-use", "taken")),
    ).toBe(false);
  });
});
