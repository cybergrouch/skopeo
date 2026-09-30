import { useId } from "react";
import { Link } from "react-router-dom";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";

/** What the menu needs of a dashboard section: its `?tab=` value and its label. */
export interface NavSection {
  value: string;
  label: string;
}

/** A club shortcut in the menu's "My clubs" group (#1096). */
export interface NavClub {
  name: string;
  publicCode: string;
}

/**
 * The "My clubs" group: the clubs the viewer owns, each linking to its public page, where every club
 * is organized (#794). Its open state is held by the caller so the rail and the drawer agree, and so
 * it survives the drawer unmounting its content on close.
 */
export interface NavClubsGroup {
  clubs: readonly NavClub[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * The dashboard's section menu (#1095). One component, rendered in the small-screen drawer and in the
 * persistent rail from `md:` up, so both breakpoints show the same capability-gated `sections[]` in the
 * same order — only where the list sits differs.
 */
export function SectionNav({
  sections,
  active,
  onSelect,
  onIntent,
  clubs,
  className,
}: {
  sections: readonly NavSection[];
  active: string;
  onSelect: (value: string) => void;
  /** The viewer is about to pick [value] — pointer over, or focus on, its item (#1092). */
  onIntent?: (value: string) => void;
  clubs?: NavClubsGroup;
  className?: string;
}) {
  const clubsId = useId();
  return (
    <nav aria-label="Dashboard" className={cn("flex flex-col gap-1", className)}>
      {sections.map((section) => (
        <button
          key={section.value}
          type="button"
          onClick={() => onSelect(section.value)}
          onPointerEnter={() => onIntent?.(section.value)}
          onFocus={() => onIntent?.(section.value)}
          aria-current={section.value === active ? "page" : undefined}
          className={cn(
            "rounded-md px-3 py-2 text-left text-sm font-medium transition-colors hover:bg-muted",
            section.value === active && "bg-muted",
          )}
        >
          {section.label}
        </button>
      ))}

      {/* Owning no club renders no group at all, not an empty one. The heading is a disclosure that
          navigates nowhere; it also marks the entries below as venues, not features. The entries are
          shortcuts, never gates: the club page re-derives what the viewer may do there (#789). */}
      {clubs && clubs.clubs.length > 0 ? (
        <div className="mt-2 border-t pt-2">
          <button
            type="button"
            aria-expanded={clubs.open}
            aria-controls={clubsId}
            onClick={() => clubs.onOpenChange(!clubs.open)}
            className="flex w-full items-center justify-between rounded-md px-3 py-2 text-left text-sm font-semibold text-muted-foreground transition-colors hover:bg-muted"
          >
            My clubs
            <ChevronDown
              aria-hidden
              className={cn("size-4 transition-transform", clubs.open && "rotate-180")}
            />
          </button>
          <ul id={clubsId} hidden={!clubs.open} className="flex flex-col gap-1">
            {clubs.clubs.map((club) => (
              <li key={club.publicCode}>
                <Link
                  to={`/clubs/${club.publicCode}`}
                  className="block rounded-md px-3 py-2 pl-6 text-sm font-medium transition-colors hover:bg-muted"
                >
                  {club.name}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </nav>
  );
}
