import { CreateFixtureSection } from './matches/CreateFixtureSection'
import { AwaitingResultsSection } from './matches/AwaitingResultsSection'

export function MatchesTab() {
  return (
    <div className="grid gap-4">
      <CreateFixtureSection />
      <AwaitingResultsSection />
    </div>
  )
}
