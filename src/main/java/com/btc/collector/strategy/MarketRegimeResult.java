package com.btc.collector.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Result of market regime detection including confidence score.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketRegimeResult {

    private MarketRegime regime;

    /**
     * Confidence score between 0.0 and 1.0.
     * Calculated as matchedConditions / totalConditions.
     */
    private double confidence;

    /**
     * Timestamp when this regime was detected.
     */
    private LocalDateTime detectedAt;

    /**
     * Number of conditions that matched for this regime.
     */
    private int matchedConditions;

    /**
     * Total number of conditions checked.
     */
    private int totalConditions;

    /**
     * Get the effective multiplier (regime multiplier × confidence).
     */
    public double getEffectiveMultiplier() {
        return regime.getMultiplier() * confidence;
    }

    /**
     * Get confidence as percentage string.
     */
    public String getConfidencePercent() {
        return String.format("%.0f%%", confidence * 100);
    }
}
