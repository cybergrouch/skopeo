import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useDebouncedValue } from '@/hooks/useDebouncedValue'
import { useGetApiV1Users } from '@/api/generated/users/users'
import type { UserSummaryResponse } from '@/api/generated/model'

const MIN_QUERY = 2

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

  const query = useGetApiV1Users(
    { name: debounced },
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
        placeholder={placeholder ?? 'Search by name…'}
        onChange={(e) => setTerm(e.target.value)}
      />
      {enabled && results.length > 0 ? (
        <ul className="rounded-md border" role="listbox">
          {results.map((user) => (
            <li key={user.id}>
              <button
                type="button"
                className="block w-full px-3 py-2 text-left text-sm hover:bg-accent"
                onClick={() => pick(user)}
              >
                {user.displayName ?? user.id}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {enabled && !query.isLoading && results.length === 0 ? (
        <p className="text-sm text-muted-foreground">No matches.</p>
      ) : null}
    </div>
  )
}
