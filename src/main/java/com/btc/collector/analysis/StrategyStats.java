package com.btc.collector.analysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyStats {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final BigDecimal MIN_WEIGHT = BigDecimal.valueOf(0.05);
    private static final BigDecimal MAX_WEIGHT = BigDecimal.ONE;

    private String strategyId;
    private int totalPredictions;
    private int successfulPredictions;
    private int failedPredictions;
    private BigDecimal successRate;
    private BigDecimal score;
    private BigDecimal weight;
    private LocalDateTime lastUpdated;
    private boolean degradationAlerted;

    // Rate2.0 / Confidence evaluation fields
    private int confidencePositive;
    private int confidenceNegative;
    private int confidenceNeutral;
    private int confidenceScore;
    private BigDecimal rate2;

    // Profit evaluation fields
    private BigDecimal totalPnlUsd;
    private int profitTradesCount;
    private BigDecimal avgProfitPct;

    public static StrategyStats createNew(String strategyId) {
        return StrategyStats.builder()
                .strategyId(strategyId)
                .totalPredictions(0)
                .successfulPredictions(0)
                .failedPredictions(0)
                .successRate(BigDecimal.valueOf(50))
                .score(BigDecimal.ZERO)
                .weight(BigDecimal.valueOf(0.5))
                .lastUpdated(LocalDateTime.now())
                .degradationAlerted(false)
                .build();
    }

    public void recordSuccess() {
        totalPredictions++;
        successfulPredictions++;
        score = score.add(BigDecimal.ONE);
        recalculate();
    }

    public void recordFailure() {
        totalPredictions++;
        failedPredictions++;
        score = score.subtract(BigDecimal.ONE);
        recalculate();
    }

    private void recalculate() {
        // Success rate
        if (totalPredictions > 0) {
            successRate = BigDecimal.valueOf(successfulPredictions)
                    .divide(BigDecimal.valueOf(totalPredictions), MC)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // weight = clamp(0.1 + successRate * log(total_predictions + 1), 0.05, 1.0)
        double successRateDecimal = successRate.doubleValue() / 100.0;
        double logFactor = Math.log(totalPredictions + 1);
        double rawWeight = 0.1 + successRateDecimal * logFactor;
        weight = BigDecimal.valueOf(rawWeight)
                .max(MIN_WEIGHT)
                .min(MAX_WEIGHT)
                .setScale(4, RoundingMode.HALF_UP);

        lastUpdated = LocalDateTime.now();
    }

    public boolean isDegraded() {
        return weight.compareTo(MIN_WEIGHT) <= 0;
    }

    public boolean needsDegradationAlert() {
        return isDegraded() && !degradationAlerted;
    }

    public void markDegradationAlerted() {
        degradationAlerted = true;
    }

    /**
     * Get total number of alerts evaluated for confidence (Rate2.0).
     * Only counts positive and negative (neutral is excluded from rate calculation).
     */
    public int getConfidenceEvaluated() {
        return confidencePositive + confidenceNegative;
    }
}
