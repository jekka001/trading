package com.btc.collector.service;

import com.btc.collector.analysis.PatternAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Service that orchestrates full synchronization pipeline:
 * 1. Sync candles from exchange
 * 2. Calculate/resume indicators
 * 3. Build patterns incrementally
 *
 * Executes steps strictly in sequence, stops on any error.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FullSyncService {

    private final CandleSyncService candleSyncService;
    private final IndicatorCalculationService indicatorService;
    private final PatternAnalyzer patternAnalyzer;

    private volatile boolean running = false;

    public record SyncResult(boolean success, String message, long totalTimeMs) {}

    public boolean isRunning() {
        return running;
    }

    /**
     * Execute full sync pipeline with progress callbacks.
     *
     * @param onProgress callback for progress updates (can be null)
     * @return SyncResult with success status and message
     */
    public SyncResult executeFullSync(Consumer<String> onProgress) {
        if (running) {
            return new SyncResult(false, "Full sync already in progress", 0);
        }

        running = true;
        Instant totalStart = Instant.now();
        StringBuilder report = new StringBuilder();

        try {
            // Step 1: Sync candles
            log.info("=== FULL SYNC: Step 1/4 - Syncing candles ===");
            notify(onProgress, "Step 1/4: Syncing candles...");

            Instant stepStart = Instant.now();
            int syncedCandles = executeCandleSync();
            long step1Time = Duration.between(stepStart, Instant.now()).toMillis();

            report.append("Step 1: Candle sync - ").append(syncedCandles).append(" candles")
                  .append(" (").append(formatDuration(step1Time)).append(")\n");
            log.info("Step 1 completed: {} candles synced in {}", syncedCandles, formatDuration(step1Time));

            // Step 2: Calculate indicators
            log.info("=== FULL SYNC: Step 2/4 - Calculating indicators ===");
            notify(onProgress, "Step 2/4: Calculating indicators...");

            stepStart = Instant.now();
            int calculatedIndicators = executeIndicatorResume();
            long step2Time = Duration.between(stepStart, Instant.now()).toMillis();

            report.append("Step 2: Indicators - ").append(calculatedIndicators).append(" calculated")
                  .append(" (").append(formatDuration(step2Time)).append(")\n");
            log.info("Step 2 completed: {} indicators calculated in {}", calculatedIndicators, formatDuration(step2Time));

            // Step 3: Build patterns incrementally
            log.info("=== FULL SYNC: Step 3/4 - Building patterns ===");
            notify(onProgress, "Step 3/4: Building patterns...");

            stepStart = Instant.now();
            int builtPatterns = executePatternBuild();
            long step3Time = Duration.between(stepStart, Instant.now()).toMillis();

            report.append("Step 3: Patterns - ").append(builtPatterns).append(" built")
                  .append(" (").append(formatDuration(step3Time)).append(")\n");
            log.info("Step 3 completed: {} patterns built in {}", builtPatterns, formatDuration(step3Time));

            // Step 4: Evaluate patterns
            log.info("=== FULL SYNC: Step 4/4 - Evaluating patterns ===");
            notify(onProgress, "Step 4/4: Evaluating patterns...");

            stepStart = Instant.now();
            int evaluatedPatterns = executePatternEvaluation();
            long step4Time = Duration.between(stepStart, Instant.now()).toMillis();

            report.append("Step 4: Patterns - ").append(evaluatedPatterns).append(" evaluated")
                  .append(" (").append(formatDuration(step4Time)).append(")\n");
            log.info("Step 4 completed: {} patterns evaluated in {}", evaluatedPatterns, formatDuration(step4Time));

            // Success
            long totalTime = Duration.between(totalStart, Instant.now()).toMillis();
            report.append("\nTotal time: ").append(formatDuration(totalTime));

            log.info("=== FULL SYNC COMPLETED SUCCESSFULLY in {} ===", formatDuration(totalTime));
            return new SyncResult(true, report.toString(), totalTime);

        } catch (Exception e) {
            long totalTime = Duration.between(totalStart, Instant.now()).toMillis();
            String errorMsg = "Full sync failed: " + e.getMessage();
            log.error("=== FULL SYNC FAILED: {} ===", e.getMessage(), e);
            report.append("\nFAILED: ").append(e.getMessage());
            return new SyncResult(false, report.toString(), totalTime);

        } finally {
            running = false;
        }
    }

    private int executeCandleSync() {
        if (candleSyncService.isInitialLoadInProgress()) {
            throw new IllegalStateException("Initial candle load already in progress");
        }

        // Use updateLatest for incremental sync (idempotent)
        int synced = candleSyncService.updateLatest();

        // Verify sync completed
        if (candleSyncService.getCandleCount() == 0) {
            throw new IllegalStateException("Candle sync failed: no candles in database");
        }

        return synced;
    }

    private int executeIndicatorResume() {
        if (indicatorService.isCalcInProgress()) {
            throw new IllegalStateException("Indicator calculation already in progress");
        }

        long beforeCount = indicatorService.getIndicatorCount();

        // Resume calculation - runs synchronously (idempotent - only calculates missing)
        indicatorService.resumeCalculation();

        // Verify completion
        if (indicatorService.isCalcInProgress()) {
            throw new IllegalStateException("Indicator calculation did not complete properly");
        }

        long afterCount = indicatorService.getIndicatorCount();
        return (int) (afterCount - beforeCount);
    }

    private int executePatternBuild() {
        if (patternAnalyzer.isBuildInProgress()) {
            throw new IllegalStateException("Pattern build already in progress");
        }

        long beforeCount = patternAnalyzer.getDbPatternCount();

        // Use resumePatternBuilding for immediate pattern building
        patternAnalyzer.resumePatternBuilding();

        // Verify completion
        if (patternAnalyzer.isBuildInProgress()) {
            throw new IllegalStateException("Pattern build did not complete properly");
        }

        long afterCount = patternAnalyzer.getDbPatternCount();
        return (int) (afterCount - beforeCount);
    }

    private int executePatternEvaluation() {
        // Evaluate patterns that have enough future data (older than 24h)
        return patternAnalyzer.evaluatePatterns();
    }

    private void notify(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    private String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60000) {
            return String.format("%.1fs", ms / 1000.0);
        } else {
            long minutes = ms / 60000;
            long seconds = (ms % 60000) / 1000;
            return String.format("%dm %ds", minutes, seconds);
        }
    }
}
