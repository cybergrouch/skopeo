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

MDC carries `requestId` and the trace, and nothing else. The access line's own fields do **not** come
through MDC — see below for why.

### MDC is an allowlist, not a scratchpad (#806)

MDC is a *publication channel*. Every appender forwards it: the JSON encoder writes it as top-level
fields, and an error-tracking appender attaches it to every event as searchable tags. So a stray
`MDC.put("email", …)` anywhere would publish that value on every subsequent line of the request, to every
sink, with nothing failing.

`logback.xml` therefore enforces an allowlist at the encoder via `<includeMdcKeyName>`; an unlisted key is
**dropped**, not trusted. `LogFields.ALLOWED_MDC_KEYS` is the single source of truth and a test asserts the
two do not drift apart — without that, adding a key to one and not the other fails silently, because the
field simply never appears.

Currently allowed: `requestId`, `logging.googleapis.com/trace`. Note what is deliberately absent — no user
id, no email, no Firebase UID, no display name. A caller's identity is reachable from the request id if it
is ever needed, which keeps identity out of every line by default rather than in it by default.

**The limit of that enforcement, stated plainly:** the encoder allowlist protects the *log* sink only. An
error-tracking appender reads the MDC map directly and never passes through this encoder, so it would see
an unlisted key. The allowlist is defence in depth; the actual control is not putting personal data in MDC
in the first place.

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

- Nothing scraped it — and a scrape would have been misleading anyway: the service runs
  `--max-instances=2`, so one pull reads one instance's counters rather than the service's total.
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

## Keeping personal data out of logs (#806)

This is the gate that makes switching on an error tracker safe (#810/#811). Two facts make the *sources*
the right place to control it rather than a vendor hook:

- **A Logback appender sends only log events** — message, exception, MDC. HTTP request/body/cookie capture
  comes from web-framework integrations (Spring, servlet), and **Ktor has none**. So the raw request body,
  the largest single payload, is never captured at all.
- Which means what reaches a vendor is exactly *what we chose to log*. Clean sources protect every sink,
  including ones not yet chosen.

### The rules

1. **Never interpolate a domain object.** Every PII-carrying type is a `data class`, so `toString()` covers
   every field: `logger.info { "provisioned $user" }` publishes an email, a date of birth and a Firebase
   UID, and nothing in the build objects.
2. **Never put personal data in an exception or `ServiceError` message.** Those are authored by us and are
   logged with the throwable, so they land in `stack_trace`. Internal UUIDs are fine —
   `"User $userId has no rating"` is not personal data.
3. **Identifiers, not identities.** Where a log line needs to say *which* player or user, use the id. The
   calculator's audit trail does this deliberately: it reports `playerId`, not the player's name, because
   `RankingRoutes` logs every audit entry at INFO.
4. **Credential-shaped values get redacted at the call site.** `redactedJdbcUrl` drops a JDBC URL's query
   string before it is logged, because `?user=…&password=…` is the conventional Postgres form and that line
   is emitted on every boot.

### How it is verified

`PiiLeakTest` drives a request carrying an email, a date of birth, a Firebase UID and a bearer token
through both a 500 and a malformed-JSON 400, then **encodes every captured log event through the shipped
`logback.xml`** and asserts none of the four appears. Asserting on the message alone would miss the two
ways a value actually escapes: the MDC map, and an exception's own message inside `stack_trace`.

Deliberately still open: redacting value types in the domain model (**#801**), as defence in depth against
rule 1 rather than a substitute for it.

## `Redactable<T>`: keeping a value out of every `toString()` (#801)

Some values must never reach a log line, and the realistic way they get there is not a deliberate
`logger.info { user.email }` — it is interpolating a whole object: `logger.info { "provisioned $user" }`.
Every model here is a `data class`, so Kotlin's generated `toString()` covers **every** field, and one such
line publishes whatever the object holds, forever, with nothing in the build objecting.

```kotlin
@JvmInline
value class Redactable<out T : Any>(val revealed: T) {
    override fun toString(): String = "***"
}
```

### Why the wrapper, and not a `toString()` override per model

Kotlin generates a data class's `toString()` from its constructor properties, and that generated method
calls `toString()` on each field. So **making the field's type redact protects every containing model
automatically** — no per-model code, and nothing to forget when a model is added later.

Overriding `toString()` on each of the ~29 PII-carrying classes would work too, but it is ongoing
boilerplate that fails silently the moment someone adds a class without knowing the convention.

**One trap:** a `value class` inherits a generated `toString()` that prints the wrapped value. The override
is load-bearing, not decorative, and `RedactableTest` asserts exactly that.

### Using it

```kotlin
// declare
data class Invite(val email: Redactable<String>, /* … */)

// wrap at the boundary where the value enters the domain
email = row[InviteTable.email].asRedactable()

// unwrap only where the value is genuinely needed
apiKey = plaintext.revealed
```

`asRedactable()` is named `as…` (like `asSequence`) because **the value is not changed** — it is only
tagged. At a call site whose purpose is handing the value to a caller, "redact this" would be exactly the
wrong thing to suggest.

The accessor is `revealed`, not `value`, for two reasons: `contact.value.value` is unreadable, and
`\.revealed` greps out every place a protected value is deliberately exposed — which is the list a
reviewer actually wants. There are currently 14, across 11 files.

### What is wrapped

| Target | Holds |
| --- | --- |
| `IssuedApiKey.plaintext` | a **working partner API key** — only its SHA-256 hash is persisted |
| `VerifiedFirebaseToken.email` / `.providerUid` | raw verified identity, built on every authenticated request |
| `Contact.value` and `ContactInfo.value` | email/phone, stored and incoming forms |
| `Invite.email` | invitee address |

Both contact forms are wrapped deliberately: covering one leaves the other leaking, and services handle
`Contact` far more often than `ContactInfo`.

### What is deliberately **not** wrapped

| Excluded | Occurrences | Why |
| --- | --- | --- |
| `User.dateOfBirth` | ~126 | Cost/benefit. The rules above and `PiiLeakTest` already cover it, and a date of birth in a log is a materially smaller problem than a live credential. |
| `User.firebaseUid` | ~238 | Same, more so. |
| `UserName.value` | — | Display names are shown publicly on player pages anyway. |
| `UserIdentity.providerUid` | — | It is the join key the repository looks up by. |

### Two limits, stated so they are not mistaken for guarantees

**It stops `"$user"`. It does not stop `"${user.email.revealed}"`.** Reading the value out and logging it
directly is beyond anything a type can prevent; that is what the clean-sources rules above and
`PiiLeakTest` are for. This is layer 2 — it removes the *accidental* leak, which is the one that happens.

**The type system does not catch string interpolation.** Wrapping `Contact.value` silently turned an
audit-log summary into `"Enabled EMAIL ***"` with **no compile error** — only `ContactServiceTest` caught
it. `ContactService` and `InviteService` both legitimately record the address in the audit table (our own
database, administrator-only), so both are explicit `.revealed` unwraps with a comment. **If you wrap
another field, run the full suite rather than trusting a clean compile.**

### Nothing enforces adoption yet

A new sensitive field added as a plain `String` will not fail the build. That guard is **#822**, which
needs a decision between a source scan, a reflective test, and a logger-interpolation scan.

### The better end state

The [Redacted compiler plugin](https://github.com/ZacSweers/redacted-compiler-plugin) does this with a
property annotation and **zero** call-site churn. It does not work on Kotlin 2.2.21 — verified: `1.13.0`
(stable, targets 2.1.20) fails `compileKotlin` with `AbstractMethodError` in the FIR checker, and
`1.14.0-alpha01` compiles main but fails `compileTestKotlin` with `NoSuchMethodError`.

Revisit when a stable release targeting 2.2.x ships; the swap is delete-the-wrapper, add-annotations. Not
worth downgrading Kotlin for — the Gradle daemon is already pinned to Java 21 for detekt, and a second
toolchain shackle for defence in depth is a poor trade.

## References

- [Cloud Logging: structured logging](https://cloud.google.com/logging/docs/structured-logging)
- [Cloud Logging: LogSeverity](https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#logseverity)
- [Cloud Error Reporting: formatting error messages](https://cloud.google.com/error-reporting/docs/formatting-error-messages)
- [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder)
