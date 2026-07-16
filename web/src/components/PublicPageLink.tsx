import { Link, type LinkProps } from 'react-router-dom'
import { cn } from '@/lib/utils'

/**
 * A content link to a public/shareable page (#394) — the "Public page (QR)" / "Public page" anchors
 * scattered across the dashboard and public routes. It wears the `.public-page-link` class, which
 * colours the link from the per-theme `--link`/`--link-underline`/`--link-hover` tokens so these
 * links stay WCAG-AA readable in every seasonal theme (the shared `text-primary` treatment was
 * failing against the white card surface in the AO and Off-Season themes). Callers keep passing any
 * layout classes (spacing, size) via `className`; only the colour/underline treatment is centralised.
 */
export function PublicPageLink({ className, ...props }: LinkProps) {
  return <Link className={cn('public-page-link', className)} {...props} />
}
