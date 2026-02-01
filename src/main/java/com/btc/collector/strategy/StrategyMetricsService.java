package com.btc.collector.strategy;

import com.btc.collector.persistence.AlertHistoryEntity;
import com.btc.collector.persistence.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for calculating comprehensive strategy metrics.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyMetricsService {

    private final AlertHistoryRepository alertRepository;
    private final StrategyRegistry strategyRegistry;

    /**
     * Calculate metrics for all strategies.
     */
    public List<StrategyMetrics> calculateAllMetrics() {
        List<StrategyMetrics> allMetrics = new ArrayList<>();

        // Get all alerts grouped by strategy
        Map<String, List<AlertHistoryEntity>> alertsByStrategy = alertRepository.findAll().stream()
                .collect(Collectors.groupingBy(AlertHistoryEntity::getStrategyId));

        for (Map.Entry<String, List<AlertHistoryEntity>> entry : alertsByStrategy.entrySet()) {
            String strategyId = entry.getKey();
            List<AlertHistoryEntity> alerts = entry.getValue();

            StrategyMetrics metrics = calculateMetrics(strategyId, alerts);
            allMetrics.add(metrics);
        }

        // Sort by score descending
        allMetrics.sort((a, b) -> {
            if (a.getScore() == null && b.getScore() == null) return 0;
            if (a.getScore() == null) return 1;
            if (b.getScore() == null) return -1;
            return b.getScore().compareTo(a.getScore());
        });

        // Assign ranks
        for (int i = 0; i < allMetrics.size(); i++) {
            allMetrics.get(i).setRank(i + 1);
        }

        return allMetrics;
    }

    /**
     * Calculate metrics for a specific strategy.
     */
    public StrategyMetrics calculateMetrics(String strategyId) {
        List<AlertHistoryEntity> alerts = alertRepository.findByStrategyId(strategyId);
        return calculateMetrics(strategyId, alerts);
    }

    /**
     * Calculate metrics from alerts.
     */
    private StrategyMetrics calculateMetrics(String strategyId, List<AlertHistoryEntity> alerts) {
        Optional<StrategyDefinition> strategyDef = strategyRegistry.getStrategy(strategyId);

        // Filter evaluated alerts
        List<AlertHistoryEntity> evaluated = alerts.stream()
                .filter(AlertHistoryEntity::isEvaluated)
                .toList();

        List<AlertHistoryEntity> successful = evaluated.stream()
                .filter(a -> Boolean.TRUE.equals(a.getSuccess()))
                .toList();

        List<AlertHistoryEntity> failed = evaluated.stream()
                .filter(a -> Boolean.FALSE.equals(a.getSuccess()))
                .toList();

        // Calculate win rate
        BigDecimal winRate = BigDecimal.ZERO;
        if (!evaluated.isEmpty()) {
            winRate = BigDecimal.valueOf(successful.size())
                    .divide(BigDecimal.valueOf(evaluated.size()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // Calculate average profit/loss
        BigDecimal avgProfit = calculateAverageProfit(successful);
        BigDecimal avgLoss = calculateAverageLoss(failed);

        // Calculate expected value
        BigDecimal expectedValue = calculateExpectedValue(winRate, avgProfit, avgLoss);

        // Calculate time metrics
        BigDecimal avgTimeToMax = calculateAverageTimeToMax(successful);

        // Calculate max drawdown
        BigDecimal maxDrawdown = calculateMaxDrawdown(evaluated);

        // Calculate profit factor
        BigDecimal profitFactor = calculateProfitFactor(successful, failed);

        // Calculate recent metrics (last 30 days)
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        List<AlertHistoryEntity> recentEvaluated = evaluated.stream()
                .filter(a -> a.getAlertTime().isAfter(thirtyDaysAgo))
                .toList();

        long recentSuccessful = recentEvaluated.stream()
                .filter(a -> Boolean.TRUE.equals(a.getSuccess()))
                .count();

        BigDecimal recentWinRate = BigDecimal.ZERO;
        if (!recentEvaluated.isEmpty()) {
            recentWinRate = BigDecimal.valueOf(recentSuccessful)
                    .divide(BigDecimal.valueOf(recentEvaluated.size()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // Calculate composite score
        BigDecimal score = calculateCompositeScore(winRate, avgProfit, evaluated.size(), expectedValue);

        // Get timestamps
        LocalDateTime firstSignal = alerts.stream()
                .map(AlertHistoryEntity::getAlertTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);

        LocalDateTime lastSignal = alerts.stream()
                .map(AlertHistoryEntity::getAlertTime)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        return StrategyMetrics.builder()
                .strategyId(strategyId)
                .humanReadableName(strategyDef.map(StrategyDefinition::getHumanReadableName).orElse(strategyId))
                .strategyType(strategyDef.map(StrategyDefinition::getType).orElse(null))
                .totalSignals(alerts.size())
                .evaluatedSignals(evaluated.size())
                .successfulSignals(successful.size())
                .failedSignals(failed.size())
                .winRate(winRate)
                .avgProfitPct(avgProfit)
                .avgLossPct(avgLoss)
                .expectedValue(expectedValue)
                .avgTimeToMaxProfit(avgTimeToMax)
                .maxDrawdown(maxDrawdown)
                .profitFactor(profitFactor)
                .recentWinRate(recentWinRate)
                .recentSignals(recentEvaluated.size())
                .score(score)
                .firstSignal(firstSignal)
                .lastSignal(lastSignal)
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Calculate average profit from successful alerts.
     */
    private BigDecimal calculateAverageProfit(List<AlertHistoryEntity> successful) {
        if (successful.isEmpty()) return BigDecimal.ZERO;

        BigDecimal sum = successful.stream()
                .map(AlertHistoryEntity::getActualProfitPct)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long count = successful.stream()
                .filter(a -> a.getActualProfitPct() != null)
                .count();

        if (count == 0) return BigDecimal.ZERO;

        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate average loss from failed alerts.
     */
    private BigDecimal calculateAverageLoss(List<AlertHistoryEntity> failed) {
        if (failed.isEmpty()) return BigDecimal.ZERO;

        BigDecimal sum = failed.stream()
                .map(AlertHistoryEntity::getActualProfitPct)
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long count = failed.stream()
                .filter(a -> a.getActualProfitPct() != null)
                .count();

        if (count == 0) return BigDecimal.ZERO;

        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate expected value.
     * EV = (winRate * avgProfit) - ((1 - winRate) * avgLoss)
     */
    private BigDecimal calculateExpectedValue(BigDecimal winRate, BigDecimal avgProfit, BigDecimal avgLoss) {
        if (winRate == null) return BigDecimal.ZERO;

        BigDecimal winRateDecimal = winRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal lossRateDecimal = BigDecimal.ONE.subtract(winRateDecimal);

        BigDecimal expectedGain = winRateDecimal.multiply(avgProfit);
        BigDecimal expectedLoss = lossRateDecimal.multiply(avgLoss);

        return expectedGain.subtract(expectedLoss).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate average time to max profit.
     */
    private BigDecimal calculateAverageTimeToMax(List<AlertHistoryEntity> successful) {
        if (successful.isEmpty()) return BigDecimal.ZERO;

        double sum = successful.stream()
                .mapToInt(AlertHistoryEntity::getPredictedHours)
                .sum();

        return BigDecimal.valueOf(sum / successful.size()).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * Calculate maximum drawdown.
     */
    private BigDecimal calculateMaxDrawdown(List<AlertHistoryEntity> evaluated) {
        if (evaluated.isEmpty()) return BigDecimal.ZERO;

        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (AlertHistoryEntity alert : evaluated) {
            if (alert.getActualProfitPct() != null && alert.getActualProfitPct().compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal drawdown = alert.getActualProfitPct().abs();
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }

        return maxDrawdown.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate profit factor (gross profit / gross loss).
     */
    private BigDecimal calculateProfitFactor(List<AlertHistoryEntity> successful, List<AlertHistoryEntity> failed) {
        BigDecimal grossProfit = successful.stream()
                .map(AlertHistoryEntity::getActualProfitPct)
                .filter(Objects::nonNull)
                .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLoss = failed.stream()
                .map(AlertHistoryEntity::getActualProfitPct)
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (grossLoss.compareTo(BigDecimal.ZERO) == 0) {
            return grossProfit.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO;
        }

        return grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate composite score for ranking.
     * Considers win rate, profit, sample size, and expected value.
     */
    private BigDecimal calculateCompositeScore(BigDecimal winRate, BigDecimal avgProfit,
                                                int sampleSize, BigDecimal expectedValue) {
        if (sampleSize < 5) {
            return BigDecimal.ZERO; // Not enough data
        }

        // Weights for scoring
        double winRateWeight = 0.35;
        double profitWeight = 0.25;
        double evWeight = 0.30;
        double sampleWeight = 0.10;

        double winRateScore = winRate != null ? winRate.doubleValue() : 0;
        double profitScore = avgProfit != null ? Math.min(avgProfit.doubleValue() * 20, 100) : 0; // Cap at 5% = 100
        double evScore = expectedValue != null ? Math.max(0, expectedValue.doubleValue() * 50 + 50) : 0;
        double sampleScore = Math.min(sampleSize / 50.0 * 100, 100); // 50 samples = 100 score

        double score = (winRateScore * winRateWeight) +
                       (profitScore * profitWeight) +
                       (evScore * evWeight) +
                       (sampleScore * sampleWeight);

        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Get top N strategies by score.
     */
    public List<StrategyMetrics> getTopStrategies(int n) {
        return calculateAllMetrics().stream()
                .filter(StrategyMetrics::hasReliableData)
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Get strategies that are currently performing well.
     */
    public List<StrategyMetrics> getPerformingStrategies() {
        return calculateAllMetrics().stream()
                .filter(StrategyMetrics::isPerformingWell)
                .collect(Collectors.toList());
    }

    /**
     * Get strategies that are degrading.
     */
    public List<StrategyMetrics> getDegradingStrategies() {
        return calculateAllMetrics().stream()
                .filter(StrategyMetrics::isDegrading)
                .collect(Collectors.toList());
    }
}
