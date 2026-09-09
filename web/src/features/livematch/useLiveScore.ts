import { useEffect, useState } from 'react'
import { doc, getFirestore, onSnapshot } from 'firebase/firestore'
import { firebaseApp } from '@/lib/firebase'

/** The spectator-facing document the server writes on every score change (#911). */
export interface LiveScore {
  publicCode: string
  sequence: number
  pointsTeam1: string
  pointsTeam2: string
  gamesTeam1: number
  gamesTeam2: number
  sets: {
    gamesTeam1: number
    gamesTeam2: number
    tiebreakTeam1Points: number | null
    tiebreakTeam2Points: number | null
  }[]
  isTiebreak: boolean
  isPaused: boolean
  hasStarted: boolean
  serverId: string | null
  outcomeKind: string | null
  outcomeWinner: string | null
}

/**
 * Watch a match's live score, pushed rather than polled (#911 §3).
 *
 * The subscription is to Firestore directly, **not** to our API. That is the whole reason Firestore is
 * here: Cloud Run runs `--min-instances=1 --max-instances=2`, so an SSE or WebSocket stream held by one
 * instance would never see a write that landed on the other — broken about half the time, and worse
 * under exactly the load that makes a match worth watching.
 *
 * Keyed by the match's **public code**, which is what a spectator has; the internal id is deliberately
 * withheld from ordinary viewers, and a world-readable collection path is no place for it.
 *
 * Returns `null` while there is no live document, which is the normal state for the overwhelming
 * majority of matches. Callers should render nothing rather than an empty scoreboard.
 *
 * **Reconnect needs no special handling.** The document is the current state rather than a stream of
 * events, so a spectator who was offline simply reads the latest one — there is no gap to fill and no
 * ordering to reconstruct. That is the main practical payoff of broadcasting state instead of
 * keystrokes, and it is what makes this hook as short as it is.
 */
export function useLiveScore(publicCode: string | undefined): LiveScore | null {
  // Keyed by the code rather than reset on change: clearing the score from inside the effect would be a
  // synchronous set-state in an effect body, and it would also mean a moment where the previous match's
  // score is still on screen under the new match's name. Comparing the key makes that impossible.
  const [state, setState] = useState<{ code: string; score: LiveScore | null }>({
    code: '',
    score: null,
  })

  useEffect(() => {
    if (!publicCode) return

    // Firestore is optional infrastructure: a deployment without it configured still serves everything
    // else, and a spectator simply sees no live scoreboard. Failing loudly here would break the whole
    // public match page over a feature most matches never use.
    let unsubscribe: (() => void) | undefined
    try {
      unsubscribe = onSnapshot(
        doc(getFirestore(firebaseApp), 'liveScores', publicCode),
        (snapshot) =>
          setState({
            code: publicCode,
            score: snapshot.exists() ? (snapshot.data() as LiveScore) : null,
          }),
        () => setState({ code: publicCode, score: null }),
      )
    } catch {
      // Nothing to set: state is still keyed to a different code, so the read below already yields null.
      // Setting it here would be a synchronous state update from an effect body for no gain.
    }
    return () => unsubscribe?.()
  }, [publicCode])

  return state.code === publicCode ? state.score : null
}
