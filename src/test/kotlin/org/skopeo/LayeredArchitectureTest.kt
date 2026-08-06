// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Enforces the layered package dependencies (issues #69, mapper extraction) so they don't erode. The
 * invariants below are the dependency directions that actually hold in this codebase:
 *  - repository is the foundation — it never reaches up to routes/service/dto/mapper;
 *  - model is the domain — it never reaches routes/service/repository/dto/mapper; it MAY depend on the
 *    neutral `common` foundation (e.g. the `Capability` enum);
 *  - dto is a pure boundary record — it never reaches routes/repository/service, and never model
 *    (save for a small allowlist of shared `@Serializable` value types used as a wire contract, below);
 *  - mapper owns the dto↔model translation — it depends on dto + model only, never routes/repository/service;
 *  - service never depends on the transport (routes); it MAY call mapper (one-way, acyclic);
 *  - routes never depend on mapper OR model — translation is hidden behind the service (services
 *    return/accept DTOs) and all HTTP input is parsed service-side, so a route only touches service + dto
 *    (+ the neutral `common` packages). routes↛model is enforced with no exception;
 *  - common (`common.{error,security,contract}`) is the sanctioned cross-cutting foundation — dependency-free
 *    shared value types (ServiceError, the auth principals + the `Capability` enum, the serializable
 *    points-config contracts). ANY layer may depend on common; common depends on nothing above it, not even model.
 *
 * dto↔model exemption: three v1 stateless-calculator contract DTOs (`RankingCalculationRequest`/`Response`,
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
            .resideInAnyPackage("..routes..", "..service..", "org.skopeo.dto..", "..mapper..")
            .check(classes)
    }

    @Test
    fun `model is pure domain and depends on no other app layer`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.model..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..routes..",
                "..service..",
                "..repository..",
                "org.skopeo.dto..",
                "..mapper..",
                "org.skopeo.persistence..",
            )
            .check(classes)
    }

    @Test
    fun `persistence entities are a leaf and depend on no other app layer including model`() {
        // The entity/data-model layer (#633): dumb raw-row types the repository returns. Kept a leaf like
        // `model`/`common` — repositories map rows to entities; the `mapper.entity` package converts
        // entity→domain, so nothing flows the other way. May depend only on `common`.
        noClasses()
            .that().resideInAPackage("org.skopeo.persistence..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..routes..",
                "..service..",
                "..repository..",
                "org.skopeo.dto..",
                "..mapper..",
                "org.skopeo.model..",
            )
            .check(classes)
    }

    @Test
    fun `common is a cross-cutting foundation and never depends on model or any app layer`() {
        // `common` holds shared, dependency-free value types — ServiceError (#115), the auth principals
        // + the Capability enum (#597/#106), and the serializable points-config contracts (#552). ANY
        // layer (including model) may depend on common; common itself depends on nothing above it — not
        // even model. This is the sanctioned cross-cutting package.
        noClasses()
            .that().resideInAPackage("org.skopeo.common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..routes..",
                "..service..",
                "..repository..",
                "org.skopeo.dto..",
                "..mapper..",
                "org.skopeo.model..",
                "org.skopeo.persistence..",
            )
            .check(classes)
    }

    @Test
    fun `dto does not depend on routes or repository`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.dto..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..", "..repository..")
            .check(classes)
    }

    @Test
    fun `dto does not depend on service`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.dto..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..")
            .check(classes)
    }

    @Test
    fun `dto is a pure record and does not depend on model, save the wire-contract value types`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.dto..")
            .and().haveNameNotMatching(wireContractDtos)
            .should().dependOnClassesThat().resideInAPackage("org.skopeo.model..")
            .check(classes)
    }

    @Test
    fun `mapper does not depend on routes, repository, or service`() {
        noClasses()
            .that().resideInAPackage("..mapper..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..", "..repository..", "..service..")
            .check(classes)
    }

    @Test
    fun `dto mappers do not depend on persistence`() {
        // Two distinct mapper packages, both consumed only by the service layer: `mapper.dto` owns the
        // dto↔model translation and must never see persistence entities — that is `mapper.entity`'s job.
        noClasses()
            .that().resideInAPackage("org.skopeo.mapper.dto..")
            .should().dependOnClassesThat().resideInAPackage("org.skopeo.persistence..")
            .check(classes)
    }

    @Test
    fun `entity mappers do not depend on dto`() {
        // `mapper.entity` owns the entity↔domain translation (persistence ⟷ model) and must never touch
        // DTOs — the wire contract is `mapper.dto`'s concern. Together the two rules keep the mapper sets
        // cleanly separated even though both live under `..mapper..`.
        noClasses()
            .that().resideInAPackage("org.skopeo.mapper.entity..")
            .should().dependOnClassesThat().resideInAPackage("org.skopeo.dto..")
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
            .should().dependOnClassesThat().resideInAPackage("org.skopeo.model..")
            .check(classes)
    }
}
