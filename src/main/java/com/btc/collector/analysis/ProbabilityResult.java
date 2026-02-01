package com.btc.collector.analysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProbabilityResult {

    private BigDecimal probabilityUpPct;
    private BigDecimal avgProfitPct;
    private BigDecimal avgHoursToMax;
    private int matchedSamplesCount;

    // Current market snapshot for alert message
    private MarketSnapshot currentSnapshot;

    // Strategy tracking
    private String strategyId;
    private BigDecimal strategyWeight;

    // Historical indicator factor (from StrategyEvaluator)
    private BigDecimal historicalFactor;

    // Final probability = f(probabilityUpPct, strategyWeight, historicalFactor)
    private BigDecimal weightedProbability;

    public boolean meetsSignalConditions(BigDecimal minProbability, BigDecimal minProfit, int minSamples) {
        if (probabilityUpPct == null || avgProfitPct == null) return false;

        // Use weighted probability if available
        BigDecimal effectiveProbability = weightedProbability != null ? weightedProbability : probabilityUpPct;

        return effectiveProbability.compareTo(minProbability) >= 0
                && avgProfitPct.compareTo(minProfit) >= 0
                && matchedSamplesCount >= minSamples;
    }
}
