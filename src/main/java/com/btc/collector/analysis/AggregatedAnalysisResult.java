package com.btc.collector.analysis;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated analysis result combining all strategy evaluations for a snapshot.
 */
@Data
@Builder
public class AggregatedAnalysisResult {

    private String snapshotId;              // Unique ID to group related alerts
    private LocalDateTime snapshotTime;
    private MarketSnapshot snapshot;
    private List<StrategyAnalysisResult> strategyResults;

    // Aggregated metrics
    private BigDecimal avgProbability;       // Average probability across strategies
    private BigDecimal weightedAvgProbability; // Weighted average by strategy weight
    private BigDecimal avgProfitPct;
    private int totalMatchedPatterns;
    private int strategiesWithData;          // Count of strategies that had matching patterns

    /**
     * Create aggregated result from individual strategy results.
     */
    public static AggregatedAnalysisResult aggregate(
            MarketSnapshot snapshot,
            List<StrategyAnalysisResult> results) {

        String snapshotId = UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now();

        // Filter strategies with data
        List<StrategyAnalysisResult> withData = results.stream()
                .filter(StrategyAnalysisResult::hasData)
                .toList();

        if (withData.isEmpty()) {
            return AggregatedAnalysisResult.builder()
                    .snapshotId(snapshotId)
                    .snapshotTime(now)
                    .snapshot(snapshot)
                    .strategyResults(results)
                    .avgProbability(BigDecimal.ZERO)
                    .weightedAvgProbability(BigDecimal.ZERO)
                    .avgProfitPct(BigDecimal.ZERO)
                    .totalMatchedPatterns(0)
                    .strategiesWithData(0)
                    .build();
        }

        // Calculate averages
        BigDecimal sumProb = BigDecimal.ZERO;
        BigDecimal sumWeightedProb = BigDecimal.ZERO;
        BigDecimal sumWeight = BigDecimal.ZERO;
        BigDecimal sumProfit = BigDecimal.ZERO;
        int totalPatterns = 0;

        for (StrategyAnalysisResult r : withData) {
            if (r.getFinalProbability() != null) {
                sumProb = sumProb.add(r.getFinalProbability());

                BigDecimal weight = r.getStrategyWeight() != null
                        ? r.getStrategyWeight()
                        : BigDecimal.valueOf(0.5);
                sumWeightedProb = sumWeightedProb.add(r.getFinalProbability().multiply(weight));
                sumWeight = sumWeight.add(weight);
            }
            if (r.getAvgProfitPct() != null) {
                sumProfit = sumProfit.add(r.getAvgProfitPct());
            }
            totalPatterns += r.getMatchedPatterns();
        }

        BigDecimal count = BigDecimal.valueOf(withData.size());
        BigDecimal avgProb = sumProb.divide(count, 2, RoundingMode.HALF_UP);
        BigDecimal avgProfit = sumProfit.divide(count, 2, RoundingMode.HALF_UP);

        BigDecimal weightedAvgProb = sumWeight.compareTo(BigDecimal.ZERO) > 0
                ? sumWeightedProb.divide(sumWeight, 2, RoundingMode.HALF_UP)
                : avgProb;

        return AggregatedAnalysisResult.builder()
                .snapshotId(snapshotId)
                .snapshotTime(now)
                .snapshot(snapshot)
                .strategyResults(results)
                .avgProbability(avgProb)
                .weightedAvgProbability(weightedAvgProb)
                .avgProfitPct(avgProfit)
                .totalMatchedPatterns(totalPatterns)
                .strategiesWithData(withData.size())
                .build();
    }

    /**
     * Get the best performing strategy (highest final probability with data).
     */
    public StrategyAnalysisResult getBestStrategy() {
        return strategyResults.stream()
                .filter(StrategyAnalysisResult::hasData)
                .filter(r -> r.getFinalProbability() != null)
                .max((a, b) -> a.getFinalProbability().compareTo(b.getFinalProbability()))
                .orElse(null);
    }

    /**
     * Check if any strategy meets the signal conditions.
     */
    public boolean hasQualifyingStrategy(BigDecimal minProbability, BigDecimal minProfit, int minSamples) {
        return strategyResults.stream()
                .anyMatch(r -> r.meetsConditions(minProbability, minProfit, minSamples));
    }
}
