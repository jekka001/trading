package com.btc.collector.strategy;

import com.btc.collector.analysis.MarketSnapshot;
import com.btc.collector.strategy.StrategyDefinition.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Central registry for all strategy definitions.
 * Strategies are registered here and can be looked up by ID or conditions.
 */
@Component
@Slf4j
public class StrategyRegistry {

    private final Map<String, StrategyDefinition> strategies = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        registerAllStrategies();
        log.info("Strategy registry initialized with {} strategies", strategies.size());
    }

    /**
     * Register all predefined strategies.
     */
    private void registerAllStrategies() {
        // Generate all combinations of RSI, EMA, Volume
        for (RsiBucket rsi : RsiBucket.values()) {
            for (EmaTrend ema : EmaTrend.values()) {
                for (VolumeBucket vol : VolumeBucket.values()) {
                    registerStrategy(createStrategy(rsi, ema, vol));
                }
            }
        }
    }

    /**
     * Create a strategy definition from components.
     */
    private StrategyDefinition createStrategy(RsiBucket rsi, EmaTrend ema, VolumeBucket vol) {
        String id = StrategyDefinition.generateId(rsi, ema, vol);
        String name = generateHumanReadableName(rsi, ema, vol);
        StrategyType type = determineStrategyType(rsi, ema, vol);
        String description = generateDescription(rsi, ema, vol, type);
        Set<MarketRegime> allowedRegimes = StrategyDefinition.getDefaultAllowedRegimes(type);

        return StrategyDefinition.builder()
                .strategyId(id)
                .humanReadableName(name)
                .description(description)
                .type(type)
                .rsiBucket(rsi)
                .emaTrend(ema)
                .volumeBucket(vol)
                .allowedRegimes(allowedRegimes)
                .build();
    }

    /**
     * Generate human-readable name.
     */
    private String generateHumanReadableName(RsiBucket rsi, EmaTrend ema, VolumeBucket vol) {
        String rsiName = switch (rsi) {
            case LOW -> "Oversold";
            case MID -> "Neutral RSI";
            case HIGH -> "Overbought";
        };

        String emaName = ema == EmaTrend.BULL ? "Bullish Trend" : "Bearish Trend";

        String volName = switch (vol) {
            case LOW -> "Low Volume";
            case MED -> "Normal Volume";
            case HIGH -> "High Volume";
        };

        return String.format("%s + %s + %s", rsiName, emaName, volName);
    }

    /**
     * Determine strategy type based on conditions.
     */
    private StrategyType determineStrategyType(RsiBucket rsi, EmaTrend ema, VolumeBucket vol) {
        // Oversold in bull trend = mean reversion buy opportunity
        if (rsi == RsiBucket.LOW && ema == EmaTrend.BULL) {
            return StrategyType.MEAN_REVERSION;
        }

        // Overbought in bear trend = mean reversion (potential reversal)
        if (rsi == RsiBucket.HIGH && ema == EmaTrend.BEAR) {
            return StrategyType.MEAN_REVERSION;
        }

        // High volume = potential breakout
        if (vol == VolumeBucket.HIGH) {
            return StrategyType.BREAKOUT;
        }

        // Bull trend with neutral/high RSI = trend following
        if (ema == EmaTrend.BULL && rsi != RsiBucket.LOW) {
            return StrategyType.TREND_FOLLOWING;
        }

        // Low RSI with high volume = momentum
        if (rsi == RsiBucket.LOW && vol == VolumeBucket.HIGH) {
            return StrategyType.MOMENTUM;
        }

        return StrategyType.HYBRID;
    }

    /**
     * Generate strategy description.
     */
    private String generateDescription(RsiBucket rsi, EmaTrend ema, VolumeBucket vol, StrategyType type) {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name().replace("_", " ")).append(" strategy: ");

        switch (rsi) {
            case LOW -> sb.append("RSI indicates oversold conditions (<30). ");
            case MID -> sb.append("RSI in neutral zone (30-70). ");
            case HIGH -> sb.append("RSI indicates overbought conditions (>70). ");
        }

        if (ema == EmaTrend.BULL) {
            sb.append("EMA50 above EMA200 suggests uptrend. ");
        } else {
            sb.append("EMA50 below EMA200 suggests downtrend. ");
        }

        switch (vol) {
            case LOW -> sb.append("Low volume may indicate weak conviction.");
            case MED -> sb.append("Normal volume levels.");
            case HIGH -> sb.append("High volume suggests strong market interest.");
        }

        return sb.toString();
    }

    /**
     * Register a strategy.
     */
    public void registerStrategy(StrategyDefinition strategy) {
        strategies.put(strategy.getStrategyId(), strategy);
    }

    /**
     * Get strategy by ID.
     */
    public Optional<StrategyDefinition> getStrategy(String strategyId) {
        return Optional.ofNullable(strategies.get(strategyId));
    }

    /**
     * Get all registered strategies.
     */
    public Collection<StrategyDefinition> getAllStrategies() {
        return Collections.unmodifiableCollection(strategies.values());
    }

    /**
     * Get strategies by type.
     */
    public List<StrategyDefinition> getStrategiesByType(StrategyType type) {
        return strategies.values().stream()
                .filter(s -> s.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Find matching strategies for a market snapshot.
     */
    public List<StrategyDefinition> findMatchingStrategies(MarketSnapshot snapshot) {
        return strategies.values().stream()
                .filter(s -> s.matches(snapshot))
                .collect(Collectors.toList());
    }

    /**
     * Get strategy count.
     */
    public int getStrategyCount() {
        return strategies.size();
    }

    /**
     * Get strategy ID from market snapshot.
     */
    public String getStrategyIdForSnapshot(MarketSnapshot snapshot) {
        if (snapshot == null || snapshot.getRsi() == null) {
            return "UNKNOWN";
        }

        RsiBucket rsi = StrategyDefinition.classifyRsi(snapshot.getRsi());
        EmaTrend ema = snapshot.isEma50BelowEma200() ? EmaTrend.BEAR : EmaTrend.BULL;

        // Convert from MarketSnapshot.VolumeBucket to StrategyDefinition.VolumeBucket
        MarketSnapshot.VolumeBucket snapshotVol = snapshot.getVolumeBucket();
        VolumeBucket vol = StrategyDefinition.convertVolumeBucket(snapshotVol);

        return StrategyDefinition.generateId(rsi, ema, vol);
    }

    /**
     * Get strategies allowed in a specific market regime.
     */
    public List<StrategyDefinition> getStrategiesForRegime(MarketRegime regime) {
        return strategies.values().stream()
                .filter(s -> s.isAllowedInRegime(regime))
                .collect(Collectors.toList());
    }

    /**
     * Check if a strategy is allowed in the given regime.
     */
    public boolean isStrategyAllowedInRegime(String strategyId, MarketRegime regime) {
        return getStrategy(strategyId)
                .map(s -> s.isAllowedInRegime(regime))
                .orElse(false);
    }

    /**
     * Filter matching strategies by regime.
     */
    public List<StrategyDefinition> findMatchingStrategiesForRegime(MarketSnapshot snapshot, MarketRegime regime) {
        return strategies.values().stream()
                .filter(s -> s.matches(snapshot))
                .filter(s -> s.isAllowedInRegime(regime))
                .collect(Collectors.toList());
    }

    /**
     * Get count of strategies allowed in each regime.
     */
    public Map<MarketRegime, Long> getStrategyCountByRegime() {
        Map<MarketRegime, Long> counts = new EnumMap<>(MarketRegime.class);
        for (MarketRegime regime : MarketRegime.values()) {
            counts.put(regime, strategies.values().stream()
                    .filter(s -> s.isAllowedInRegime(regime))
                    .count());
        }
        return counts;
    }
}
