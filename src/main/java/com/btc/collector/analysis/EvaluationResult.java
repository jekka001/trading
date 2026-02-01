package com.btc.collector.analysis;

import com.btc.collector.strategy.MarketRegime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Result of strategy evaluation combining pattern matching,
 * historical indicators, strategy performance, and market regime.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResult {

    // Base probability from pattern matching
    private BigDecimal baseProbability;

    // Strategy performance weight (from StrategyTracker)
    private BigDecimal strategyWeight;

    // Historical indicator factor (typically 0.5 - 1.5)
    private BigDecimal historicalFactor;

    // Market regime adjustment factor (typically 0.7 - 1.1)
    private BigDecimal regimeFactor;

    // Market regime confidence (0.0 - 1.0)
    private double regimeConfidence;

    // Detected market regime
    private MarketRegime marketRegime;

    // Final combined probability
    private BigDecimal finalProbability;

    // Whether history-based evaluation was used
    private boolean historyEnabled;

    // Whether strategy is allowed in current regime
    @Builder.Default
    private boolean strategyAllowedInRegime = true;

    /**
     * Get regime confidence as percentage string.
     */
    public String getRegimeConfidencePercent() {
        return String.format("%.0f%%", regimeConfidence * 100);
    }
}
