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
    private BigDecimal finalProbability;     // Weighted probability (stored in DB)
    private BigDecimal strategyWeight;
    private BigDecimal historicalFactor;
    private BigDecimal avgProfitPct;
    private BigDecimal avgHoursToMax;
    private int matchedPatterns;

    // PnL coefficient fields (for Telegram decision only)
    private BigDecimal pnlCoefficient;       // PnL-based boost coefficient (1.0 = no boost)
    private BigDecimal boostedProbability;   // finalProbability * pnlCoefficient (for Telegram)
    private BigDecimal totalPnl;             // Strategy's total PnL (for observability)

    /**
     * Check if this strategy has enough data for meaningful analysis.
     */
    public boolean hasData() {
        return matchedPatterns > 0;
    }

    /**
     * Check if strategy meets minimum signal conditions using base probability.
     * Used for database storage decisions.
     */
    public boolean meetsConditions(BigDecimal minProbability, BigDecimal minProfit, int minSamples) {
        if (matchedPatterns < minSamples) return false;
        if (finalProbability == null || finalProbability.compareTo(minProbability) < 0) return false;
        if (avgProfitPct == null || avgProfitPct.compareTo(minProfit) < 0) return false;
        return true;
    }

    /**
     * Check if strategy meets Telegram alert conditions using boosted probability.
     * Uses boostedProbability if available, otherwise falls back to finalProbability.
     */
    public boolean meetsTelegramConditions(BigDecimal minProbability, BigDecimal minProfit, int minSamples) {
        if (matchedPatterns < minSamples) return false;

        // Use boosted probability for Telegram decision
        BigDecimal effectiveProb = boostedProbability != null ? boostedProbability : finalProbability;
        if (effectiveProb == null || effectiveProb.compareTo(minProbability) < 0) return false;

        if (avgProfitPct == null || avgProfitPct.compareTo(minProfit) < 0) return false;
        return true;
    }

    /**
     * Check if this strategy has a PnL boost applied.
     */
    public boolean hasPnlBoost() {
        return pnlCoefficient != null && pnlCoefficient.compareTo(BigDecimal.ONE) > 0;
    }
}
