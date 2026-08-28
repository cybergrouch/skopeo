# Monitoring as code

Cloud Monitoring configuration for the Skopeo API, kept here rather than only in the console (#751/#808)
so it is reviewable, reproducible, and survives someone clicking around.

| File | What it is |
| --- | --- |
| `uptime-check-health.json` | Uptime check against `GET /health` from multiple regions |
| `alert-uptime-failure.json` | **Paging.** The uptime check failing from more than one region |
| `alert-sustained-5xx.json` | **Paging.** Any 5xx sustained over a short window |
| `apply.sh` | Applies all of the above; idempotent, and prints what it would change |

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

## Applying

Needs `roles/monitoring.editor` on the project. `apply.sh` is idempotent: it looks each resource up by
display name and updates rather than duplicating.

```bash
export GCP_PROJECT_ID=skopeo-prod
export ALERT_EMAIL=skopeo-alerts@googlegroups.com
./infra/monitoring/apply.sh
```

## ⚠️ Verify delivery, do not assume it

Cloud Monitoring sends alerts from **`alerting-noreply@google.com`** and does **not** verify an email
notification channel when you create it. A group whose posting policy rejects non-members will show a
perfectly healthy channel and deliver nothing.

So after applying, send a message to the group from an address outside it and confirm it arrives — then
force a real alert (see the runbook). An alerting path that has never delivered a message is not an
alerting path.
