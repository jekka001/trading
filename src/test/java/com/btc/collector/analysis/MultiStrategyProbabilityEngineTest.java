package com.btc.collector.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Section 5: Multi-Strategy Probability Engine
 *
 * Tests:
 * - Verify that analyzeAllStrategies() evaluates all strategies correctly
 * - Check that average probability and best strategy are calculated and logged
 * - Ensure each strategy has its own coefficient updated based on past alerts
 * - Test different market conditions to confirm diverse strategies trigger as expected
 */
@DisplayName("5. Multi-Strategy Probability Engine Tests")
class MultiStrategyProbabilityEngineTest {

    @BeforeEach
    void setUp() {
    }

    @Test
    @DisplayName("5.1 StrategyAnalysisResult correctly identifies data presence")
    void strategyAnalysisResultIdentifiesDataPresence() {
        // Given: Result with data
        StrategyAnalysisResult withData = StrategyAnalysisResult.builder()
                .strategyId("RSI_MID_EMA_BULL_VOL_MED")
                .baseProbability(BigDecimal.valueOf(65))
                .finalProbability(BigDecimal.valueOf(60))
                .avgProfitPct(BigDecimal.valueOf(2.5))
                .matchedPatterns(50)
                .build();

        // Given: Result without data
        StrategyAnalysisResult withoutData = StrategyAnalysisResult.builder()
                .strategyId("RSI_LOW_EMA_BEAR_VOL_LOW")
                .baseProbability(BigDecimal.ZERO)
                .finalProbability(BigDecimal.ZERO)
                .avgProfitPct(BigDecimal.ZERO)
                .matchedPatterns(0)
                .build();

        // Then
        assertThat(withData.hasData()).isTrue();
        assertThat(withoutData.hasData()).isFalse();
    }

    @Test
    @DisplayName("5.2 StrategyAnalysisResult validates conditions correctly")
    void strategyAnalysisResultValidatesConditions() {
        BigDecimal minProb = BigDecimal.valueOf(50);
        BigDecimal minProfit = BigDecimal.valueOf(1.5);
        int minSamples = 30;

        // Meets all conditions
        StrategyAnalysisResult good = StrategyAnalysisResult.builder()
                .strategyId("GOOD_STRATEGY")
                .finalProbability(BigDecimal.valueOf(60))
                .avgProfitPct(BigDecimal.valueOf(2.5))
                .matchedPatterns(50)
                .build();

        // Low probability
        StrategyAnalysisResult lowProb = StrategyAnalysisResult.builder()
                .strategyId("LOW_PROB")
                .finalProbability(BigDecimal.valueOf(40))
                .avgProfitPct(BigDecimal.valueOf(2.5))
                .matchedPatterns(50)
                .build();

        // Low profit
        StrategyAnalysisResult lowProfit = StrategyAnalysisResult.builder()
                .strategyId("LOW_PROFIT")
                .finalProbability(BigDecimal.valueOf(60))
                .avgProfitPct(BigDecimal.valueOf(1.0))
                .matchedPatterns(50)
                .build();

        // Low samples
        StrategyAnalysisResult lowSamples = StrategyAnalysisResult.builder()
                .strategyId("LOW_SAMPLES")
                .finalProbability(BigDecimal.valueOf(60))
                .avgProfitPct(BigDecimal.valueOf(2.5))
                .matchedPatterns(10)
                .build();

        // Then
        assertThat(good.meetsConditions(minProb, minProfit, minSamples)).isTrue();
        assertThat(lowProb.meetsConditions(minProb, minProfit, minSamples)).isFalse();
        assertThat(lowProfit.meetsConditions(minProb, minProfit, minSamples)).isFalse();
        assertThat(lowSamples.meetsConditions(minProb, minProfit, minSamples)).isFalse();
    }

    @Test
    @DisplayName("5.3 AggregatedAnalysisResult calculates averages correctly")
    void aggregatedResultCalculatesAveragesCorrectly() {
        // Given: Multiple strategy results
        List<StrategyAnalysisResult> results = new ArrayList<>();

        results.add(StrategyAnalysisResult.builder()
                .strategyId("STRATEGY_1")
                .finalProbability(BigDecimal.valueOf(60))
                .strategyWeight(BigDecimal.valueOf(0.6))
                .avgProfitPct(BigDecimal.valueOf(2.0))
                .matchedPatterns(50)
                .build());

        results.add(StrategyAnalysisResult.builder()
                .strategyId("STRATEGY_2")
                .finalProbability(BigDecimal.valueOf(70))
                .strategyWeight(BigDecimal.valueOf(0.8))
                .avgProfitPct(BigDecimal.valueOf(3.0))
                .matchedPatterns(30)
                .build());

        results.add(StrategyAnalysisResult.builder()
                .strategyId("STRATEGY_3")
                .finalProbability(BigDecimal.ZERO)
                .avgProfitPct(BigDecimal.ZERO)
                .matchedPatterns(0) // No data
                .build());

        // When: Aggregate
        MarketSnapshot snapshot = createTestSnapshot();
        AggregatedAnalysisResult aggregated = AggregatedAnalysisResult.aggregate(snapshot, results);

        // Then: Averages calculated from strategies with data only
        assertThat(aggregated.getStrategiesWithData()).isEqualTo(2);
        assertThat(aggregated.getAvgProbability()).isEqualByComparingTo(BigDecimal.valueOf(65)); // (60+70)/2
        assertThat(aggregated.getAvgProfitPct()).isEqualByComparingTo(BigDecimal.valueOf(2.5)); // (2+3)/2
        assertThat(aggregated.getTotalMatchedPatterns()).isEqualTo(80); // 50+30
    }

    @Test
    @DisplayName("5.4 AggregatedAnalysisResult finds best strategy")
    void aggregatedResultFindsBestStrategy() {
        // Given: Multiple strategy results
        List<StrategyAnalysisResult> results = new ArrayList<>();

        results.add(StrategyAnalysisResult.builder()
                .strategyId("STRATEGY_A")
                .finalProbability(BigDecimal.valueOf(55))
                .avgProfitPct(BigDecimal.valueOf(2.0))
                .matchedPatterns(40)
                .build());

        results.add(StrategyAnalysisResult.builder()
                .strategyId("STRATEGY_B")
                .finalProbability(BigDecimal.valueOf(75)) // Highest
                .avgProfitPct(BigDecimal.valueOf(2.5))
                .matchedPatterns(60)
                .build());

        results.add(StrategyAnalysisResult.builder()
                .strategyId("STRATEGY_C")
                .finalProbability(BigDecimal.valueOf(65))
                .avgProfitPct(BigDecimal.valueOf(3.0))
                .matchedPatterns(35)
                .build());

        // When: Aggregate
        MarketSnapshot snapshot = createTestSnapshot();
        AggregatedAnalysisResult aggregated = AggregatedAnalysisResult.aggregate(snapshot, results);

        // Then: Best strategy found
        StrategyAnalysisResult best = aggregated.getBestStrategy();
        assertThat(best).isNotNull();
        assertThat(best.getStrategyId()).isEqualTo("STRATEGY_B");
        assertThat(best.getFinalProbability()).isEqualByComparingTo(BigDecimal.valueOf(75));
    }

    @Test
    @DisplayName("5.5 AggregatedAnalysisResult checks qualifying strategies")
    void aggregatedResultChecksQualifyingStrategies() {
        BigDecimal minProb = BigDecimal.valueOf(50);
        BigDecimal minProfit = BigDecimal.valueOf(1.5);
        int minSamples = 30;

        // Given: Results with one qualifying strategy
        List<StrategyAnalysisResult> withQualifying = new ArrayList<>();
        withQualifying.add(StrategyAnalysisResult.builder()
                .strategyId("QUALIFYING")
                .finalProbability(BigDecimal.valueOf(60))
                .avgProfitPct(BigDecimal.valueOf(2.0))
                .matchedPatterns(50)
                .build());

        // Given: Results with no qualifying strategies
        List<StrategyAnalysisResult> withoutQualifying = new ArrayList<>();
        withoutQualifying.add(StrategyAnalysisResult.builder()
                .strategyId("NOT_QUALIFYING")
                .finalProbability(BigDecimal.valueOf(40)) // Below threshold
                .avgProfitPct(BigDecimal.valueOf(2.0))
                .matchedPatterns(50)
                .build());

        MarketSnapshot snapshot = createTestSnapshot();
        AggregatedAnalysisResult aggWithQualifying = AggregatedAnalysisResult.aggregate(snapshot, withQualifying);
        AggregatedAnalysisResult aggWithoutQualifying = AggregatedAnalysisResult.aggregate(snapshot, withoutQualifying);

        // Then
        assertThat(aggWithQualifying.hasQualifyingStrategy(minProb, minProfit, minSamples)).isTrue();
        assertThat(aggWithoutQualifying.hasQualifyingStrategy(minProb, minProfit, minSamples)).isFalse();
    }

    @Test
    @DisplayName("5.6 AggregatedAnalysisResult calculates weighted average")
    void aggregatedResultCalculatesWeightedAverage() {
        // Given: Results with different weights
        List<StrategyAnalysisResult> results = new ArrayList<>();

        // Weight 0.8, probability 80%
        results.add(StrategyAnalysisResult.builder()
                .strategyId("HIGH_WEIGHT")
                .finalProbability(BigDecimal.valueOf(80))
                .strategyWeight(BigDecimal.valueOf(0.8))
                .avgProfitPct(BigDecimal.valueOf(2.0))
                .matchedPatterns(50)
                .build());

        // Weight 0.2, probability 40%
        results.add(StrategyAnalysisResult.builder()
                .strategyId("LOW_WEIGHT")
                .finalProbability(BigDecimal.valueOf(40))
                .strategyWeight(BigDecimal.valueOf(0.2))
                .avgProfitPct(BigDecimal.valueOf(2.0))
                .matchedPatterns(50)
                .build());

        // When: Aggregate
        MarketSnapshot snapshot = createTestSnapshot();
        AggregatedAnalysisResult aggregated = AggregatedAnalysisResult.aggregate(snapshot, results);

        // Then: Weighted average favors higher weight
        // Weighted: (80*0.8 + 40*0.2) / (0.8 + 0.2) = (64 + 8) / 1.0 = 72
        // Simple average: (80 + 40) / 2 = 60
        assertThat(aggregated.getAvgProbability()).isEqualByComparingTo(BigDecimal.valueOf(60));
        assertThat(aggregated.getWeightedAvgProbability()).isEqualByComparingTo(BigDecimal.valueOf(72));
    }

    @Test
    @DisplayName("5.7 AggregatedAnalysisResult handles empty results")
    void aggregatedResultHandlesEmptyResults() {
        // Given: No results with data
        List<StrategyAnalysisResult> results = new ArrayList<>();
        results.add(StrategyAnalysisResult.builder()
                .strategyId("NO_DATA")
                .finalProbability(BigDecimal.ZERO)
                .avgProfitPct(BigDecimal.ZERO)
                .matchedPatterns(0)
                .build());

        // When: Aggregate
        MarketSnapshot snapshot = createTestSnapshot();
        AggregatedAnalysisResult aggregated = AggregatedAnalysisResult.aggregate(snapshot, results);

        // Then: Zero values, no best strategy
        assertThat(aggregated.getStrategiesWithData()).isZero();
        assertThat(aggregated.getAvgProbability()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aggregated.getBestStrategy()).isNull();
    }

    @Test
    @DisplayName("5.8 Snapshot ID is generated uniquely")
    void snapshotIdIsGeneratedUniquely() {
        // Given: Multiple aggregations
        MarketSnapshot snapshot = createTestSnapshot();
        List<StrategyAnalysisResult> results = new ArrayList<>();
        results.add(StrategyAnalysisResult.builder()
                .strategyId("TEST")
                .finalProbability(BigDecimal.valueOf(50))
                .avgProfitPct(BigDecimal.valueOf(2.0))
                .matchedPatterns(10)
                .build());

        // When: Create multiple aggregations
        AggregatedAnalysisResult agg1 = AggregatedAnalysisResult.aggregate(snapshot, results);
        AggregatedAnalysisResult agg2 = AggregatedAnalysisResult.aggregate(snapshot, results);

        // Then: Different snapshot IDs
        assertThat(agg1.getSnapshotId()).isNotNull();
        assertThat(agg2.getSnapshotId()).isNotNull();
        assertThat(agg1.getSnapshotId()).isNotEqualTo(agg2.getSnapshotId());
    }

    private MarketSnapshot createTestSnapshot() {
        return MarketSnapshot.builder()
                .price(BigDecimal.valueOf(50000))
                .rsi(BigDecimal.valueOf(50))
                .ema50(BigDecimal.valueOf(49500))
                .ema200(BigDecimal.valueOf(49000))
                .volumeChangePct(BigDecimal.valueOf(10))
                .priceChange1h(BigDecimal.valueOf(0.5))
                .priceChange4h(BigDecimal.valueOf(1.0))
                .build();
    }
}
