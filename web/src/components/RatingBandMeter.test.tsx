import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { RatingBandMeter } from './RatingBandMeter'

/** Install a matchMedia stub that reports the given reduced-motion preference. */
function mockMatchMedia(reduce: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: query.includes('prefers-reduced-motion') ? reduce : false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }))
}

const needle = () => screen.getByTestId('rating-band-needle')

describe('RatingBandMeter', () => {
  beforeEach(() => {
    mockMatchMedia(false)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('is an accessible image that never reveals a number', () => {
    const { container } = render(<RatingBandMeter position={0.5} />)
    const gauge = screen.getByRole('img', {
      name: 'Where you sit within your rating band',
    })
    expect(gauge).toBeInTheDocument()
    // No numeric position/rating leaks into the rendered text.
    expect(container.textContent ?? '').not.toMatch(/\d/)
  })

  it('positions the needle differently at low, mid, and high', () => {
    const { unmount } = render(<RatingBandMeter position={0} />)
    const low = needle().getAttribute('data-position')
    unmount()

    render(<RatingBandMeter position={0.5} />)
    const mid = needle().getAttribute('data-position')
    expect(mid).not.toBe(low)
  })

  it('maps position to an inverted angle (0 → 180°, 1 → 0°) under reduced motion', () => {
    mockMatchMedia(true)
    const { unmount } = render(<RatingBandMeter position={0} />)
    expect(needle().getAttribute('data-angle')).toBe('180')
    unmount()

    render(<RatingBandMeter position={1} />)
    expect(needle().getAttribute('data-angle')).toBe('0')
  })

  it('places the needle directly at the target with reduced motion (no sweep)', () => {
    mockMatchMedia(true)
    render(<RatingBandMeter position={0.5} />)
    const el = needle()
    expect(el.getAttribute('data-reduced-motion')).toBe('true')
    expect(el.getAttribute('data-position')).toBe('0.5')
    // Settled immediately: angle is exactly the target mapping, 90° for mid-band.
    expect(el.getAttribute('data-angle')).toBe('90')
  })

  it('sweeps the needle and settles on the target when motion is allowed (#114)', () => {
    // Remove matchMedia entirely: exercises the "no matchMedia" guard (treated as motion-allowed),
    // then drive requestAnimationFrame manually to run the sweep timeline.
    const originalMatchMedia = window.matchMedia
    const originalRaf = window.requestAnimationFrame
    const originalCaf = window.cancelAnimationFrame
    const originalNow = performance.now
    delete (window as { matchMedia?: unknown }).matchMedia

    const frames: FrameRequestCallback[] = []
    let clock = 0
    window.requestAnimationFrame = ((cb: FrameRequestCallback) => {
      frames.push(cb)
      return frames.length
    }) as typeof window.requestAnimationFrame
    window.cancelAnimationFrame = (() => {}) as typeof window.cancelAnimationFrame
    performance.now = () => clock

    try {
      render(<RatingBandMeter position={0.5} />)
      const el = needle()
      expect(el.getAttribute('data-reduced-motion')).toBe('false')

      // Mid-sweep frame: the needle is away from the 90° mid-band target.
      const midFrame = frames.shift()
      expect(midFrame).toBeDefined()
      act(() => {
        clock = 700 // t ≈ 0.5 of the 1400ms timeline
        midFrame?.(clock)
      })
      expect(el.getAttribute('data-angle')).not.toBe('90')

      // Final frame: settles exactly on the target (90° for position 0.5).
      const lastFrame = frames.pop()
      expect(lastFrame).toBeDefined()
      act(() => {
        clock = 1400 // t = 1
        lastFrame?.(clock)
      })
      expect(el.getAttribute('data-angle')).toBe('90')
    } finally {
      window.requestAnimationFrame = originalRaf
      window.cancelAnimationFrame = originalCaf
      performance.now = originalNow
      window.matchMedia = originalMatchMedia
    }
  })

  it('clamps out-of-range positions to [0, 1]', () => {
    mockMatchMedia(true)
    const { unmount } = render(<RatingBandMeter position={1.5} />)
    expect(needle().getAttribute('data-position')).toBe('1')
    expect(needle().getAttribute('data-angle')).toBe('0')
    unmount()

    render(<RatingBandMeter position={-0.3} />)
    expect(needle().getAttribute('data-position')).toBe('0')
    expect(needle().getAttribute('data-angle')).toBe('180')
  })
})
