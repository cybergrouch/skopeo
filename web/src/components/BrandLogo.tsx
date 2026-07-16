import { BrandMark } from "./BrandMark";

interface BrandLogoProps {
  className?: string;
}

/**
 * The Skopeo brand mark — the telescope-and-tennis-ball icon plus the wordmark (#109).
 * The icon is rendered as an inline vector `<BrandMark>` colored with the `text-foreground` theme
 * token (#397), so it recolors with the seasonal theme and stays legible on the dark US Open and
 * Christmas backgrounds (an `<img>` to the raster /logo-mark.svg could do neither).
 */
export function BrandLogo({ className }: BrandLogoProps) {
  return (
    <span className={`inline-flex items-center gap-2 ${className ?? ""}`}>
      <BrandMark className="h-6 w-6 shrink-0 text-foreground" />
      <span className="font-semibold">Skopeo</span>
    </span>
  );
}
