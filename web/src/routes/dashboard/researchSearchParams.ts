/**
 * The Research tab's search, expressed as URL query params (#1054).
 *
 * The tab used to hold its search in `useState`, which meant opening a result's public profile — an
 * unmount — threw it away: Back landed on an empty form with no results. Keeping it in the URL fixes
 * that without a second storage mechanism, because #1027 already records the dashboard's path *with*
 * its query string as the place Back returns to. It also makes a search shareable, bookmarkable and
 * reload-proof, which component state never can be.
 *
 * Read and write live here, as pure functions over `URLSearchParams`, so the round trip
 * (form → URL → form) can be tested without rendering anything. They are deliberately
 * Research-specific, and stay that way: `useUrlViewState` (#1056) was extracted from this one real use
 * and is what a new page should adopt, but these params are the search API's own facets — interval
 * strings the form must be able to reproduce — which is more than a reusable field codec should know.
 *
 * Param names are the wire's own (`name`, `sex`, `status`, `age`, `rating`, `sort`, `direction`), so
 * the URL reads like the request it produces. The one exception is `page`, which is 1-based here
 * while the API pages by `offset` — a URL is for humans, and "page 1" is what a human means by the
 * first page.
 */

import { interval } from '@/lib/searchInterval'
import {
  GetApiV1UsersSearchDirection,
  GetApiV1UsersSearchSort,
  GetApiV1UsersSex,
  GetApiV1UsersStatus,
} from '@/api/generated/model'
import type {
  GetApiV1UsersParams,
  GetApiV1UsersSearchParams,
} from '@/api/generated/model'
import type { PlayerSearchFields } from '@/components/PlayerSearchForm'

export type SortColumn = NonNullable<GetApiV1UsersSearchParams['sort']>
export type SortDirection = NonNullable<GetApiV1UsersSearchParams['direction']>

/** The filter facets that live in the URL, named exactly as the API names them. */
const FACET_KEYS = ['name', 'sex', 'status', 'age', 'rating'] as const

/** The first page, 1-based as it appears in the URL. */
const FIRST_PAGE = 1

/** Everything the Research tab needs to rebuild itself from the URL. */
export interface ResearchSearch {
  /** The filters to search with, or null when the URL carries none — the tab's untouched state. */
  applied: GetApiV1UsersParams | null
  /** The same filters as form-input values, so the form always describes the results on screen. */
  fields: PlayerSearchFields
  /** 0-based page index, as the pager and the `offset` calculation want it. */
  page: number
  sort: SortColumn | null
  direction: SortDirection
  /**
   * Params that were present but unusable, named so the tab can say which filters it dropped. A
   * hand-edited or stale link must not produce an unexplained empty tab.
   */
  ignored: string[]
}

/** The value if it is one the API accepts, else null. */
function oneOf<T extends string>(raw: string, allowed: Record<string, T>): T | null {
  const values: string[] = Object.values(allowed)
  return values.includes(raw) ? (raw as T) : null
}

/**
 * Split an interval back into the min/max its two form inputs hold, or null when the form could not
 * reproduce it.
 *
 * The test is a round trip — re-emit through `interval()` and compare — which makes "the form can
 * express this" true by construction rather than by a second, drifting parser. So `[20,30]`,
 * `[20,)` and `(,30]` come back apart, while `(20,30]` does not: the API accepts an exclusive bound
 * (20 < x ≤ 30), but two plain number inputs cannot show one. Keeping it would put a filter on
 * screen that the next keystroke silently rewrites to `[20,30]` — exactly the form-versus-results
 * mismatch #1054 exists to remove — so it is reported as ignored instead of quietly normalised.
 */
function splitInterval(raw: string): { min: string; max: string } | null {
  const match = /^[[(]([^,]*),([^,]*)[\])]$/.exec(raw)
  if (!match) return null
  const min = match[1].trim()
  const max = match[2].trim()
  return interval(min, max) === raw ? { min, max } : null
}

/** Read one facet, recording it as ignored when it is present but not a value the API would take. */
function facet<T extends string>(
  params: URLSearchParams,
  key: string,
  allowed: Record<string, T>,
  ignored: string[],
): T | null {
  const raw = params.get(key)
  if (raw === null || raw === '') return null
  const value = oneOf(raw, allowed)
  if (value === null) ignored.push(key)
  return value
}

/** Read an interval facet into both its form fields and its wire value. */
function range(
  params: URLSearchParams,
  key: string,
  ignored: string[],
): { raw: string; min: string; max: string } | null {
  const raw = params.get(key)
  if (raw === null || raw === '') return null
  const bounds = splitInterval(raw)
  if (bounds === null) {
    ignored.push(key)
    return null
  }
  return { raw, min: bounds.min, max: bounds.max }
}

/** Read the 1-based `page` param as a 0-based index, ignoring anything that is not a page number. */
function pageIndex(params: URLSearchParams, ignored: string[]): number {
  const raw = params.get('page')
  if (raw === null || raw === '') return 0
  const value = Number(raw)
  if (!Number.isInteger(value) || value < FIRST_PAGE) {
    ignored.push('page')
    return 0
  }
  return value - FIRST_PAGE
}

/**
 * Rebuild the Research search from the URL. Unknown or malformed values are dropped and named in
 * `ignored` rather than passed through: a stale `status=RETIRED` would 400 the whole request, which
 * would read as "no results" for every other filter the link got right.
 */
export function readResearchSearch(params: URLSearchParams): ResearchSearch {
  const ignored: string[] = []

  const name = params.get('name')?.trim() ?? ''
  const sex = facet(params, 'sex', GetApiV1UsersSex, ignored)
  const status = facet(params, 'status', GetApiV1UsersStatus, ignored)
  const age = range(params, 'age', ignored)
  const rating = range(params, 'rating', ignored)

  const applied: GetApiV1UsersParams = {}
  if (name) applied.name = name
  if (sex) applied.sex = sex
  if (age) applied.age = age.raw
  if (rating) applied.rating = rating.raw
  if (status) applied.status = status

  return {
    // No facets at all means the tab was never searched, which is what keeps the results card off
    // screen — the same `applied === null` signal the tab used when this lived in component state.
    applied: Object.keys(applied).length > 0 ? applied : null,
    fields: {
      name,
      sex: sex ?? '',
      status: status ?? '',
      ageMin: age?.min ?? '',
      ageMax: age?.max ?? '',
      ratingMin: rating?.min ?? '',
      ratingMax: rating?.max ?? '',
    },
    page: pageIndex(params, ignored),
    sort: facet(params, 'sort', GetApiV1UsersSearchSort, ignored),
    direction: facet(params, 'direction', GetApiV1UsersSearchDirection, ignored) ?? 'ASC',
    ignored,
  }
}

/**
 * The URL params for a search, merged onto [current] so unrelated params survive — notably the
 * dashboard's own `?tab=research` (#323), without which a write here would navigate off the tab.
 *
 * Anything at its default is omitted rather than written, so an untouched tab keeps a clean URL and
 * a shared link carries only what the user actually chose.
 */
export function researchSearchParams({
  current,
  filters,
  page,
  sort,
  direction,
}: {
  current: URLSearchParams
  filters: GetApiV1UsersParams | null
  page: number
  sort: SortColumn | null
  direction: SortDirection
}): URLSearchParams {
  const next = new URLSearchParams(current)
  for (const key of FACET_KEYS) {
    const value = filters?.[key]
    if (value) {
      next.set(key, String(value))
    } else {
      next.delete(key)
    }
  }
  if (sort) {
    next.set('sort', sort)
  } else {
    next.delete('sort')
  }
  // ASC is the default, and the API ignores `direction` without `sort`.
  if (sort && direction === 'DESC') {
    next.set('direction', direction)
  } else {
    next.delete('direction')
  }
  if (page > 0) {
    next.set('page', String(page + FIRST_PAGE))
  } else {
    next.delete('page')
  }
  return next
}
