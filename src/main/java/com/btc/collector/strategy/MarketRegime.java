package com.btc.collector.strategy;

/**
 * Market regime classification (MVP: exactly 3 regimes).
 * Used to adapt strategy weights based on current market conditions.
 */
public enum MarketRegime {

    /**
     * Clear directional movement.
     * EMA50 and EMA200 diverging, price making consistent higher/lower highs.
     */
    TREND("Trend", "Market showing clear directional movement", 1.1),

    /**
     * Sideways movement within a range.
     * EMAs close together, price oscillating around mean.
     */
    RANGE("Range", "Market moving sideways in a defined range", 0.9),

    /**
     * Large price swings, high ATR, chaos conditions.
     * Increased uncertainty and risk.
     */
    HIGH_VOLATILITY("High Volatility", "Market experiencing large price swings", 0.7);

    private final String displayName;
    private final String description;
    private final double multiplier;

    MarketRegime(String displayName, String description, double multiplier) {
        this.displayName = displayName;
        this.description = description;
        this.multiplier = multiplier;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get the probability multiplier for this regime.
     * TREND = 1.1, RANGE = 0.9, HIGH_VOLATILITY = 0.7
     */
    public double getMultiplier() {
        return multiplier;
    }
}
