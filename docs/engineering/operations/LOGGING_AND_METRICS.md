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
- Request-scoped fields (`requestId`, `route`, `method`, `status`, `durationMs`) arrive with #805.

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
