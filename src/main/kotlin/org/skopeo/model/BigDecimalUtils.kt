// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.math.RoundingMode

// Precision for all calculations (6 decimal places)
private const val CALCULATION_SCALE = 6
private val ROUNDING_MODE = RoundingMode.HALF_UP

private val BigDecimal.adjustedScale: BigDecimal
    get() = this.setScale(CALCULATION_SCALE, ROUNDING_MODE)

private fun String.toBigDecimalPrecise(): BigDecimal = BigDecimal(this).adjustedScale

private fun Int.toBigDecimalPrecise(): BigDecimal = BigDecimal(this).adjustedScale

private fun Double.toBigDecimalPrecise(): BigDecimal = BigDecimal(this).adjustedScale

/**
 * Convert BigDecimal to String preserving full precision (6 decimal places).
 * Used for rating change values and percentages to maintain exact precision.
 */
internal fun BigDecimal.toStringPrecise(): String =
    this.setScale(CALCULATION_SCALE, ROUNDING_MODE)
        .toPlainString()

internal fun BigDecimal.divideBy(divisor: BigDecimal): BigDecimal = this.divide(divisor, CALCULATION_SCALE, ROUNDING_MODE)

// Kotlin idiom: Extension properties for cleaner BigDecimal creation
internal val String.bd: BigDecimal get() = this.toBigDecimalPrecise()

internal val Int.bd: BigDecimal get() = this.toBigDecimalPrecise()

internal val Double.bd: BigDecimal get() = this.toBigDecimalPrecise()

internal val BigDecimal.asRating: Rating get() = Rating.fromValue(value = this.toStringPrecise())
