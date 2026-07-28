import { useId, useState, type ReactNode } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface CollapsibleCardProps {
  /** Card title — the same node the wrapped card used to pass to CardTitle. */
  title: ReactNode;
  /** Optional subtitle rendered as a CardDescription. */
  description?: ReactNode;
  /** Applied to the inner CardContent so a wrapped card keeps its original body spacing. */
  contentClassName?: string;
  /**
   * When true (#589) the body starts as a compact, height-clipped preview with a "Show all" toggle so
   * a long history doesn't dominate the Profile tab; when false the card renders in full with no toggle
   * (the default — public profiles and admin views are unchanged).
   */
  collapsible?: boolean;
  /** Collapsed preview height (Tailwind max-height class). */
  previewClassName?: string;
  children: ReactNode;
}

/**
 * A Card whose body can start minimized (#589). In collapsible mode the content is clipped to a short
 * preview — a few recent rows — behind a "Show all" / "Show less" toggle; all rows stay in the DOM
 * (the clip is CSS-only) so the toggle just reveals what's already there. Non-collapsible mode renders
 * an ordinary card, so callers that don't opt in are unaffected.
 */
export function CollapsibleCard({
  title,
  description,
  contentClassName,
  collapsible = false,
  previewClassName = "max-h-44",
  children,
}: CollapsibleCardProps) {
  const [open, setOpen] = useState(false);
  const bodyId = useId();

  const header = (
    <CardHeader>
      <CardTitle>{title}</CardTitle>
      {description ? <CardDescription>{description}</CardDescription> : null}
    </CardHeader>
  );

  if (!collapsible) {
    return (
      <Card>
        {header}
        <CardContent className={contentClassName}>{children}</CardContent>
      </Card>
    );
  }

  return (
    <Card>
      {header}
      <div className="relative">
        <CardContent
          id={bodyId}
          className={cn(
            contentClassName,
            !open && cn(previewClassName, "overflow-hidden"),
          )}
        >
          {children}
        </CardContent>
        {!open ? (
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-0 bottom-0 h-10 bg-gradient-to-t from-card to-transparent"
          />
        ) : null}
      </div>
      <CardFooter className="pt-3">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="w-full"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((prev) => !prev)}
        >
          {open ? "Show less" : "Show all"}
        </Button>
      </CardFooter>
    </Card>
  );
}
