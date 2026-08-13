import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { PageContainer } from "@/components/PageContainer";
import { PublicPageNav } from "@/components/PublicPageNav";

/**
 * The shared frame for the public-by-code pages (#193/#742): the nav, then the page's cards.
 *
 * These pages were each a fixed 384px column, so on a desktop they served a phone-width layout with
 * an empty window either side. The frame is now fluid (see [PageContainer]) and, at `lg` and up,
 * lays the cards out in two columns — a page with a details card and a share card uses the width
 * instead of stacking into one tall ribbon. Below `lg` it is the single padded column it always was.
 *
 * Pass `columns={false}` for a page whose content is one wide block (a table, or the event page's
 * organizer surface), where splitting into two columns would only narrow it.
 */
export function PublicPageShell({
  children,
  columns = true,
}: {
  children: ReactNode;
  columns?: boolean;
}) {
  return (
    <div className="min-h-svh bg-muted/40 pb-8">
      <PageContainer className="pt-10">
        <PublicPageNav />
        <div
          className={cn(
            "mt-4 grid gap-4",
            columns && "lg:grid-cols-2 lg:items-start",
          )}
        >
          {children}
        </div>
      </PageContainer>
    </div>
  );
}
