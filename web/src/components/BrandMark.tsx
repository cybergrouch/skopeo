interface BrandMarkProps {
  className?: string;
}

/**
 * The Skopeo brand mark as a true inline vector SVG (#397).
 *
 * Fixes the header logo, which previously used `<img src="/logo-mark.svg">`. That asset is a raster
 * PNG wrapped in `<svg>` (no `<path>`s), so it could neither inherit the theme color nor be
 * recolored — and, being a dark mark, it dissolved into the dark US Open (#121824) and Christmas
 * (#a6192e/#164a35) backgrounds.
 *
 * Drawn with `fill`/`stroke="currentColor"` so callers control the color via a text-color token.
 * The header renders it as `text-foreground`; since the all-dark inversion (#399) every seasonal
 * theme has a light `--foreground`, so the mark renders near-white and stays legible everywhere.
 *
 * The artwork mirrors the purpose-built favicon vector (#386/#396) — a rounded tile with a tennis
 * ball and its seam — so the browser-tab favicon and the in-app header mark read as the same icon.
 * (A future cleanup could unify the two into a single shared path source.)
 */
export function BrandMark({ className }: BrandMarkProps) {
  return (
    <svg
      viewBox="0 0 64 64"
      role="img"
      aria-hidden="true"
      className={className}
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect
        x="2"
        y="2"
        width="60"
        height="60"
        rx="14"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
      />
      <circle
        cx="32"
        cy="32"
        r="17"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
      />
      <path
        d="M20 20a24 24 0 0 1 0 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
      <path
        d="M44 20a24 24 0 0 0 0 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  );
}
