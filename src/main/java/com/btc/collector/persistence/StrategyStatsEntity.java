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
}
