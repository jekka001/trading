package com.btc.collector.strategy;

import com.btc.collector.analysis.MarketSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StrategyDefinition.
 */
@DisplayName("StrategyDefinition Tests")
class StrategyDefinitionTest {

    @Nested
    @DisplayName("RSI Classification")
    class RsiClassificationTests {

        @Test
        @DisplayName("RSI below 30 should classify as LOW")
        void rsiBelow30ShouldBeLow() {
            assertEquals(StrategyDefinition.RsiBucket.LOW,
                    StrategyDefinition.classifyRsi(BigDecimal.valueOf(25)));
            assertEquals(StrategyDefinition.RsiBucket.LOW,
                    StrategyDefinition.classifyRsi(BigDecimal.valueOf(29.9)));
        }

        @Test
        @DisplayName("RSI between 30-70 should classify as MID")
        void rsiBetween30And70ShouldBeMid() {
            assertEquals(StrategyDefinition.RsiBucket.MID,
                    StrategyDefinition.classifyRsi(BigDecimal.valueOf(30)));
            assertEquals(StrategyDefinition.RsiBucket.MID,
                    StrategyDefinition.classifyRsi(BigDecimal.valueOf(50)));
            assertEquals(StrategyDefinition.RsiBucket.MID,
                    StrategyDefinition.classifyRsi(BigDecimal.valueOf(70)));
        }

        @Test
        @DisplayName("RSI above 70 should classify as HIGH")
        void rsiAbove70ShouldBeHigh() {
            assertEquals(StrategyDefinition.RsiBucket.HIGH,
                    StrategyDefinition.classifyRsi(BigDecimal.valueOf(70.1)));
            assertEquals(StrategyDefinition.RsiBucket.HIGH,
                    StrategyDefinition.classifyRsi(BigDecimal.valueOf(85)));
        }

        @Test
        @DisplayName("Null RSI should default to MID")
        void nullRsiShouldBeMid() {
            assertEquals(StrategyDefinition.RsiBucket.MID,
                    StrategyDefinition.classifyRsi(null));
        }
    }

    @Nested
    @DisplayName("Strategy ID Generation")
    class IdGenerationTests {

        @Test
        @DisplayName("Should generate correct ID from components")
        void shouldGenerateCorrectId() {
            String id = StrategyDefinition.generateId(
                    StrategyDefinition.RsiBucket.LOW,
                    StrategyDefinition.EmaTrend.BULL,
                    StrategyDefinition.VolumeBucket.HIGH);

            assertEquals("RSI_LOW_EMA_BULL_VOL_HIGH", id);
        }

        @Test
        @DisplayName("Should handle null components with ANY")
        void shouldHandleNullComponents() {
            String id = StrategyDefinition.generateId(null, null, null);
            assertEquals("RSI_ANY_EMA_ANY_VOL_ANY", id);
        }

        @Test
        @DisplayName("Should generate all valid combinations")
        void shouldGenerateAllCombinations() {
            for (StrategyDefinition.RsiBucket rsi : StrategyDefinition.RsiBucket.values()) {
                for (StrategyDefinition.EmaTrend ema : StrategyDefinition.EmaTrend.values()) {
                    for (StrategyDefinition.VolumeBucket vol : StrategyDefinition.VolumeBucket.values()) {
                        String id = StrategyDefinition.generateId(rsi, ema, vol);
                        assertNotNull(id);
                        assertTrue(id.contains(rsi.name()));
                        assertTrue(id.contains(ema.name()));
                        assertTrue(id.contains(vol.name()));
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Strategy Matching")
    class MatchingTests {

        @Test
        @DisplayName("Should match when all conditions align")
        void shouldMatchWhenConditionsAlign() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .rsiBucket(StrategyDefinition.RsiBucket.LOW)
                    .emaTrend(StrategyDefinition.EmaTrend.BULL)
                    .volumeBucket(StrategyDefinition.VolumeBucket.HIGH)
                    .build();

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(25))
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(45000))
                    .volumeChangePct(BigDecimal.valueOf(60))  // HIGH (>50)
                    .build();

            assertTrue(strategy.matches(snapshot));
        }

        @Test
        @DisplayName("Should not match when RSI differs")
        void shouldNotMatchWhenRsiDiffers() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .rsiBucket(StrategyDefinition.RsiBucket.LOW)
                    .emaTrend(StrategyDefinition.EmaTrend.BULL)
                    .build();

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(50)) // MID, not LOW
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(45000))
                    .build();

            assertFalse(strategy.matches(snapshot));
        }

        @Test
        @DisplayName("Should not match when EMA trend differs")
        void shouldNotMatchWhenEmaTrendDiffers() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .rsiBucket(StrategyDefinition.RsiBucket.MID)
                    .emaTrend(StrategyDefinition.EmaTrend.BULL)
                    .build();

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(50))
                    .ema50(BigDecimal.valueOf(45000)) // Below EMA200 = BEAR
                    .ema200(BigDecimal.valueOf(50000))
                    .build();

            assertFalse(strategy.matches(snapshot));
        }

        @Test
        @DisplayName("Should return false for null snapshot")
        void shouldReturnFalseForNullSnapshot() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .build();

            assertFalse(strategy.matches(null));
        }

        @Test
        @DisplayName("Should return false for snapshot with null RSI")
        void shouldReturnFalseForNullRsi() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .build();

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .rsi(null)
                    .build();

            assertFalse(strategy.matches(snapshot));
        }
    }

    @Nested
    @DisplayName("Regime Filtering")
    class RegimeFilteringTests {

        @Test
        @DisplayName("Should be allowed in regime when in allowedRegimes")
        void shouldBeAllowedInRegime() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .type(StrategyDefinition.StrategyType.TREND_FOLLOWING)
                    .allowedRegimes(EnumSet.of(MarketRegime.TREND))
                    .build();

            assertTrue(strategy.isAllowedInRegime(MarketRegime.TREND));
            assertFalse(strategy.isAllowedInRegime(MarketRegime.RANGE));
        }

        @Test
        @DisplayName("Should be allowed in all regimes with null allowedRegimes")
        void shouldBeAllowedInAllRegimesWithNull() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .allowedRegimes(null)
                    .build();

            assertTrue(strategy.isAllowedInRegime(MarketRegime.TREND));
            assertTrue(strategy.isAllowedInRegime(MarketRegime.RANGE));
            assertTrue(strategy.isAllowedInRegime(MarketRegime.HIGH_VOLATILITY));
        }

        @Test
        @DisplayName("Should return correct default regimes for strategy types")
        void shouldReturnCorrectDefaultRegimes() {
            Set<MarketRegime> trendRegimes = StrategyDefinition.getDefaultAllowedRegimes(
                    StrategyDefinition.StrategyType.TREND_FOLLOWING);
            assertTrue(trendRegimes.contains(MarketRegime.TREND));
            assertFalse(trendRegimes.contains(MarketRegime.RANGE));

            Set<MarketRegime> meanRevRegimes = StrategyDefinition.getDefaultAllowedRegimes(
                    StrategyDefinition.StrategyType.MEAN_REVERSION);
            assertTrue(meanRevRegimes.contains(MarketRegime.RANGE));
            assertFalse(meanRevRegimes.contains(MarketRegime.TREND));

            Set<MarketRegime> hybridRegimes = StrategyDefinition.getDefaultAllowedRegimes(
                    StrategyDefinition.StrategyType.HYBRID);
            assertEquals(3, hybridRegimes.size()); // All regimes
        }
    }

    @Nested
    @DisplayName("RSI Threshold Tests")
    class RsiThresholdTests {

        @Test
        @DisplayName("Should respect minRsi threshold")
        void shouldRespectMinRsiThreshold() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .minRsi(BigDecimal.valueOf(40))
                    .build();

            MarketSnapshot lowRsi = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(35))
                    .build();
            assertFalse(strategy.matches(lowRsi));

            MarketSnapshot validRsi = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(45))
                    .build();
            assertTrue(strategy.matches(validRsi));
        }

        @Test
        @DisplayName("Should respect maxRsi threshold")
        void shouldRespectMaxRsiThreshold() {
            StrategyDefinition strategy = StrategyDefinition.builder()
                    .strategyId("TEST")
                    .maxRsi(BigDecimal.valueOf(60))
                    .build();

            MarketSnapshot highRsi = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(65))
                    .build();
            assertFalse(strategy.matches(highRsi));

            MarketSnapshot validRsi = MarketSnapshot.builder()
                    .rsi(BigDecimal.valueOf(55))
                    .build();
            assertTrue(strategy.matches(validRsi));
        }
    }
}
