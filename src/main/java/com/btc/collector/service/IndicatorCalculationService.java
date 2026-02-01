package com.btc.collector.service;

import com.btc.collector.indicator.IndicatorCalculator;
import com.btc.collector.persistence.Candle15mEntity;
import com.btc.collector.persistence.Candle15mRepository;
import com.btc.collector.persistence.Indicator15mEntity;
import com.btc.collector.persistence.Indicator15mRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class IndicatorCalculationService {

    private static final int LOOKBACK_PERIOD = 200; // Max period needed (EMA 200)
    private static final int MIN_CANDLES_REQUIRED = 201; // Need 201 for EMA 200 calculation
    private static final int BATCH_SIZE = 1000;
    private static final int LOG_INTERVAL = 100;

    private final Candle15mRepository candleRepository;
    private final Indicator15mRepository indicatorRepository;

    private final AtomicBoolean calcInProgress = new AtomicBoolean(false);

    /**
     * Calculate indicators for new candles (real-time updates).
     * All candles in DB are closed (Binance only returns closed candles).
     */
    public int calculateNewIndicators() {
        if (!calcInProgress.compareAndSet(false, true)) {
            log.info("Calculation already in progress");
            return 0;
        }

        try {
            Optional<LocalDateTime> lastIndicatorTime = indicatorRepository.findMaxOpenTime();

            if (lastIndicatorTime.isEmpty()) {
                log.info("No indicators exist, use /ind_recalc or /ind_resume");
                return 0;
            }

            List<Candle15mEntity> newCandles = candleRepository.findCandlesAfter(lastIndicatorTime.get());

            if (newCandles.isEmpty()) {
                log.debug("No new candles to process");
                return 0;
            }

            log.info("Processing {} new candles", newCandles.size());
            return doCalculate(newCandles, lastIndicatorTime.get(), false);

        } catch (Exception e) {
            log.error("Error calculating indicators: {}", e.getMessage(), e);
            return 0;
        } finally {
            calcInProgress.set(false);
        }
    }

    /**
     * Resume calculation from last indicator (no delete).
     * All candles in DB are closed (Binance only returns closed candles).
     */
    public int resumeCalculation() {
        if (!calcInProgress.compareAndSet(false, true)) {
            log.warn("Calculation already in progress");
            return 0;
        }

        try {
            log.info("Resuming indicator calculation...");

            Optional<LocalDateTime> lastIndicatorTime = indicatorRepository.findMaxOpenTime();
            List<Candle15mEntity> candles;

            if (lastIndicatorTime.isPresent()) {
                candles = candleRepository.findCandlesAfter(lastIndicatorTime.get());
                log.info("Resuming from: {}", lastIndicatorTime.get());
            } else {
                candles = candleRepository.findAllOrderByOpenTimeAsc();
                log.info("No indicators found, starting from beginning");
            }

            if (candles.isEmpty()) {
                log.info("No candles to process");
                return 0;
            }

            log.info("Processing {} candles", candles.size());
            int total = doCalculate(candles, lastIndicatorTime.orElse(null), true);
            log.info("Resume completed. Calculated: {}", total);
            return total;

        } catch (Exception e) {
            log.error("Error during resume: {}", e.getMessage(), e);
            return 0;
        } finally {
            calcInProgress.set(false);
        }
    }

    /**
     * Full recalculation - deletes all and starts from scratch.
     */
    public void recalculateAll() {
        if (!calcInProgress.compareAndSet(false, true)) {
            log.warn("Calculation already in progress");
            return;
        }

        try {
            log.info("Starting full indicator recalculation...");

            indicatorRepository.deleteAll();
            log.info("Cleared existing indicators");

            List<Candle15mEntity> candles = candleRepository.findAllOrderByOpenTimeAsc();

            if (candles.isEmpty()) {
                log.info("No candles in database");
                return;
            }

            int total = doCalculate(candles, null, true);
            log.info("Recalculation completed. Total indicators: {}", total);

        } catch (Exception e) {
            log.error("Error during recalculation: {}", e.getMessage(), e);
        } finally {
            calcInProgress.set(false);
        }
    }

    private int doCalculate(List<Candle15mEntity> candles, LocalDateTime lastIndicatorTime, boolean logProgress) {
        int calculated = 0;
        int total = candles.size();
        BigDecimal prevEma50 = null;
        BigDecimal prevEma200 = null;

        // Get previous EMAs if exists
        if (lastIndicatorTime != null) {
            Optional<Indicator15mEntity> lastIndicator = indicatorRepository.findById(lastIndicatorTime);
            if (lastIndicator.isPresent()) {
                prevEma50 = lastIndicator.get().getEma50();
                prevEma200 = lastIndicator.get().getEma200();
            }
        }

        if (logProgress) {
            log.info("Processing {} candles...", total);
        }

        for (int i = 0; i < candles.size(); i++) {
            Candle15mEntity candle = candles.get(i);

            // Load last 200 candles for calculation
            List<Candle15mEntity> lookbackCandles = candleRepository.findLastNCandles(
                    candle.getOpenTime(), LOOKBACK_PERIOD + 1);

            if (lookbackCandles.size() < MIN_CANDLES_REQUIRED) {
                if (logProgress && i < 10) {
                    log.debug("Not enough candles for {}, skipping (have {}, need {})",
                            candle.getOpenTime(), lookbackCandles.size(), MIN_CANDLES_REQUIRED);
                }
                continue;
            }

            // Reverse to chronological order
            Collections.reverse(lookbackCandles);

            Indicator15mEntity indicator = buildIndicator(candle.getOpenTime(), lookbackCandles, prevEma50, prevEma200);

            if (indicator != null) {
                indicatorRepository.save(indicator);
                prevEma50 = indicator.getEma50();
                prevEma200 = indicator.getEma200();
                calculated++;

                // Log progress
                if (logProgress && calculated % LOG_INTERVAL == 0) {
                    log.info("Progress: {}/{} ({} calculated)", i + 1, total, calculated);
                }
            }
        }

        return calculated;
    }

    private Indicator15mEntity buildIndicator(LocalDateTime openTime, List<Candle15mEntity> candles,
                                               BigDecimal prevEma50, BigDecimal prevEma200) {
        try {
            BigDecimal ema50 = IndicatorCalculator.calculateEMA(candles, 50, prevEma50);
            BigDecimal ema200 = IndicatorCalculator.calculateEMA(candles, 200, prevEma200);
            BigDecimal rsi14 = IndicatorCalculator.calculateRSI(candles, 14);
            BigDecimal atr14 = IndicatorCalculator.calculateATR(candles, 14);
            BigDecimal[] bb = IndicatorCalculator.calculateBollingerBands(candles, 20, 2.0);
            BigDecimal avgVolume = IndicatorCalculator.calculateAvgVolume(candles, 20);

            return Indicator15mEntity.builder()
                    .openTime(openTime)
                    .ema50(ema50)
                    .ema200(ema200)
                    .rsi14(rsi14)
                    .atr14(atr14)
                    .bbUpper(bb != null ? bb[0] : null)
                    .bbMiddle(bb != null ? bb[1] : null)
                    .bbLower(bb != null ? bb[2] : null)
                    .avgVolume20(avgVolume)
                    .build();

        } catch (Exception e) {
            log.error("Error calculating indicators for {}: {}", openTime, e.getMessage());
            return null;
        }
    }

    public long getIndicatorCount() {
        return indicatorRepository.countIndicators();
    }

    public Optional<LocalDateTime> getLatestIndicatorTime() {
        return indicatorRepository.findMaxOpenTime();
    }

    public Optional<Indicator15mEntity> getLatestIndicator() {
        return indicatorRepository.findTopByOrderByOpenTimeDesc();
    }

    public boolean isCalcInProgress() {
        return calcInProgress.get();
    }
}
