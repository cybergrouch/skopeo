import { describe, it, expect, beforeEach } from "vitest";
import {
  accentFor,
  applyThemeFavicon,
  faviconDataUri,
  faviconSvg,
} from "./favicon";
import type { ThemeName } from "@/lib/season";

function currentFaviconLink(): HTMLLinkElement | null {
  return document.head.querySelector<HTMLLinkElement>(
    "link[data-theme-favicon]",
  );
}

/** The accent each theme should tint the favicon with (mirrors the doc/palette choices). */
const EXPECTED: Record<ThemeName, string> = {
  offseason: "#212529",
  christmas: "#D92B34",
  ao: "#0080C8",
  clay: "#C1522D",
  grass: "#00703C",
  uso: "#63B233",
};

describe("favicon helper", () => {
  beforeEach(() => {
    document.head
      .querySelectorAll("link[data-theme-favicon]")
      .forEach((l) => l.remove());
  });

  it("embeds the accent color in the generated SVG + data URI", () => {
    expect(faviconSvg("#0080C8")).toContain("#0080C8");
    // Colors are URI-encoded (# → %23) so they don't truncate the data URI.
    expect(faviconDataUri("#0080C8")).toContain(encodeURIComponent("#0080C8"));
    expect(faviconDataUri("#0080C8").startsWith("data:image/svg+xml,")).toBe(
      true,
    );
  });

  it.each(Object.entries(EXPECTED))(
    "sets an icon link tinted with the %s accent",
    (theme, accent) => {
      applyThemeFavicon(theme as ThemeName);
      const link = currentFaviconLink();
      expect(link).not.toBeNull();
      expect(link?.getAttribute("rel")).toBe("icon");
      expect(link?.getAttribute("type")).toBe("image/svg+xml");
      expect(accentFor(theme as ThemeName)).toBe(accent);
      expect(decodeURIComponent(link?.getAttribute("href") ?? "")).toContain(
        accent,
      );
    },
  );

  it("uses the neutral (dark slate) mark for an undefined theme", () => {
    applyThemeFavicon(undefined);
    const href = decodeURIComponent(
      currentFaviconLink()?.getAttribute("href") ?? "",
    );
    expect(href).toContain("#212529");
  });

  it("updates the existing link in place without piling up duplicates", () => {
    applyThemeFavicon("ao");
    applyThemeFavicon("clay");
    applyThemeFavicon("grass");

    const links = document.head.querySelectorAll("link[data-theme-favicon]");
    expect(links.length).toBe(1);
    const href = decodeURIComponent(links[0].getAttribute("href") ?? "");
    expect(href).toContain("#00703C"); // last applied (grass) wins
  });
});
