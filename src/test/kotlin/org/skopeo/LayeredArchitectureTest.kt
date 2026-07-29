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
 *  - model is pure domain — it depends on no other app layer (not even mapper);
 *  - dto is a pure boundary record — it never reaches routes/repository/service, and never model
 *    (save for a small allowlist of shared `@Serializable` value types used as a wire contract, below);
 *  - mapper owns the dto↔model translation — it depends on dto + model only, never routes/repository/service;
 *  - service never depends on the transport (routes); it MAY call mapper (one-way, acyclic).
 *
 * dto↔model exemption: three v1 stateless-calculator contract DTOs (`RankingCalculationRequest`/`Response`,
 * `RatingChange`) and the two points-config responses (`OpenPlayConfigResponse`, `TournamentConfigResponse`)
 * deliberately embed shared `@Serializable` domain value types (`Team`, `MatchScore`, `PlayerProfile`,
 * `Rating`, `RatingCalculationOptions`, `OpenPlayPointsConfig`, `TournamentPointsConfig`) directly as their
 * wire format, rather than mirroring them. Fully decoupling that contract is a separate, larger change; until
 * then these classes are the only sanctioned dto→model dependency. Uses ArchUnit (bytecode-based) — chosen
 * over Konsist for robustness against the Kotlin compiler version (see #69).
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
        ".*\\.(RankingCalculationRequest|RankingCalculationResponse|RatingChange|" +
            "OpenPlayConfigResponse|TournamentConfigResponse).*"

    @Test
    fun `repository does not depend on routes, service, dto, or mapper`() {
        noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..routes..", "..service..", "..dto..", "..mapper..")
            .check(classes)
    }

    @Test
    fun `model is pure domain and depends on no other app layer`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.model..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..routes..", "..service..", "..repository..", "..dto..", "..mapper..")
            .check(classes)
    }

    @Test
    fun `dto does not depend on routes or repository`() {
        noClasses()
            .that().resideInAPackage("..dto..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..", "..repository..")
            .check(classes)
    }

    @Test
    fun `dto does not depend on service`() {
        noClasses()
            .that().resideInAPackage("..dto..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..")
            .check(classes)
    }

    @Test
    fun `dto is a pure record and does not depend on model, save the wire-contract value types`() {
        noClasses()
            .that().resideInAPackage("..dto..")
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
    fun `service does not depend on the transport layer (routes)`() {
        noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..")
            .check(classes)
    }
}
