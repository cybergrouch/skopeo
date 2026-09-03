import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, expect, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

/**
 * React reports invalid DOM nesting — a `<button>` inside a `<button>`, a `<div>` inside a `<p>` — as a
 * `console.error`, which a passing test swallows. Two nested-trigger defects shipped that way (#852): the
 * suite printed the warning on every run and stayed green, and in one case the nesting made a disclaimer
 * **unreachable** rather than merely invalid.
 *
 * So the warning is promoted to a failure. Collected during the test and thrown in `afterEach` rather than
 * thrown from inside `console.error`, because throwing mid-render derails React's own error handling and
 * reports the wrong thing.
 */
const nestingErrors: string[] = []

/** React's wording has changed across versions; match the stable parts rather than a whole sentence. */
const NESTING_PATTERN = /validateDOMNesting|cannot contain a nested|cannot be a descendant of|cannot appear as a descendant of/i

/**
 * React passes its warnings as a printf-style template plus arguments, so the element names live in the
 * later arguments rather than the message. Substituting them is what turns an unactionable
 * "In HTML, %s cannot be a descendant of <%s>" into "<button> cannot be a descendant of <button>".
 */
function formatConsoleArgs(args: unknown[]): string {
  const [template, ...rest] = args
  if (typeof template !== 'string' || !template.includes('%s')) {
    return args.map((arg) => String(arg)).join(' ')
  }
  let index = 0
  const filled = template.replace(/%s/g, () => (index < rest.length ? String(rest[index++]) : '%s'))
  // Anything left over (React appends the component stack) is dropped: the first line is the finding.
  return filled
}

beforeEach(() => {
  nestingErrors.length = 0
  vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
    const message = formatConsoleArgs(args)
    if (NESTING_PATTERN.test(message)) {
      nestingErrors.push(message.split('\n')[0].trim())
      return
    }
    // Everything else still reaches the console — this guard is about nesting, not about silencing React.
    console.info(...args)
  })
})

afterEach(() => {
  // Unmount React trees between tests so the DOM doesn't leak across cases.
  cleanup()
  vi.mocked(console.error).mockRestore?.()
  const unique = [...new Set(nestingErrors)]
  nestingErrors.length = 0
  expect(
    unique,
    'Invalid DOM nesting (#852). An interactive element inside another interactive element is invalid ' +
      'HTML and can make the inner one unreachable. Move the inner control outside its host — see the ' +
      'NTRP-disclaimer section of docs/engineering/architecture/WEB_UI_ARCHITECTURE.md.',
  ).toEqual([])
})
