import { useEffect, useRef, useState } from 'react'

/**
 * A privacy-preserving "speed meter" (issue #114) for a player's own Profile tab. It visualizes
 * where the player sits within their current NTRP band (0 = band floor, 1 = band ceiling) WITHOUT
 * ever revealing the exact rating — no number is rendered in text or in the accessible name.
 *
 * The semicircular arc runs yellow (low / band floor) → orange → red (high / near promotion), and a
 * needle points to `position`. On mount the needle performs a classic speedometer move: it sweeps
 * 0 → 1, oscillates with a damped swing, then settles on the real position. Users who request
 * reduced motion get the needle placed directly at the target with no animation.
 */

const CLAMP_MIN = 0
const CLAMP_MAX = 1

// Semicircle geometry. The arc spans 180° (left) → 0° (right); position 0 maps to the left end
// (180°) and position 1 to the right end (0°). SVG y grows downward, so a half-disc on top.
const VIEW_WIDTH = 120
const VIEW_HEIGHT = 68
const CENTER_X = VIEW_WIDTH / 2
const CENTER_Y = 60
const RADIUS = 50
const STROKE = 10

/** Map a clamped 0..1 position to a needle rotation (degrees) within the 180°→0° semicircle. */
function positionToAngle(position: number): number {
  return 180 - position * 180
}

/** Point on the gauge arc for a given 0..1 fraction along the semicircle. */
function arcPoint(fraction: number): { x: number; y: number } {
  const angleRad = (positionToAngle(fraction) * Math.PI) / 180
  return {
    x: CENTER_X + RADIUS * Math.cos(angleRad),
    y: CENTER_Y - RADIUS * Math.sin(angleRad),
  }
}

const arcStart = arcPoint(0)
const arcEnd = arcPoint(1)
// `A rx ry rotation large-arc sweep x y` — sweep flag 1 draws the upper half clockwise.
const ARC_PATH = `M ${arcStart.x} ${arcStart.y} A ${RADIUS} ${RADIUS} 0 0 1 ${arcEnd.x} ${arcEnd.y}`

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false
  }
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export function RatingBandMeter({ position }: { position: number }) {
  const clamped = Math.min(CLAMP_MAX, Math.max(CLAMP_MIN, position))
  const reducedMotion = prefersReducedMotion()

  const targetAngle = positionToAngle(clamped)
  // While the mount sweep runs, this holds the in-flight needle angle; `null` means "not animating",
  // in which case the needle renders directly at the target. Under reduced motion it stays null.
  const [sweepAngle, setSweepAngle] = useState<number | null>(null)
  // Once the settle finishes (or immediately under reduced motion) we add a CSS transition so any
  // later position change eases smoothly rather than snapping.
  const [settled, setSettled] = useState(reducedMotion)
  const frameRef = useRef<number | null>(null)

  const angle = sweepAngle ?? targetAngle

  useEffect(() => {
    // Under reduced motion the needle is already at the target (initial state) and `settled` is
    // true, so there is nothing to animate.
    if (reducedMotion) {
      return
    }

    // A scripted keyframe timeline (fractions of the band, 0..1): sweep to the top, overshoot and
    // undershoot the target a couple of times, then come to rest exactly on it.
    const target = clamped
    const overshoot = Math.min(CLAMP_MAX, target + 0.18)
    const undershoot = Math.max(CLAMP_MIN, target - 0.1)
    const keyframes: Array<{ at: number; value: number }> = [
      { at: 0, value: 0 },
      { at: 0.45, value: 1 },
      { at: 0.62, value: overshoot },
      { at: 0.76, value: undershoot },
      { at: 0.88, value: Math.min(CLAMP_MAX, target + 0.05) },
      { at: 1, value: target },
    ]
    const duration = 1400
    const startTime = performance.now()

    const tick = (now: number) => {
      const t = Math.min(1, (now - startTime) / duration)
      // Find the current keyframe segment and linearly interpolate within it.
      let i = 0
      while (i < keyframes.length - 1 && t > keyframes[i + 1].at) {
        i += 1
      }
      const from = keyframes[i]
      const to = keyframes[Math.min(i + 1, keyframes.length - 1)]
      const span = to.at - from.at
      const localT = span > 0 ? (t - from.at) / span : 1
      const value = from.value + (to.value - from.value) * localT
      setSweepAngle(positionToAngle(value))

      if (t < 1) {
        frameRef.current = requestAnimationFrame(tick)
      } else {
        // Drop back to rendering the target directly and enable the smooth-transition path.
        setSweepAngle(null)
        setSettled(true)
      }
    }

    frameRef.current = requestAnimationFrame(tick)
    return () => {
      if (frameRef.current !== null) {
        cancelAnimationFrame(frameRef.current)
      }
    }
  }, [clamped, reducedMotion])

  return (
    <svg
      role="img"
      aria-label="Where you sit within your rating band"
      viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
      className="h-16 w-full max-w-[120px]"
    >
      <defs>
        <linearGradient id="rating-band-meter-gradient" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="#eab308" />
          <stop offset="50%" stopColor="#f97316" />
          <stop offset="100%" stopColor="#dc2626" />
        </linearGradient>
      </defs>

      {/* The colored band arc (yellow → orange → red). */}
      <path
        d={ARC_PATH}
        fill="none"
        stroke="url(#rating-band-meter-gradient)"
        strokeWidth={STROKE}
        strokeLinecap="round"
      />

      {/* Needle. Rotated about the gauge center; pointing straight up at 90°. */}
      <g
        data-testid="rating-band-needle"
        data-position={clamped}
        data-angle={angle}
        data-reduced-motion={reducedMotion ? 'true' : 'false'}
        style={{
          transform: `rotate(${90 - angle}deg)`,
          transformOrigin: `${CENTER_X}px ${CENTER_Y}px`,
          transition: settled ? 'transform 300ms ease-out' : undefined,
        }}
      >
        <line
          x1={CENTER_X}
          y1={CENTER_Y}
          x2={CENTER_X}
          y2={CENTER_Y - RADIUS + STROKE / 2}
          stroke="currentColor"
          strokeWidth={2.5}
          strokeLinecap="round"
          className="text-foreground"
        />
      </g>
      <circle cx={CENTER_X} cy={CENTER_Y} r={4} className="fill-foreground" />
    </svg>
  )
}
