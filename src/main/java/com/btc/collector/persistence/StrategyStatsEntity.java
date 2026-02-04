package com.btc.collector.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_stats")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyStatsEntity {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final BigDecimal MIN_WEIGHT = BigDecimal.valueOf(0.05);
    private static final BigDecimal MAX_WEIGHT = BigDecimal.ONE;

    @Id
    @Column(name = "strategy_name", length = 100)
    private String strategyName;

    @Column(name = "total_predictions")
    private int totalPredictions;

    @Column(name = "successful_predictions")
    private int successfulPredictions;

    @Column(name = "failed_predictions")
    private int failedPredictions;

    @Column(name = "score", precision = 10, scale = 2)
    private BigDecimal score;

    @Column(name = "weight", precision = 5, scale = 4)
    private BigDecimal weight;

    @Column(name = "success_rate", precision = 5, scale = 2)
    private BigDecimal successRate;

    @Column(name = "degradation_alerted")
    private boolean degradationAlerted;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    // Rate2.0 / Confidence evaluation fields
    @Column(name = "confidence_positive")
    private int confidencePositive;

    @Column(name = "confidence_negative")
    private int confidenceNegative;

    @Column(name = "confidence_neutral")
    private int confidenceNeutral;

    @Column(name = "confidence_score")
    private int confidenceScore;

    @Column(name = "rate2", precision = 5, scale = 2)
    private BigDecimal rate2;

    // Profit evaluation fields
    @Column(name = "total_pnl_usd", precision = 12, scale = 4)
    private BigDecimal totalPnlUsd;

    @Column(name = "profit_trades_count")
    private int profitTradesCount;

    @Column(name = "sum_profit_pct", precision = 12, scale = 4)
    private BigDecimal sumProfitPct;

    public static StrategyStatsEntity createNew(String strategyName) {
        return StrategyStatsEntity.builder()
                .strategyName(strategyName)
                .totalPredictions(0)
                .successfulPredictions(0)
                .failedPredictions(0)
                .successRate(BigDecimal.valueOf(50))
                .score(BigDecimal.ZERO)
                .weight(BigDecimal.valueOf(0.5))
                .degradationAlerted(false)
                .lastUpdated(LocalDateTime.now())
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

    /**
     * Record a confidence score from Rate2.0 evaluation.
     * @param score +1 (positive), 0 (neutral), -1 (negative)
     */
    public void recordConfidenceScore(int score) {
        if (score > 0) {
            confidencePositive++;
            confidenceScore++;
        } else if (score < 0) {
            confidenceNegative++;
            confidenceScore--;
        } else {
            confidenceNeutral++;
        }
        recalculateRate2();
        lastUpdated = LocalDateTime.now();
    }

    private void recalculateRate2() {
        int evaluated = confidencePositive + confidenceNegative;
        if (evaluated > 0) {
            rate2 = BigDecimal.valueOf(confidencePositive)
                    .divide(BigDecimal.valueOf(evaluated), MC)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            rate2 = null;
        }
    }

    /**
     * Record profit from a trade evaluation.
     * @param profitPct actual profit percentage (can be negative for losses)
     * @param profitUsd actual profit in USD (can be negative for losses)
     */
    public void recordProfit(BigDecimal profitPct, BigDecimal profitUsd) {
        profitTradesCount++;
        if (totalPnlUsd == null) {
            totalPnlUsd = BigDecimal.ZERO;
        }
        if (sumProfitPct == null) {
            sumProfitPct = BigDecimal.ZERO;
        }
        totalPnlUsd = totalPnlUsd.add(profitUsd);
        sumProfitPct = sumProfitPct.add(profitPct);
        lastUpdated = LocalDateTime.now();
    }

    /**
     * Get average profit percentage for this strategy.
     */
    public BigDecimal getAvgProfitPct() {
        if (profitTradesCount == 0 || sumProfitPct == null) {
            return null;
        }
        return sumProfitPct.divide(BigDecimal.valueOf(profitTradesCount), MC)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
