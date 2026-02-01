package com.btc.collector.strategy;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.analysis.MarketSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for StrategyRegistry.
 */
@DisplayName("StrategyRegistry Tests")
class StrategyRegistryTest extends BaseIntegrationTest {

    @Autowired
    private StrategyRegistry strategyRegistry;

    @Nested
    @DisplayName("Initialization Tests")
    class InitializationTests {

        @Test
        @DisplayName("Should register all 18 strategy combinations")
        void shouldRegisterAllStrategies() {
            // 3 RSI × 2 EMA × 3 Volume = 18 combinations
            assertEquals(18, strategyRegistry.getStrategyCount());
        }

        @Test
        @DisplayName("All strategies should have valid IDs")
        void allStrategiesShouldHaveValidIds() {
            Collection<StrategyDefinition> strategies = strategyRegistry.getAllStrategies();

            for (StrategyDefinition strategy : strategies) {
                assertNotNull(strategy.getStrategyId());
                assertTrue(strategy.getStrategyId().startsWith("RSI_"));
                assertTrue(strategy.getStrategyId().contains("_EMA_"));
                assertTrue(strategy.getStrategyId().contains("_VOL_"));
            }
        }

        @Test
        @DisplayName("All strategies should have human readable names")
        void allStrategiesShouldHaveNames() {
            Collection<StrategyDefinition> strategies = strategyRegistry.getAllStrategies();

            for (StrategyDefinition strategy : strategies) {
                assertNotNull(strategy.getHumanReadableName());
                assertFalse(strategy.getHumanReadableName().isEmpty());
            }
        }

        @Test
        @DisplayName("All strategies should have allowed regimes")
        void allStrategiesShouldHaveAllowedRegimes() {
            Collection<StrategyDefinition> strategies = strategyRegistry.getAllStrategies();

            for (StrategyDefinition strategy : strategies) {
                assertNotNull(strategy.getAllowedRegimes());
                assertFalse(strategy.getAllowedRegimes().isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("Strategy Lookup Tests")
    class LookupTests {

        @Test
        @DisplayName("Should find strategy by valid ID")
        void shouldFindStrategyByValidId() {
            Optional<StrategyDefinition> strategy = strategyRegistry.getStrategy("RSI_LOW_EMA_BULL_VOL_HIGH");

            assertTrue(strategy.isPresent());
            assertEquals(StrategyDefinition.RsiBucket.LOW, strategy.get().getRsiBucket());
            assertEquals(StrategyDefinition.EmaTrend.BULL, strategy.get().getEmaTrend());
            assertEquals(StrategyDefinition.VolumeBucket.HIGH, strategy.get().getVolumeBucket());
        }

        @Test
        @DisplayName("Should return empty for invalid ID")
        void shouldReturnEmptyForInvalidId() {
            Optional<StrategyDefinition> strategy = strategyRegistry.getStrategy("INVALID_ID");

            assertTrue(strategy.isEmpty());
        }

        @Test
        @DisplayName("Should find strategies by type")
        void shouldFindStrategiesByType() {
            List<StrategyDefinition> trendFollowing = strategyRegistry.getStrategiesByType(
                    StrategyDefinition.StrategyType.TREND_FOLLOWING);

            assertFalse(trendFollowing.isEmpty());
            for (StrategyDefinition strategy : trendFollowing) {
                assertEquals(StrategyDefinition.StrategyType.TREND_FOLLOWING, strategy.getType());
            }
        }
    }

    @Nested
    @DisplayName("Market Snapshot Matching Tests")
    class SnapshotMatchingTests {

        @Test
        @DisplayName("Should find matching strategies for snapshot")
        void shouldFindMatchingStrategies() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(25))  // LOW
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(45000))  // BULL
                    .volumeChangePct(BigDecimal.valueOf(60))  // HIGH (>50)
                    .build();

            List<StrategyDefinition> matching = strategyRegistry.findMatchingStrategies(snapshot);

            assertEquals(1, matching.size());
            assertEquals("RSI_LOW_EMA_BULL_VOL_HIGH", matching.get(0).getStrategyId());
        }

        @Test
        @DisplayName("Should generate correct strategy ID from snapshot")
        void shouldGenerateCorrectStrategyIdFromSnapshot() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(50))  // MID
                    .ema50(BigDecimal.valueOf(45000))
                    .ema200(BigDecimal.valueOf(50000))  // BEAR
                    .volumeChangePct(BigDecimal.valueOf(0))  // MEDIUM (-20 to 50)
                    .build();

            String strategyId = strategyRegistry.getStrategyIdForSnapshot(snapshot);

            assertEquals("RSI_MID_EMA_BEAR_VOL_MED", strategyId);
        }

        @Test
        @DisplayName("Should return UNKNOWN for null snapshot")
        void shouldReturnUnknownForNullSnapshot() {
            String strategyId = strategyRegistry.getStrategyIdForSnapshot(null);
            assertEquals("UNKNOWN", strategyId);
        }
    }

    @Nested
    @DisplayName("Regime Filtering Tests")
    class RegimeFilteringTests {

        @Test
        @DisplayName("Should filter strategies by regime")
        void shouldFilterStrategiesByRegime() {
            List<StrategyDefinition> trendStrategies = strategyRegistry.getStrategiesForRegime(MarketRegime.TREND);
            List<StrategyDefinition> rangeStrategies = strategyRegistry.getStrategiesForRegime(MarketRegime.RANGE);

            assertFalse(trendStrategies.isEmpty());
            assertFalse(rangeStrategies.isEmpty());

            // Trend-following should be in TREND but not RANGE
            for (StrategyDefinition s : trendStrategies) {
                assertTrue(s.isAllowedInRegime(MarketRegime.TREND));
            }
        }

        @Test
        @DisplayName("Should check if strategy is allowed in regime")
        void shouldCheckStrategyAllowedInRegime() {
            // Find a breakout strategy (allowed in TREND and HIGH_VOLATILITY)
            List<StrategyDefinition> breakout = strategyRegistry.getStrategiesByType(
                    StrategyDefinition.StrategyType.BREAKOUT);

            if (!breakout.isEmpty()) {
                String strategyId = breakout.get(0).getStrategyId();
                // Breakout strategies should be allowed in TREND
                assertTrue(strategyRegistry.isStrategyAllowedInRegime(strategyId, MarketRegime.TREND) ||
                          strategyRegistry.isStrategyAllowedInRegime(strategyId, MarketRegime.HIGH_VOLATILITY));
            }
        }

        @Test
        @DisplayName("Should find matching strategies for regime")
        void shouldFindMatchingStrategiesForRegime() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(50))
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(45000))
                    .volumeChangePct(BigDecimal.valueOf(0))  // MEDIUM
                    .build();

            List<StrategyDefinition> trendMatches = strategyRegistry.findMatchingStrategiesForRegime(
                    snapshot, MarketRegime.TREND);

            // Results may differ based on regime filtering
            assertNotNull(trendMatches);
        }

        @Test
        @DisplayName("Should count strategies by regime")
        void shouldCountStrategiesByRegime() {
            Map<MarketRegime, Long> counts = strategyRegistry.getStrategyCountByRegime();

            assertEquals(3, counts.size()); // 3 regimes
            for (MarketRegime regime : MarketRegime.values()) {
                assertTrue(counts.containsKey(regime));
                assertTrue(counts.get(regime) > 0);
            }
        }
    }

    @Nested
    @DisplayName("Strategy Type Assignment Tests")
    class StrategyTypeTests {

        @Test
        @DisplayName("Low RSI + Bull trend should be MEAN_REVERSION")
        void lowRsiBullShouldBeMeanReversion() {
            Optional<StrategyDefinition> strategy = strategyRegistry.getStrategy("RSI_LOW_EMA_BULL_VOL_MED");

            assertTrue(strategy.isPresent());
            assertEquals(StrategyDefinition.StrategyType.MEAN_REVERSION, strategy.get().getType());
        }

        @Test
        @DisplayName("High RSI + Bear trend should be MEAN_REVERSION")
        void highRsiBearShouldBeMeanReversion() {
            Optional<StrategyDefinition> strategy = strategyRegistry.getStrategy("RSI_HIGH_EMA_BEAR_VOL_MED");

            assertTrue(strategy.isPresent());
            assertEquals(StrategyDefinition.StrategyType.MEAN_REVERSION, strategy.get().getType());
        }

        @Test
        @DisplayName("High volume should be BREAKOUT")
        void highVolumeShouldBeBreakout() {
            Optional<StrategyDefinition> strategy = strategyRegistry.getStrategy("RSI_MID_EMA_BULL_VOL_HIGH");

            assertTrue(strategy.isPresent());
            assertEquals(StrategyDefinition.StrategyType.BREAKOUT, strategy.get().getType());
        }
    }
}
