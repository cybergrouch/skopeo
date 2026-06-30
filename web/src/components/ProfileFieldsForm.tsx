import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  getGetApiV1UsersIdQueryKey,
  getGetApiV1UsersMeQueryKey,
  useGetApiV1UsersId,
  usePatchApiV1UsersId,
} from '@/api/generated/users/users'
import {
  getGetApiV1UsersUserIdNamesQueryKey,
  usePostApiV1UsersUserIdNames,
} from '@/api/generated/names/names'
import { NameCreateRequestType, NameDtoType } from '@/api/generated/model'
import type { NameDto, ProfileRequestSex } from '@/api/generated/model'

/** The single active value of a name [type] for prefilling, or '' when none. */
function activeName(names: NameDto[] | undefined, type: NameDtoType): string {
  return names?.find((n) => n.type === type && n.isActive)?.value ?? ''
}

interface InitialFields {
  displayName: string
  firstName: string
  lastName: string
  sex: string
  dateOfBirth: string
}

/**
 * Editable name + demographics fields, shared by the owner's Profile tab and the admin's player
 * management (#196, #199). Display name goes through the append-only names API (a new value
 * supersedes the old); first/last likewise (kept private — never on the public profile). Sex and
 * date of birth go through PATCH. Both endpoints are authorized self-or-ADMINISTRATOR, so the same
 * form serves the owner and an admin editing someone else.
 */
function Fields({ userId, initial }: { userId: string; initial: InitialFields }) {
  const queryClient = useQueryClient()
  const [displayName, setDisplayName] = useState(initial.displayName)
  const [firstName, setFirstName] = useState(initial.firstName)
  const [lastName, setLastName] = useState(initial.lastName)
  const [sex, setSex] = useState(initial.sex)
  const [dateOfBirth, setDateOfBirth] = useState(initial.dateOfBirth)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const patch = usePatchApiV1UsersId()
  const addName = usePostApiV1UsersUserIdNames()
  const busy = patch.isPending || addName.isPending

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSaved(false)
    setError(null)
    const display = displayName.trim()
    if (!display) {
      setError('Display name is required.')
      return
    }
    try {
      // Demographics via PATCH — only when changed (PATCH leaves untouched fields alone).
      if (sex !== initial.sex || dateOfBirth !== initial.dateOfBirth) {
        const sexValue: ProfileRequestSex = sex === 'Male' || sex === 'Female' ? sex : null
        await patch.mutateAsync({ id: userId, data: { sex: sexValue, dateOfBirth: dateOfBirth || null } })
      }
      // Names via the append-only names API; a new value supersedes the prior active one.
      if (display !== initial.displayName) {
        await addName.mutateAsync({ userId, data: { type: NameCreateRequestType.DISPLAY, value: display } })
      }
      if (firstName.trim() && firstName.trim() !== initial.firstName) {
        await addName.mutateAsync({ userId, data: { type: NameCreateRequestType.FIRST, value: firstName.trim() } })
      }
      if (lastName.trim() && lastName.trim() !== initial.lastName) {
        await addName.mutateAsync({ userId, data: { type: NameCreateRequestType.LAST, value: lastName.trim() } })
      }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: getGetApiV1UsersIdQueryKey(userId) }),
        queryClient.invalidateQueries({ queryKey: getGetApiV1UsersMeQueryKey() }),
        queryClient.invalidateQueries({ queryKey: getGetApiV1UsersUserIdNamesQueryKey(userId) }),
      ])
      setSaved(true)
    } catch {
      setError('Could not save the profile. Check the values and try again.')
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <div className="space-y-1">
        <Label htmlFor="pf-display">Display name</Label>
        <Input
          id="pf-display"
          value={displayName}
          onChange={(e) => {
            setDisplayName(e.target.value)
            setSaved(false)
          }}
        />
      </div>
      <div className="flex flex-wrap gap-3">
        <div className="flex-1 space-y-1">
          <Label htmlFor="pf-first">First name</Label>
          <Input
            id="pf-first"
            value={firstName}
            onChange={(e) => {
              setFirstName(e.target.value)
              setSaved(false)
            }}
          />
        </div>
        <div className="flex-1 space-y-1">
          <Label htmlFor="pf-last">Last name</Label>
          <Input
            id="pf-last"
            value={lastName}
            onChange={(e) => {
              setLastName(e.target.value)
              setSaved(false)
            }}
          />
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        First and last name are private — only you and administrators can see them.
      </p>
      <div className="space-y-1">
        <Label htmlFor="pf-sex">Sex</Label>
        <select
          id="pf-sex"
          value={sex}
          onChange={(e) => {
            setSex(e.target.value)
            setSaved(false)
          }}
          className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
        >
          <option value="">—</option>
          <option value="Male">Male</option>
          <option value="Female">Female</option>
        </select>
      </div>
      <div className="space-y-1">
        <Label htmlFor="pf-dob">Date of birth</Label>
        <Input
          id="pf-dob"
          type="date"
          value={dateOfBirth}
          onChange={(e) => {
            setDateOfBirth(e.target.value)
            setSaved(false)
          }}
        />
      </div>
      <div className="flex items-center gap-2">
        <Button type="submit" size="sm" disabled={busy}>
          {busy ? 'Saving…' : 'Save profile'}
        </Button>
        {saved ? (
          <span className="text-xs text-muted-foreground" role="status">
            Saved
          </span>
        ) : null}
        {error ? (
          <span className="text-xs text-destructive" role="alert">
            {error}
          </span>
        ) : null}
      </div>
    </form>
  )
}

/** Loads the user, then renders the editable fields prefilled from the current values. */
export function ProfileFieldsForm({ userId }: { userId: string }) {
  const userQuery = useGetApiV1UsersId(userId, { query: { enabled: Boolean(userId) } })
  if (userQuery.isLoading || !userQuery.data) {
    return <p className="text-sm text-muted-foreground">Loading…</p>
  }
  const data = userQuery.data
  return (
    <Fields
      key={userId}
      userId={userId}
      initial={{
        displayName: activeName(data.names, NameDtoType.DISPLAY),
        firstName: activeName(data.names, NameDtoType.FIRST),
        lastName: activeName(data.names, NameDtoType.LAST),
        sex: data.sex ?? '',
        dateOfBirth: data.dateOfBirth ?? '',
      }}
    />
  )
}
