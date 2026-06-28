import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CalculationBreakdownDetail } from './CalculationBreakdownDetail'
import type { CalculationBreakdownResponse } from '@/api/generated/model'

const net: CalculationBreakdownResponse = {
  dominance: '0.200000',
  scale: '1.000000',
  ratingGap: '0.500000',
  normalizedGap: '0.083333',
  competitiveThresholdPct: '0.083000',
  isUpset: false,
  upsetMultiplier: '2.000000',
  kFactor: '0.160000',
  sets: [],
}

const setStep = (overrides = {}) => ({
  setIndex: 0,
  score: '6-2',
  dominance: '0.500000',
  scale: '0.400000',
  ratingGap: '0.500000',
  normalizedGap: '0.083333',
  competitiveThresholdPct: '0.083000',
  isUpset: false,
  upsetMultiplier: '2.000000',
  kFactor: '0.160000',
  delta: '0.032000',
  ratingAfter: '4.032000',
  ...overrides,
})

describe('CalculationBreakdownDetail', () => {
  it('renders the v1 net line when no per-set steps are present', () => {
    render(<CalculationBreakdownDetail breakdown={net} />)
    expect(
      screen.getByText(/dominance 0\.200000 · scale 1\.000000/),
    ).toBeInTheDocument()
    expect(screen.getByText(/· expected · K 0\.160000/)).toBeInTheDocument()
    // No per-set "Set N" rows.
    expect(screen.queryByText(/Set 1/)).not.toBeInTheDocument()
  })

  it('renders one row per set (1-based, with Δ and carried rating) for the v2 calculator', () => {
    const breakdown: CalculationBreakdownResponse = {
      dominance: null,
      scale: null,
      ratingGap: null,
      normalizedGap: null,
      competitiveThresholdPct: null,
      isUpset: null,
      upsetMultiplier: null,
      kFactor: null,
      sets: [
        setStep(),
        setStep({ setIndex: 1, score: '6-4', isUpset: true, delta: '0.064000', ratingAfter: '4.096000' }),
      ],
    }
    render(<CalculationBreakdownDetail breakdown={breakdown} />)

    expect(screen.getByText('Set 1')).toBeInTheDocument()
    expect(screen.getByText('Set 2')).toBeInTheDocument()
    expect(screen.getByText(/\(6-2\):/)).toBeInTheDocument()
    expect(screen.getByText(/expected · K 0\.160000 · Δ 0\.032000 → 4\.032000/)).toBeInTheDocument()
    expect(screen.getByText(/upset · K 0\.160000 · Δ 0\.064000 → 4\.096000/)).toBeInTheDocument()
    // The net line is not rendered when per-set steps exist.
    expect(screen.queryByText(/^dominance/)).not.toBeInTheDocument()
  })
})
