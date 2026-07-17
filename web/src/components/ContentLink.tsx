import { Link, type LinkProps } from 'react-router-dom'
import { cn } from '@/lib/utils'

/**
 * A themed inline/content link (#417) — the in-app anchors scattered through the dashboard (Activity
 * Log Who/Target cells, Reports player rows, Profile "view all matches", …). It wears the
 * `.content-link` class, which colours the link from the per-theme
 * `--link`/`--link-underline`/`--link-hover` tokens so these links stay WCAG-AA readable in every
 * seasonal theme. The plain `text-primary` treatment fell back to the generic primary colour, which
 * failed AA against some cards — e.g. Wimbledon-purple #452263 on the grass card #1b3b2b. Callers
 * keep passing any layout classes (spacing, size) via `className`; only the colour/underline
 * treatment is centralised. Sibling of PublicPageLink, which uses the same token-based style for the
 * public "Public page (QR)" anchors.
 */
export function ContentLink({ className, ...props }: LinkProps) {
  return <Link className={cn('content-link', className)} {...props} />
}
