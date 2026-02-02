package com.btc.collector.analysis;

import com.btc.collector.persistence.Candle15mEntity;
import com.btc.collector.persistence.Candle15mRepository;
import com.btc.collector.persistence.HistoricalPatternEntity;
import com.btc.collector.persistence.HistoricalPatternRepository;
import com.btc.collector.persistence.Indicator15mEntity;
import com.btc.collector.persistence.Indicator15mRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Memory-safe pattern analyzer for low-RAM environments (500MB).
 *
 * MEMORY SAFETY RULES:
 * 1. NEVER use List.subList() - it keeps parent list in memory
 * 2. Always copy data with new ArrayList<>() or copyCandles()
 * 3. Flush based on pattern count, not batch count
 * 4. Explicitly null references after use
 * 5. Keep batch windows small
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PatternAnalyzer {

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final int SCALE = 4;

    // 24 hours = 96 candles of 15 minutes
    private static final int FUTURE_CANDLES = 96;

    // Minimum candles needed for indicator calculation
    private static final int LOOKBACK_CANDLES = 100;

    // Batch size for processing (small for low memory)
    private static final int BATCH_SIZE = 100;

    // Flush checkpoint - clear memory every N patterns (NOT batches)
    private static final int PATTERNS_PER_FLUSH = 2000;

    // DB save batch size
    private static final int DB_SAVE_BATCH = 500;

    private final Candle15mRepository candleRepository;
    private final HistoricalPatternRepository patternRepository;
    private final Indicator15mRepository indicatorRepository;
    private final IndicatorEngine indicatorEngine;
    private final StrategyTracker strategyTracker;

    @Value("${pattern.cache.days:30}")
    private int cacheDays;

    // In-memory cache for fast pattern matching (loaded AFTER batch processing)
    private final List<HistoricalPattern> patterns = new ArrayList<>();
    private final ReentrantReadWriteLock patternsLock = new ReentrantReadWriteLock();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean buildInProgress = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        loadPatternsFromDb();
    }

    /**
     * MEMORY-SAFE: Copy candles from a list range into a NEW ArrayList.
     * This breaks the reference to the parent list, allowing GC.
     *
     * CRITICAL: Never use list.subList() directly - it keeps parent reference!
     */
    private List<Candle15mEntity> copyCandles(List<Candle15mEntity> source, int fromIndex, int toIndex) {
        List<Candle15mEntity> copy = new ArrayList<>(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            copy.add(source.get(i));
        }
        return copy;
    }

    /**
     * MEMORY-SAFE: Calculate max profit from future candles.
     * Uses direct index access instead of subList to avoid memory leak.
     * Returns [maxProfitPct, hoursToMax] as BigDecimal array.
     */
    private BigDecimal[] calculateMaxProfit(List<Candle15mEntity> candles, int startIdx, int count, BigDecimal entryPrice) {
        BigDecimal maxPrice = entryPrice;
        int hoursToMax = 0;

        int endIdx = Math.min(startIdx + count, candles.size());
        for (int j = startIdx; j < endIdx; j++) {
            BigDecimal highPrice = candles.get(j).getHighPrice();
            if (highPrice.compareTo(maxPrice) > 0) {
                maxPrice = highPrice;
                hoursToMax = (j - startIdx + 1) / 4;
            }
        }

        BigDecimal maxProfitPct = maxPrice.subtract(entryPrice)
                .divide(entryPrice, MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);

        return new BigDecimal[]{maxProfitPct, BigDecimal.valueOf(hoursToMax)};
    }

    /**
     * MEMORY-SAFE: Flush checkpoint - aggressively clear memory.
     */
    private void flushMemory(String context, int patternCount) {
        log.info("[MEMORY] Flush checkpoint at {} patterns - {}", patternCount, context);
        System.gc();
        try {
            Thread.sleep(100); // Give GC time
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        log.info("[MEMORY] Flush complete, continuing...");
    }

    @Transactional
    public void buildPatternDataset() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return;
        }

        try {
            log.info("=== MEMORY-SAFE PATTERN BUILD ===");
            log.info("[CONFIG] Batch size: {}, Flush every: {} patterns, DB save batch: {}",
                    BATCH_SIZE, PATTERNS_PER_FLUSH, DB_SAVE_BATCH);
            long startTime = System.currentTimeMillis();

            long totalCandles = candleRepository.count();
            if (totalCandles < LOOKBACK_CANDLES + FUTURE_CANDLES) {
                log.warn("Not enough candles to build patterns: {}", totalCandles);
                return;
            }

            log.info("Clearing existing patterns from database...");
            patternRepository.deleteAllPatterns();

            LocalDateTime minTime = candleRepository.findMinOpenTime().orElse(null);
            LocalDateTime maxTime = candleRepository.findMaxOpenTime().orElse(null);

            if (minTime == null || maxTime == null) {
                log.warn("Cannot determine candle time range");
                return;
            }

            // Start after LOOKBACK, end before FUTURE
            LocalDateTime cursor = minTime.plusMinutes(LOOKBACK_CANDLES * 15L);
            LocalDateTime endTime = maxTime.minusMinutes(FUTURE_CANDLES * 15L);

            log.info("Building patterns from {} to {}", cursor, endTime);

            int totalBuilt = 0;
            int patternsSinceFlush = 0;
            int batchNum = 0;

            // Entity buffer for DB saves
            List<HistoricalPatternEntity> entityBuffer = new ArrayList<>(DB_SAVE_BATCH);

            while (!cursor.isAfter(endTime)) {
                batchNum++;

                // Calculate batch window (small to minimize memory)
                LocalDateTime batchEnd = cursor.plusMinutes(BATCH_SIZE * 15L);
                if (batchEnd.isAfter(endTime)) {
                    batchEnd = endTime;
                }

                // Fetch only candles needed for this batch
                LocalDateTime fetchStart = cursor.minusMinutes(LOOKBACK_CANDLES * 15L);
                LocalDateTime fetchEnd = batchEnd.plusMinutes(FUTURE_CANDLES * 15L);

                List<Candle15mEntity> batchCandles = candleRepository.findCandlesBetween(fetchStart, fetchEnd);

                if (batchCandles.size() < LOOKBACK_CANDLES + FUTURE_CANDLES + 1) {
                    cursor = batchEnd.plusMinutes(15);
                    batchCandles = null; // Explicit null
                    continue;
                }

                // Build index for O(1) lookup
                Map<LocalDateTime, Integer> candleIndex = new HashMap<>(batchCandles.size());
                for (int i = 0; i < batchCandles.size(); i++) {
                    candleIndex.put(batchCandles.get(i).getOpenTime(), i);
                }

                // Process each candle in this batch
                LocalDateTime processingTime = cursor;
                while (!processingTime.isAfter(batchEnd)) {
                    Integer idx = candleIndex.get(processingTime);

                    if (idx != null && idx >= LOOKBACK_CANDLES && idx + FUTURE_CANDLES < batchCandles.size()) {
                        Candle15mEntity currentCandle = batchCandles.get(idx);

                        // MEMORY-SAFE: Copy candles instead of subList
                        List<Candle15mEntity> historyCandles = copyCandles(batchCandles, idx - LOOKBACK_CANDLES, idx + 1);
                        MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);

                        // Immediately release historyCandles
                        historyCandles.clear();
                        historyCandles = null;

                        if (snapshot != null) {
                            // Calculate max profit using direct index access (no subList)
                            BigDecimal[] profitResult = calculateMaxProfit(
                                    batchCandles, idx + 1, FUTURE_CANDLES, currentCandle.getClosePrice());

                            BigDecimal maxProfitPct = profitResult[0];
                            int hoursToMax = profitResult[1].intValue();

                            String strategyId = strategyTracker.generateStrategyId(snapshot);
                            HistoricalPatternEntity entity = toEntity(
                                    currentCandle.getOpenTime(), snapshot, strategyId, maxProfitPct, hoursToMax);

                            entityBuffer.add(entity);
                            totalBuilt++;
                            patternsSinceFlush++;

                            // Save to DB when buffer is full
                            if (entityBuffer.size() >= DB_SAVE_BATCH) {
                                patternRepository.saveAll(entityBuffer);
                                log.info("Saved {} patterns to DB (total: {})", entityBuffer.size(), totalBuilt);
                                entityBuffer.clear();
                            }
                        }
                    }

                    processingTime = processingTime.plusMinutes(15);
                }

                // Clear batch data immediately
                batchCandles.clear();
                batchCandles = null;
                candleIndex.clear();
                candleIndex = null;

                // PATTERN-COUNT FLUSH (not batch-count)
                if (patternsSinceFlush >= PATTERNS_PER_FLUSH) {
                    // Save any pending entities first
                    if (!entityBuffer.isEmpty()) {
                        patternRepository.saveAll(entityBuffer);
                        entityBuffer.clear();
                    }
                    entityBuffer = null; // Release reference

                    flushMemory("batch " + batchNum, totalBuilt);

                    // Recreate buffer
                    entityBuffer = new ArrayList<>(DB_SAVE_BATCH);
                    patternsSinceFlush = 0;
                }

                cursor = batchEnd.plusMinutes(15);
            }

            // Save remaining entities
            if (entityBuffer != null && !entityBuffer.isEmpty()) {
                patternRepository.saveAll(entityBuffer);
                entityBuffer.clear();
            }
            entityBuffer = null;

            // Final cleanup
            flushMemory("final cleanup", totalBuilt);

            log.info("Pattern build complete: {} patterns saved to DB", totalBuilt);

            // Load cache
            log.info("Loading last {} days into memory cache...", cacheDays);
            loadPatternsFromDb();

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("=== BUILD COMPLETE: {} patterns in {}s ===", totalBuilt, duration);

        } catch (Exception e) {
            log.error("Error building pattern dataset: {}", e.getMessage(), e);
        } finally {
            buildInProgress.set(false);
        }
    }

    @Transactional
    public void updateWithNewCandle() {
        if (!initialized.get()) {
            log.debug("Patterns not initialized, skipping update");
            return;
        }

        try {
            int requiredCandles = LOOKBACK_CANDLES + FUTURE_CANDLES + 1;
            List<Candle15mEntity> recentCandles = new ArrayList<>(candleRepository.findRecentCandles(requiredCandles));
            Collections.reverse(recentCandles);

            if (recentCandles.size() < requiredCandles) {
                return;
            }

            Candle15mEntity entryCandle = recentCandles.get(LOOKBACK_CANDLES);

            if (patternRepository.existsByCandleTime(entryCandle.getOpenTime())) {
                log.debug("Pattern already exists for candle: {}", entryCandle.getOpenTime());
                recentCandles.clear();
                return;
            }

            // MEMORY-SAFE: Copy instead of subList
            List<Candle15mEntity> historyCandles = copyCandles(recentCandles, 0, LOOKBACK_CANDLES + 1);

            MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);
            historyCandles.clear();
            historyCandles = null;

            if (snapshot == null) {
                recentCandles.clear();
                return;
            }

            // Calculate max profit using direct access
            BigDecimal[] profitResult = calculateMaxProfit(
                    recentCandles, LOOKBACK_CANDLES + 1, FUTURE_CANDLES, entryCandle.getClosePrice());

            BigDecimal maxProfitPct = profitResult[0];
            int hoursToMax = profitResult[1].intValue();

            String strategyId = strategyTracker.generateStrategyId(snapshot);

            HistoricalPattern pattern = HistoricalPattern.builder()
                    .snapshot(snapshot)
                    .maxProfitPct24h(maxProfitPct)
                    .hoursToMax(hoursToMax)
                    .build();

            HistoricalPatternEntity entity = toEntity(entryCandle.getOpenTime(), snapshot, strategyId, maxProfitPct, hoursToMax);
            patternRepository.save(entity);

            // Clear source list
            recentCandles.clear();

            patternsLock.writeLock().lock();
            try {
                patterns.add(pattern);
                log.debug("Added new pattern, total in cache: {}", patterns.size());
            } finally {
                patternsLock.writeLock().unlock();
            }

        } catch (Exception e) {
            log.error("Error updating patterns: {}", e.getMessage());
        }
    }

    public List<HistoricalPattern> getPatternsSnapshot() {
        patternsLock.readLock().lock();
        try {
            return List.copyOf(patterns);
        } finally {
            patternsLock.readLock().unlock();
        }
    }

    public List<HistoricalPattern> getPatternsForStrategy(String strategyId, int limit) {
        List<HistoricalPatternEntity> entities = patternRepository.findByStrategyIdLimited(strategyId, limit);
        List<HistoricalPattern> result = new ArrayList<>(entities.size());
        for (HistoricalPatternEntity entity : entities) {
            if (entity.isEvaluated() && entity.getMaxProfitPct() != null) {
                result.add(toPattern(entity));
            }
        }
        entities.clear();
        return result;
    }

    public int getPatternCount() {
        patternsLock.readLock().lock();
        try {
            return patterns.size();
        } finally {
            patternsLock.readLock().unlock();
        }
    }

    public long getDbPatternCount() {
        return patternRepository.countPatterns();
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public boolean isBuildInProgress() {
        return buildInProgress.get();
    }

    private void loadPatternsFromDb() {
        try {
            long dbCount = patternRepository.countPatterns();
            if (dbCount == 0) {
                log.info("No patterns in database. Use /build_patterns to build dataset.");
                return;
            }

            log.info("Loading patterns from database (last {} days)...", cacheDays);
            long startTime = System.currentTimeMillis();

            LocalDateTime since = LocalDateTime.now().minusDays(cacheDays);
            List<HistoricalPatternEntity> entities = patternRepository.findRecentPatterns(since);

            List<HistoricalPattern> loadedPatterns = new ArrayList<>(entities.size());
            for (HistoricalPatternEntity entity : entities) {
                if (entity.isEvaluated() && entity.getMaxProfitPct() != null && entity.getHoursToMax() != null) {
                    loadedPatterns.add(toPattern(entity));
                }
            }

            entities.clear();
            entities = null;

            patternsLock.writeLock().lock();
            try {
                patterns.clear();
                patterns.addAll(loadedPatterns);
            } finally {
                patternsLock.writeLock().unlock();
            }

            initialized.set(true);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Loaded {} patterns into memory (last {} days, DB has {}) in {}ms",
                    loadedPatterns.size(), cacheDays, dbCount, duration);

        } catch (Exception e) {
            log.error("Error loading patterns from database: {}", e.getMessage(), e);
        }
    }

    private HistoricalPattern toPattern(HistoricalPatternEntity entity) {
        MarketSnapshot snapshot = MarketSnapshot.builder()
                .timestamp(entity.getCandleTime())
                .rsi(entity.getRsi())
                .ema50(entity.getEma50())
                .ema200(entity.getEma200())
                .volumeChangePct(entity.getVolumeChangePct())
                .priceChange1h(entity.getPriceChange1h())
                .priceChange4h(entity.getPriceChange4h())
                .build();

        return HistoricalPattern.builder()
                .snapshot(snapshot)
                .maxProfitPct24h(entity.getMaxProfitPct())
                .hoursToMax(entity.getHoursToMax() != null ? entity.getHoursToMax() : 0)
                .build();
    }

    private HistoricalPatternEntity toEntity(LocalDateTime candleTime, MarketSnapshot snapshot,
                                              String strategyId, BigDecimal maxProfitPct, Integer hoursToMax) {
        return HistoricalPatternEntity.builder()
                .candleTime(candleTime)
                .strategyId(strategyId)
                .rsi(snapshot.getRsi())
                .ema50(snapshot.getEma50())
                .ema200(snapshot.getEma200())
                .volumeChangePct(snapshot.getVolumeChangePct())
                .priceChange1h(snapshot.getPriceChange1h())
                .priceChange4h(snapshot.getPriceChange4h())
                .maxProfitPct(maxProfitPct)
                .hoursToMax(hoursToMax)
                .evaluated(maxProfitPct != null)
                .evaluatedAt(maxProfitPct != null ? LocalDateTime.now() : null)
                .build();
    }

    public Optional<LocalDateTime> getLatestPatternTime() {
        return patternRepository.findMaxCandleTime();
    }

    /**
     * Resume pattern building - memory-safe version.
     */
    public ResumeResult resumePatternBuilding() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return new ResumeResult(false, 0, 0, 0, "Build already in progress");
        }

        long startTimeMs = System.currentTimeMillis();
        log.info("=== PATTERN RESUME: Starting (memory-safe) ===");

        try {
            Optional<LocalDateTime> latestIndicatorOpt = indicatorRepository.findMaxOpenTime();
            if (latestIndicatorOpt.isEmpty()) {
                log.warn("No indicators in database");
                return new ResumeResult(false, 0, 0, 0, "No indicators in database");
            }
            LocalDateTime latestIndicator = latestIndicatorOpt.get();

            Optional<LocalDateTime> latestPatternOpt = patternRepository.findMaxCandleTime();
            LocalDateTime lastPatternTime = latestPatternOpt.orElse(LocalDateTime.MIN);

            log.info("Latest indicator: {}, Latest pattern: {}",
                    latestIndicator, latestPatternOpt.orElse(null));

            if (!lastPatternTime.isBefore(latestIndicator)) {
                log.info("Patterns are up to date (no gap to fill)");
                return new ResumeResult(true, 0, 0, System.currentTimeMillis() - startTimeMs, "Already up to date");
            }

            long indicatorsToProcess = indicatorRepository.countIndicatorsAfter(lastPatternTime);
            log.info("Indicators to process: {}", indicatorsToProcess);

            int totalBuilt = 0;
            int totalSkipped = 0;
            int patternsSinceFlush = 0;
            LocalDateTime cursor = lastPatternTime;
            int batchNum = 0;

            List<HistoricalPatternEntity> entityBuffer = new ArrayList<>(DB_SAVE_BATCH);

            while (true) {
                batchNum++;

                List<Indicator15mEntity> indicatorBatch = indicatorRepository.findIndicatorsAfter(cursor, BATCH_SIZE);

                if (indicatorBatch.isEmpty()) {
                    break;
                }

                LocalDateTime batchStart = indicatorBatch.get(0).getOpenTime()
                        .minusMinutes(LOOKBACK_CANDLES * 15L);
                LocalDateTime batchEnd = indicatorBatch.get(indicatorBatch.size() - 1).getOpenTime();

                List<Candle15mEntity> candles = candleRepository.findCandlesBetween(batchStart, batchEnd);

                if (candles.size() < LOOKBACK_CANDLES) {
                    log.warn("Not enough candles for batch {}: {} candles", batchNum, candles.size());
                    indicatorBatch.clear();
                    candles.clear();
                    break;
                }

                Map<LocalDateTime, Integer> candleIndex = new HashMap<>(candles.size());
                for (int i = 0; i < candles.size(); i++) {
                    candleIndex.put(candles.get(i).getOpenTime(), i);
                }

                for (Indicator15mEntity indicator : indicatorBatch) {
                    LocalDateTime candleTime = indicator.getOpenTime();

                    if (patternRepository.existsByCandleTime(candleTime)) {
                        totalSkipped++;
                        continue;
                    }

                    Integer idx = candleIndex.get(candleTime);
                    if (idx == null || idx < LOOKBACK_CANDLES) {
                        totalSkipped++;
                        continue;
                    }

                    // MEMORY-SAFE: Copy instead of subList
                    List<Candle15mEntity> historyCandles = copyCandles(candles, idx - LOOKBACK_CANDLES, idx + 1);

                    MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);
                    historyCandles.clear();
                    historyCandles = null;

                    if (snapshot == null) {
                        totalSkipped++;
                        continue;
                    }

                    String strategyId = strategyTracker.generateStrategyId(snapshot);
                    HistoricalPatternEntity entity = toEntityUnevaluated(candleTime, snapshot, strategyId);
                    entityBuffer.add(entity);
                    totalBuilt++;
                    patternsSinceFlush++;

                    if (entityBuffer.size() >= DB_SAVE_BATCH) {
                        patternRepository.saveAll(entityBuffer);
                        log.info("Batch {}: saved {} patterns (total: {})", batchNum, entityBuffer.size(), totalBuilt);
                        entityBuffer.clear();
                    }
                }

                cursor = indicatorBatch.get(indicatorBatch.size() - 1).getOpenTime();

                // Clear batch data
                indicatorBatch.clear();
                candles.clear();
                candleIndex.clear();

                // Pattern-count based flush
                if (patternsSinceFlush >= PATTERNS_PER_FLUSH) {
                    if (!entityBuffer.isEmpty()) {
                        patternRepository.saveAll(entityBuffer);
                        entityBuffer.clear();
                    }
                    entityBuffer = null;
                    flushMemory("resume batch " + batchNum, totalBuilt);
                    entityBuffer = new ArrayList<>(DB_SAVE_BATCH);
                    patternsSinceFlush = 0;
                }

                if (indicatorBatch.size() < BATCH_SIZE) {
                    break;
                }
            }

            // Save remaining
            if (entityBuffer != null && !entityBuffer.isEmpty()) {
                patternRepository.saveAll(entityBuffer);
                entityBuffer.clear();
            }

            loadPatternsFromDb();

            long execTimeMs = System.currentTimeMillis() - startTimeMs;
            log.info("=== PATTERN RESUME: Completed - {} built, {} skipped, {}ms ===",
                    totalBuilt, totalSkipped, execTimeMs);

            return new ResumeResult(true, totalBuilt, totalSkipped, execTimeMs, "Success");

        } catch (Exception e) {
            log.error("=== PATTERN RESUME: Failed - {} ===", e.getMessage(), e);
            return new ResumeResult(false, 0, 0, System.currentTimeMillis() - startTimeMs, e.getMessage());
        } finally {
            buildInProgress.set(false);
        }
    }

    public record ResumeResult(
            boolean success,
            int patternsBuilt,
            int patternsSkipped,
            long executionTimeMs,
            String message
    ) {}

    @Transactional
    public int evaluatePatterns() {
        log.info("=== PATTERN EVALUATION: Starting ===");
        long startTimeMs = System.currentTimeMillis();

        LocalDateTime maxEvaluableTime = LocalDateTime.now().minusMinutes(FUTURE_CANDLES * 15L);

        List<HistoricalPatternEntity> unevaluated = patternRepository.findUnevaluatedPatternsBefore(maxEvaluableTime);

        if (unevaluated.isEmpty()) {
            log.info("No patterns to evaluate");
            return 0;
        }

        log.info("Evaluating {} patterns", unevaluated.size());

        int evaluated = 0;
        int patternsSinceFlush = 0;

        for (HistoricalPatternEntity pattern : unevaluated) {
            try {
                LocalDateTime candleTime = pattern.getCandleTime();
                LocalDateTime futureEndTime = candleTime.plusMinutes(FUTURE_CANDLES * 15L);

                List<Candle15mEntity> entryCandles = candleRepository.findCandlesBetween(candleTime, candleTime);
                if (entryCandles.isEmpty()) {
                    continue;
                }
                BigDecimal entryPrice = entryCandles.get(0).getClosePrice();
                entryCandles.clear();

                List<Candle15mEntity> futureCandles = candleRepository.findCandlesBetween(
                        candleTime.plusMinutes(15), futureEndTime);

                if (futureCandles.size() < FUTURE_CANDLES - 1) {
                    futureCandles.clear();
                    continue;
                }

                // Calculate max profit using direct iteration (no subList)
                BigDecimal maxPrice = entryPrice;
                int hoursToMax = 0;

                for (int j = 0; j < futureCandles.size(); j++) {
                    BigDecimal highPrice = futureCandles.get(j).getHighPrice();
                    if (highPrice.compareTo(maxPrice) > 0) {
                        maxPrice = highPrice;
                        hoursToMax = (j + 1) / 4;
                    }
                }

                futureCandles.clear();

                BigDecimal maxProfitPct = maxPrice.subtract(entryPrice)
                        .divide(entryPrice, MC)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(SCALE, RoundingMode.HALF_UP);

                pattern.setMaxProfitPct(maxProfitPct);
                pattern.setHoursToMax(hoursToMax);
                pattern.setEvaluated(true);
                pattern.setEvaluatedAt(LocalDateTime.now());

                patternRepository.save(pattern);
                evaluated++;
                patternsSinceFlush++;

                // Flush every PATTERNS_PER_FLUSH
                if (patternsSinceFlush >= PATTERNS_PER_FLUSH) {
                    flushMemory("evaluation", evaluated);
                    patternsSinceFlush = 0;
                }

            } catch (Exception e) {
                log.error("Failed to evaluate pattern {}: {}", pattern.getId(), e.getMessage());
            }
        }

        unevaluated.clear();

        long execTimeMs = System.currentTimeMillis() - startTimeMs;
        log.info("=== PATTERN EVALUATION: Completed - {} evaluated, {}ms ===", evaluated, execTimeMs);

        return evaluated;
    }

    public Optional<LocalDateTime> getLatestEvaluatedPatternTime() {
        return patternRepository.findMaxEvaluatedCandleTime();
    }

    private HistoricalPatternEntity toEntityUnevaluated(LocalDateTime candleTime, MarketSnapshot snapshot, String strategyId) {
        return HistoricalPatternEntity.builder()
                .candleTime(candleTime)
                .strategyId(strategyId)
                .rsi(snapshot.getRsi())
                .ema50(snapshot.getEma50())
                .ema200(snapshot.getEma200())
                .volumeChangePct(snapshot.getVolumeChangePct())
                .priceChange1h(snapshot.getPriceChange1h())
                .priceChange4h(snapshot.getPriceChange4h())
                .maxProfitPct(null)
                .hoursToMax(null)
                .evaluated(false)
                .build();
    }

    public void refreshCache() {
        loadPatternsFromDb();
    }

    /**
     * Incremental build - memory-safe version.
     */
    @Transactional
    public void buildPatternsIncremental() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return;
        }

        try {
            log.info("=== INCREMENTAL BUILD (memory-safe) ===");
            log.info("[CONFIG] Batch: {}, Flush every: {} patterns", BATCH_SIZE, PATTERNS_PER_FLUSH);
            long startTime = System.currentTimeMillis();

            LocalDateTime lastPatternTime = patternRepository.findMaxCandleTime().orElse(null);
            LocalDateTime maxCandleTime = candleRepository.findMaxOpenTime().orElse(null);
            LocalDateTime minCandleTime = candleRepository.findMinOpenTime().orElse(null);

            if (maxCandleTime == null || minCandleTime == null) {
                log.warn("No candles in database");
                return;
            }

            LocalDateTime cursor;
            if (lastPatternTime != null) {
                cursor = lastPatternTime.plusMinutes(15);
            } else {
                cursor = minCandleTime.plusMinutes(LOOKBACK_CANDLES * 15L);
            }

            LocalDateTime endTime = maxCandleTime.minusMinutes(FUTURE_CANDLES * 15L);

            if (!cursor.isBefore(endTime)) {
                log.info("No new patterns to build (already up to date)");
                loadPatternsFromDb();
                return;
            }

            log.info("Building patterns from {} to {}", cursor, endTime);

            int built = 0;
            int patternsSinceFlush = 0;
            int batchNum = 0;

            List<HistoricalPatternEntity> entityBuffer = new ArrayList<>(DB_SAVE_BATCH);

            while (!cursor.isAfter(endTime)) {
                batchNum++;

                LocalDateTime batchEnd = cursor.plusMinutes(BATCH_SIZE * 15L);
                if (batchEnd.isAfter(endTime)) {
                    batchEnd = endTime;
                }

                LocalDateTime fetchStart = cursor.minusMinutes(LOOKBACK_CANDLES * 15L);
                LocalDateTime fetchEnd = batchEnd.plusMinutes(FUTURE_CANDLES * 15L);

                List<Candle15mEntity> batchCandles = candleRepository.findCandlesBetween(fetchStart, fetchEnd);

                if (batchCandles.size() < LOOKBACK_CANDLES + FUTURE_CANDLES + 1) {
                    cursor = batchEnd.plusMinutes(15);
                    batchCandles = null;
                    continue;
                }

                Map<LocalDateTime, Integer> candleIndex = new HashMap<>(batchCandles.size());
                for (int i = 0; i < batchCandles.size(); i++) {
                    candleIndex.put(batchCandles.get(i).getOpenTime(), i);
                }

                LocalDateTime processingTime = cursor;
                while (!processingTime.isAfter(batchEnd)) {
                    if (!patternRepository.existsByCandleTime(processingTime)) {
                        Integer idx = candleIndex.get(processingTime);

                        if (idx != null && idx >= LOOKBACK_CANDLES && idx + FUTURE_CANDLES < batchCandles.size()) {
                            Candle15mEntity currentCandle = batchCandles.get(idx);

                            // MEMORY-SAFE: Copy instead of subList
                            List<Candle15mEntity> historyCandles = copyCandles(batchCandles, idx - LOOKBACK_CANDLES, idx + 1);
                            MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);
                            historyCandles.clear();
                            historyCandles = null;

                            if (snapshot != null) {
                                // Calculate max profit using direct index access
                                BigDecimal[] profitResult = calculateMaxProfit(
                                        batchCandles, idx + 1, FUTURE_CANDLES, currentCandle.getClosePrice());

                                BigDecimal maxProfitPct = profitResult[0];
                                int hoursToMax = profitResult[1].intValue();

                                String strategyId = strategyTracker.generateStrategyId(snapshot);
                                HistoricalPatternEntity entity = toEntity(
                                        currentCandle.getOpenTime(), snapshot, strategyId, maxProfitPct, hoursToMax);

                                entityBuffer.add(entity);
                                built++;
                                patternsSinceFlush++;

                                if (entityBuffer.size() >= DB_SAVE_BATCH) {
                                    patternRepository.saveAll(entityBuffer);
                                    log.info("Batch {}: saved {} patterns (total: {})", batchNum, entityBuffer.size(), built);
                                    entityBuffer.clear();
                                }
                            }
                        }
                    }

                    processingTime = processingTime.plusMinutes(15);
                }

                // Clear batch data
                batchCandles.clear();
                batchCandles = null;
                candleIndex.clear();
                candleIndex = null;

                // Pattern-count based flush
                if (patternsSinceFlush >= PATTERNS_PER_FLUSH) {
                    if (!entityBuffer.isEmpty()) {
                        patternRepository.saveAll(entityBuffer);
                        entityBuffer.clear();
                    }
                    entityBuffer = null;
                    flushMemory("incremental batch " + batchNum, built);
                    entityBuffer = new ArrayList<>(DB_SAVE_BATCH);
                    patternsSinceFlush = 0;
                }

                cursor = batchEnd.plusMinutes(15);
            }

            // Save remaining
            if (entityBuffer != null && !entityBuffer.isEmpty()) {
                patternRepository.saveAll(entityBuffer);
                entityBuffer.clear();
            }

            flushMemory("final", built);
            loadPatternsFromDb();

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("=== INCREMENTAL COMPLETE: {} new patterns in {}s ===", built, duration);

        } catch (Exception e) {
            log.error("Error during incremental build: {}", e.getMessage(), e);
        } finally {
            buildInProgress.set(false);
        }
    }
}
