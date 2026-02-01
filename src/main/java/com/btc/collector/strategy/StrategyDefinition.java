package com.btc.collector.strategy;

import com.btc.collector.analysis.MarketSnapshot;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Explicit strategy definition - makes strategies first-class objects.
 * Each strategy has clear conditions that must be met for it to trigger.
 */
@Data
@Builder
public class StrategyDefinition {

    private final String strategyId;
    private final String humanReadableName;
    private final String description;
    private final StrategyType type;

    // RSI conditions
    private final RsiBucket rsiBucket;

    // EMA conditions
    private final EmaTrend emaTrend;

    // Volume conditions
    private final VolumeBucket volumeBucket;

    // Optional thresholds
    @Builder.Default
    private final BigDecimal minRsi = null;
    @Builder.Default
    private final BigDecimal maxRsi = null;

    /**
     * Market regimes where this strategy is allowed to operate.
     * If empty or null, strategy is allowed in all regimes.
     */
    @Builder.Default
    private final Set<MarketRegime> allowedRegimes = EnumSet.allOf(MarketRegime.class);

    /**
     * Strategy types for categorization.
     */
    public enum StrategyType {
        TREND_FOLLOWING,    // Works best in trending markets
        MEAN_REVERSION,     // Works best in ranging markets
        MOMENTUM,           // Works in volatile conditions
        BREAKOUT,           // Works on volume spikes
        HYBRID              // Multi-condition strategy
    }

    /**
     * RSI bucket classification.
     */
    public enum RsiBucket {
        LOW,    // RSI < 30 (oversold)
        MID,    // 30 <= RSI <= 70 (neutral)
        HIGH    // RSI > 70 (overbought)
    }

    /**
     * EMA trend classification.
     */
    public enum EmaTrend {
        BULL,   // EMA50 > EMA200
        BEAR    // EMA50 < EMA200
    }

    /**
     * Check if this strategy matches the given market snapshot.
     */
    public boolean matches(MarketSnapshot snapshot) {
        if (snapshot == null || snapshot.getRsi() == null) {
            return false;
        }

        // Check RSI bucket
        RsiBucket snapshotRsiBucket = classifyRsi(snapshot.getRsi());
        if (rsiBucket != null && rsiBucket != snapshotRsiBucket) {
            return false;
        }

        // Check EMA trend
        EmaTrend snapshotEmaTrend = snapshot.isEma50BelowEma200() ? EmaTrend.BEAR : EmaTrend.BULL;
        if (emaTrend != null && emaTrend != snapshotEmaTrend) {
            return false;
        }

        // Check volume bucket (convert from MarketSnapshot.VolumeBucket)
        if (volumeBucket != null) {
            MarketSnapshot.VolumeBucket snapshotVolBucket = snapshot.getVolumeBucket();
            if (snapshotVolBucket == null) return false;

            // Compare with mapping (MEDIUM -> MED)
            VolumeBucket convertedVol = convertVolumeBucket(snapshotVolBucket);
            if (volumeBucket != convertedVol) {
                return false;
            }
        }

        // Check optional RSI thresholds
        if (minRsi != null && snapshot.getRsi().compareTo(minRsi) < 0) {
            return false;
        }
        if (maxRsi != null && snapshot.getRsi().compareTo(maxRsi) > 0) {
            return false;
        }

        return true;
    }

    /**
     * Volume bucket classification (mirrors MarketSnapshot.VolumeBucket).
     */
    public enum VolumeBucket {
        LOW,    // Below average volume
        MED,    // Average volume
        HIGH    // Above average volume
    }

    /**
     * Classify RSI value into bucket.
     */
    public static RsiBucket classifyRsi(BigDecimal rsi) {
        if (rsi == null) return RsiBucket.MID;
        double value = rsi.doubleValue();
        if (value < 30) return RsiBucket.LOW;
        if (value > 70) return RsiBucket.HIGH;
        return RsiBucket.MID;
    }

    /**
     * Convert MarketSnapshot.VolumeBucket to StrategyDefinition.VolumeBucket.
     * Handles the naming difference (MEDIUM vs MED).
     */
    public static VolumeBucket convertVolumeBucket(MarketSnapshot.VolumeBucket snapshotVol) {
        if (snapshotVol == null) {
            return VolumeBucket.MED;
        }
        return switch (snapshotVol) {
            case LOW -> VolumeBucket.LOW;
            case MEDIUM -> VolumeBucket.MED;
            case HIGH -> VolumeBucket.HIGH;
        };
    }

    /**
     * Generate strategy ID from components.
     */
    public static String generateId(RsiBucket rsi, EmaTrend ema, VolumeBucket volume) {
        return String.format("RSI_%s_EMA_%s_VOL_%s",
                rsi != null ? rsi.name() : "ANY",
                ema != null ? ema.name() : "ANY",
                volume != null ? volume.name() : "ANY");
    }

    /**
     * Check if this strategy is allowed in the given market regime.
     */
    public boolean isAllowedInRegime(MarketRegime regime) {
        if (allowedRegimes == null || allowedRegimes.isEmpty()) {
            return true; // No restrictions
        }
        return allowedRegimes.contains(regime);
    }

    /**
     * Get default allowed regimes based on strategy type.
     */
    public static Set<MarketRegime> getDefaultAllowedRegimes(StrategyType type) {
        return switch (type) {
            case TREND_FOLLOWING -> EnumSet.of(MarketRegime.TREND);
            case MEAN_REVERSION -> EnumSet.of(MarketRegime.RANGE);
            case MOMENTUM -> EnumSet.of(MarketRegime.TREND, MarketRegime.HIGH_VOLATILITY);
            case BREAKOUT -> EnumSet.of(MarketRegime.TREND, MarketRegime.HIGH_VOLATILITY);
            case HYBRID -> EnumSet.allOf(MarketRegime.class);
        };
    }
}
