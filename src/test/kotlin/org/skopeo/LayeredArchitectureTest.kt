// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Enforces the layered package dependencies (issue #69) so they don't erode. The invariants below
 * are the dependency directions that actually hold in this codebase:
 *  - repository is the foundation — it never reaches up to routes/service/dto;
 *  - model is pure domain — it depends on no other app layer;
 *  - dto is a boundary type — it never reaches routes or repository;
 *  - service never depends on the transport (routes).
 *
 * Note: service and dto are intentionally coupled at the HTTP boundary (services accept request DTOs
 * and some return response DTOs; one DTO maps a service result type), so that relationship is left
 * unconstrained rather than forced into a command/result refactor. Uses ArchUnit (bytecode-based) —
 * chosen over Konsist for robustness against the Kotlin compiler version (see #69).
 */
@Suppress("NamedArguments") // ArchUnit's fluent Java DSL has no Kotlin parameter names to name.
class LayeredArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.skopeo")

    @Test
    fun `repository does not depend on routes, service, or dto`() {
        noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..", "..service..", "..dto..")
            .check(classes)
    }

    @Test
    fun `model is pure domain and depends on no other app layer`() {
        noClasses()
            .that().resideInAPackage("org.skopeo.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..", "..service..", "..repository..", "..dto..")
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
    fun `service does not depend on the transport layer (routes)`() {
        noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAnyPackage("..routes..")
            .check(classes)
    }
}
