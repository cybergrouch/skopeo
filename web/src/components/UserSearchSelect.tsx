import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useDebouncedValue } from '@/hooks/useDebouncedValue'
import { useGetApiV1Users } from '@/api/generated/users/users'
import type { UserSummaryResponse } from '@/api/generated/model'

const MIN_QUERY = 2
// A player code is 6 chars from the Crockford-style alphabet (no I/L/O/U), case-insensitive.
const CODE_RE = /^[0-9A-HJKMNP-TV-Z]{6}$/i

/** Secondary suggestion line — sex · age · NTRP band — to disambiguate similar names (#87). */
function detailLine(user: UserSummaryResponse): string {
  const parts: string[] = []
  if (user.sex) parts.push(user.sex)
  if (user.age != null) parts.push(String(user.age))
  if (user.rating) parts.push(`NTRP ${user.rating.level ?? user.rating.value}`)
  return parts.join(' · ')
}

interface UserSearchSelectProps {
  label: string
  placeholder?: string
  /** Users already chosen elsewhere, hidden from the results. */
  excludeIds?: string[]
  onSelect: (user: UserSummaryResponse) => void
}

/** Typeahead that searches users by name (GET /users?name=) and emits the picked one. */
export function UserSearchSelect({
  label,
  placeholder,
  excludeIds = [],
  onSelect,
}: UserSearchSelectProps) {
  const [term, setTerm] = useState('')
  const debounced = useDebouncedValue(term).trim()
  const enabled = debounced.length >= MIN_QUERY
  // Exact match by player ID when the input looks like a code; otherwise fuzzy name search.
  const isCode = CODE_RE.test(debounced)

  const query = useGetApiV1Users(
    isCode ? { code: debounced.toUpperCase() } : { name: debounced },
    { query: { enabled } },
  )
  const results = (query.data ?? []).filter((u) => !excludeIds.includes(u.id))

  function pick(user: UserSummaryResponse) {
    onSelect(user)
    setTerm('')
  }

  return (
    <div className="space-y-1">
      <Label htmlFor={`search-${label}`}>{label}</Label>
      <Input
        id={`search-${label}`}
        value={term}
        placeholder={placeholder ?? 'Search by name or player ID…'}
        onChange={(e) => setTerm(e.target.value)}
      />
      {enabled && results.length > 0 ? (
        <ul className="rounded-md border" role="listbox">
          {results.map((user) => {
            const detail = detailLine(user)
            return (
              <li key={user.id}>
                <button
                  type="button"
                  className="block w-full px-3 py-2 text-left text-sm hover:bg-accent"
                  onClick={() => pick(user)}
                >
                  <span className="font-medium">
                    {user.displayName ?? user.id}
                  </span>{' '}
                  <span className="text-muted-foreground">
                    · {user.publicCode}
                  </span>
                  {detail ? (
                    <span className="block text-xs text-muted-foreground">
                      {detail}
                    </span>
                  ) : null}
                </button>
              </li>
            )
          })}
        </ul>
      ) : null}
      {enabled && !query.isLoading && results.length === 0 ? (
        <p className="text-sm text-muted-foreground">No matches.</p>
      ) : null}
    </div>
  )
}
