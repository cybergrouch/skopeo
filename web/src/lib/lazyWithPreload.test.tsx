import { Suspense } from "react";
import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { lazyWithPreload } from "./lazyWithPreload";

function Greeting({ name }: { name: string }) {
  return <p>hello {name}</p>;
}

describe("lazyWithPreload", () => {
  it("renders the loaded component behind Suspense", async () => {
    const Lazy = lazyWithPreload(() => Promise.resolve(Greeting));
    render(
      <Suspense fallback={<p>loading</p>}>
        <Lazy name="Ana" />
      </Suspense>,
    );
    expect(screen.getByText("loading")).toBeInTheDocument();
    expect(await screen.findByText("hello Ana")).toBeInTheDocument();
  });

  it("loads once, whether preload or the first render starts it", async () => {
    const load = vi.fn(() => Promise.resolve(Greeting));
    const Lazy = lazyWithPreload(load);
    expect(load).not.toHaveBeenCalled();

    const first = Lazy.preload();
    expect(Lazy.preload()).toBe(first);
    render(
      <Suspense fallback={null}>
        <Lazy name="Ben" />
      </Suspense>,
    );
    expect(await screen.findByText("hello Ben")).toBeInTheDocument();
    expect(load).toHaveBeenCalledTimes(1);
  });
});
