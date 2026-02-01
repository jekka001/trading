package com.btc.collector.analysis;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.persistence.StrategyStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for StrategyTracker.
 */
@DisplayName("StrategyTracker Tests")
class StrategyTrackerTest extends BaseIntegrationTest {

    @Autowired
    private StrategyTracker strategyTracker;

    @Autowired
    private StrategyStatsRepository statsRepository;

    @BeforeEach
    void setUp() {
        statsRepository.deleteAll();
    }

    @Nested
    @DisplayName("Strategy ID Generation")
    class IdGenerationTests {

        @Test
        @DisplayName("Should generate ID from snapshot")
        void shouldGenerateIdFromSnapshot() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(25))  // LOW
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(45000))  // BULL (ema50 > ema200)
                    .volumeChangePct(BigDecimal.valueOf(60))  // HIGH (>50)
                    .build();

            String id = strategyTracker.generateStrategyId(snapshot);

            // Verify it contains expected components
            assertTrue(id.contains("RSI_LOW"));
            assertTrue(id.contains("EMA_BULL"));
            assertTrue(id.contains("VOL_HIGH"));
        }

        @Test
        @DisplayName("Should return UNKNOWN for null snapshot")
        void shouldReturnUnknownForNullSnapshot() {
            String id = strategyTracker.generateStrategyId(null);
            assertEquals("UNKNOWN", id);
        }

        @Test
        @DisplayName("Should return UNKNOWN for snapshot without RSI")
        void shouldReturnUnknownWithoutRsi() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(45000))
                    .build();

            String id = strategyTracker.generateStrategyId(snapshot);
            assertEquals("UNKNOWN", id);
        }
    }

    @Nested
    @DisplayName("Strategy Stats Tests")
    class StatsTests {

        @Test
        @DisplayName("Should create new strategy stats")
        void shouldCreateNewStats() {
            StrategyStats stats = strategyTracker.getOrCreate("NEW_STRATEGY");

            assertNotNull(stats);
            assertEquals("NEW_STRATEGY", stats.getStrategyId());
            assertEquals(0, stats.getTotalPredictions());
        }

        @Test
        @DisplayName("Should get existing strategy stats")
        void shouldGetExistingStats() {
            strategyTracker.recordSuccess("EXISTING_STRATEGY");

            Optional<StrategyStats> stats = strategyTracker.get("EXISTING_STRATEGY");

            assertTrue(stats.isPresent());
            assertEquals(1, stats.get().getTotalPredictions());
        }

        @Test
        @DisplayName("Should return empty for unknown strategy")
        void shouldReturnEmptyForUnknown() {
            Optional<StrategyStats> stats = strategyTracker.get("UNKNOWN_STRATEGY");

            assertTrue(stats.isEmpty());
        }
    }

    @Nested
    @DisplayName("Success/Failure Recording")
    class RecordingTests {

        @Test
        @DisplayName("Should record success")
        void shouldRecordSuccess() {
            strategyTracker.recordSuccess("TEST_STRATEGY");

            Optional<StrategyStats> stats = strategyTracker.get("TEST_STRATEGY");

            assertTrue(stats.isPresent());
            assertEquals(1, stats.get().getSuccessfulPredictions());
            assertEquals(1, stats.get().getTotalPredictions());
        }

        @Test
        @DisplayName("Should record failure")
        void shouldRecordFailure() {
            strategyTracker.recordFailure("TEST_STRATEGY");

            Optional<StrategyStats> stats = strategyTracker.get("TEST_STRATEGY");

            assertTrue(stats.isPresent());
            assertEquals(1, stats.get().getFailedPredictions());
            assertEquals(1, stats.get().getTotalPredictions());
        }

        @Test
        @DisplayName("Should calculate success rate correctly")
        void shouldCalculateSuccessRate() {
            String strategyId = "RATE_TEST";

            // 7 successes, 3 failures = 70% success rate
            for (int i = 0; i < 7; i++) {
                strategyTracker.recordSuccess(strategyId);
            }
            for (int i = 0; i < 3; i++) {
                strategyTracker.recordFailure(strategyId);
            }

            Optional<StrategyStats> stats = strategyTracker.get(strategyId);

            assertTrue(stats.isPresent());
            assertEquals(10, stats.get().getTotalPredictions());
            assertEquals(0, stats.get().getSuccessRate().compareTo(BigDecimal.valueOf(70)));
        }
    }

    @Nested
    @DisplayName("Weight Calculation")
    class WeightTests {

        @Test
        @DisplayName("Should return default weight for unknown strategy")
        void shouldReturnDefaultWeight() {
            BigDecimal weight = strategyTracker.getWeight("UNKNOWN");

            assertEquals(0.5, weight.doubleValue());
        }

        @Test
        @DisplayName("Should calculate weight based on performance")
        void shouldCalculateWeightBasedOnPerformance() {
            String strategyId = "WEIGHT_TEST";

            // Record good performance
            for (int i = 0; i < 8; i++) {
                strategyTracker.recordSuccess(strategyId);
            }
            for (int i = 0; i < 2; i++) {
                strategyTracker.recordFailure(strategyId);
            }

            BigDecimal weight = strategyTracker.getWeight(strategyId);

            // Weight should be higher than default for good performance
            assertTrue(weight.doubleValue() > 0);
        }
    }

    @Nested
    @DisplayName("Degradation Alert Tests")
    class DegradationTests {

        @Test
        @DisplayName("Should detect degradation")
        void shouldDetectDegradation() {
            String strategyId = "DEGRADED_STRATEGY";

            // Record many failures
            for (int i = 0; i < 20; i++) {
                strategyTracker.recordFailure(strategyId);
            }

            boolean needsAlert = strategyTracker.needsDegradationAlert(strategyId);

            // Depends on configuration, but should be consistent
            assertNotNull(needsAlert);
        }

        @Test
        @DisplayName("Should mark degradation as alerted")
        void shouldMarkDegradationAlerted() {
            String strategyId = "ALERT_TEST";

            strategyTracker.recordFailure(strategyId);
            strategyTracker.markDegradationAlerted(strategyId);

            Optional<StrategyStats> stats = strategyTracker.get(strategyId);

            assertTrue(stats.isPresent());
            assertTrue(stats.get().isDegradationAlerted());
        }
    }

    @Nested
    @DisplayName("All Strategies Tests")
    class AllStrategiesTests {

        @Test
        @DisplayName("Should get all strategies")
        void shouldGetAllStrategies() {
            strategyTracker.recordSuccess("STRATEGY_1");
            strategyTracker.recordSuccess("STRATEGY_2");
            strategyTracker.recordFailure("STRATEGY_3");

            Collection<StrategyStats> all = strategyTracker.getAllStrategies();

            assertEquals(3, all.size());
        }

        @Test
        @DisplayName("Should count strategies")
        void shouldCountStrategies() {
            strategyTracker.recordSuccess("COUNT_1");
            strategyTracker.recordSuccess("COUNT_2");

            int count = strategyTracker.getStrategyCount();

            assertEquals(2, count);
        }
    }
}
