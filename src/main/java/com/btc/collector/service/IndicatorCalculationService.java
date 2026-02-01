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
     * Processes in batches to avoid OutOfMemoryError.
     */
    public int resumeCalculation() {
        if (!calcInProgress.compareAndSet(false, true)) {
            log.warn("Calculation already in progress");
            return 0;
        }

        try {
            log.info("Resuming indicator calculation...");

            Optional<LocalDateTime> lastIndicatorTime = indicatorRepository.findMaxOpenTime();
            BigDecimal prevEma50 = null;
            BigDecimal prevEma200 = null;

            if (lastIndicatorTime.isPresent()) {
                log.info("Resuming from: {}", lastIndicatorTime.get());
                Optional<Indicator15mEntity> lastIndicator = indicatorRepository.findById(lastIndicatorTime.get());
                if (lastIndicator.isPresent()) {
                    prevEma50 = lastIndicator.get().getEma50();
                    prevEma200 = lastIndicator.get().getEma200();
                }
            } else {
                log.info("No indicators found, starting from beginning");
            }

            int totalCalculated = 0;
            LocalDateTime lastProcessedTime = lastIndicatorTime.orElse(null);

            while (true) {
                List<Candle15mEntity> batch;
                if (lastProcessedTime == null) {
                    batch = candleRepository.findFirstNCandles(BATCH_SIZE);
                } else {
                    batch = candleRepository.findCandlesAfterLimit(lastProcessedTime, BATCH_SIZE);
                }

                if (batch.isEmpty()) {
                    break;
                }

                int batchCalculated = doCalculateBatch(batch, prevEma50, prevEma200);
                totalCalculated += batchCalculated;

                lastProcessedTime = batch.get(batch.size() - 1).getOpenTime();

                // Update prev EMAs for next batch
                Optional<Indicator15mEntity> lastIndicator = indicatorRepository.findById(lastProcessedTime);
                if (lastIndicator.isPresent()) {
                    prevEma50 = lastIndicator.get().getEma50();
                    prevEma200 = lastIndicator.get().getEma200();
                }

                if (batchCalculated > 0) {
                    log.info("Batch complete. Total calculated: {}", totalCalculated);
                }

                batch.clear();
                System.gc();
            }

            log.info("Resume completed. Calculated: {}", totalCalculated);
            return totalCalculated;

        } catch (Exception e) {
            log.error("Error during resume: {}", e.getMessage(), e);
            return 0;
        } finally {
            calcInProgress.set(false);
        }
    }

    /**
     * Full recalculation - deletes all and starts from scratch.
     * Processes in batches to avoid OutOfMemoryError.
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

            long totalCandles = candleRepository.count();
            if (totalCandles == 0) {
                log.info("No candles in database");
                return;
            }

            log.info("Processing {} candles in batches of {}", totalCandles, BATCH_SIZE);

            int totalCalculated = 0;
            LocalDateTime lastProcessedTime = null;
            BigDecimal prevEma50 = null;
            BigDecimal prevEma200 = null;

            while (true) {
                List<Candle15mEntity> batch;
                if (lastProcessedTime == null) {
                    batch = candleRepository.findFirstNCandles(BATCH_SIZE);
                } else {
                    batch = candleRepository.findCandlesAfterLimit(lastProcessedTime, BATCH_SIZE);
                }

                if (batch.isEmpty()) {
                    break;
                }

                // Get previous EMAs from last indicator
                if (prevEma50 == null && lastProcessedTime != null) {
                    Optional<Indicator15mEntity> lastIndicator = indicatorRepository.findById(lastProcessedTime);
                    if (lastIndicator.isPresent()) {
                        prevEma50 = lastIndicator.get().getEma50();
                        prevEma200 = lastIndicator.get().getEma200();
                    }
                }

                int batchCalculated = doCalculateBatch(batch, prevEma50, prevEma200);
                totalCalculated += batchCalculated;

                lastProcessedTime = batch.get(batch.size() - 1).getOpenTime();

                // Update prev EMAs for next batch
                Optional<Indicator15mEntity> lastIndicator = indicatorRepository.findById(lastProcessedTime);
                if (lastIndicator.isPresent()) {
                    prevEma50 = lastIndicator.get().getEma50();
                    prevEma200 = lastIndicator.get().getEma200();
                }

                log.info("Batch complete. Total calculated: {}", totalCalculated);

                // Clear batch from memory
                batch.clear();
                System.gc();
            }

            log.info("Recalculation completed. Total indicators: {}", totalCalculated);

        } catch (Exception e) {
            log.error("Error during recalculation: {}", e.getMessage(), e);
        } finally {
            calcInProgress.set(false);
        }
    }

    private int doCalculateBatch(List<Candle15mEntity> candles, BigDecimal prevEma50, BigDecimal prevEma200) {
        int calculated = 0;

        for (Candle15mEntity candle : candles) {
            List<Candle15mEntity> lookbackCandles = candleRepository.findLastNCandles(
                    candle.getOpenTime(), LOOKBACK_PERIOD + 1);

            if (lookbackCandles.size() < MIN_CANDLES_REQUIRED) {
                continue;
            }

            Collections.reverse(lookbackCandles);

            Indicator15mEntity indicator = buildIndicator(candle.getOpenTime(), lookbackCandles, prevEma50, prevEma200);

            if (indicator != null) {
                indicatorRepository.save(indicator);
                prevEma50 = indicator.getEma50();
                prevEma200 = indicator.getEma200();
                calculated++;
            }

            // Clear lookback from memory
            lookbackCandles.clear();
        }

        return calculated;
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
