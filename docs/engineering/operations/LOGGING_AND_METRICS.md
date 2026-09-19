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
5. **Never log a database exception's own message.** Rule 2 holds for messages *we* author; Postgres
   authors its own, and puts the offending row in them. Attaching a `SQLException` to a log event is
   enforced against rather than merely discouraged — see the section below.

### How it is verified

`PiiLeakTest` drives a request carrying an email, a date of birth, a Firebase UID and a bearer token
through both a 500 and a malformed-JSON 400, then **encodes every captured log event through the shipped
`logback.xml`** and asserts none of the four appears. Asserting on the message alone would miss the two
ways a value actually escapes: the MDC map, and an exception's own message inside `stack_trace`. Since
#992 it also drives a duplicate verified email against a real Postgres and asserts on both halves of the
outcome: the address is absent, the constraint name and SQLSTATE are present.

## Driver-composed messages: the leak no type can reach (#992)

A unique-violation message is composed inside the JDBC driver, from column values:

```
duplicate key value violates unique constraint "uq_contact_verified_value"
  Detail: Key (contact_type, value)=(EMAIL, someone@example.com) already exists.
```

`contact_information.value` **is** the email address or phone number. `Contact.value` is `@Redacted`,
and that is irrelevant here: no Kotlin call site formats this string, so there is nothing to wrap. It
reached the log two ways — our own 500 handler logging the throwable, and Exposed logging the failed
transaction attempt itself, at WARN, before our code saw the exception at all.

The fix keeps the failure and drops the wording:

- `common/logging/SqlErrorRedaction.kt` — `sqlFailureFacts()` reads the server's *structured* error
  fields (constraint, table, column, routine, SQLSTATE), all of them identifiers rather than values;
  `redactedForLogging()` returns a copy of the cause chain with every driver message replaced and every
  stack frame kept. It is the identity for anything without a `SQLException` in it.
- `respondMappingErrors` uses both, so a 500 keeps its own wording and gains the facts.
- `SqlExceptionRedactingFilter`, registered as a `<turboFilter>` in `logback.xml`, refuses **any** event
  carrying a `SQLException` and re-emits it — same logger, same level, facts and frames intact. That is
  what covers Exposed, and any future library, without a call site to remember.

Scrubbing `Detail:` out of the text was the alternative, and it fails open: it has to track Postgres'
wording across versions and every library that interpolates `cause.message`. Rebuilding the line from
fields we have individually judged safe fails closed. The cost is that a log site's own message is
dropped when it reaches the filter — deliberate, because a site that interpolates `e.message` into its
own sentence is exactly the mistake being guarded.

Note what was *not* done: the unique index stands (a verified value belongs to one active contact), and
nothing was silenced. #989 is the cautionary tale in the other direction — a constraint violation nobody
logged, misreported as a lost sequence race.

### The second path through the same area, which the filter does not cover (#1031)

The filter above keys on **an event carrying a `SQLException`**. Exposed has another way of putting column
values in a log line, and it carries no exception at all:

`Slf4jSqlDebugLogger` logs every statement with its arguments **expanded inline** —
`logger.debug(expandArgs(context, transaction))`. At DEBUG that produces

```
INSERT INTO contact_information (..., "value", ...) VALUES (..., 'someone@example.com', ...)
```

and `contact_information.value` **is** the email address. No `SQLException`, so the turboFilter returns
`NEUTRAL` and the line passes through untouched. Different path, no protection — widening the filter to
cover it would mean scrubbing arbitrary statement text, which is the fail-open approach rejected above.

**The control is a level pin, not a filter:** `<logger name="Exposed" level="INFO" />` in `logback.xml`.
Pinned rather than left to inherit, because inheriting means one change to `<root>` — the obvious thing to
do while debugging — starts writing personal data to Cloud Logging. With the pin, raising root has no
effect on Exposed; enabling SQL logging requires a deliberate, reviewable edit to that element.

**Never raise it in a deployed environment.** For local debugging it is fine, and `SKOPEO_TEST_FORKS`-style
env gating was considered and rejected: a logging level that can be turned on by configuration is exactly
the hazard, so it should require a code change that shows up in review.

Two `PiiLeakTest` cases guard this, and both were confirmed to fail when the pin is removed — one asserts
the shipped `logback.xml` still declares it, the other drives a real insert with root at DEBUG and asserts
nothing leaks. Note the deliberate contrast with the Exposed-retry case in the same class, which raises the
`Exposed` logger *itself* to DEBUG on purpose: that one polices the filter's scrubbing of an
exception-bearing event. Opposite sides of the same logger, not a contradiction.

This gap predates the Exposed 1.0 upgrade (#1024) — verified by reproducing it on 0.61 — and was found
while re-pointing the retry test during that work.

Deliberately still open: redacting value types in the domain model (**#801**), as defence in depth against
rule 1 rather than a substitute for it.

## `@Redacted`: keeping a value out of every `toString()` (#801, #822, #825)

Some values must never reach a log line, and the realistic way they get there is not a deliberate
`logger.info { user.email }` — it is interpolating a whole object: `logger.info { "provisioned $user" }`.
Every model here is a `data class`, so Kotlin's generated `toString()` covers **every** field, and one such
line publishes whatever the object holds, forever, with nothing in the build objecting.

The [redacted compiler plugin](https://github.com/ZacSweers/redacted-compiler-plugin) rewrites that
generated `toString()` at compile time:

```kotlin
data class User(
    @Redacted val dateOfBirth: LocalDate?,   // type unchanged
    @Redacted val firebaseUid: String?,      // accessors unchanged
    val publicCode: String,
)

// User(publicCode=K7Q2MX, firebaseUid=***, dateOfBirth=***, …)
```

The mask is configured in `build.gradle.kts` as `***` rather than the plugin's default `██`, so the
output is byte-identical to what the hand-rolled wrapper produced and every existing assertion and log
sample still reads the same.

### Using it

Annotate the property. That is the entire procedure — the field keeps its type, so **no call site
changes**: no wrapping at the boundary, no unwrapping where the value is needed, no mapper changes at
either #633 boundary.

`@Unredacted` opts a single property out when a whole class is annotated.

### What is annotated

| Target | Holds |
| --- | --- |
| `IssuedApiKey.plaintext`, `GeneratedClaimCode.plaintext`, `ApiKeyCrypto.GeneratedKey.plaintext` | a **working credential** — only its hash is persisted |
| `VerifiedFirebaseToken.email` / `.providerUid` | raw verified identity, built on every authenticated request |
| `Contact.value` and `ContactInfo.value` | email/phone, stored and incoming forms |
| `Invite.email` | invitee address |
| `User.firebaseUid` / `.dateOfBirth`, and the same fields on `ProvisionUserCommand`, `ProfilePatch`, `CreatePlaceholderCommand`, `PendingAssessment` | personal data |

Both contact forms are covered deliberately: covering one leaves the other leaking, and services handle
`Contact` far more often than `ContactInfo`.

**The cost/benefit arithmetic that used to exclude fields is gone.** `User.dateOfBirth` (~126 reads) and
`User.firebaseUid` (~238) were once weighed field by field, because wrapping changed the declared type
and every read had to be touched. An annotation costs one line, so the question "is this field worth
protecting" no longer has a price attached to it.

### What this replaced, and what that fixes

`Redactable<T>` (#801/#822) was a `value class` whose `toString()` was `***`. It worked, and it cost
call-site churn: every construction needed `.asRedactable()`, every read `.revealed`. Removing it
deleted **341 wrapping calls and 68 unwrapping calls across 136 files**.

Two documented blind spots disappear with it, because the property now keeps its raw type and only the
*enclosing* class's `toString()` is rewritten:

- **`.toString()` on the field no longer yields `"***"`.** This one had reached production code: two DTO
  mappers did `dateOfBirth?.toString()`, so `UserResponse.dateOfBirth` and
  `PendingAssessmentResponse.dateOfBirth` would have shipped a redacted placeholder to clients instead of
  the date. It compiled cleanly, because `toString()` exists on everything. It cannot recur.
- **A reader that legitimately needs the value gets it.** Wrapping `Contact.value` had turned an
  audit-log summary into `"Enabled EMAIL ***"` with no compile error. `ContactService` and `InviteService`
  record the address in the audit table on purpose, and now simply read the field.

### The trade, stated plainly

The wrapper covered one thing the annotation does not: **field-level interpolation**.
`logger.info { "${user.dateOfBirth}" }` used to print `***`, because the wrapper's own `toString()`
redacted. It now prints the date.

That was never in scope — `Redactable`'s own KDoc said it stopped `"$user"` and not
`"${user.dateOfBirth.revealed}"` — but the wrapper did cover it incidentally, and writing `.revealed`
was a visible act a reviewer could grep for. Both are gone.

What covers it instead is **behavioural rather than type-level**: `PiiLeakTest` (#806) drives real flows
with canary values and asserts no encoded log line contains them, which is independent of how redaction
is implemented, and the clean-sources rules above. That is the right layer for it — a type could never
have stopped a deliberate read anyway.

### What still enforces adoption

`RedactionConventionTest` (#822) fails the build when a sensitive-looking field in `domain/model` or
`domain/service` is declared without `@Redacted` and without an allowlist entry giving the reason. It was
retargeted rather than rewritten: the rule is unchanged, it just looks for the annotation instead of the
wrapper type. It also now asserts the plugin is **wired and masking with `***`** — a compiler plugin that
silently fails to run leaves code that compiles and tests that pass, with the protection simply absent.

### The gap none of this closes (#992)

`Contact.value` is protected and a Postgres unique violation still printed the address, because Postgres
composed that sentence from the column, inside the driver, with no Kotlin call site in between. Neither a
wrapper nor an annotation can protect a value the application never formats. See "Driver-composed
messages" above for the guard that covers that class of leak.

### Upgrading Kotlin

A compiler plugin binds to a compiler version. `1.18.0` integrates Metro's compiler-compat infra to
support a range of them, and is verified here against Kotlin 2.4.20 on **both** `compileKotlin` and
`compileTestKotlin` — check both, because `1.14.0-alpha01` passed the first and failed the second. Treat
a Kotlin bump as also a plugin-compatibility question, the same way `detekt` is
(`docs/engineering/operations/JVM_COMPATIBILITY.md`).

## References

- [Cloud Logging: structured logging](https://cloud.google.com/logging/docs/structured-logging)
- [Cloud Logging: LogSeverity](https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#logseverity)
- [Cloud Error Reporting: formatting error messages](https://cloud.google.com/error-reporting/docs/formatting-error-messages)
- [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder)
