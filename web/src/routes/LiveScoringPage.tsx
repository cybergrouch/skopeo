import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  useGetApiV1MatchesCodeCode,
  useGetApiV1MatchesMatchIdLive,
  usePostApiV1MatchesMatchIdLiveClaim,
  usePostApiV1MatchesMatchIdLiveEvents,
  usePostApiV1MatchesMatchIdLiveFinalize,
  usePostApiV1MatchesMatchIdLiveUndo,
} from '@/api/generated/matches/matches'
import type { LiveScoreEventRequestKind } from '@/api/generated/model'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import { canManageMatches, canScore } from '@/auth/capabilities'
import { useLockedLandscape } from '@/features/livematch/useLockedLandscape'
import {
  CompletedSets,
  LiveScoringBoard,
  ScoringActions,
} from '@/features/livematch/LiveScoringBoard'

type Side = 'TEAM1' | 'TEAM2'

/**
 * The umpire's scoring view (#911 step 4).
 *
 * **Locked landscape, full-screen, no scrolling in either axis.** The whole page is one flex column
 * sized in `dvh`, with the board taking `flex-1` and everything else `shrink-0`, so nothing can push
 * anything off-screen regardless of aspect ratio. `useLockedLandscape` pins the document, requests
 * fullscreen and attempts the orientation lock; the portrait guard below is the actual guarantee,
 * because iOS Safari does not support `screen.orientation.lock` at all.
 *
 * The page is addressed by the match's **public code** like every other match surface, but the live API
 * is keyed by the internal id — which the public endpoint reveals to callers who have an action to take
 * on the match (#776, widened to scorers here).
 *
 * **Points are never computed here.** The server returns them rendered (`40`, `AD`, or a tiebreak
 * ordinal), so deuce has exactly one implementation and the umpire view and spectator view cannot
 * disagree about the same match.
 */
export function LiveScoringPage() {
  const { code } = useParams<{ code: string }>()
  const navigate = useNavigate()
  const [flipped, setFlipped] = useState(false)
  const [started, setStarted] = useState(false)
  const { isPortrait, enter, exit } = useLockedLandscape()

  const { data: me } = useGetApiV1UsersMe()
  const { data: match, isLoading } = useGetApiV1MatchesCodeCode(code ?? '', {
    query: { enabled: Boolean(code) },
  })

  const matchId = match?.id ?? ''
  const { data: view, refetch } = useGetApiV1MatchesMatchIdLive(matchId, {
    query: { enabled: Boolean(matchId) && started },
  })

  const onError = (message: string) => () => toast.error(message)
  const afterWrite = { onSuccess: () => void refetch() }

  const claim = usePostApiV1MatchesMatchIdLiveClaim({
    mutation: { ...afterWrite, onError: onError('Could not take over scoring') },
  })
  const record = usePostApiV1MatchesMatchIdLiveEvents({
    mutation: { ...afterWrite, onError: onError('Could not record that') },
  })
  const undo = usePostApiV1MatchesMatchIdLiveUndo({
    mutation: { ...afterWrite, onError: onError('Could not undo') },
  })
  const finalize = usePostApiV1MatchesMatchIdLiveFinalize({
    mutation: {
      onSuccess: () => {
        toast.success('Result recorded')
        void exit()
        navigate(`/matches/${code}`)
      },
      onError: onError('Could not finalize'),
    },
  })

  const busy = record.isPending || undo.isPending || claim.isPending || finalize.isPending
  const capabilities = me?.capabilities
  const send = (kind: LiveScoreEventRequestKind, side?: Side) =>
    record.mutate({ matchId, data: { kind, ...(side ? { side } : {}) } })

  if (isPortrait) {
    return (
      <div className="flex h-[100dvh] w-[100dvw] items-center justify-center bg-background p-8 text-center">
        <div>
          <p className="text-2xl font-semibold">Rotate your device</p>
          <p className="mt-2 text-muted-foreground">
            Scoring needs landscape so the whole court fits on screen.
          </p>
        </div>
      </div>
    )
  }

  if (isLoading) {
    return <div className="flex h-[100dvh] items-center justify-center">Loading…</div>
  }

  if (!canScore(capabilities)) {
    return (
      <div className="flex h-[100dvh] flex-col items-center justify-center gap-4 p-8 text-center">
        <p className="text-xl font-semibold">You cannot score this match</p>
        <p className="text-muted-foreground">Scoring needs the SCORER role.</p>
        <Button variant="secondary" onClick={() => navigate(`/matches/${code}`)}>
          Back to the match
        </Button>
      </div>
    )
  }

  if (!match?.id) {
    // The id is only revealed to callers who may act on the match, so its absence means either an
    // unknown code or a viewer without the right — both dead ends for scoring.
    return (
      <div className="flex h-[100dvh] flex-col items-center justify-center gap-4 p-8 text-center">
        <p className="text-xl font-semibold">Match not available for scoring</p>
        <Button variant="secondary" onClick={() => navigate(`/matches/${code}`)}>
          Back to the match
        </Button>
      </div>
    )
  }

  if (!started) {
    // Fullscreen must come from a user gesture, so it cannot happen on mount.
    return (
      <div className="flex h-[100dvh] flex-col items-center justify-center gap-4 p-8 text-center">
        <p className="text-xl font-semibold">Match #{match.matchNumber}</p>
        <Button
          size="lg"
          onClick={async () => {
            await enter()
            claim.mutate({ matchId: match.id as string })
            setStarted(true)
          }}
        >
          Start scoring
        </Button>
      </div>
    )
  }

  if (!view) {
    return <div className="flex h-[100dvh] items-center justify-center">Loading score…</div>
  }

  return (
    <div className="flex h-[100dvh] w-[100dvw] flex-col overflow-hidden bg-background px-[1.5dvw] py-[1dvh]">
      <div className="flex shrink-0 items-center justify-between gap-[1dvw]">
        <CompletedSets view={view} />
        <div className="flex items-center gap-[1dvw] text-[2.2dvh] text-muted-foreground">
          {view.isPaused && <span className="font-semibold text-amber-600">Paused</span>}
          {view.isTiebreak && <span className="font-semibold">Tiebreak</span>}
          {!view.hasStarted && (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => send('MATCH_STARTED')}>
              Start match
            </Button>
          )}
        </div>
      </div>

      <LiveScoringBoard
        view={view}
        flipped={flipped}
        busy={busy}
        onPoint={(side) => send('POINT_WON', side)}
      />

      <ScoringActions
        view={view}
        busy={busy}
        canFinalize={canManageMatches(capabilities)}
        onUndo={() => undo.mutate({ matchId })}
        onEndSet={(side) => send('SET_AWARDED', side)}
        onTiebreak={() => send('TIEBREAK_STARTED')}
        onRetire={(side) => send('RETIRED', side)}
        onDefault={(side) => send('DEFAULTED', side)}
        onPauseResume={() => send(view.isPaused ? 'RESUMED' : 'PAUSED')}
        onFlip={() => setFlipped((f) => !f)}
        onFinalize={() => finalize.mutate({ matchId })}
      />
    </div>
  )
}
