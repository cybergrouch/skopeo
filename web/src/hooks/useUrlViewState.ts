/**
 * View state held in the URL's query string instead of `useState` (#1056).
 *
 * The generalisation of `routes/dashboard/researchSearchParams.ts` (#1054), extracted from that one
 * real use once it shipped. Component state resets on unmount and on reload, which for the public,
 * share-by-code pages means a link can never describe what the sender was looking at: "this club's
 * finalized events, page 3" or "this player's matches vs 4.0 opponents" is not expressible today.
 * Keeping the same state in the query string makes those pages **reload-proof and shareable**, with
 * no second storage mechanism.
 *
 * ## What it is NOT for
 *
 * **This does not change the "← Back" control, and must not be "fixed" to.** `isOriginCandidate`
 * deliberately returns false for every public path (#1027), so a public page is never recorded as an
 * origin and its query string is never stored — Back returns to where the excursion *started*, which
 * is the point of #1027. The payoff here is reload-proofing and shareability. (For dashboard tabs,
 * restoring *is* a side benefit, because the dashboard path is recorded with its query string.)
 *
 * `ShareCard` also keeps sharing the bare canonical URL (`${origin}/clubs/${code}`). A QR code must
 * not pin its recipient to page 3 of a filter they never chose.
 *
 * ## The opt-in boundary
 *
 * Adoption is per state item, never "all `useState` → URL":
 *
 * > State describing **what data is shown** belongs in the URL. State describing **how the user is
 * > poking at it** does not.
 *
 * Paging, filters, sort and search are the former. Expansion, hover, open menus and in-progress form
 * input are the latter — `RatingHistoryCard` puts its `page` here and keeps its `expanded` row set in
 * `useState`, because a URL carrying a set of open accordions would be grotesque to share and would
 * reopen rows nobody asked to reopen.
 *
 * Only **settled** values may be passed in. `PlayerMatchesPage` keeps its raw `searchInput` local and
 * writes only the `useDebouncedValue` result, so typing does not rewrite the URL per keystroke.
 *
 * ## Conventions, all inherited from #1054/#323
 *
 * - **Writes `replace`, never `push`** — a filter tweak is not its own Back step (#323), so the
 *   browser's Back keeps behaving like the on-page "← Back" control.
 * - **Defaults are omitted**, so an untouched page has a clean, pristine URL.
 * - **Every value is validated on read**; an unusable one falls back to the default and is named in
 *   `ignored` so the page can say so (see `IgnoredParamsNotice`) rather than erroring or showing an
 *   unexplained empty view.
 * - **Params unrelated to this instance survive** a write, which is what lets `?tab=research` and
 *   three sibling cards coexist with each other.
 *
 * ## Namespacing
 *
 * `ClubPage` renders `ClubEventsCard` three times — Upcoming / Unfinalized / Finalized — and each
 * card pages independently. A shared `?page=` would page all three at once, so every instance may
 * carry a namespace that prefixes its params: `?upcoming.page=2&finalized.page=5`. A page that owns
 * its state alone (`PlayerMatchesPage`) passes none and gets bare `?page=2&search=ben`.
 */

import { useSearchParams } from 'react-router-dom'

/**
 * How one value crosses the URL boundary: its default, how to read it, and how to write it.
 *
 * `parse` returns null for a value the view cannot use — that is what lands the param in `ignored`
 * instead of throwing or passing nonsense to the API. `format` returns null to omit the param, which
 * is how a default keeps the URL clean.
 */
export interface UrlViewField<T> {
  /** The value used when the param is absent, empty, or unusable. Never written to the URL. */
  fallback: T
  /** The parsed value, or null when the raw text is not something the view can show. */
  parse: (raw: string) => T | null
  /** The text to write, or null to omit the param entirely. */
  format: (value: T) => string | null
}

/** One field per key of [V] — the spec a component hands the hook. */
export type UrlViewFields<V> = { [K in keyof V]: UrlViewField<V[K]> }

/** What the hook hands back. */
export interface UrlViewState<V> {
  /** The current values, read from the URL with defaults filled in for anything absent or unusable. */
  view: V
  /**
   * Merge a patch into the URL (`replace`, defaults omitted, unrelated params untouched).
   *
   * A patch rather than per-field setters so one user action is one write: selecting a filter that
   * also resets the page is `setView({ band, page: 0 })`, which two sequential writes could not do
   * without one clobbering the other.
   */
  setView: (patch: Partial<V>) => void
  /**
   * The param names — namespaced exactly as they appear in the URL — that were present but unusable.
   * Render them; a hand-edited, stale or truncated link must explain itself.
   */
  ignored: string[]
}

/** The param name a field occupies: namespaced per instance, bare when the page owns it alone. */
export function urlViewParamName(key: string, ns?: string): string {
  return ns ? `${ns}.${key}` : key
}

/**
 * The fields with their value types erased, for the loops that must treat every field alike.
 *
 * One assertion, here, rather than at each use: a generic `UrlViewFields<V>` cannot be read as a
 * uniform record without it, because `format` makes the type parameter contravariant.
 */
function erase<V>(fields: UrlViewFields<V>): Record<string, UrlViewField<unknown>> {
  return fields as unknown as Record<string, UrlViewField<unknown>>
}

/**
 * Read a view's values out of [params]. Anything absent or empty takes its default silently; anything
 * present but unusable takes its default and is named in `ignored`.
 */
export function readUrlView<V extends object>({
  params,
  fields,
  ns,
}: {
  params: URLSearchParams
  fields: UrlViewFields<V>
  ns?: string
}): { values: V; ignored: string[] } {
  const ignored: string[] = []
  const entries = Object.entries(erase(fields)).map(([key, field]): [string, unknown] => {
    const name = urlViewParamName(key, ns)
    const raw = params.get(name)
    if (raw === null || raw === '') return [key, field.fallback]
    const parsed = field.parse(raw)
    if (parsed === null) {
      ignored.push(name)
      return [key, field.fallback]
    }
    return [key, parsed]
  })
  return { values: Object.fromEntries(entries) as V, ignored }
}

/**
 * The params for [values], merged onto [current] so everything this instance does not own survives —
 * the dashboard's `?tab=`, and the two sibling cards' own pages.
 *
 * Every owned param is visited, set or deleted: writing only the non-defaults would leave a previous
 * page number behind in the URL forever.
 */
export function urlViewParams<V extends object>({
  current,
  values,
  fields,
  ns,
}: {
  current: URLSearchParams
  values: V
  fields: UrlViewFields<V>
  ns?: string
}): URLSearchParams {
  const next = new URLSearchParams(current)
  const erased = values as Record<string, unknown>
  for (const [key, field] of Object.entries(erase(fields))) {
    const name = urlViewParamName(key, ns)
    const text = field.format(erased[key])
    if (text === null) {
      next.delete(name)
    } else {
      next.set(name, text)
    }
  }
  return next
}

/** See the module comment: URL-backed view state, namespaced per instance, written with `replace`. */
export function useUrlViewState<V extends object>({
  fields,
  ns,
}: {
  fields: UrlViewFields<V>
  ns?: string
}): UrlViewState<V> {
  const [searchParams, setSearchParams] = useSearchParams()
  const { values, ignored } = readUrlView({ params: searchParams, fields, ns })

  function setView(patch: Partial<V>) {
    const next = urlViewParams({
      current: searchParams,
      values: { ...values, ...patch },
      fields,
      ns,
    })
    // Replace, matching `?tab=` (#323): a page or filter change is a new view of the same place, not
    // a new place, so it must not become a Back step.
    setSearchParams(next, { replace: true })
  }

  return { view: values, setView, ignored }
}

/** The first page as it appears in the URL: 1, because that is what a human means by "page 1". */
const FIRST_PAGE = 1

/**
 * A pager's 0-based page index, written 1-based (`?page=3` is the third page) exactly as #1054 writes
 * the Research tab's. A URL is for humans; `offset` is for the API.
 */
export function pageField(): UrlViewField<number> {
  return {
    fallback: 0,
    parse: (raw) => {
      const value = Number(raw)
      // `page=0`, `page=-2`, `page=1.5` and `page=abc` are all unusable rather than clamped: a link
      // that says something impossible should say so, not silently show page 1.
      if (!Number.isInteger(value) || value < FIRST_PAGE) return null
      return value - FIRST_PAGE
    },
    format: (value) => (value > 0 ? String(value + FIRST_PAGE) : null),
  }
}

/**
 * One of a fixed set of values, with [fallback] (blank by default) meaning "no filter" — the shape a
 * `<select>` with an "All …" option holds. A value outside the set is ignored rather than passed on,
 * because the API would reject it and the rejection would read as "no results".
 */
export function choiceField(allowed: readonly string[], fallback = ''): UrlViewField<string> {
  return {
    fallback,
    parse: (raw) => (allowed.includes(raw) ? raw : null),
    format: (value) => (value === fallback ? null : value),
  }
}

/**
 * Free text — a search term. Trimmed, so a padded link and a typed search are the same view; any text
 * is usable, so nothing here is ever ignored.
 *
 * Pass only a settled value (see `useDebouncedValue`); the raw input stays in `useState`.
 */
export function textField(fallback = ''): UrlViewField<string> {
  return {
    fallback,
    parse: (raw) => raw.trim(),
    format: (value) => (value.trim() === fallback ? null : value.trim()),
  }
}
