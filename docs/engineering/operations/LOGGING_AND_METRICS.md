# Logging and Metrics

Skopeo logs **structured JSON to stdout**. Cloud Run collects stdout, parses each JSON line into
`jsonPayload`, and reads two field names specially — which is the entire reason for the format
(#751/#804).

| Field | Why it is spelled exactly this way |
| --- | --- |
| `severity` | Cloud Logging reads it as the entry's severity, so `severity>=ERROR` filtering, saved queries and severity-based alerting work. |
| `stack_trace` | **Cloud Error Reporting picks this up automatically** and groups exceptions into issues with a readable trace. No agent, no SDK, no vendor — the field name *is* the integration. |

Everything else in the object (`message`, `logger`, `thread`, `time`, plus MDC) is ordinary payload.

## Configuration

`src/main/resources/logback.xml`, using `logstash-logback-encoder`. One appender, console only:
containers and cloud platforms collect stdout, and a file appender inside a container is invisible to
the platform and lost when the container is replaced.

## The severity trap

Logback's level names are *almost* Cloud Logging's, and that is the hazard. GCP's `LogSeverity` enum is
`DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY`. Three of Logback's five map by
identity. Two do not:

- **`WARN` is not a LogSeverity.** An unmapped value degrades to `DEFAULT`, which sorts *below* `DEBUG`,
  so every warning disappears from "everything at WARNING or above".
- **`TRACE` is not one either** — `DEBUG` is GCP's floor.

`gcpSeverityOf` in `org.skopeo.common.logging.GcpSeverity.kt` does the mapping; `GcpSeverityConverter`
wraps it and is registered as the `%gcpSeverity` conversion word. It is a converter rather than a `%replace(%replace(...))` chain in XML precisely because this is
the part most likely to be silently wrong: `StructuredLogFormatTest` loads the shipped `logback.xml` and
asserts a `WARN` event encodes as `"severity":"WARNING"`.

## Correlating a line with its request

`logging.googleapis.com/trace` is set from Cloud Run's inbound `X-Cloud-Trace-Context` header, qualified
as `projects/<projectId>/traces/<TRACE_ID>` — Cloud Logging ignores a bare trace id, which is why
`GCP_PROJECT_ID` is required for the field to appear at all. The project id comes from the same repo
variable the deploy workflow already uses for `--project`; it is unset locally and in tests, and the
field is then **omitted rather than emitted empty**, since a value Cloud Logging cannot resolve looks
like a link that goes nowhere.

## MDC

MDC entries are emitted **flat**, as top-level fields. That is what makes them queryable in the Logs
Explorer and usable directly as log-based metric labels; nested under an `mdc` object they would be
neither.

Two consequences worth internalising:

- **MDC is an allowlist, not a scratchpad.** Every appender forwards MDC — a future error tracker would
  receive it as tags — so anything put there is published. Emails, Firebase UIDs and dates of birth must
  never go in. See #806.
- MDC carries `requestId` and the trace. The access line's own fields do **not** come through MDC — see
  below for why.

## The request id

`X-Request-Id`, via Ktor's `CallId` plugin (#805): accepted from the caller when supplied, generated as a
UUID when not, and **echoed on the response**. Echoing is what makes it useful to a human — a user's
screenshot of an error is enough to find the log line.

It appears in three places: the `requestId` MDC field on every line the request emits, the response
header, and the body of a 500. Deliberately not in 4xx bodies: those are the API's normal contract, and
the header already covers every response.

Inbound values are length-capped and rejected if blank, so a caller cannot push an unbounded string into
every log line the request produces.

### Both 500 paths carry it

There are two, and this is easy to get wrong. `respondMappingErrors` (`RouteSupport.kt`) ends in
`catch (e: Exception)` that logs at ERROR and responds 500 — and **28 of 31 route files go through it**.
Because it swallows the exception, `StatusPages` never sees those. So:

| Path | Covers |
| --- | --- |
| `respondMappingErrors` | the large majority of routes |
| `RankingRoutes`' own catch | that one route, which predates the helper |
| `StatusPages` | the remainder — `OpenGraphRoutes` (no handling at all), plugin and authentication failures, response-serialization errors |

Treating `StatusPages` as "where 500s are handled" would leave most 500s without a request id, and nothing
would fail.

## The access line

One structured line per request, emitted by the `RequestLog` plugin: `method`, `route`, `status`,
`durationMs`.

**Why a plugin and not `CallLogging`.** Three of those four fields are unavailable where `CallLogging`
would put them:

- its `mdc { }` providers resolve at call *start*, before routing — so there is no matched route yet, and
  no status or duration;
- its `format { }` runs after the response, but the result is the log *message*, a single string. Fields
  inside a message are not queryable and cannot be a metric label.

`CallLogging` is still installed, purely as the MDC vehicle — it is what propagates `requestId` and the
trace into the coroutine context so application logs inside a handler carry them. Its own access line is
emitted at DEBUG and suppressed by the `INFO` threshold on `io.ktor`, so there is exactly one access line
per request. Raising `io.ktor` to DEBUG will produce two.

**Why a marker and not MDC.** MDC values are always strings. `status` and `durationMs` must stay JSON
*numbers* so a Cloud Logging distribution metric can compute latency percentiles over them;
`Markers.appendEntries` preserves the type, and `logback.xml` carries the matching `<logstashMarkers/>`
provider.

**Why the route pattern.** `route` is the matched pattern (`/api/v1/matches/{id}`), never the concrete
path. Raw URIs would give it unbounded cardinality — one metric series per match id, useless and
expensive — and it is also the string a future error tracker uses as its `transaction` name, so the two
have to agree for a metric to link to a trace.

One wrinkle worth knowing: the `ResponseSent` hook receives the application-level call, **not** the
`RoutingCall`, so reading the route there always yields `(unmatched)`. The pattern is captured on
`RoutingCallStarted` and read back at response time.

Requests that matched nothing collapse into a single `(unmatched)` bucket rather than logging their raw
path. Scanner traffic, typos and stale QR links are unbounded and attacker-controlled; one bucket answers
the useful question ("how much traffic is hitting nothing") and stays bounded.

## Using logging in code

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

logger.info { "Rating calculation committed for event $eventId" }
logger.error(throwable = e) { "Failed to persist rating history" }
```

Pass the exception as `throwable` rather than interpolating it — that is what populates `stack_trace`,
and therefore what reaches Cloud Error Reporting.

**Never interpolate a domain object.** Every PII-carrying type is a `data class` with an auto-generated
`toString()`, so `logger.info { "provisioned $user" }` publishes an email, a date of birth and a Firebase
UID in plain text, and nothing in the build objects. #806 makes that structurally hard; until then it is
a rule.

## Metrics

**There is no `/metrics` endpoint and no Micrometer/Prometheus registry.** Both were removed in #804:

- Nothing scraped it. Cloud Run scales to zero, which suits pull-based scraping badly.
- It was registered in `routing { }` with no `authenticate` wrapper, so anyone could read JVM internals
  and the full route list off production.

Per-endpoint **call volume, latency distribution and error rate** come instead from **Cloud Logging
log-based metrics** over the access-line fields, grouped by `route`. Two properties this depends on:

- `route` must be the **matched pattern** (`/api/v1/matches/{id}/score-correction`), never the raw URI.
  Raw URIs would make every match id its own metric series — useless and expensive.
- The metrics group by a *label*, so a newly added endpoint appears automatically with no metric
  definition change. See #805 and #809.

Cloud Run also publishes `run.googleapis.com/request_latencies`, `request_count` and instance counts with
no instrumentation at all; Cloud SQL publishes CPU, memory and connections. Those are the dashboard's
infrastructure panels.

## What this deliberately does not cover

- **Intra-request breakdown.** Log-based metrics see total request time only. Attributing time to a slow
  DB query needs span instrumentation or an error tracker's auto-instrumentation — not built.
- **Frontend errors.** #807 adds the boundary and a vendor-neutral reporter seam.
- **Alerting and dashboards.** #808 and #809.
- **An error-tracking vendor.** Deliberately the last decision (#751); the Logback appender seam means
  adding one is configuration, not code (#810).

## References

- [Cloud Logging: structured logging](https://cloud.google.com/logging/docs/structured-logging)
- [Cloud Logging: LogSeverity](https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#logseverity)
- [Cloud Error Reporting: formatting error messages](https://cloud.google.com/error-reporting/docs/formatting-error-messages)
- [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder)
