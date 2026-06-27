interface BrandLogoProps {
  className?: string
}

/** The Skopeo brand mark — the telescope-and-tennis-ball icon plus the wordmark (#109). */
export function BrandLogo({ className }: BrandLogoProps) {
  return (
    <span className={`inline-flex items-center gap-2 ${className ?? ''}`}>
      <img src="/favicon.svg" alt="" aria-hidden="true" className="h-6 w-6 shrink-0" />
      <span className="font-semibold">Skopeo</span>
    </span>
  )
}
