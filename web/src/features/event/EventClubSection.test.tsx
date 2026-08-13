import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { setupUser } from "@/test/user";
import type { ClubResponse } from "@/api/generated/model";
import { EventClubSection } from "./EventClubSection";

const clubs: ClubResponse[] = [
  { id: "c1", name: "Alpha TC", publicCode: "AAA", isActive: true, owners: [] },
  { id: "c2", name: "Beta TC", publicCode: "BBB", isActive: true, owners: [] },
];

// One component serves both audiences (#741), so its two faces are worth pinning down directly:
// what a viewer who can't change the club sees, and what a match manager can do with it.
describe("EventClubSection", () => {
  it("states the club as a read-only fact when there is no handler", () => {
    render(<EventClubSection clubName="Alpha TC" />);
    expect(screen.getByText("Club")).toBeInTheDocument();
    expect(screen.getByText("Alpha TC")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("renders nothing read-only for a clubless event — there is no fact to state (#313)", () => {
    const { container } = render(<EventClubSection clubName={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("offers the club as a select for a manager, preselecting the current one (#319)", () => {
    render(
      <EventClubSection clubId="c2" clubs={clubs} onChange={() => {}} />,
    );
    expect(screen.getByLabelText("Club")).toHaveValue("c2");
    expect(screen.getByRole("option", { name: "No club (Open)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Beta TC" })).toBeInTheDocument();
  });

  it("still offers the Open choice when no clubs exist yet", () => {
    // The manager surface loads clubs separately, so the select can render before (or without) them.
    render(<EventClubSection onChange={() => {}} />);
    expect(screen.getAllByRole("option")).toHaveLength(1);
    expect(screen.getByRole("option", { name: "No club (Open)" })).toBeInTheDocument();
  });

  it("reports a chosen club, and the empty option that clears it", async () => {
    const onChange = vi.fn();
    const user = setupUser();
    render(
      <EventClubSection clubId="c1" clubs={clubs} onChange={onChange} />,
    );

    await user.selectOptions(screen.getByLabelText("Club"), "c2");
    expect(onChange).toHaveBeenCalledWith("c2");

    await user.selectOptions(screen.getByLabelText("Club"), "");
    expect(onChange).toHaveBeenCalledWith("");
  });

  it("locks the select while a save is in flight or the event is closed to changes", () => {
    render(<EventClubSection clubs={clubs} onChange={() => {}} disabled />);
    expect(screen.getByLabelText("Club")).toBeDisabled();
  });
});
