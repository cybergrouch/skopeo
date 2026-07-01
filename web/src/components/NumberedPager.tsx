import { Button } from '@/components/ui/button'

const ELLIPSIS = -1

/** Windowed page indices (0-based) with ELLIPSIS gaps: first, last, and current ±1 once past 7 pages. */
function pageWindow(page: number, pageCount: number): number[] {
  if (pageCount <= 7) return Array.from({ length: pageCount }, (_, i) => i)
  const out: number[] = [0]
  const lo = Math.max(1, page - 1)
  const hi = Math.min(pageCount - 2, page + 1)
  if (lo > 1) out.push(ELLIPSIS)
  for (let i = lo; i <= hi; i++) out.push(i)
  if (hi < pageCount - 2) out.push(ELLIPSIS)
  out.push(pageCount - 1)
  return out
}

/**
 * Numbered pagination (#232): shows "Showing X–Y of N", Previous/Next, and links to each page (windowed
 * for large result sets), with the current page highlighted. [page] is 0-based; [onPage] navigates.
 */
export function NumberedPager({
  page,
  total,
  pageSize,
  onPage,
}: {
  page: number
  total: number
  pageSize: number
  onPage: (page: number) => void
}) {
  if (total === 0) return null
  const pageCount = Math.max(1, Math.ceil(total / pageSize))
  const start = page * pageSize + 1
  const end = Math.min(total, (page + 1) * pageSize)

  return (
    <div className="flex flex-col gap-2 pt-2 text-sm">
      <div className="text-muted-foreground">
        Showing {start}–{end} of {total}
      </div>
      <div className="flex flex-wrap items-center gap-1">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={page === 0}
          onClick={() => onPage(page - 1)}
        >
          Previous
        </Button>
        {pageWindow(page, pageCount).map((n, i) =>
          n === ELLIPSIS ? (
            <span key={`ellipsis-${i}`} className="px-1 text-muted-foreground">
              …
            </span>
          ) : (
            <Button
              key={n}
              type="button"
              size="sm"
              variant={n === page ? 'default' : 'outline'}
              aria-current={n === page ? 'page' : undefined}
              onClick={() => onPage(n)}
            >
              {n + 1}
            </Button>
          ),
        )}
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={page >= pageCount - 1}
          onClick={() => onPage(page + 1)}
        >
          Next
        </Button>
      </div>
    </div>
  )
}
