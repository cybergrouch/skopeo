import { Button } from '@/components/ui/button'

/**
 * Prev/Next pager for the look-ahead style player search (#205): no total count — "Next" is enabled
 * while a [hasMore] look-ahead row was fetched. Shared by the Research + Ratings search results.
 */
export function SearchPager({
  page,
  hasMore,
  onPrev,
  onNext,
}: {
  page: number
  hasMore: boolean
  onPrev: () => void
  onNext: () => void
}) {
  if (page === 0 && !hasMore) return null
  return (
    <div className="mt-3 flex items-center justify-between">
      <Button type="button" variant="outline" size="sm" disabled={page === 0} onClick={onPrev}>
        Previous
      </Button>
      <span className="text-sm text-muted-foreground" aria-live="polite">
        Page {page + 1}
      </span>
      <Button type="button" variant="outline" size="sm" disabled={!hasMore} onClick={onNext}>
        Next
      </Button>
    </div>
  )
}
