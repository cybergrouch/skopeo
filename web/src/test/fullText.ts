/**
 * A Testing Library text matcher that compares an element's **full** `textContent` — descendants
 * included — instead of the default behaviour, which concatenates only *direct* child text nodes.
 *
 * Needed wherever a phrase has an element embedded mid-sentence. Since #842 every NTRP label is a
 * disclaimer trigger (a `<button>`), so `"NTRP 4.0 vs 3.5 (at the time)"` is no longer any single
 * element's direct text and `getByText` cannot see it. Rather than assert on fragments, tests keep
 * asserting the sentence a user actually reads.
 *
 * Resolves to the **innermost** matching element so the query stays unambiguous — otherwise every
 * ancestor up to `<body>` would also match and the query would throw.
 */
export function fullText(expected: string | RegExp) {
  const normalize = (value: string | null | undefined) => (value ?? '').replace(/\s+/g, ' ').trim()
  const hit = (value: string) => (typeof expected === 'string' ? value === expected : expected.test(value))
  return (_content: string, node: Element | null) => {
    if (!node) return false
    if (!hit(normalize(node.textContent))) return false
    return !Array.from(node.children).some((child) => hit(normalize(child.textContent)))
  }
}
