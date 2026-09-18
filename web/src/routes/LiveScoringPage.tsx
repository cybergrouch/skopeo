import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  useDeleteApiV1MatchesMatchIdLiveClaim,
  useGetApiV1MatchesCodeCode,
  useGetApiV1MatchesMatchIdLive,
  usePostApiV1MatchesMatchIdLiveClaim,
  usePostApiV1MatchesMatchIdLiveEvents,
  usePostApiV1MatchesMatchIdLiveFinalize,
  usePostApiV1MatchesMatchIdLiveUndo,
} from '@/api/generated/matches/matches'
import type {
  LiveMatchResponse,
  LiveScoreEventRequestKind,
  MatchPublicPlayer,
} from '@/api/generated/model'
import { useGetApiV1UsersMe } from '@/api/generated/users/users'
import { canManageMatches, canScore } from '@/auth/capabilities'
import { useLockedLandscape } from '@/features/livematch/useLockedLandscape'
import {
  CompletedSets,
  LiveScoringBoard,
  ScoringActions,
  ServerControl,
} from '@/features/livematch/LiveScoringBoard'
import { MatchClock } from '@/features/livematch/MatchClock'

type Side = 'TEAM1' | 'TEAM2'

/** A side's players as one label, e.g. "Ana & Bea". Falls back so a placeholder still reads as someone. */
/**
 * What the centred set label reads (#1074).
 *
 * `sets.length + 1` is the set in progress, which is correct *while one is being played* and wrong at
 * both edges. Between sets nothing is in progress — the next set has not begun — so announcing "Set 2"
 * the instant set 1 is awarded is the same overclaim that made the old layout confusing, just relocated.
 * Saying "next" instead is what the umpire is actually looking at: a decision point (#984), not a set.
 *
 * Before the match starts the plain number is kept. #1070 puts the "press Start match" guidance in its
 * own prompt, so repeating it here would say the same thing twice in one band.
 */
function setLabel(view: LiveMatchResponse): string {
  const current = view.sets.length + 1
  if (view.isBetweenSets) return `Next: Set ${current}`
  return `Set ${current}`
}

function sideName(players: MatchPublicPlayer[] | undefined): string {
  const names = (players ?? []).map((p) => p.displayName ?? p.publicCode ?? 'Unknown')
  return names.length > 0 ? names.join(' & ') : 'Unknown'
}

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
  const release = useDeleteApiV1MatchesMatchIdLiveClaim({
    mutation: { onError: onError('Could not release the match') },
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

  /**
   * Leave without finalizing (#937).
   *
   * Three things, in this order. Release the claim, because somebody who has left the view is not
   * scoring the match and a stale claim would tell the next umpire otherwise. Release the display locks,
   * or the whole app stays full-screen and rotation-locked after navigating away. Then go back.
   *
   * The score is already persisted server-side, so nothing is lost and re-entering resumes exactly where
   * this left off.
   */
  const leave = async () => {
    if (matchId) release.mutate({ matchId })
    await exit()
    navigate(`/matches/${code}`)
  }

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
    // Fullscreen must come from a user gesture, so this screen cannot be skipped — which makes it the
    // only confirmation an umpire gets, and the last chance to notice they opened the wrong court.
    // Starting CLAIMS the match (displacing whoever held it) and moves the fixture to IN_PROGRESS, so
    // "Match #1" alone is far too little to confirm against: every event has one, and an umpire running
    // several courts sees the same string on all of them (#956).
    return (
      <div className="flex h-[100dvh] flex-col items-center justify-center gap-5 p-8 text-center">
        <div className="space-y-1">
          {match.event?.name ? (
            <p className="text-sm uppercase tracking-wide text-muted-foreground">
              {match.event.name}
            </p>
          ) : null}
          <p className="text-xl font-semibold">Match #{match.matchNumber}</p>
          {/* The part that actually confirms it: a glance tells you whether these are the players in
              front of you. Same helper as the in-view labels, so the two cannot disagree. */}
          <p className="text-lg">
            {sideName(match.team1)}
            <span className="px-2 text-muted-foreground">vs</span>
            {sideName(match.team2)}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <Button variant="secondary" onClick={() => navigate(`/matches/${code}`)}>
            Back
          </Button>
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
      </div>
    )
  }

  if (!view) {
    return <div className="flex h-[100dvh] items-center justify-center">Loading score…</div>
  }

  return (
    <div className="flex h-[100dvh] w-[100dvw] flex-col overflow-hidden bg-background px-[1.5dvw] py-[1dvh]">
      <div className="relative flex shrink-0 items-center justify-between gap-[1dvw]">
        <div className="flex min-w-0 items-center gap-[1.2dvw]">
          {/*
            The only way out (#986/#1073). There were two Back controls — this one and a second in the
            right-hand cluster — and they were NOT equivalent: that one called `exit()` and navigated,
            leaving the claim behind, so the match still looked claimed by an umpire who had walked
            away. This one goes through `leave()`, which releases the claim first. The duplicate was
            removed rather than this one for exactly that reason.

            Stays live whatever state the match is in: it is the one control the gates must never
            disable. On a match that has not started it is the only way out, so a `busy` guard here
            would strand an umpire who opened the wrong court.

            Deliberately still `ghost`, unlike the board's controls (#1071). Two reasons: it is
            navigation rather than a scoring action, and it sits alone in the header rather than beside
            disabled siblings — so the inverted-affordance problem that forced Retire/Default off ghost
            does not arise here. Making it prominent would invite an accidental mid-match exit, and
            leaving releases the claim.
          */}
          <Button size="sm" variant="ghost" onClick={() => void leave()}>
            ← Back
          </Button>
          {/* Which match this screen is, for an umpire running several courts (#937). */}
          <span className="truncate text-[2.2dvh] text-muted-foreground">
            {match.event?.name ? `${match.event.name} · ` : ''}Match #{match.matchNumber}
          </span>
          <CompletedSets view={view} />
        </div>
        {/*
          The current set, floating dead centre (#1074).

          It used to sit in the left cluster immediately before the banked-set chips, so after a 6-4
          first set the band read "Set 2  [6-4]" and the chip parsed as the CURRENT set's score. Moving
          it away from the chips is the fix; the chips gained their own set numbers in the same change,
          because a bare "6-4" invites the same misreading wherever it sits.

          Absolutely positioned rather than a third flex child: the left cluster truncates a long event
          name, which would drag a flex-centred label off-centre. Absolute positioning is independent of
          its siblings' widths, so the label is centred whatever the match is called.

          `pointer-events-none` is not optional — this floats over the header and, at narrow widths,
          could overlap a control. A label that swallowed a tap on the server toggle or Start match
          would be a bad courtside bug with an invisible cause.

          The gradient stops are theme tokens, never literal colours: #394 records a shared hardcoded
          treatment failing WCAG-AA against the card surface in the AO and Off-Season themes.
        */}
        <span
          role="status"
          className="pointer-events-none absolute left-1/2 -translate-x-1/2 whitespace-nowrap
                     rounded-full bg-gradient-to-r from-transparent via-secondary to-transparent
                     px-[2dvw] py-[0.2dvh] text-[2.4dvh] font-semibold text-foreground"
        >
          {setLabel(view)}
        </span>

        <div className="flex items-center gap-[1dvw] text-[2.2dvh] text-muted-foreground">
          <ServerControl
            view={view}
            busy={busy}
            onAssign={(playerId) =>
              record.mutate({ matchId, data: { kind: 'SERVER_ASSIGNED', playerId } })
            }
          />
          <MatchClock
            elapsedSeconds={view.elapsedSeconds ?? 0}
            isRunning={view.isRunning ?? false}
            className="text-[2.4dvh] font-semibold tabular-nums text-foreground"
          />
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
        team1Name={sideName(match.team1)}
        team2Name={sideName(match.team2)}
        onPoint={(side) => send('POINT_WON', side)}
        onGame={(side) => send('GAME_AWARDED', side)}
        onEndSet={(side) => send('SET_AWARDED', side)}
        onRetire={(side) => send('RETIRED', side)}
        onDefault={(side) => send('DEFAULTED', side)}
      />

      <ScoringActions
        view={view}
        busy={busy}
        canFinalize={canManageMatches(capabilities)}
        onUndo={() => undo.mutate({ matchId })}
        onTiebreak={() => send('TIEBREAK_STARTED')}
        onPauseResume={() => send(view.isPaused ? 'RESUMED' : 'PAUSED')}
        onFlip={() => setFlipped((f) => !f)}
        onStartSet={() => send('SET_STARTED')}
        onFinalize={() => finalize.mutate({ matchId })}
      />
    </div>
  )
}
