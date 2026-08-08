import userEvent from '@testing-library/user-event'

/**
 * `userEvent.setup()` bound with `delay: null`, so simulated events resolve on microtasks instead of
 * real `setTimeout` waits. This keeps heavy component tests fast and immune to the CPU-contention timer
 * starvation that made a few of them intermittently time out under load (see #701).
 *
 * Do NOT use this for tests that exercise real debounce / typeahead timing — those genuinely need the
 * default inter-event delay. Pass overrides through `options` if a specific test needs them.
 */
export function setupUser(options?: Parameters<typeof userEvent.setup>[0]) {
  return userEvent.setup({ delay: null, ...options })
}
