import type { SetThemeRequestTheme } from '@/api/generated/model'

// The setting enum + human labels. AUTO resolves by date on each client; the rest pin a theme. Shared by
// the global theme picker and the AUTO schedule editor (#1196), which offers every option but AUTO.
export const THEME_OPTIONS: ReadonlyArray<{ value: SetThemeRequestTheme; label: string }> = [
  { value: 'AUTO', label: 'Auto (by season)' },
  { value: 'GRASS', label: 'Grass' },
  { value: 'CLAY', label: 'Clay' },
  { value: 'AO', label: 'Australian Open' },
  { value: 'US_OPEN', label: 'US Open' },
  { value: 'OFF_SEASON', label: 'Off-Season' },
  { value: 'CHRISTMAS', label: 'Christmas' },
  { value: 'VALENTINES', label: "Valentine's Day" },
  { value: 'SPRING', label: 'Spring' },
  { value: 'RAINY', label: 'Rainy' },
  { value: 'HALLOWEEN', label: 'Halloween' },
  { value: 'AUTUMN', label: 'Autumn' },
  // Skopeo OG (#512): the original all-white look; also AUTO's theme for any uncovered date (#1196).
  { value: 'SKOPEO_OG', label: 'Skopeo OG' },
]

/** The human label for a theme enum name, or the name itself when this build doesn't know it. */
export function themeLabel(value: string): string {
  return THEME_OPTIONS.find((option) => option.value === value)?.label ?? value
}
