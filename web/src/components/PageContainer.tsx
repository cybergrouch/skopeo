import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/**
 * How wide a page's content is allowed to grow (#742). One place decides, so no route carries its own
 * `max-w-*` literal:
 *
 * - `wide` — data pages (dashboard tabs, the public event / player / match / club pages). Fluid up to
 *   a cap that keeps a 4K monitor from stretching tables into unreadable lines.
 * - `prose` — long-form reading (About). Capped at a comfortable measure; more width would hurt.
 * - `form` — a single centred card (log in, sign up). Deliberately narrow: a login box shouldn't span
 *   the window just because it can.
 *
 * Every size is fluid below its cap and centred, with gutters that grow with the viewport, so small
 * screens get the same single padded column they had before.
 */
const SIZES = {
  wide: "max-w-[1400px]",
  prose: "max-w-3xl",
  form: "max-w-sm",
} as const;

/**
 * The horizontal gutter, which is also every page's guard against a notch (#1076).
 *
 * `viewport-fit=cover` (index.html) lets the page draw into the safe-area insets — which is what the
 * umpire view needs, but it applies to every page. In *landscape* the notch/sensor housing is on a
 * left or right edge and can be ~45-60px wide, so a flat 1rem gutter would put a heading behind it.
 *
 * `max()` keeps the old value as a floor, so nothing shrinks and a device with no insets (every
 * desktop, and any phone in portrait where the browser chrome already covers the band) renders
 * exactly as before. Both sides take the larger of the two insets rather than one each: the content
 * column stays centred, which reads better than a column nudged off-axis by one notch.
 */
const GUTTER = [
  "px-[max(1rem,env(safe-area-inset-left),env(safe-area-inset-right))]",
  "sm:px-[max(1.5rem,env(safe-area-inset-left),env(safe-area-inset-right))]",
  "lg:px-[max(2rem,env(safe-area-inset-left),env(safe-area-inset-right))]",
].join(" ");

export function PageContainer({
  size = "wide",
  className,
  children,
}: {
  size?: keyof typeof SIZES;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div
      className={cn("mx-auto w-full", GUTTER, SIZES[size], className)}
    >
      {children}
    </div>
  );
}
