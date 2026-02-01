package com.btc.collector.strategy;

import com.btc.collector.analysis.MarketSnapshot;
import com.btc.collector.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Detects the current market regime based on technical indicators.
 * Uses EMA distance, ATR, Bollinger Bands, and price volatility.
 *
 * MVP: Exactly 3 regimes - TREND, RANGE, HIGH_VOLATILITY
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketRegimeDetector {

    private final Candle15mRepository candleRepository;
    private final Indicator15mRepository indicatorRepository;
    private final MarketRegimeRepository regimeRepository;

    // Detection parameters
    private static final int LOOKBACK_CANDLES = 96; // 24 hours of 15m candles
    private static final double TREND_EMA_DISTANCE_THRESHOLD = 1.5; // 1.5% distance for trend
    private static final double RANGE_EMA_DISTANCE_THRESHOLD = 0.5; // 0.5% max distance for range
    private static final double RANGE_ATR_THRESHOLD = 1.0; // ATR/price < 1% for range
    private static final double HIGH_VOL_ATR_THRESHOLD = 2.0; // ATR/price > 2% for high volatility
    private static final double HIGH_VOL_PRICE_CHANGE_THRESHOLD = 2.0; // 2% price change
    private static final double BB_INSIDE_THRESHOLD = 0.8; // 80% candles inside BB for range

    // Cache for current regime
    private MarketRegimeResult currentRegime = null;
    private LocalDateTime lastDetectionTime = null;

    /**
     * Detect current market regime from latest indicators.
     * Main entry point for regime detection.
     */
    public MarketRegimeResult detectRegime() {
        LocalDateTime now = LocalDateTime.now();

        // Get recent candles and indicators
        List<Candle15mEntity> candles = candleRepository.findRecentCandles(LOOKBACK_CANDLES);
        List<Indicator15mEntity> indicators = indicatorRepository.findRecentIndicators(LOOKBACK_CANDLES);

        if (candles.size() < 10 || indicators.isEmpty()) {
            log.warn("Not enough data for regime detection: candles={}, indicators={}",
                    candles.size(), indicators.size());
            return MarketRegimeResult.builder()
                    .regime(MarketRegime.RANGE)
                    .confidence(0.5)
                    .detectedAt(now)
                    .matchedConditions(0)
                    .totalConditions(1)
                    .build();
        }

        Indicator15mEntity latestIndicator = indicators.get(0); // Most recent
        BigDecimal currentPrice = candles.get(0).getClosePrice();

        // Calculate scores for each regime
        RegimeScore trendScore = calculateTrendScore(latestIndicator, currentPrice, candles);
        RegimeScore rangeScore = calculateRangeScore(latestIndicator, currentPrice, candles, indicators);
        RegimeScore highVolScore = calculateHighVolatilityScore(latestIndicator, currentPrice, candles);

        // Choose regime with highest confidence
        MarketRegimeResult result;
        if (highVolScore.confidence >= trendScore.confidence && highVolScore.confidence >= rangeScore.confidence) {
            result = MarketRegimeResult.builder()
                    .regime(MarketRegime.HIGH_VOLATILITY)
                    .confidence(highVolScore.confidence)
                    .detectedAt(now)
                    .matchedConditions(highVolScore.matched)
                    .totalConditions(highVolScore.total)
                    .build();
        } else if (trendScore.confidence >= rangeScore.confidence) {
            result = MarketRegimeResult.builder()
                    .regime(MarketRegime.TREND)
                    .confidence(trendScore.confidence)
                    .detectedAt(now)
                    .matchedConditions(trendScore.matched)
                    .totalConditions(trendScore.total)
                    .build();
        } else {
            result = MarketRegimeResult.builder()
                    .regime(MarketRegime.RANGE)
                    .confidence(rangeScore.confidence)
                    .detectedAt(now)
                    .matchedConditions(rangeScore.matched)
                    .totalConditions(rangeScore.total)
                    .build();
        }

        // Update cache
        currentRegime = result;
        lastDetectionTime = now;

        log.info("Regime detected: {} (confidence: {}%, matched: {}/{})",
                result.getRegime(),
                String.format("%.0f", result.getConfidence() * 100),
                result.getMatchedConditions(),
                result.getTotalConditions());

        return result;
    }

    /**
     * Detect and persist regime to database.
     */
    public MarketRegimeResult detectAndPersist() {
        MarketRegimeResult result = detectRegime();

        // Persist to database
        MarketRegimeEntity entity = MarketRegimeEntity.builder()
                .timestamp(result.getDetectedAt())
                .regimeType(result.getRegime())
                .confidence(result.getConfidence())
                .matchedConditions(result.getMatchedConditions())
                .totalConditions(result.getTotalConditions())
                .build();

        regimeRepository.save(entity);
        log.debug("Regime persisted: {} at {}", result.getRegime(), result.getDetectedAt());

        return result;
    }

    /**
     * Calculate TREND regime score.
     *
     * Conditions:
     * 1. |EMA50 - EMA200| / price > 1.5%
     * 2. (EMA50 > EMA200 AND price > EMA50) OR (EMA50 < EMA200 AND price < EMA50)
     * 3. Price making consistent higher highs/lower lows
     * 4. RSI not in extreme zone (30-70) - sustainable trend
     */
    private RegimeScore calculateTrendScore(Indicator15mEntity indicator, BigDecimal price,
                                            List<Candle15mEntity> candles) {
        int matched = 0;
        int total = 4;

        if (indicator.getEma50() == null || indicator.getEma200() == null) {
            return new RegimeScore(0, total, 0.0);
        }

        BigDecimal ema50 = indicator.getEma50();
        BigDecimal ema200 = indicator.getEma200();
        double priceValue = price.doubleValue();

        // Condition 1: EMA distance > 1.5%
        double emaDistance = Math.abs(ema50.subtract(ema200).doubleValue()) / priceValue * 100;
        if (emaDistance > TREND_EMA_DISTANCE_THRESHOLD) {
            matched++;
        }

        // Condition 2: Price aligned with EMAs (trending direction)
        boolean bullTrend = ema50.compareTo(ema200) > 0 && price.compareTo(ema50) > 0;
        boolean bearTrend = ema50.compareTo(ema200) < 0 && price.compareTo(ema50) < 0;
        if (bullTrend || bearTrend) {
            matched++;
        }

        // Condition 3: Consistent higher highs or lower lows (last 8 candles)
        if (candles.size() >= 8) {
            boolean higherHighs = true;
            boolean lowerLows = true;
            for (int i = 0; i < 7; i++) {
                BigDecimal current = candles.get(i).getHighPrice();
                BigDecimal previous = candles.get(i + 1).getHighPrice();
                if (current.compareTo(previous) <= 0) higherHighs = false;

                current = candles.get(i).getLowPrice();
                previous = candles.get(i + 1).getLowPrice();
                if (current.compareTo(previous) >= 0) lowerLows = false;
            }
            if (higherHighs || lowerLows) {
                matched++;
            }
        }

        // Condition 4: RSI in sustainable zone (not extreme)
        if (indicator.getRsi14() != null) {
            double rsi = indicator.getRsi14().doubleValue();
            if (rsi > 35 && rsi < 65) {
                matched++;
            }
        }

        double confidence = (double) matched / total;
        return new RegimeScore(matched, total, confidence);
    }

    /**
     * Calculate RANGE regime score.
     *
     * Conditions:
     * 1. Price inside Bollinger Bands >= 80% of candles (last 24h)
     * 2. ATR / price < 1%
     * 3. |EMA50 - EMA200| / price < 0.5%
     * 4. RSI oscillating (not trending one direction)
     */
    private RegimeScore calculateRangeScore(Indicator15mEntity indicator, BigDecimal price,
                                            List<Candle15mEntity> candles,
                                            List<Indicator15mEntity> indicators) {
        int matched = 0;
        int total = 4;

        if (indicator.getEma50() == null || indicator.getEma200() == null) {
            return new RegimeScore(0, total, 0.0);
        }

        double priceValue = price.doubleValue();

        // Condition 1: Price inside BB >= 80% of time
        int insideBB = 0;
        int totalChecked = 0;
        for (int i = 0; i < Math.min(candles.size(), indicators.size()); i++) {
            Indicator15mEntity ind = indicators.get(i);
            Candle15mEntity candle = candles.get(i);

            if (ind.getBbLower() != null && ind.getBbUpper() != null) {
                BigDecimal close = candle.getClosePrice();
                if (close.compareTo(ind.getBbLower()) >= 0 && close.compareTo(ind.getBbUpper()) <= 0) {
                    insideBB++;
                }
                totalChecked++;
            }
        }
        if (totalChecked > 0 && (double) insideBB / totalChecked >= BB_INSIDE_THRESHOLD) {
            matched++;
        }

        // Condition 2: ATR / price < 1%
        if (indicator.getAtr14() != null) {
            double atrPercent = indicator.getAtr14().doubleValue() / priceValue * 100;
            if (atrPercent < RANGE_ATR_THRESHOLD) {
                matched++;
            }
        }

        // Condition 3: EMA distance < 0.5%
        double emaDistance = Math.abs(indicator.getEma50().subtract(indicator.getEma200()).doubleValue())
                / priceValue * 100;
        if (emaDistance < RANGE_EMA_DISTANCE_THRESHOLD) {
            matched++;
        }

        // Condition 4: RSI oscillating (crosses 50 multiple times)
        int rsiCrosses = countRsiCrosses(indicators);
        if (rsiCrosses >= 3) { // At least 3 crosses in 24h
            matched++;
        }

        double confidence = (double) matched / total;
        return new RegimeScore(matched, total, confidence);
    }

    /**
     * Calculate HIGH_VOLATILITY regime score.
     *
     * Conditions:
     * 1. ATR / price > 2%
     * 2. |close[i] - close[i-1]| / price > 2% occurs multiple times
     * 3. Price outside Bollinger Bands frequently
     * 4. Large wicks (high-low much larger than body)
     */
    private RegimeScore calculateHighVolatilityScore(Indicator15mEntity indicator, BigDecimal price,
                                                      List<Candle15mEntity> candles) {
        int matched = 0;
        int total = 4;

        double priceValue = price.doubleValue();

        // Condition 1: ATR / price > 2%
        if (indicator.getAtr14() != null) {
            double atrPercent = indicator.getAtr14().doubleValue() / priceValue * 100;
            if (atrPercent > HIGH_VOL_ATR_THRESHOLD) {
                matched++;
            }
        }

        // Condition 2: Large price changes (> 2%) multiple times in 24h
        int largeMoves = 0;
        for (int i = 0; i < candles.size() - 1; i++) {
            BigDecimal current = candles.get(i).getClosePrice();
            BigDecimal previous = candles.get(i + 1).getClosePrice();
            double changePercent = Math.abs(current.subtract(previous).doubleValue()) / previous.doubleValue() * 100;
            if (changePercent > HIGH_VOL_PRICE_CHANGE_THRESHOLD) {
                largeMoves++;
            }
        }
        if (largeMoves >= 3) { // At least 3 large moves
            matched++;
        }

        // Condition 3: Price outside BB frequently
        if (indicator.getBbLower() != null && indicator.getBbUpper() != null) {
            int outsideBB = 0;
            for (Candle15mEntity candle : candles) {
                BigDecimal close = candle.getClosePrice();
                if (close.compareTo(indicator.getBbLower()) < 0 || close.compareTo(indicator.getBbUpper()) > 0) {
                    outsideBB++;
                }
            }
            if (outsideBB >= candles.size() * 0.15) { // > 15% outside BB
                matched++;
            }
        }

        // Condition 4: Large wicks (wick > 2x body)
        int largeWicks = 0;
        for (Candle15mEntity candle : candles) {
            double body = Math.abs(candle.getClosePrice().subtract(candle.getOpenPrice()).doubleValue());
            double totalRange = candle.getHighPrice().subtract(candle.getLowPrice()).doubleValue();
            double wick = totalRange - body;
            if (body > 0 && wick > body * 2) {
                largeWicks++;
            }
        }
        if (largeWicks >= candles.size() * 0.2) { // > 20% large wicks
            matched++;
        }

        double confidence = (double) matched / total;
        return new RegimeScore(matched, total, confidence);
    }

    /**
     * Count how many times RSI crosses the 50 level.
     */
    private int countRsiCrosses(List<Indicator15mEntity> indicators) {
        int crosses = 0;
        Double lastRsi = null;

        for (Indicator15mEntity ind : indicators) {
            if (ind.getRsi14() != null) {
                double rsi = ind.getRsi14().doubleValue();
                if (lastRsi != null) {
                    boolean crossed = (lastRsi < 50 && rsi >= 50) || (lastRsi >= 50 && rsi < 50);
                    if (crossed) crosses++;
                }
                lastRsi = rsi;
            }
        }
        return crosses;
    }

    /**
     * Detect regime from a market snapshot (for quick checks without DB access).
     */
    public MarketRegimeResult detectRegime(MarketSnapshot snapshot) {
        if (snapshot == null) {
            return MarketRegimeResult.builder()
                    .regime(MarketRegime.RANGE)
                    .confidence(0.5)
                    .detectedAt(LocalDateTime.now())
                    .build();
        }

        int trendMatched = 0;
        int rangeMatched = 0;
        int volatilityMatched = 0;
        int total = 3;

        // Check EMA distance
        if (snapshot.getEma50() != null && snapshot.getEma200() != null && snapshot.getPrice() != null) {
            double price = snapshot.getPrice().doubleValue();
            double emaDistance = Math.abs(snapshot.getEma50().subtract(snapshot.getEma200()).doubleValue())
                    / price * 100;

            if (emaDistance > TREND_EMA_DISTANCE_THRESHOLD) trendMatched++;
            if (emaDistance < RANGE_EMA_DISTANCE_THRESHOLD) rangeMatched++;
        }

        // Check price changes
        if (snapshot.getPriceChange1h() != null) {
            double change = Math.abs(snapshot.getPriceChange1h().doubleValue());
            if (change > HIGH_VOL_PRICE_CHANGE_THRESHOLD) volatilityMatched++;
            if (change < 0.5) rangeMatched++;
        }

        // Check RSI
        if (snapshot.getRsi() != null) {
            double rsi = snapshot.getRsi().doubleValue();
            if (rsi > 35 && rsi < 65) {
                trendMatched++;
                rangeMatched++;
            }
            if (rsi < 25 || rsi > 75) volatilityMatched++;
        }

        // Determine winner
        MarketRegime regime;
        int matched;
        if (volatilityMatched >= trendMatched && volatilityMatched >= rangeMatched && volatilityMatched > 0) {
            regime = MarketRegime.HIGH_VOLATILITY;
            matched = volatilityMatched;
        } else if (trendMatched >= rangeMatched) {
            regime = MarketRegime.TREND;
            matched = trendMatched;
        } else {
            regime = MarketRegime.RANGE;
            matched = rangeMatched;
        }

        double confidence = Math.max(0.3, (double) matched / total);

        return MarketRegimeResult.builder()
                .regime(regime)
                .confidence(confidence)
                .detectedAt(LocalDateTime.now())
                .matchedConditions(matched)
                .totalConditions(total)
                .build();
    }

    /**
     * Get current cached regime.
     */
    public MarketRegimeResult getCurrentRegime() {
        if (currentRegime == null) {
            return detectRegime();
        }
        return currentRegime;
    }

    /**
     * Get last detection time.
     */
    public LocalDateTime getLastDetectionTime() {
        return lastDetectionTime;
    }

    /**
     * Get recent regime history from database.
     */
    public List<MarketRegimeEntity> getRegimeHistory(int limit) {
        return regimeRepository.findRecentRegimes(limit);
    }

    /**
     * Get recent regime changes.
     */
    public List<MarketRegimeEntity> getRegimeChanges(int limit) {
        return regimeRepository.findRegimeChanges(limit);
    }

    /**
     * Get regime adjustment factor for a strategy type.
     * Boosts or reduces probability based on regime compatibility.
     */
    public BigDecimal getRegimeAdjustmentFactor(MarketRegime regime, StrategyDefinition.StrategyType strategyType) {
        double factor = 1.0;

        switch (regime) {
            case TREND:
                factor = switch (strategyType) {
                    case TREND_FOLLOWING -> 1.2;
                    case MEAN_REVERSION -> 0.7;
                    case MOMENTUM -> 1.1;
                    case BREAKOUT -> 1.0;
                    case HYBRID -> 1.0;
                };
                break;

            case RANGE:
                factor = switch (strategyType) {
                    case TREND_FOLLOWING -> 0.7;
                    case MEAN_REVERSION -> 1.3;
                    case MOMENTUM -> 0.8;
                    case BREAKOUT -> 0.9;
                    case HYBRID -> 1.0;
                };
                break;

            case HIGH_VOLATILITY:
                factor = switch (strategyType) {
                    case TREND_FOLLOWING -> 0.8;
                    case MEAN_REVERSION -> 0.7;
                    case MOMENTUM -> 1.2;
                    case BREAKOUT -> 1.3;
                    case HYBRID -> 0.9;
                };
                break;
        }

        return BigDecimal.valueOf(factor);
    }

    /**
     * Internal score holder.
     */
    private record RegimeScore(int matched, int total, double confidence) {}
}
