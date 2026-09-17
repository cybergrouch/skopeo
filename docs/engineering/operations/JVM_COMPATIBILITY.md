# JVM Compatibility: Build Failure Investigation and Resolution

**Date:** 2026-06-10
**Status:** Resolved — configuration adopted, follow-up tracked (see [Future Work](#future-work))

This document records the investigation of a full build failure (`./gradlew build`),
the root causes found along the way, the JVM compatibility analysis for our
deployment targets (GCP / AWS), and the final JVM configuration adopted for the
project.

---

## TL;DR

| Concern | Decision |
|---|---|
| Compile toolchain (bytecode target) | **Java 25** (`build.gradle.kts`) — was 17 until #1030 |
| Gradle daemon / build tooling JVM | **Java 25**, pinned in `gradle/gradle-daemon-jvm.properties` (was 21 until #1008) |
| Docker build stage | `eclipse-temurin:25-jdk` (Debian-based) — must track the daemon pin; since #1030 it satisfies the compile toolchain too, so the old `jdk17` stage is gone |
| Docker runtime stage (**production JVM**) | `eclipse-temurin:25-jre-noble` (was 17 until #1008) |
| Production runtime upgrades | Change the Dockerfile runtime base image alone — but **no longer freely revertible** since #1030. The compile target is now 25, so the bytecode is class-file major 69 and will not load on a 17 JRE; going back means moving the toolchain back and rebuilding |
| Ceiling on build JVM | **None** since #1008. detekt 2.0.0-alpha.6 removed the Java 24 ceiling that 1.23.8 imposed |

---

## Symptom

`./gradlew build` failed with a cryptic, message-less error:

```
* What went wrong:
Execution failed for task ':detekt' (registered by plugin 'io.gitlab.arturbosch.detekt').
> 26.0.1
```

Running with `--stacktrace` revealed the real exception, thrown from the Kotlin
compiler **embedded inside detekt**:

```
Caused by: java.lang.IllegalArgumentException: 26.0.1
    at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:307)
    at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.current(JavaVersion.java:176)
    ...
```

The "error message" was just the Java version string of the JVM running the build.

## Root Cause

Two independent problems compounded:

### 1. detekt's bundled Kotlin compiler rejects Java 25+

detekt 1.23.8 (the latest 1.x release) bundles a Kotlin 2.0.21 compiler. That
compiler's IntelliJ `JavaVersion.parse` utility hardcodes an upper bound on the
Java versions it accepts — anything **≥ 25** throws `IllegalArgumentException`.

This is a known upstream issue:

- [detekt#8714 — Java 25 unsupported on v1.23.8](https://github.com/detekt/detekt/issues/8714)
- [detekt#8980 — Detekt fails to run using Java 25](https://github.com/detekt/detekt/issues/8980)

The parser bug was fixed in **Kotlin 2.1.20**, and the fix ships in **detekt 2.0**.
There is **no backport to the 1.x line**. As of June 2026, detekt 2.0 is at
`2.0.0-alpha.3` — no stable release yet.

Detekt runs **in-process inside the Gradle daemon**, so the daemon's JVM is what
matters. The `java { toolchain { 17 } }` block only governs *compilation* — which
is why everything except `:detekt` built fine.

### 2. The Gradle daemon was silently running on Java 26

`./gradlew --version` showed:

```
Daemon JVM:    /usr/local/Cellar/openjdk/26.0.1/... (no Daemon JVM specified, using current Java home)
```

The developer machine's `JAVA_HOME` pointed at a **jenv alias created in 2023**
(`~/.jenv/versions/20`) that symlinks to Homebrew's *unversioned* `openjdk`
formula. Homebrew upgrades that formula with every major release, so the alias
labeled "Java 20" silently became Java 26. Without an explicit daemon JVM
configuration, Gradle follows `JAVA_HOME` — and the build environment drifted
with every `brew upgrade`.

## Investigation

### Empirical ceiling test

With detekt unupgradable (1.23.8 is the latest stable; verified on Maven Central
and the Gradle plugin portal), we tested the daemon JVM version directly:

| Daemon JVM | detekt 1.23.8 result |
|---|---|
| 26 | crashes — `IllegalArgumentException: 26.0.1` |
| 25 (current LTS) | crashes — `IllegalArgumentException: 25.0.2` |
| 24 | **works** |
| 21 (LTS) | **works** |
| 17 (LTS) | **works** |

**Java 24 is the technical ceiling.** It is, however, a non-LTS release already
past end-of-life, making it a poor pin for teammates and CI images. **Java 21 is
the highest LTS under the ceiling.**

### Deployment platform JVM support (GCP / AWS, June 2026)

The project will deploy to GCP or AWS. Survey of managed Java support:

| Platform | Supported Java |
|---|---|
| AWS Lambda (managed runtimes) | `java25` (GA), `java21`, `java17`, `java11` |
| GCP Cloud Run / App Engine (managed runtimes) | Java 25 (GA), 21, 17 |
| Containers (Cloud Run, GKE, ECS/Fargate, App Runner) | **any JVM — ships inside the image** |

Sources: [AWS Lambda Java 25 announcement](https://aws.amazon.com/blogs/compute/aws-lambda-now-supports-java-25/),
[AWS Lambda runtimes](https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtimes.html),
[GCP supported Java versions](https://docs.cloud.google.com/java/docs/supported-java-versions),
[Cloud Run Java runtime](https://docs.cloud.google.com/run/docs/runtimes/java).

Two conclusions:

1. **Cloud platforms standardize on LTS releases** (17 / 21 / 25). Java 26 is
   non-LTS and will never be a managed runtime, so 25 is the relevant
   upgrade target.
2. **Skopeo deploys as a container**, so the production JVM comes from our own
   Dockerfile runtime base image. The platform imposes no JVM constraint, and —
   critically — **the detekt bug never affects production**: it constrains only
   the build-tooling JVM. Java 17 bytecode runs on any newer JRE, so upgrading
   the production runtime to 21 or 25 is a one-line Dockerfile change at any time.

## Resolution (Adopted Configuration)

### Pin the Gradle daemon to Java 25

`gradle/gradle-daemon-jvm.properties` (committed, applies to every machine and CI):

```properties
toolchainVersion=25
```

**Four places carry this number and must move together** (#1008). Three of them are not
obvious, and missing any one breaks the build rather than degrading it:

| Where | Why it must match |
|---|---|
| `gradle/gradle-daemon-jvm.properties` | the pin itself |
| `.github/workflows/ci.yml` — `java-version` | the daemon toolchain must be discoverable, or CI cannot start the build |
| `gradle.properties` — `org.gradle.java.installations.fromEnv` | names the CI env vars Gradle discovers toolchains from |
| `Dockerfile` — the `builder` stage base image | it COPYs `gradle/` in, and `auto-download=false` means no silent fallback |

Until #1008 this was 21, because detekt 1.23.8's bundled Kotlin compiler crashed on Java 25+
([detekt#8714](https://github.com/detekt/detekt/issues/8714)). detekt 2.0.0-alpha.6 fixed it,
verified by running the full detekt gate on 25 rather than taken from the changelog.

This decouples the build from the drifting `JAVA_HOME` on developer machines.
The compile toolchain remains Java 17, so produced bytecode is unchanged.

### Enable toolchain auto-provisioning

`settings.gradle.kts` adds the Foojay resolver so Gradle can download any JDK it
needs (daemon JVM or compile toolchain) on machines that don't have it:

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
```

### Fix the Docker build

The daemon pin initially broke `docker build`: the builder image only contained
JDK 17, which no longer satisfied the (committed) daemon criteria. Two changes:

- **Builder stage:** `eclipse-temurin:17-jdk-alpine` → `eclipse-temurin:21-jdk`.
  The image satisfies the daemon criteria natively, and the Foojay resolver
  provisions the Java 17 compile toolchain inside the container.
- **Debian instead of Alpine for the builder:** Foojay-provisioned JDK archives
  fail to unpack into a usable Java home on musl-based images
  (`Unpacked JDK archive does not contain a Java home`). The builder is
  Debian-based; image size is irrelevant for a multi-stage build stage.
- **Runtime stage:** `eclipse-temurin:25-jre-noble` since #1008 (this bullet previously said
  `17-jre-alpine`, stale on both counts — the move to Debian predates #1008, see the netty-tcnative
  note in the Dockerfile).

Verified end-to-end: `./gradlew clean build` green locally, `docker build`
succeeds, and the resulting container boots through configuration loading to the
expected database-connection attempt.

## Future Work

- **~~When detekt 2.0 stable is released:~~ DONE in #1008** — detekt moved to the
  new `dev.detekt` coordinates on 2.0.0-alpha.6 and the daemon `toolchainVersion`
  was raised to 25. **The compile toolchain followed in #1030**, so all three JVM
  dials are now on 25 and `jvmTarget = 25` is confirmed working on Kotlin 2.4.20.
- **~~Production runtime upgrade:~~ DONE in #1008** — the Dockerfile runtime stage
  is `eclipse-temurin:25-jre-noble`. Note it is Debian, **not** Alpine, and that is
  load-bearing: firebase-admin pulls in gRPC's precompiled glibc `netty-tcnative`,
  which segfaults on musl before the server ever listens. Do not "finish" this item
  by switching to `-alpine`.
- **Next JVM move: Java 29 (September 2027, LTS).** Java 27 GA'd 15 Sep 2026 but is
  **not** an LTS — the cadence is 21 → 25 → 29 — and gets roughly six months of
  support, so moving there would mean re-upgrading almost immediately. Java 25 has
  Oracle premier support to Sep 2030.
- **Developer machine hygiene (optional):** repoint `JAVA_HOME` away from the
  stale jenv alias `~/.jenv/versions/20` (it tracks Homebrew's unversioned
  `openjdk` formula and will keep drifting). With the daemon pin in place this
  no longer affects the build, but it still affects bare `java`/`gradle`
  invocations outside the wrapper.

## Related Issues Fixed in the Same Investigation

The original `./gradlew build` failure masked several unrelated problems,
documented here for completeness:

1. **Integration tests required a live PostgreSQL.** The database setup
   (2026-06-09, see [IMPLEMENTATION_LOG.md](../IMPLEMENTATION_LOG.md)) made
   `Application.module()` unconditionally initialize HikariCP/Flyway. All
   `testApplication`-based tests failed with
   `Property database.url not found`. Fixed by adding an
   `initDatabase: Boolean = true` parameter to `module()`; tests pass `false`.
2. **`application.yaml` was never actually loaded.** The
   `ktor-server-config-yaml` dependency was missing, and `main()` used
   `embeddedServer(...)` with a hardcoded port (which ignores config files).
   Fixed by adding the dependency and switching to `EngineMain` with a
   `ktor.application.modules` entry in the YAML.
3. **HOCON-style env-var syntax crashed Ktor's YAML parser.**
   `${DATABASE_URL:-default}` is not valid Ktor YAML syntax; replaced with
   Ktor's `"$DATABASE_URL:default"` form.
4. **detekt findings backlog.** Once detekt could run again, it reported 63
   pre-existing findings accumulated while it was crashing. Grandfathered via
   `detekt-baseline.xml`; new findings still fail the build.
5. **Coverage gate.** `DatabaseConfig` is unreachable without a live database
   and dragged JaCoCo below its thresholds; `**/config/**` is excluded from
   coverage (alongside the existing dto/model exclusions) until
   Testcontainers-based persistence tests exist.
6. **Stale `logback.xml` logger.** The logger name still referenced the
   pre-rename package (`org.lange.tennis.levelr`), so its level configuration
   matched nothing. Updated to `org.skopeo` as part of completing the project
   rename across docs and configuration.
