import { Button } from '@/components/ui/button'
import { seedingCsv } from '@/lib/seedingCsv'
import type { SeedingEntryResponse } from '@/api/generated/model'

/** Strip characters that are awkward in filenames; keep it deterministic from the source name. */
function sanitizeFilename(name: string): string {
  return name.trim().replace(/[^a-zA-Z0-9-_]+/g, '-').replace(/^-+|-+$/g, '') || 'list'
}

/**
 * The generated-seeding table + CSV export (#111/#714), shared by the Seeding tab (player lists) and
 * the Event Organizer (event participants). The seeding itself is computed and sorted server-side; this
 * component only renders the rows and exports them. The parent owns the Generate/Regenerate action.
 */
export function SeedingTable({
  entries,
  generatedAt,
  name,
  emptyMessage = 'No seeding yet.',
}: {
  entries: SeedingEntryResponse[]
  generatedAt: string
  /** Source name used for the downloaded CSV filename (list name or event name). */
  name: string
  emptyMessage?: string
}) {
  const hasSeeding = entries.length > 0

  function onDownloadCsv() {
    const csv = seedingCsv(entries)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${sanitizeFilename(name)}-seeding-${generatedAt}.csv`
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
  }

  if (!hasSeeding) {
    return <p className="text-sm text-muted-foreground">{emptyMessage}</p>
  }

  return (
    <div className="space-y-3">
      <Button type="button" variant="outline" onClick={onDownloadCsv}>
        Download CSV
      </Button>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left text-muted-foreground">
              <th className="p-2">Seed</th>
              <th className="p-2">Name</th>
              <th className="p-2">Code</th>
              <th className="p-2">NTRP</th>
              <th className="p-2">Rating</th>
              <th className="p-2">Sex</th>
              <th className="p-2">Age</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.position} className="border-b">
                <td className="p-2">{entry.seed ?? ''}</td>
                <td className="p-2">{entry.displayName ?? entry.publicCode}</td>
                <td className="p-2">{entry.publicCode}</td>
                <td className="p-2">{entry.ntrpBand ?? ''}</td>
                <td className="p-2">{entry.rating}</td>
                <td className="p-2">{entry.sex ?? ''}</td>
                <td className="p-2">{entry.age ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
