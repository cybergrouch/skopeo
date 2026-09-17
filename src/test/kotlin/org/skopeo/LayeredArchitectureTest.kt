// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits
import org.junit.jupiter.api.Test

/**
 * Enforces the layered package dependencies (issues #69, mapper extraction) so they don't erode. The
 * invariants below are the dependency directions that actually hold in this codebase:
 *  - repository is the foundation — it never reaches up to routes/service/dto/mapper;
 *  - model is the domain — it never reaches routes/service/repository/dto/mapper; it MAY depend on the
 *    neutral `common` foundation (e.g. the `Capability` enum);
 *  - dto (`common.dto`) is a pure boundary record — it never reaches routes/repository/service, and never
 *    model (save for a small allowlist of shared `@Serializable` value types used as a wire contract, below).
 *    It lives UNDER `common` as the shared wire boundary both routes and service depend on, but is NOT a
 *    pure leaf (the allowlisted DTOs embed model), so the `common`-leaf rule below excludes `common.dto`;
 *  - mapper owns the dto↔model translation — it depends on dto + model only, never routes/repository/service;
 *  - service never depends on the transport (routes); it MAY call mapper (one-way, acyclic);
 *  - routes never depend on mapper OR model — translation is hidden behind the service (services
 *    return/accept DTOs) and all HTTP input is parsed service-side, so a route only touches service + dto
 *    (+ the neutral `common` packages). routes↛model is enforced with no exception;
 *  - common (`common.{error,security,contract}`) is the sanctioned cross-cutting foundation — dependency-free
 *    shared value types (ServiceError, the auth principals + the `Capability` enum, the serializable
 *    points-config contracts). ANY layer may depend on common; common depends on nothing above it, not even model.
 *
 * dto↔model exemption: three stateless-calculator contract DTOs (`RankingCalculationRequest`/`Response`,
 * `RatingChange`) still embed shared `@Serializable` domain value types (`Team`, `MatchScore`,
 * `PlayerProfile`, `Rating`, `RatingCalculationOptions`) directly as their wire format, rather than mirroring
 * them. Fully decoupling that contract is a separate, larger change; until then these classes are the only
 * sanctioned dto→model dependency. (The points-config types were relocated to `org.skopeo.common.contract`, so
 * `OpenPlayConfigResponse`/`TournamentConfigResponse` no longer count.) Uses ArchUnit (bytecode-based) —
 * chosen over Konsist for robustness against the Kotlin compiler version (see #69).
 */
@Suppress("NamedArguments") // ArchUnit's fluent Java DSL has no Kotlin parameter names to name.
class LayeredArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.skopeo")

    // The dto classes exempt from the dto↛model rule (their `$serializer`/`$Companion` synthetics included):
    // shared serializable value types are embedded as the wire contract. See the class KDoc.
    private val wireContractDtos =
        ".*\\.(RankingCalculationRequest|RankingCalculationResponse|RatingChange).*"

    @Test
    fun `repository does not depend on routes, service, dto, or mapper`() {
        noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..routes..", "..service..", "org.skopeo.common.dto..", "..mapper..")
            .check(classes)
    }

    @Test
    fun `model is pure domain and depends on no other app layer`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.domain.model..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..routes..",
                "..service..",
                "..repository..",
                "org.skopeo.common.dto..",
                "..mapper..",
                "org.skopeo.repository.persistence..",
            )
            .check(classes)
    }

    @Test
    fun `persistence entities are a leaf and depend on no other app layer including model`() {
        // The entity/data-model layer (#633): dumb raw-row types the repository returns, in the
        // `org.skopeo.repository.persistence` sub-package. Kept a leaf — the `mapper.entity` package
        // converts entity→domain, so nothing flows the other way. May depend only on `common` and its own
        // sibling entities (an aggregate entity references its child entities). The forbidden `repository`
        // target is the EXACT `org.skopeo.repository` package (the repositories) — NOT `..repository..`,
        // which would also match this sub-package and wrongly flag those intra-entity references.
        noClasses()
            .that().resideInAPackage("org.skopeo.repository.persistence..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..routes..",
                "..service..",
                "org.skopeo.repository",
                "org.skopeo.common.dto..",
                "..mapper..",
                "org.skopeo.domain.model..",
            )
            .check(classes)
    }

    @Test
    fun `common is a cross-cutting foundation and never depends on model or any app layer`() {
        // `common.{error,security,contract}` hold shared, dependency-free value types — ServiceError (#115),
        // the auth principals + the Capability enum (#597/#106), and the serializable points-config
        // contracts (#552). ANY layer (including model) may depend on them; they depend on nothing above,
        // not even model. This rule EXCLUDES `common.dto` (`resideOutsideOfPackage`): the DTO wire boundary
        // lives under `common` but is NOT a pure leaf — the three wire-contract DTOs embed `domain.model`
        // value types (see the dto↔model allowlist rule). `common.dto` is governed by the dto rules below.
        noClasses()
            .that().resideInAPackage("org.skopeo.common..")
            .and().resideOutsideOfPackage("org.skopeo.common.dto..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..routes..",
                "..service..",
                "..repository..",
                "org.skopeo.common.dto..",
                "..mapper..",
                "org.skopeo.domain.model..",
                "org.skopeo.repository.persistence..",
            )
            .check(classes)
    }

    @Test
    fun `dto does not depend on routes or repository`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.common.dto..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..", "..repository..")
            .check(classes)
    }

    @Test
    fun `dto does not depend on service`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.common.dto..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..")
            .check(classes)
    }

    @Test
    fun `dto is a pure record and does not depend on model, save the wire-contract value types`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.common.dto..")
            .and().haveNameNotMatching(wireContractDtos)
            .should().dependOnClassesThat().resideInAPackage("org.skopeo.domain.model..")
            .check(classes)
    }

    @Test
    fun `mapper does not depend on routes, repository, or service`() {
        // The forbidden `repository` target is the EXACT `org.skopeo.repository` package (the repositories),
        // NOT `..repository..` — the latter would also match `org.skopeo.repository.persistence`, and
        // `mapper.entity` legitimately depends on those entities to convert them to domain models.
        noClasses()
            .that().resideInAPackage("..mapper..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..", "org.skopeo.repository", "..service..")
            .check(classes)
    }

    @Test
    fun `dto mappers do not depend on persistence`() {
        // Two distinct mapper packages, both consumed only by the service layer: `mapper.dto` owns the
        // dto↔model translation and must never see persistence entities — that is `mapper.entity`'s job.
        noClasses()
            .that().resideInAPackage("org.skopeo.domain.mapper.dto..")
            .should().dependOnClassesThat().resideInAPackage("org.skopeo.repository.persistence..")
            .check(classes)
    }

    @Test
    fun `entity mappers do not depend on dto`() {
        // `mapper.entity` owns the entity↔domain translation (persistence ⟷ model) and must never touch
        // DTOs — the wire contract is `mapper.dto`'s concern. Together the two rules keep the mapper sets
        // cleanly separated even though both live under `..mapper..`. `allowEmptyShould` because the
        // package is intentionally empty until the #633 flip PRs populate it (this rule guards it early).
        noClasses()
            .that().resideInAPackage("org.skopeo.domain.mapper.entity..")
            .should().dependOnClassesThat().resideInAPackage("org.skopeo.common.dto..")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `service does not depend on the transport layer (routes)`() {
        noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..")
            .check(classes)
    }

    @Test
    fun `routes do not depend on mapper`() {
        // dto↔model translation is hidden behind the service layer: services return response DTOs (and
        // accept request DTOs), so a route calls service.* and responds with the DTO it gets back — it
        // never invokes a mapper.
        noClasses()
            .that().resideInAPackage("..routes..")
            .should().dependOnClassesThat().resideInAPackage("..mapper..")
            .check(classes)
    }

    @Test
    fun `routes do not depend on model`() {
        // The transport layer speaks only DTOs (+ the neutral error/security/contract packages): responses
        // are DTOs, request bodies are DTOs or `contract` value types, and all query/path input is parsed
        // service-side. No exception — the former wire-contract request bodies (points-config) now live in
        // `org.skopeo.common.contract`.
        noClasses()
            .that().resideInAPackage("..routes..")
            .should().dependOnClassesThat().resideInAPackage("org.skopeo.domain.model..")
            .check(classes)
    }

    /**
     * The two rules below are the only *signature-shaped* ones in this class; every other rule is a
     * whole-package dependency rule. That is deliberate, and worth explaining, because a package rule
     * would be the obvious tool and cannot express this one.
     *
     * `service → persistence` is **legal by design**: a service calls a repository, receives raw
     * `<X>Entity` rows and converts them via `mapper.entity`. What is not legal is a service **exposing**
     * one across its own public boundary, because `routes` may not depend on `persistence` at all — so a
     * leaked entity would either break the routes rule or force a row type into the transport layer.
     * No `dependOnClassesThat` rule can say that: the dependency it would have to forbid is one we
     * deliberately allow.
     *
     * `LAYERED_ARCHITECTURE.md` already asserts this ("Services return response DTOs and accept request
     * DTOs") under a heading that calls the list "Enforced invariants". Until now that half was
     * aspirational — nothing failed if a service started returning an entity. These rules close it (#1029).
     *
     * **Constructors are in scope**, hence `noCodeUnits()` rather than `noMethods()`: a service
     * constructor taking an entity is the same leak as a method taking one.
     *
     * One caveat for whoever debugs a future failure: ArchUnit reads **bytecode**, and Kotlin `internal`
     * members compile to *public* JVM methods (with mangled names). So an `internal` service function
     * handling an entity would be caught here despite not being part of the public Kotlin API. That is
     * the conservative direction, and there are no such cases today.
     */
    private val persistenceEntity =
        DescribedPredicate.describe<JavaClass>(
            "a raw persistence entity",
        ) { javaClass -> javaClass.packageName.startsWith("org.skopeo.repository.persistence") }

    private val anyPersistenceEntity =
        DescribedPredicate.describe<List<JavaClass>>(
            "any raw persistence entity",
        ) { parameterTypes -> parameterTypes.any(persistenceEntity::test) }

    @Test
    fun `service never accepts a persistence entity in a public signature`() {
        noCodeUnits()
            .that().areDeclaredInClassesThat().resideInAPackage("org.skopeo.domain.service..")
            .and().arePublic()
            .should().haveRawParameterTypes(anyPersistenceEntity)
            .because(
                "a service may hold a persistence entity internally, but exposing one hands a raw " +
                    "as-stored row type to its callers — and routes cannot depend on `persistence` at all",
            )
            .check(classes)
    }

    @Test
    fun `service never returns a persistence entity from a public signature`() {
        noCodeUnits()
            .that().areDeclaredInClassesThat().resideInAPackage("org.skopeo.domain.service..")
            .and().arePublic()
            .should().haveRawReturnType(persistenceEntity)
            .because(
                "services return response DTOs and domain models; returning a `persistence` entity " +
                    "leaks the storage shape past the boundary `mapper.entity` exists to guard",
            )
            .check(classes)
    }
}
