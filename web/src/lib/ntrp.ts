/** External NTRP self-rating guide (#203), linked at sign-up and in the Ratings tab. */
export const NTRP_SELF_RATING_GUIDE_URL = 'https://www.teamtopspin.com/tennis-self-rating'

/**
 * NTRP bands 1.0–7.0 in 0.5 steps — the rating scale shared by the sign-up self-rating (#75) and the
 * rater's set-rating dropdown (#206). The backend stores a chosen band at its midpoint (e.g. 3.5 → 3.75).
 */
export const NTRP_LEVELS = [
  '1.0', '1.5', '2.0', '2.5', '3.0', '3.5', '4.0',
  '4.5', '5.0', '5.5', '6.0', '6.5', '7.0',
] as const
