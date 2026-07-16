import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { BrandLogo } from "./BrandLogo";

describe("BrandLogo", () => {
  it("renders the wordmark and an inline svg icon (not an img)", () => {
    const { container } = render(<BrandLogo />);
    expect(screen.getByText("Skopeo")).toBeInTheDocument();
    expect(container.querySelector("svg")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("colors the mark with the theme foreground token", () => {
    const { container } = render(<BrandLogo />);
    expect(container.querySelector("svg")).toHaveClass("text-foreground");
  });

  it("applies a custom class", () => {
    const { container } = render(<BrandLogo className="text-lg" />);
    expect(container.firstChild).toHaveClass("text-lg");
  });
});
