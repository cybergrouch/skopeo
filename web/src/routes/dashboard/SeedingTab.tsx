import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { UserSearchSelect } from '@/components/UserSearchSelect'
import {
  getGetApiV1PlayerListsIdQueryKey,
  getGetApiV1PlayerListsIdSeedingQueryKey,
  getGetApiV1PlayerListsQueryKey,
  useDeleteApiV1PlayerListsId,
  useDeleteApiV1PlayerListsIdMembersUserId,
  useGetApiV1PlayerLists,
  useGetApiV1PlayerListsId,
  useGetApiV1PlayerListsIdSeeding,
  usePostApiV1PlayerLists,
  usePostApiV1PlayerListsIdMembers,
  usePostApiV1PlayerListsIdSeeding,
} from '@/api/generated/player-lists/player-lists'
import type {
  GetApiV1UsersParams,
  SeedingEntryResponse,
  UserSummaryResponse,
} from '@/api/generated/model'

const SEXES = ['Male', 'Female'] as const

/** "Female · 34 · NTRP 4.0" — sex, age, NTRP band, omitting whatever is missing. */
function memberMeta(member: UserSummaryResponse): string {
  const parts: string[] = []
  if (member.sex) parts.push(member.sex)
  if (member.age != null) parts.push(String(member.age))
  if (member.rating) parts.push(`NTRP ${member.rating.level ?? member.rating.value}`)
  return parts.join(' · ')
}

/** Build inclusive interval notation from optional min/max (e.g. "[3.0,4.0]", "[3.0,)", "(,30]"). */
function interval(min: string, max: string): string | undefined {
  const lo = min.trim()
  const hi = max.trim()
  if (!lo && !hi) return undefined
  return `${lo ? `[${lo}` : '('},${hi ? `${hi}]` : ')'}`
}

/** Wrap a CSV field in quotes, doubling any embedded quotes (RFC 4180). */
function csvField(value: string): string {
  return `"${value.replace(/"/g, '""')}"`
}

const CSV_HEADER = ['Seed', 'Name', 'Code', 'NTRP', 'Rating', 'Sex', 'Age']

function seedingCsv(entries: SeedingEntryResponse[]): string {
  const rows = entries.map((entry) => [
    entry.seed != null ? String(entry.seed) : '',
    entry.displayName ?? entry.publicCode,
    entry.publicCode,
    entry.ntrpBand ?? '',
    entry.rating,
    entry.sex ?? '',
    entry.age != null ? String(entry.age) : '',
  ])
  return [CSV_HEADER, ...rows].map((row) => row.map(csvField).join(',')).join('\r\n')
}

/** Strip characters that are awkward in filenames; keep it deterministic from the list name. */
function sanitizeFilename(name: string): string {
  return name.trim().replace(/[^a-zA-Z0-9-_]+/g, '-').replace(/^-+|-+$/g, '') || 'list'
}

/**
 * Seeding generator (#111): hosts curate reusable player lists, then generate a deterministic,
 * server-sorted seeding from each list and export it as CSV for a draw sheet.
 */
export function SeedingTab() {
  const queryClient = useQueryClient()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [newName, setNewName] = useState('')
  const [addError, setAddError] = useState<string | null>(null)
  const [sex, setSex] = useState('')
  const [ageMin, setAgeMin] = useState('')
  const [ageMax, setAgeMax] = useState('')
  const [ratingMin, setRatingMin] = useState('')
  const [ratingMax, setRatingMax] = useState('')

  const lists = useGetApiV1PlayerLists()
  const createList = usePostApiV1PlayerLists()
  const deleteList = useDeleteApiV1PlayerListsId()
  const addMember = usePostApiV1PlayerListsIdMembers()
  const removeMember = useDeleteApiV1PlayerListsIdMembersUserId()
  const generate = usePostApiV1PlayerListsIdSeeding()

  const hasSelection = selectedId != null
  const detail = useGetApiV1PlayerListsId(selectedId ?? '', {
    query: { enabled: hasSelection },
  })
  const seeding = useGetApiV1PlayerListsIdSeeding(selectedId ?? '', {
    query: { enabled: hasSelection },
  })

  function invalidateLists() {
    queryClient.invalidateQueries({ queryKey: getGetApiV1PlayerListsQueryKey() })
  }

  function invalidateDetail(id: string) {
    queryClient.invalidateQueries({ queryKey: getGetApiV1PlayerListsIdQueryKey(id) })
  }

  function invalidateSeeding(id: string) {
    queryClient.invalidateQueries({ queryKey: getGetApiV1PlayerListsIdSeedingQueryKey(id) })
  }

  async function onCreate(event: FormEvent) {
    event.preventDefault()
    const name = newName.trim()
    if (!name) return
    const created = await createList.mutateAsync({ data: { name } })
    setNewName('')
    invalidateLists()
    setSelectedId(created.id)
  }

  async function onDeleteList(id: string) {
    await deleteList.mutateAsync({ id })
    setSelectedId(null)
    invalidateLists()
  }

  async function onAddMember(listId: string, user: UserSummaryResponse) {
    try {
      await addMember.mutateAsync({ id: listId, data: { userId: user.id } })
      setAddError(null)
      invalidateDetail(listId)
    } catch {
      setAddError("Couldn't add that player.")
    }
  }

  async function onRemoveMember(listId: string, userId: string) {
    await removeMember.mutateAsync({ id: listId, userId })
    invalidateDetail(listId)
  }

  async function onGenerate(listId: string) {
    await generate.mutateAsync({ id: listId })
    invalidateSeeding(listId)
  }

  function buildFilters(): Pick<GetApiV1UsersParams, 'sex' | 'age' | 'rating'> {
    const filters: Pick<GetApiV1UsersParams, 'sex' | 'age' | 'rating'> = {}
    if (sex) filters.sex = sex as GetApiV1UsersParams['sex']
    const age = interval(ageMin, ageMax)
    if (age) filters.age = age
    const rating = interval(ratingMin, ratingMax)
    if (rating) filters.rating = rating
    return filters
  }

  function onDownloadCsv(listName: string, entries: SeedingEntryResponse[], generatedAt: string) {
    const csv = seedingCsv(entries)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${sanitizeFilename(listName)}-seeding-${generatedAt}.csv`
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
  }

  const allLists = lists.data ?? []
  const members = detail.data?.members ?? []
  const memberIds = members.map((m) => m.id)
  const seedingData = seeding.data
  const entries = seedingData?.entries ?? []
  const hasSeeding = entries.length > 0

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Player lists</CardTitle>
          <CardDescription>
            Curate reusable lists of players, then generate and export a seeding.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {allLists.length === 0 ? (
              <p className="text-sm text-muted-foreground">No lists yet.</p>
            ) : (
              <ul className="space-y-2">
                {allLists.map((list) => (
                  <li key={list.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedId(list.id)}
                      aria-pressed={selectedId === list.id}
                      className={`flex w-full items-center justify-between rounded-md border p-3 text-left text-sm hover:bg-muted/50 ${
                        selectedId === list.id ? 'border-primary' : ''
                      }`}
                    >
                      <span className="font-medium">{list.name}</span>
                      <span className="text-muted-foreground">
                        {list.memberCount} {list.memberCount === 1 ? 'player' : 'players'}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <form onSubmit={onCreate} className="space-y-2 border-t pt-4">
              <Label htmlFor="new-list-name">New list</Label>
              <div className="flex gap-2">
                <Input
                  id="new-list-name"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder="List name"
                />
                <Button type="submit" disabled={!newName.trim()}>
                  Create
                </Button>
              </div>
            </form>
          </div>
        </CardContent>
      </Card>

      {hasSelection ? (
        <Card>
          <CardHeader>
            <CardTitle>{detail.data?.name ?? 'List'}</CardTitle>
            <CardDescription>
              Add or remove members, then generate a seeding.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-6">
              <section className="space-y-2">
                <p className="text-sm font-medium">Members</p>
                {members.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No members yet.</p>
                ) : (
                  <ul className="space-y-2">
                    {members.map((member) => {
                      const meta = memberMeta(member)
                      return (
                        <li
                          key={member.id}
                          className="flex items-center justify-between rounded-md border p-2 text-sm"
                        >
                          <span>
                            <span className="font-medium">
                              {member.displayName ?? member.publicCode}
                            </span>
                            {meta ? (
                              <span className="block text-xs text-muted-foreground">{meta}</span>
                            ) : null}
                          </span>
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => onRemoveMember(selectedId, member.id)}
                          >
                            Remove
                          </Button>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </section>

              <section className="space-y-3 border-t pt-4">
                <p className="text-sm font-medium">Add players</p>
                <div className="space-y-1">
                  <Label htmlFor="s-sex">Sex</Label>
                  <select
                    id="s-sex"
                    value={sex}
                    onChange={(e) => setSex(e.target.value)}
                    className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
                  >
                    <option value="">Any</option>
                    {SEXES.map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                </div>
                <fieldset className="flex items-end gap-2">
                  <div className="space-y-1">
                    <Label htmlFor="s-age-min">Age from</Label>
                    <Input
                      id="s-age-min"
                      inputMode="numeric"
                      className="w-20"
                      value={ageMin}
                      onChange={(e) => setAgeMin(e.target.value)}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="s-age-max">to</Label>
                    <Input
                      id="s-age-max"
                      inputMode="numeric"
                      className="w-20"
                      value={ageMax}
                      onChange={(e) => setAgeMax(e.target.value)}
                    />
                  </div>
                </fieldset>
                <fieldset className="flex items-end gap-2">
                  <div className="space-y-1">
                    <Label htmlFor="s-rating-min">Rating from</Label>
                    <Input
                      id="s-rating-min"
                      inputMode="decimal"
                      className="w-20"
                      value={ratingMin}
                      onChange={(e) => setRatingMin(e.target.value)}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="s-rating-max">to</Label>
                    <Input
                      id="s-rating-max"
                      inputMode="decimal"
                      className="w-20"
                      value={ratingMax}
                      onChange={(e) => setRatingMax(e.target.value)}
                    />
                  </div>
                </fieldset>
                <UserSearchSelect
                  label="Find a player"
                  excludeIds={memberIds}
                  filters={buildFilters()}
                  onSelect={(user) => onAddMember(selectedId, user)}
                />
                {addError ? (
                  <p className="text-sm text-destructive" role="alert">
                    {addError}
                  </p>
                ) : null}
              </section>

              <section className="space-y-3 border-t pt-4">
                <div className="flex flex-wrap gap-2">
                  <Button type="button" onClick={() => onGenerate(selectedId)}>
                    {hasSeeding ? 'Regenerate' : 'Generate seeding'}
                  </Button>
                  {seedingData != null && seedingData.entries.length > 0 ? (
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() =>
                        onDownloadCsv(
                          detail.data?.name ?? 'List',
                          seedingData.entries,
                          seedingData.generatedAt,
                        )
                      }
                    >
                      Download CSV
                    </Button>
                  ) : null}
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => onDeleteList(selectedId)}
                  >
                    Delete list
                  </Button>
                </div>

                {hasSeeding ? (
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b text-left text-muted-foreground">
                          <th className="p-2">Seed</th>
                          <th className="p-2">Name</th>
                          <th className="p-2">Code</th>
                          <th className="p-2">NTRP</th>
                          <th className="p-2">Rating</th>
                          <th className="p-2">Sex</th>
                          <th className="p-2">Age</th>
                        </tr>
                      </thead>
                      <tbody>
                        {entries.map((entry) => (
                          <tr key={entry.position} className="border-b">
                            <td className="p-2">{entry.seed ?? ''}</td>
                            <td className="p-2">{entry.displayName ?? entry.publicCode}</td>
                            <td className="p-2">{entry.publicCode}</td>
                            <td className="p-2">{entry.ntrpBand ?? ''}</td>
                            <td className="p-2">{entry.rating}</td>
                            <td className="p-2">{entry.sex ?? ''}</td>
                            <td className="p-2">{entry.age ?? ''}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    No seeding yet. Generate one from the members above.
                  </p>
                )}
              </section>
            </div>
          </CardContent>
        </Card>
      ) : null}
    </div>
  )
}
