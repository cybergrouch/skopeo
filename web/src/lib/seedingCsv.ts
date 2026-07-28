import type { SeedingEntryResponse } from '@/api/generated/model'

/** Wrap a CSV field in quotes, doubling any embedded quotes (RFC 4180). */
function csvField(value: string): string {
  return `"${value.replace(/"/g, '""')}"`
}

const CSV_HEADER = ['Seed', 'Name', 'Code', 'NTRP', 'Rating', 'Sex', 'Age']

/**
 * A seeding as CSV for a draw sheet (#111). The raw `rating` column is populated only when the viewer
 * is an ADMINISTRATOR (#583) — otherwise the backend sends it null and the cell is left blank; the NTRP
 * band and seed order are always present.
 */
export function seedingCsv(entries: SeedingEntryResponse[]): string {
  const rows = entries.map((entry) => [
    entry.seed != null ? String(entry.seed) : '',
    entry.displayName ?? entry.publicCode,
    entry.publicCode,
    entry.ntrpBand ?? '',
    entry.rating ?? '',
    entry.sex ?? '',
    entry.age != null ? String(entry.age) : '',
  ])
  return [CSV_HEADER, ...rows].map((row) => row.map(csvField).join(',')).join('\r\n')
}
