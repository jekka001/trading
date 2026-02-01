package com.btc.collector.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MarketRegime enum.
 */
@DisplayName("MarketRegime Tests")
class MarketRegimeTest {

    @Test
    @DisplayName("Should have exactly 3 regimes")
    void shouldHaveThreeRegimes() {
        assertEquals(3, MarketRegime.values().length);
    }

    @Test
    @DisplayName("TREND should have multiplier 1.1")
    void trendShouldHaveCorrectMultiplier() {
        assertEquals(1.1, MarketRegime.TREND.getMultiplier(), 0.001);
    }

    @Test
    @DisplayName("RANGE should have multiplier 0.9")
    void rangeShouldHaveCorrectMultiplier() {
        assertEquals(0.9, MarketRegime.RANGE.getMultiplier(), 0.001);
    }

    @Test
    @DisplayName("HIGH_VOLATILITY should have multiplier 0.7")
    void highVolatilityShouldHaveCorrectMultiplier() {
        assertEquals(0.7, MarketRegime.HIGH_VOLATILITY.getMultiplier(), 0.001);
    }

    @Test
    @DisplayName("All regimes should have display names")
    void allRegimesShouldHaveDisplayNames() {
        for (MarketRegime regime : MarketRegime.values()) {
            assertNotNull(regime.getDisplayName());
            assertFalse(regime.getDisplayName().isEmpty());
        }
    }

    @Test
    @DisplayName("All regimes should have descriptions")
    void allRegimesShouldHaveDescriptions() {
        for (MarketRegime regime : MarketRegime.values()) {
            assertNotNull(regime.getDescription());
            assertFalse(regime.getDescription().isEmpty());
        }
    }

    @Test
    @DisplayName("TREND multiplier should boost signals")
    void trendMultiplierShouldBoostSignals() {
        assertTrue(MarketRegime.TREND.getMultiplier() > 1.0);
    }

    @Test
    @DisplayName("HIGH_VOLATILITY multiplier should reduce signals")
    void highVolatilityMultiplierShouldReduceSignals() {
        assertTrue(MarketRegime.HIGH_VOLATILITY.getMultiplier() < 1.0);
    }
}
