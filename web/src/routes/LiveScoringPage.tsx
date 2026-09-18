import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { toastError } from '@/observability/toastError'
import { serverMessage } from '@/observability/serverMessage'
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
import { scoringPrompt, umpireStateOf, visibleControls } from '@/features/livematch/umpireState'

type Side = 'TEAM1' | 'TEAM2'

/**
 * Is the app already running as an installed home-screen app (#1076)?
 *
 * Two checks because the platforms disagree: `display-mode` covers Android/Chromium and modern iOS,
 * while `navigator.standalone` is the older iOS-only signal and is not in the DOM lib's types. Either
 * being true means the install hint has nothing left to offer.
 *
 * Defaults to "installed" if neither can be read, so a browser that cannot answer shows no hint. A
 * missing tip is a smaller cost than a permanent one nobody can action.
 */
function isInstalled(): boolean {
  if (typeof window === 'undefined') return true
  const standalone = (window.navigator as Navigator & { standalone?: boolean }).standalone
  if (standalone === true) return true
  return window.matchMedia?.('(display-mode: standalone)').matches ?? true
}

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
  // `MATCH_TRANSITION` now covers before the first set as well as between later ones (#1083), so this
  // reads "Next: Set 1" on a match that has started but whose first set has not — which is exactly
  // what that state is.
  if (umpireStateOf(view) === 'MATCH_TRANSITION') return `Next: Set ${current}`
  return `Set ${current}`
}

/**
 * A side's label: its own name when it has one, else its players joined — e.g. "Ana & Bea".
 *
 * [official] is set only for a standing event team (#1079/#720). An ad-hoc fixture team's stored name
 * is its members' display names snapshotted at creation, so deriving from the current roster is
 * strictly more accurate there — which is why the backend sends null rather than the snapshot.
 *
 * Falls back so a placeholder still reads as someone.
 */
function sideName(
  players: MatchPublicPlayer[] | undefined,
  official?: string | null,
): string {
  if (official) return official
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
  const { isPortrait, isFullscreen, enter, exit } = useLockedLandscape()

  const { data: me } = useGetApiV1UsersMe()
  const { data: match, isLoading } = useGetApiV1MatchesCodeCode(code ?? '', {
    query: { enabled: Boolean(code) },
  })

  const matchId = match?.id ?? ''
  const { data: view, refetch } = useGetApiV1MatchesMatchIdLive(matchId, {
    query: { enabled: Boolean(matchId) && started },
  })

  /**
   * Prefer the SERVER's sentence over our generic one (#1070/#1075).
   *
   * `LiveScoringRules` already writes exactly what the umpire needs to hear — "Nobody is serving yet.
   * Set who is serving before recording a point.", "That set has ended. Start the next set, or
   * finalize the match, before scoring again." — and its KDoc says refusing with a reason is what lets
   * the view explain why a control did nothing. Replacing all of them with "Could not record that"
   * discarded every one of those sentences, which is what both issues reported as "no feedback".
   *
   * The generic string stays as the fallback: a network failure or a 500 carries nothing worth showing.
   * Routed through `toastError` so an unexpected failure is still reported (#807).
   */
  const onError = (fallback: string) => (error: unknown) => {
    toastError(serverMessage(error) ?? fallback, { cause: error })
    // Refetch on FAILURE too (#1083). Normally the log did not move and this changes nothing — which
    // is why the state machine needs no failure edge. The case it exists for is a write that landed
    // and lost its response: without this the board keeps showing the pre-action score, the umpire
    // taps again, and nothing rejects the repeat (the client sends no expected sequence), so the point
    // is counted twice. One request on a path that is already failing turns an ambiguous failure into
    // a visible truth.
    //
    // Composed here rather than per mutation because each mutation's own `onError` OVERRIDES
    // `afterWrite`'s via the spread below — so this is also the only place both can happen.
    void refetch()
  }
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
            {sideName(match.team1, match.team1Name)}
            <span className="px-2 text-muted-foreground">vs</span>
            {sideName(match.team2, match.team2Name)}
          </p>
        </div>
        {/*
          Installing is the ONLY lever that helps iOS (#1076): element full-screen does not exist in a
          Safari tab, so the whole gap between the best and worst iOS outcome rides on an action we
          otherwise never mention. Said here because this screen is already a deliberate tap-gated
          step (#956), seen once per match by exactly the right person.

          Gated on not already being installed, so it self-hides the moment it is acted on — a
          standing instruction to do something already done is noise. Names the actual steps, because
          "install the app" is not actionable on iOS without them.
        */}
        {!isInstalled() && (
          <p className="max-w-sm text-sm text-muted-foreground">
            Tip: add Skopeo to your home screen (Share → Add to Home Screen) to score without the
            browser and system bars taking up the board.
          </p>
        )}
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

  // One table, consulted once, read by everything below (#1083) — so every control in this view has
  // the same answer to "why is this on screen", and none of them carries a predicate of its own.
  // After the guards, so there is no undefined `view` to work around.
  const controls = visibleControls(view, canManageMatches(capabilities))

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
          {controls.has('toggleServer') && (
            <ServerControl
              view={view}
              busy={busy}
              onAssign={(playerId) =>
                record.mutate({ matchId, data: { kind: 'SERVER_ASSIGNED', playerId } })
              }
            />
          )}
          <MatchClock
            elapsedSeconds={view.elapsedSeconds ?? 0}
            isRunning={view.isRunning ?? false}
            className="text-[2.4dvh] font-semibold tabular-nums text-foreground"
          />
          {/*
            Full-screen is best-effort and its failure was invisible (#1076). `enter()` swallows a
            rejected `requestFullscreen` on purpose — the layout still fits — and `isFullscreen` was
            tracked from the `fullscreenchange` event and then read by nobody. So a view that had
            silently lost full-screen looked identical to one that never had it, and the system bar a
            scorer reported could not be attributed: a refused request, an iOS Safari tab where
            element full-screen does not exist, or an installed PWA whose status bar is by design.

            This is the reading that distinguishes them, and it is a control rather than a warning
            because re-entering needs a user gesture — the same constraint that put "Start scoring" on
            the entry screen. Not shown when full-screen is held, so it is silent in the normal case.
          */}
          {!isFullscreen && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => void enter()}
              title="This view is not full-screen, so the system bar is taking height from the board"
            >
              Full screen
            </Button>
          )}
          {view.isPaused && <span className="font-semibold text-amber-600">Paused</span>}
          {view.isTiebreak && <span className="font-semibold">Tiebreak</span>}
          {/*
            One slot, two controls (#1075), and now the table guarantees what a comment used to assert:
            `startSet` and `startMatch` belong to different states, so they can never compete for the
            space — which matters in a cluster this full.

            "Start set" before the first one and "Start next set" after (#1083). Same event and the
            same edge; only the wording differs, because "next" before there has been a first would be
            wrong in the one place an umpire is most likely to hesitate.
          */}
          {controls.has('startSet') && (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => send('SET_STARTED')}>
              {view.sets.length === 0 ? 'Start set' : 'Start next set'}
            </Button>
          )}
          {/* No `title` explaining a disabled state any more: `startMatch` appears only in READY, which
              is by definition after a server has been set, so there is nothing left to explain. The
              #1070 prompt still names the outstanding step while in PRE_MATCH. */}
          {controls.has('startMatch') && (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => send('MATCH_STARTED')}>
              Start match
            </Button>
          )}
        </div>
      </div>

      {/*
        What to do next, said BEFORE the umpire probes a dead control (#1070/#1075).

        An error is the wrong mechanism for this: nothing has gone wrong, and an error only appears
        after a wrong guess. This is a next step, so it is stated up front and announced politely
        rather than asserted as an alert.
      */}
      {scoringPrompt(view) ? (
        <p
          role="status"
          className="shrink-0 py-[0.3dvh] text-center text-[2.2dvh] font-medium text-muted-foreground"
        >
          {scoringPrompt(view)}
        </p>
      ) : null}

      <LiveScoringBoard
        view={view}
        controls={controls}
        flipped={flipped}
        busy={busy}
        team1Name={sideName(match.team1, match.team1Name)}
        team2Name={sideName(match.team2, match.team2Name)}
        onPoint={(side) => send('POINT_WON', side)}
        onGame={(side) => send('GAME_AWARDED', side)}
        onEndSet={(side) => send('SET_AWARDED', side)}
        onRetire={(side) => send('RETIRED', side)}
        onDefault={(side) => send('DEFAULTED', side)}
      />

      <ScoringActions
        view={view}
        controls={controls}
        busy={busy}
        onUndo={() => undo.mutate({ matchId })}
        onStartGame={() => send('GAME_STARTED')}
        onTiebreak={() => send('TIEBREAK_STARTED')}
        onPauseResume={() => send(view.isPaused ? 'RESUMED' : 'PAUSED')}
        onFlip={() => setFlipped((f) => !f)}
        onFinalize={() => finalize.mutate({ matchId })}
      />
    </div>
  )
}
