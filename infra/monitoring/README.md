# Monitoring as code

Cloud Monitoring configuration for the Skopeo API, kept here rather than only in the console (#751/#808)
so it is reviewable, reproducible, and survives someone clicking around.

| File | What it is |
| --- | --- |
| `alert-uptime-failure.json` | **Paging.** The uptime check failing from more than one region |
| `alert-sustained-5xx.json` | **Paging.** Any 5xx sustained over a short window |
| `log-metrics/skopeo_requests.json` | Per-endpoint request counter, labelled by route/method/status |
| `log-metrics/skopeo_request_latency.json` | Per-endpoint latency distribution over `durationMs` |
| `dashboard.json` | The dashboard: 10 panels, no alerts attached |
| `apply.sh` | Applies all of the above **plus the uptime check**; idempotent, and `--dry-run` prints what it would change |

Everything defaults to this project's real settings, so the usual invocation takes no arguments:

```bash
./infra/monitoring/apply.sh --dry-run   # show what would change
./infra/monitoring/apply.sh             # apply
```

## Exactly two paging alerts, on purpose

#751's decision 4: two alerts interrupt you at launch and everything else is dashboard-only. The failure
mode being avoided is ordinary — configure ten alerts on a small app, eight fire on noise, you learn to
swipe them away, and the ninth gets swiped too.

The two are not redundant. They catch different shapes:

- **Sustained 5xx** — the app is answering, and answering wrongly.
- **Uptime check failing** — the app cannot answer at all.

The v2.0.8 boot failure was the second shape: Flyway failed, the revision never passed readiness, Cloud
Run never shifted traffic to it, and **no 5xx was ever recorded**. A 5xx-only alert would have stayed
silent through it.

## Why 5xx needs no threshold tuning but latency does

5xx should sit at zero, so "any, sustained" is meaningful from day one with no baseline.

Latency is different, and the reason is narrower than it used to be. The service runs
`--min-instances=1`, so there is always a warm instance and a cold start is **not** the common case —
it happens on a deploy and on a scale-up from 1 to 2 (`--max-instances=2`). That is rare but real, and
sets a p99 no one has measured yet. So latency stays a dashboard panel until there are a couple of weeks
of actual numbers, then gets promoted if it earns it (#809).

## What deliberately does not page

**A failed deploy.** Cloud Run refuses to shift traffic to a revision that fails readiness, so a bad
deploy produces no 5xx and no uptime dip — correctly, because users are still being served by the
previous revision. That signal is the Deploy workflow's own GitHub notification, not something to
duplicate here. See `DEPLOYMENT_RUNBOOK.md`.

**4xx of any kind.** Deliberate 4xx are this API's normal contract, and every anonymous visit to a public
page produces a 401 by design. They are tracked per endpoint on the dashboard (#809) and alert on rate
*change*, once a baseline exists — never on presence.

## Per-endpoint metrics, and why they are log-based

**Cloud Run's own metrics cannot break down by endpoint.** `run.googleapis.com/request_count` and
`request_latencies` carry `response_code` and `response_code_class` but **no path, URL or route label**.
So natively you can see *"the service returned 40 5xx"* and never *"which endpoint"*.

That is the whole reason #805 emits a structured access line. Two log-based metrics extract from it:

| Metric | Kind | Labels | Answers |
| --- | --- | --- | --- |
| `skopeo_requests` | DELTA / INT64 | `route`, `method`, `status` | call volume per endpoint; error rate per endpoint; 4xx by code |
| `skopeo_request_latency` | DELTA / DISTRIBUTION | `route` | latency percentiles per endpoint |

Split deliberately. A distribution is the expensive metric kind, so it carries `route` only — putting
`method` and `status` on it too would multiply the series count for breakdowns `skopeo_requests` already
provides more cheaply.

### Cardinality is the cost, and the route *pattern* is what bounds it

`route` is the matched pattern (`/api/v1/matches/{id}`), never the concrete path. Raw URIs would create a
series per match id: unbounded, expensive, and useless. Unrouted traffic — scanners, typos, stale QR
links, all attacker-controlled — collapses into a single `(unmatched)` bucket for the same reason.

Rough ceiling for `skopeo_requests`: ~100 route patterns × a few methods × a handful of observed statuses.
In practice far fewer, since most routes serve one method and two or three statuses.

### They do not backfill

A log-based metric starts counting when it is **created**. There is no historical data. Two consequences:

- Apply this early. The two-week alert-budget review (#751 decision 4) needs a baseline before latency
  can be promoted from a dashboard panel to a paging alert, and the clock starts at creation.
- The per-endpoint panels are empty on a fresh apply. That is expected, not a broken dashboard.

## The dashboard

Ten panels, and **no alert policies attached to any of them** — that is the point of decision 4. Latency
and 4xx are visible so they can be *reasoned about*; they page only if a review later shows they would
have caught something.

Two worth calling out:

- **`401 / 403 rate`** is the #647 panel. Users could not sign in, the API returned auth failures rather
  than 5xx, nothing alerted, and a human found it. Watch for a **step change**, never for presence — a
  steady baseline of 401s is healthy, because every anonymous visit to a public page produces one.
- **`Cloud Run instances`** is meaningful precisely because the service runs `--min-instances=1
  --max-instances=2`: sitting at 2 means saturation, not headroom.

## Applying

Needs `roles/monitoring.editor` and `roles/logging.configWriter` on the project. `apply.sh` is
idempotent — it looks each resource up by display name and updates rather than duplicating.

```bash
./infra/monitoring/apply.sh --dry-run
./infra/monitoring/apply.sh
```

Override anything per-run: `--project`, `--region`, `--service`, `--alert-email`, `--api-host`.
Precedence is flag > environment > default.

### The `beta` component, and the capture hazard

`gcloud monitoring policies`, `uptime`, `dashboards` and `gcloud logging metrics` are all GA. Only
`gcloud beta monitoring channels` is not. If it's missing:

```bash
gcloud components install beta
```

**The hazard was never the install — it was installing inside `$(...)`.** gcloud offers to install a
missing component on demand, and when that happens within a command substitution its progress output is
captured as if it were the command's result. An earlier version of this script consequently passed
`/Applications/Xcode.app/Contents/Developer` as a notification channel id and
`Collecting cryptography==42.0.7` as a policy id.

Three defences, since any one of them can be defeated:

1. Prompts are disabled (`CLOUDSDK_CORE_DISABLE_PROMPTS=1`).
2. The `beta` group is touched once **outside** any substitution, so first-run noise lands on the
   terminal where it is visible and harmless.
3. Every captured value must match the shape of a Monitoring resource name
   (`projects/<p>/<kind>/<id>`); anything else is discarded rather than used.

There is deliberately **no** check against `gcloud components list`. Its `--format` output differs
between gcloud releases, and parsing it produced a false negative that blocked a machine where `beta`
was in fact installed. The script instead fails with a clear message at the point the command actually
cannot run — which also distinguishes "no channel exists yet" from "the command didn't run at all",
two states that otherwise look identical on a first apply.

### `--dry-run` does validate the dashboard

Dry-run echoes the `gcloud` calls rather than running them, but for the dashboard it calls
`--validate-only`, which is a real server-side schema check. So a malformed widget fails in dry-run
rather than on first apply.

### Why the uptime check isn't a JSON file

`gcloud monitoring uptime create` is flag-based — unlike policies, dashboards and log metrics, it has no
`--config-from-file`. Its parameters (path, period, timeout, regions) are therefore named variables at
the top of `apply.sh`, which is still one reviewable place, just not the same shape as the rest.

Note the CLI's enums differ from the API's: `--period` is in **minutes** (`1`, `5`, `10`, `15`) and
regions are lowercase (`asia-pacific`, `europe`, `usa-oregon`), not the API's `ASIA_PACIFIC`.

## ⚠️ Verify delivery, do not assume it

Cloud Monitoring sends alerts from **`alerting-noreply@google.com`** and does **not** verify an email
notification channel when you create it. A group whose posting policy rejects non-members will show a
perfectly healthy channel and deliver nothing.

So after applying, send a message to the group from an address outside it and confirm it arrives — then
force a real alert (see the runbook). An alerting path that has never delivered a message is not an
alerting path.
