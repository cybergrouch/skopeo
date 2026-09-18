/**
 * The server's own `ErrorResponse.message` from a failed request, or `undefined`.
 *
 * The backend writes genuinely useful sentences — "Nobody is serving yet. Set who is serving before
 * recording a point.", "That set has ended. Start the next set, or finalize the match, before scoring
 * again." — and `LiveScoringRules`' KDoc states the intent outright: *"Refusing with a reason is what
 * lets the view say why a control did nothing."* A caller that replaces them with a generic string
 * throws that away, which is exactly what #1070 and #1075 reported as "no feedback".
 *
 * Use it with a fallback, never alone: a network failure or a 500 has no message worth showing, and
 * `undefined` is the signal to fall back to the call site's own copy.
 *
 * The shape is asserted rather than validated because it is our own API's error contract
 * (`ErrorResponse`: `{ error, message }`) and the only consequence of a miss is `undefined`. The same
 * cast already appears inline in `EventManagerView`, `EditFixturePlayersDialog`, `ProfileTab` and
 * `DeletedAccountsSection` — this is that expression, named once. Those four are left alone here to
 * keep #1070's change to the umpire view; folding them in is a tidy-up worth doing separately.
 */
export function serverMessage(error: unknown): string | undefined {
  const message = (error as { response?: { data?: { message?: unknown } } })?.response?.data
    ?.message
  return typeof message === 'string' && message.trim() !== '' ? message : undefined
}
