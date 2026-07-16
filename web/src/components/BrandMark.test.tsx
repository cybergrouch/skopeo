import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import { BrandMark } from "./BrandMark";

describe("BrandMark", () => {
  it("renders an inline svg, not an img", () => {
    const { container } = render(<BrandMark />);
    expect(container.querySelector("svg")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("draws with currentColor so it inherits the theme text color", () => {
    const { container } = render(<BrandMark />);
    const colored = container.querySelectorAll(
      '[fill="currentColor"], [stroke="currentColor"]',
    );
    expect(colored.length).toBeGreaterThan(0);
  });

  it("passes the className through to the svg", () => {
    const { container } = render(
      <BrandMark className="h-6 w-6 text-foreground" />,
    );
    const svg = container.querySelector("svg");
    expect(svg).toHaveClass("h-6", "w-6", "text-foreground");
  });
});
