import { useState } from 'react'

/**
 * Open/closed state for a list of collapsible blocks where one block — the one holding the selection — is
 * open by default (#1156).
 *
 * [focus] is the key of the block holding [selected]. A block the reader toggled keeps the state they gave
 * it; every other block is open exactly when it is the focus. Whenever the selection moves, the focus block's
 * own toggle is forgotten, so selecting into a set always opens it — even one the reader had closed — while
 * the set just left falls back to closed unless the reader opened it themselves.
 */
export function useFollowedDisclosure(focus: string | null, selected: number) {
  const [toggled, setToggled] = useState<ReadonlyMap<string, boolean>>(() => new Map())
  const [seenSelected, setSeenSelected] = useState(selected)

  // Adjusting state while rendering, the documented alternative to an effect: the selection moved, so the
  // block now holding it drops any closed state the reader gave it before.
  if (seenSelected !== selected) {
    setSeenSelected(selected)
    if (focus !== null && toggled.has(focus)) {
      const next = new Map(toggled)
      next.delete(focus)
      setToggled(next)
    }
  }

  const isOpen = (key: string) => toggled.get(key) ?? key === focus
  const toggle = (key: string) => setToggled(new Map(toggled).set(key, !isOpen(key)))
  return { isOpen, toggle }
}
