# Input validation belongs at the route boundary

Issue #116. The **route layer validates request shape**; **services trust their typed inputs** and
enforce only *business rules*. This keeps "is this a well-formed request?" (a 400) separate from
"is this allowed / possible given current state?" (a domain outcome), and pairs with #115 (service
`Result` types).

## The split

- **Input validation → routes** (a `400`): required fields, enum membership, parseable
  dates/UUIDs/intervals, simple numeric ranges, value formats (e.g. email shape). Routes parse raw
  request strings into validated, typed values and pass those to services. Shared helpers live in
  `routes/RouteSupport.kt` (`uuidParam`, and `parseEnumParam<T>` added here).
- **Business rules → services**: authorization, existence, and state-dependent rules. These stay in
  services and surface as `Forbidden` / `NotFound` / `Conflict`.

DTO `init` blocks remain an acceptable first line of *shape* validation (they already map to `400`
via `respondMappingErrors`); prefer the route boundary for new validation.

## Convention established (exemplar)

`CapabilityService.grant`/`revoke` now take a typed `Capability` (was a `String` it parsed via
`Capability.valueOf`). `CapabilityRoutes` parses the value with `parseEnumParam<Capability>(...)`
(400 on an unknown name). The invalid-capability check is now a route-level test
(`CapabilityApiIntegrationTest`), not a service test.

## Audit — service-layer input validation to migrate

| Service | Input validation (→ route) | Business rules (stay) |
|---|---|---|
| **CapabilityService** | ~~`parseCapability` (enum)~~ ✅ migrated | admin-only; PLAYER not revocable; last/own ADMINISTRATOR guards; user exists |
| **NameService** | ~~`parseType` (NameType enum)~~ ✅ migrated | self-or-admin; display-name can't be disabled; name exists |
| **ContactService** | ~~`parseType` (ContactType), `parseStatus` (VerificationStatus), `parseMethod` (VerificationMethod)~~ ✅ migrated | self-or-admin (edit) / admin-only (verify); contact exists; active-to-verify; VERIFIED→ADMIN_OVERRIDE default |
| **RatingService** | ~~`parseConfidence` (0..1 range); NTRP value range/format~~ ✅ migrated | admin-only; user exists. (Level *derivation* stays in the service.) |
| **MatchService** | `matchType`/`matchFormat` enums, `matchDate` date, team-id UUIDs, composition (players/side), score validity (non-negative games, single-set count, clear winner) | staff-only; participants/staff read; not-disabled; not-already-completed; rated-lock. **Coordinate with #108** (removes `matchFormat`). |
| **UserService.search** | ~~`validatedSex` (enum), `age`/`rating` interval parsing~~ ✅ migrated | staff-only; "at least one filter" + term normalization (trim/uppercase) stay in the service — the presence check is coupled to normalization |
| **InviteService** | ~~`normalizeEmail` (email shape)~~ ✅ migrated | admin-only |

✅ = migrated in this PR. The rest follow incrementally, one service/route at a time, preserving the
current status codes and messages.
