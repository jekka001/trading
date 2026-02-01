package com.btc.collector.strategy;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Comprehensive metrics for a strategy's performance.
 */
@Data
@Builder
public class StrategyMetrics {

    private String strategyId;
    private String humanReadableName;
    private StrategyDefinition.StrategyType strategyType;

    // Core metrics
    private int totalSignals;
    private int evaluatedSignals;
    private int successfulSignals;
    private int failedSignals;

    // Rates
    private BigDecimal winRate;           // % of successful signals
    private BigDecimal avgProfitPct;      // Average profit when successful
    private BigDecimal avgLossPct;        // Average loss when failed
    private BigDecimal expectedValue;     // (winRate * avgProfit) - ((1-winRate) * avgLoss)

    // Time metrics
    private BigDecimal avgTimeToMaxProfit; // Hours
    private BigDecimal avgHoldingTime;     // Hours

    // Risk metrics
    private BigDecimal maxDrawdown;        // Maximum observed drawdown
    private BigDecimal sharpeRatio;        // Risk-adjusted return
    private BigDecimal profitFactor;       // Gross profit / gross loss

    // Recent performance
    private BigDecimal recentWinRate;      // Win rate in last 30 days
    private int recentSignals;             // Signals in last 30 days

    // Ranking
    private int rank;
    private BigDecimal score;              // Composite score for ranking

    // Timestamps
    private LocalDateTime firstSignal;
    private LocalDateTime lastSignal;
    private LocalDateTime calculatedAt;

    /**
     * Check if strategy has enough data for reliable metrics.
     */
    public boolean hasReliableData() {
        return evaluatedSignals >= 10;
    }

    /**
     * Check if strategy is performing well.
     */
    public boolean isPerformingWell() {
        if (!hasReliableData()) return false;
        return winRate != null && winRate.compareTo(BigDecimal.valueOf(50)) > 0
                && expectedValue != null && expectedValue.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Check if strategy is degrading.
     */
    public boolean isDegrading() {
        if (!hasReliableData()) return false;
        return winRate != null && winRate.compareTo(BigDecimal.valueOf(30)) < 0;
    }
}
