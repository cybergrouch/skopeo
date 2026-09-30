import { cn } from "@/lib/utils";

/** What the menu needs of a dashboard section: its `?tab=` value and its label. */
export interface NavSection {
  value: string;
  label: string;
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
  className,
}: {
  sections: readonly NavSection[];
  active: string;
  onSelect: (value: string) => void;
  className?: string;
}) {
  return (
    <nav aria-label="Dashboard" className={cn("flex flex-col gap-1", className)}>
      {sections.map((section) => (
        <button
          key={section.value}
          type="button"
          onClick={() => onSelect(section.value)}
          aria-current={section.value === active ? "page" : undefined}
          className={cn(
            "rounded-md px-3 py-2 text-left text-sm font-medium transition-colors hover:bg-muted",
            section.value === active && "bg-muted",
          )}
        >
          {section.label}
        </button>
      ))}
    </nav>
  );
}
