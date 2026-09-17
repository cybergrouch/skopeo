<!--
SPDX-FileCopyrightText: 2026 Lange Pantoja
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Test suite performance

What the backend suite costs, the one knob that controls it, how to profile it — and, most
importantly, **what cannot be measured**, so the next person does not spend a day proving that.

Written out of #978. The numbers below are from CI runs in September 2026.

## Shape of the suite

| | |
|---|---|
| Suites / tests | **162 / 1713** |
| Sequential total (1 fork) | **~547s** |
| DB-backed suites | **~106 of 162** — roughly 70% is integration-weight |
| `build` job wall time | **6-9 minutes** (was a tight ~12.9m before #1038) |

The suite is slow because it **serialises on one database**, not because any individual test is slow.
Median suite is 1.27s; the slowest is ~52s.

## The one knob: `SKOPEO_TEST_FORKS`

```bash
./gradlew test                        # 1 fork — unchanged, the default
SKOPEO_TEST_FORKS=4 ./gradlew test    # opt in locally
```

Set in `build.gradle.kts`; CI passes **4** in `.github/workflows/ci.yml`, matching `ubuntu-latest`'s
4 vCPU.

**Default 1, deliberately.** Each fork costs a JVM *and* a Postgres container. That is free on a
runner and punishing on a constrained developer machine, so local parallelism is an opt-in act rather
than something that silently saturates the machine.

### Why it is safe — the isolation model is untouched

`PostgresTestDatabase` is a Kotlin `object`, i.e. a per-JVM singleton that starts its own container,
migrates it, and truncates between tests. N Gradle forks are N JVMs, so they get **N independent
containers** on Testcontainers-assigned ports. There is no `withReuse`, no fixed host port and no
cross-JVM coordination, so sharing is structurally impossible rather than merely unlikely.

Verified rather than assumed: a 2-fork run produced two distinct containers (`localhost:61550` and
`:61551`), 11 suites, 138 tests, 0 failures.

> **A smaller probe proved nothing.** An earlier 4-suite probe showed only *one* container: with too
> little work Gradle starts a second executor but never hands it a DB-backed class, so the interesting
> case never occurred. If you re-verify this, use enough suites to spread the work.

### Fork count: 4, and tuning it further is not worth it

Measured properly — 18 CI runs, 6 per arm, same commit, only the fork count differing:

| arm | median | mean | IQR |
|---|---:|---:|---|
| forks=2 | 450s | 488s | 442-542s |
| forks=3 | **510s** | 484s | 394-559s |
| forks=4 | 452s | **456s** | 380-546s |

IQRs overlap; there is no separable difference. Note `forks=3` looked **25% faster** after three
samples and finished with the *worst* median after six. Two adjacent numbers are not a trend.

## Profiling

Every CI run emits the data — added in #1037, because CI had been generating it and throwing it away
(`dorny/test-reporter` is configured `list-suites: failed`, so passing suites contributed no timing
information anywhere).

- **Run Summary** → "Test timing profile": slowest 20 suites, plus sequential total and slowest single
  suite.
- **Artifact** `backend-test-results` (14-day retention): the raw JUnit XMLs, so a profile can be
  analysed off-box without re-running a 12-minute suite on a constrained machine.

```bash
gh run download <run-id> -R cybergrouch/skopeo -n backend-test-results -D /tmp/prof
```

### The diagnostic that actually finds defects

Per-test timings hide setup cost: **JUnit bills field initialisation to neither the test method nor
any reported step.** So look at the **gap between a suite's reported time and the sum of its tests**.

That gap is how #1043 was found: `LayeredArchitectureTest` reported ~54s locally while its 15 tests
summed to 2.6s. Its `ClassFileImporter` was an *instance* field, and JUnit 5 defaults to
`Lifecycle.PER_METHOD`, so the entire bytecode graph was re-imported once per test — 15 imports to
check 15 rules. Nothing in the report pointed at a slow test, because there wasn't one.

```python
# per suite: suite_time - sum(test_times)
# ~0.1s  -> clean
# >10s   -> hidden per-instance or per-class setup, worth a look
```

**Expect exactly N large gaps for N forks.** Each fork pays one `PostgresTestDatabase.start()`
(container boot + 60 Flyway migrations, ~18s), billed to whichever suite reaches the singleton first.
At 4 forks, four suites show a ~10-25s gap and shuffle between runs. Those are **not** defects, and
"fixing" one re-elects another.

## ⚠️ What cannot be measured

**CI runner hardware dominates the measurement.** Every arm of the fork experiment produced a
**bimodal** distribution — two clusters roughly 150s apart:

```
forks=2: 440, 441, 447, 452  |  572, 577
forks=4: 334, 371, 406       |  498, 562, 564
```

GitHub's hosted pool is not homogeneous, and which machine you land on outweighs most things under
your control.

**Practical rule: do not trust a per-suite CI delta below roughly 50s**, and never from a single run.
Two illustrations, both from #978:

- The `forks=3` "25% win" that reversed with more samples.
- #1043's local 54.3s → 10.2s did **not** reproduce on CI: post-fix runs measured 14.1s and 30.1s for
  the same suite, straddling the 28.5s pre-fix baseline. The change is still correct — one import
  beats fifteen — but its CI benefit is unproven, and the local benefit is what matters for the
  constrained-machine case.

If you need to compare two configurations, use **≥6 repeats per arm on the same commit** (`gh run
rerun` gives repeats without content changes) and compare medians *and* IQRs. Branches run in
parallel because `concurrency` is keyed on `github.ref`, so three arms cost one round of wall time,
not three.

## Tried and rejected

| Idea | Why not |
|---|---|
| Tune the fork count below/above 4 | No separable difference across 18 runs |
| Split fast unit tests into their own task | The 69 sub-second suites are 43% of suites but **2% of the time (13s)**. A feedback-latency change, not a speed-up |
| CI matrix by package | Needs JaCoCo report merging before the coverage gate and `diff-cover`; forking got the same win without touching either |
| Make `MigrationChecksumManifestTest` container-free | Its 11.4s is the shared container startup, which relocates rather than disappears. Computing checksums offline would discard the property #854 chose deliberately — comparing the manifest against what a database *actually recorded* |

## Where the remaining headroom is

Not in the test suite. Roughly **3.8 minutes** of the `build` job is non-test work — compile, ktlint,
detekt, JaCoCo report and coverage verification — and it is untouched by test parallelism. That, or a
larger runner, is the next real lever.

## Related

#978 (the parallelisation work), #1037 (profiling), #1038 (forks), #1043 (the import defect),
`DB_MIGRATIONS.md` (why CI cannot validate a tightening migration — the same empty-container property
that makes the suite correct also limits what it can prove).
