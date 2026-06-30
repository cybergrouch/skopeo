interface BrandLogoProps {
  className?: string
}

/**
 * The Skopeo brand mark — the telescope-and-tennis-ball icon plus the wordmark (#109).
 * Uses /logo-mark.svg (the bare mark), NOT /favicon.svg, so the tab favicon's dark-mode contrast
 * plate (#177) doesn't bleed a white box into the in-app logo.
 */
export function BrandLogo({ className }: BrandLogoProps) {
  return (
    <span className={`inline-flex items-center gap-2 ${className ?? ''}`}>
      <img src="/logo-mark.svg" alt="" aria-hidden="true" className="h-6 w-6 shrink-0" />
      <span className="font-semibold">Skopeo</span>
    </span>
  )
}
