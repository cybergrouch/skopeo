import { Label } from '@/components/ui/label'
import { NTRP_SELF_RATING_GUIDE_URL } from '@/lib/ntrp'

/** NTRP bands 1.0–7.0 in 0.5 steps — the self-rating a RATER later approves or overrides (#75). */
const NTRP_LEVELS = [
  '1.0', '1.5', '2.0', '2.5', '3.0', '3.5', '4.0',
  '4.5', '5.0', '5.5', '6.0', '6.5', '7.0',
] as const

/**
 * Required NTRP self-rating dropdown shared by every sign-up path (OAuth, admin-invite accept, and
 * complete-profile) so the value is always captured (#75). The empty placeholder is disabled, so a
 * native required `<select>` blocks submission until a level is picked.
 */
export function NtrpSelfRatingSelect({
  value,
  onChange,
  id = 'proposedRating',
}: {
  value: string
  onChange: (value: string) => void
  id?: string
}) {
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>NTRP self-rating</Label>
      <select
        id={id}
        required
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
      >
        <option value="" disabled>
          Select your level…
        </option>
        {NTRP_LEVELS.map((level) => (
          <option key={level} value={level}>
            {level}
          </option>
        ))}
      </select>
      <p className="text-xs text-muted-foreground">
        Your best guess (1.0–7.0); a rater reviews and finalizes it.{' '}
        <a
          href={NTRP_SELF_RATING_GUIDE_URL}
          target="_blank"
          rel="noopener noreferrer"
          className="text-primary hover:underline"
        >
          Not sure? See the NTRP self-rating guide.
        </a>
      </p>
    </div>
  )
}
