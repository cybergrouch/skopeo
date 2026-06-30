import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useApiHealth } from '@/api/health'
import { webVersion, webCommit } from '@/lib/version'

/** Strip a leading "v" so a web tag ("v0.0.2") compares to the API version ("0.0.2"). */
function normalize(version: string): string {
  return version.replace(/^v/, '')
}

/**
 * Build-info advisory (#229): the deployed web version (build-time `VITE_APP_VERSION`) alongside the
 * API's `/health` version, with a soft warning when they differ — usually a stale cached web bundle
 * or a partial deploy. The web version is `dev` locally, in which case there's nothing to compare.
 */
export function BuildInfoSection() {
  const web = webVersion()
  const commit = webCommit()
  const health = useApiHealth()
  const api = health.data?.version

  // Only compare two concrete versions (skip local `dev`, or when the API is unreachable).
  const comparable = web !== 'dev' && api != null
  const mismatch = comparable && normalize(web) !== normalize(api)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Build info</CardTitle>
        <CardDescription>
          Deployed versions of the web UI and the API. A mismatch usually means a
          stale cached bundle or a partial deploy.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        <div className="flex items-center justify-between gap-2">
          <span className="text-muted-foreground">Web</span>
          <span className="font-mono">
            {web}
            {commit ? ` (${commit.slice(0, 7)})` : ''}
          </span>
        </div>
        <div className="flex items-center justify-between gap-2">
          <span className="text-muted-foreground">API</span>
          <span className="font-mono">
            {health.isLoading ? 'checking…' : (api ?? 'unavailable')}
          </span>
        </div>
        {mismatch ? (
          <p role="alert" className="text-destructive">
            Web ({web}) and API ({api}) versions differ — the web bundle may be
            cached, or a deploy is incomplete. Try a hard refresh; if it persists,
            check the deploy.
          </p>
        ) : comparable ? (
          <p className="text-muted-foreground">Web and API are in sync.</p>
        ) : null}
      </CardContent>
    </Card>
  )
}
