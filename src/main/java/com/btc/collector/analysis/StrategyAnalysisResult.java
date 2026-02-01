package com.btc.collector.analysis;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Result of analyzing a single strategy against current market conditions.
 */
@Data
@Builder
public class StrategyAnalysisResult {

    private String strategyId;
    private BigDecimal baseProbability;      // Raw probability from pattern matching
    private BigDecimal finalProbability;     // Weighted probability
    private BigDecimal strategyWeight;
    private BigDecimal historicalFactor;
    private BigDecimal avgProfitPct;
    private BigDecimal avgHoursToMax;
    private int matchedPatterns;

    /**
     * Check if this strategy has enough data for meaningful analysis.
     */
    public boolean hasData() {
        return matchedPatterns > 0;
    }

    /**
     * Check if strategy meets minimum signal conditions.
     */
    public boolean meetsConditions(BigDecimal minProbability, BigDecimal minProfit, int minSamples) {
        if (matchedPatterns < minSamples) return false;
        if (finalProbability == null || finalProbability.compareTo(minProbability) < 0) return false;
        if (avgProfitPct == null || avgProfitPct.compareTo(minProfit) < 0) return false;
        return true;
    }
}
