package org.lange.tennis.levelr.service.calculator.impl.v2

import java.math.BigDecimal

class MatchClassifier {
    data class MatchClassification(
        val isUpset: Boolean,
        val dominanceFactor: BigDecimal,
    )
}
