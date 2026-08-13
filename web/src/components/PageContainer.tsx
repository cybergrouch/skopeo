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
      className={cn(
        "mx-auto w-full px-4 sm:px-6 lg:px-8",
        SIZES[size],
        className,
      )}
    >
      {children}
    </div>
  );
}
