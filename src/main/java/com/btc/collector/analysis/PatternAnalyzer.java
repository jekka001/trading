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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    // Batch size for DB inserts (small for low memory)
    private static final int BATCH_SIZE = 200;

    private final Candle15mRepository candleRepository;
    private final HistoricalPatternRepository patternRepository;
    private final Indicator15mRepository indicatorRepository;
    private final IndicatorEngine indicatorEngine;
    private final StrategyTracker strategyTracker;

    @Value("${pattern.cache.days:30}")
    private int cacheDays;

    private final List<HistoricalPattern> patterns = new ArrayList<>();
    private final ReentrantReadWriteLock patternsLock = new ReentrantReadWriteLock();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean buildInProgress = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        // Try to load existing patterns from DB on startup
        loadPatternsFromDb();
    }

    @Transactional
    public void buildPatternDataset() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return;
        }

        try {
            log.info("Building historical pattern dataset (batch mode)...");
            long startTime = System.currentTimeMillis();

            long totalCandles = candleRepository.count();
            if (totalCandles < LOOKBACK_CANDLES + FUTURE_CANDLES) {
                log.warn("Not enough candles to build patterns: {}", totalCandles);
                return;
            }

            // Clear existing patterns in DB
            log.info("Clearing existing patterns from database...");
            patternRepository.deleteAllPatterns();

            // Clear in-memory cache
            patternsLock.writeLock().lock();
            try {
                patterns.clear();
            } finally {
                patternsLock.writeLock().unlock();
            }

            int built = 0;
            int batchNum = 0;

            // Get time boundaries
            LocalDateTime minTime = candleRepository.findMinOpenTime().orElse(null);
            LocalDateTime maxTime = candleRepository.findMaxOpenTime().orElse(null);

            if (minTime == null || maxTime == null) {
                log.warn("Cannot determine candle time range");
                return;
            }

            // Start processing from minTime + LOOKBACK
            LocalDateTime cursor = minTime.plusMinutes(LOOKBACK_CANDLES * 15L);
            // End at maxTime - FUTURE_CANDLES
            LocalDateTime endTime = maxTime.minusMinutes(FUTURE_CANDLES * 15L);

            log.info("Building patterns from {} to {}", cursor, endTime);

            while (cursor.isBefore(endTime) || cursor.isEqual(endTime)) {
                batchNum++;

                // Calculate batch end time
                LocalDateTime batchEndTime = cursor.plusMinutes(BATCH_SIZE * 15L);
                if (batchEndTime.isAfter(endTime)) {
                    batchEndTime = endTime;
                }

                // Fetch candles needed for this batch (with lookback and future)
                LocalDateTime fetchStart = cursor.minusMinutes(LOOKBACK_CANDLES * 15L);
                LocalDateTime fetchEnd = batchEndTime.plusMinutes(FUTURE_CANDLES * 15L);

                List<Candle15mEntity> batchCandles = candleRepository.findCandlesBetween(fetchStart, fetchEnd);

                if (batchCandles.size() < LOOKBACK_CANDLES + FUTURE_CANDLES + 1) {
                    cursor = batchEndTime.plusMinutes(15);
                    continue;
                }

                // Build index for fast lookup
                java.util.Map<LocalDateTime, Integer> candleIndex = new java.util.HashMap<>();
                for (int i = 0; i < batchCandles.size(); i++) {
                    candleIndex.put(batchCandles.get(i).getOpenTime(), i);
                }

                List<HistoricalPatternEntity> batchEntities = new ArrayList<>();

                // Process candles in this batch
                LocalDateTime processingTime = cursor;
                while (!processingTime.isAfter(batchEndTime)) {
                    Integer idx = candleIndex.get(processingTime);

                    if (idx != null && idx >= LOOKBACK_CANDLES && idx + FUTURE_CANDLES < batchCandles.size()) {
                        Candle15mEntity currentCandle = batchCandles.get(idx);

                        List<Candle15mEntity> historyCandles = batchCandles.subList(idx - LOOKBACK_CANDLES, idx + 1);
                        MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);

                        if (snapshot != null) {
                            List<Candle15mEntity> futureCandles = batchCandles.subList(idx + 1, idx + 1 + FUTURE_CANDLES);

                            BigDecimal entryPrice = currentCandle.getClosePrice();
                            BigDecimal maxPrice = entryPrice;
                            int hoursToMax = 0;

                            for (int j = 0; j < futureCandles.size(); j++) {
                                BigDecimal highPrice = futureCandles.get(j).getHighPrice();
                                if (highPrice.compareTo(maxPrice) > 0) {
                                    maxPrice = highPrice;
                                    hoursToMax = (j + 1) / 4;
                                }
                            }

                            BigDecimal maxProfitPct = maxPrice.subtract(entryPrice)
                                    .divide(entryPrice, MC)
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(SCALE, RoundingMode.HALF_UP);

                            String strategyId = strategyTracker.generateStrategyId(snapshot);
                            HistoricalPatternEntity entity = toEntity(currentCandle.getOpenTime(), snapshot, strategyId, maxProfitPct, hoursToMax);
                            batchEntities.add(entity);
                            built++;
                        }
                    }

                    processingTime = processingTime.plusMinutes(15);
                }

                // Save batch to DB
                if (!batchEntities.isEmpty()) {
                    patternRepository.saveAll(batchEntities);
                    log.info("Batch {}: saved {} patterns (total: {})", batchNum, batchEntities.size(), built);
                }

                // Clear batch data and move cursor
                batchCandles.clear();
                batchEntities.clear();
                System.gc();

                cursor = batchEndTime.plusMinutes(15);
            }

            // Load patterns into memory cache from DB
            loadPatternsFromDb();

            initialized.set(true);
            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("Pattern dataset built: {} patterns saved to DB ({}s)", built, duration);

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

        // Add new pattern for the candle that now has 24h of future data
        // This keeps the dataset up to date without full rebuild
        try {
            int requiredCandles = LOOKBACK_CANDLES + FUTURE_CANDLES + 1;
            List<Candle15mEntity> recentCandles = new ArrayList<>(candleRepository.findRecentCandles(requiredCandles));
            Collections.reverse(recentCandles); // Convert DESC to ASC order

            if (recentCandles.size() < requiredCandles) {
                return;
            }

            Candle15mEntity entryCandle = recentCandles.get(LOOKBACK_CANDLES);

            // Check if pattern already exists for this candle
            if (patternRepository.existsByCandleTime(entryCandle.getOpenTime())) {
                log.debug("Pattern already exists for candle: {}", entryCandle.getOpenTime());
                return;
            }

            // Structure: [0..LOOKBACK-1] history, [LOOKBACK] entry candle, [LOOKBACK+1..end] future
            List<Candle15mEntity> historyCandles = recentCandles.subList(0, LOOKBACK_CANDLES + 1);

            MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);
            if (snapshot == null) return;

            List<Candle15mEntity> futureCandles = recentCandles.subList(LOOKBACK_CANDLES + 1, requiredCandles);

            BigDecimal entryPrice = entryCandle.getClosePrice();
            BigDecimal maxPrice = entryPrice;
            int hoursToMax = 0;

            for (int j = 0; j < futureCandles.size(); j++) {
                BigDecimal highPrice = futureCandles.get(j).getHighPrice();
                if (highPrice.compareTo(maxPrice) > 0) {
                    maxPrice = highPrice;
                    hoursToMax = (j + 1) / 4;
                }
            }

            BigDecimal maxProfitPct = maxPrice.subtract(entryPrice)
                    .divide(entryPrice, MC)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(SCALE, RoundingMode.HALF_UP);

            String strategyId = strategyTracker.generateStrategyId(snapshot);

            HistoricalPattern pattern = HistoricalPattern.builder()
                    .snapshot(snapshot)
                    .maxProfitPct24h(maxProfitPct)
                    .hoursToMax(hoursToMax)
                    .build();

            // Save to DB
            HistoricalPatternEntity entity = toEntity(entryCandle.getOpenTime(), snapshot, strategyId, maxProfitPct, hoursToMax);
            patternRepository.save(entity);

            // Add to memory cache
            patternsLock.writeLock().lock();
            try {
                patterns.add(pattern);
                log.debug("Added new pattern, total: {} (saved to DB)", patterns.size());
            } finally {
                patternsLock.writeLock().unlock();
            }

        } catch (Exception e) {
            log.error("Error updating patterns: {}", e.getMessage());
        }
    }

    /**
     * Returns immutable snapshot of patterns for thread-safe reading.
     * Use this method instead of direct list access.
     */
    public List<HistoricalPattern> getPatternsSnapshot() {
        patternsLock.readLock().lock();
        try {
            return List.copyOf(patterns);
        } finally {
            patternsLock.readLock().unlock();
        }
    }

    public int getPatternCount() {
        patternsLock.readLock().lock();
        try {
            return patterns.size();
        } finally {
            patternsLock.readLock().unlock();
        }
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public boolean isBuildInProgress() {
        return buildInProgress.get();
    }

    /**
     * Load patterns from database into memory cache.
     * Called on startup and after rebuild.
     */
    private void loadPatternsFromDb() {
        try {
            long dbCount = patternRepository.countPatterns();
            if (dbCount == 0) {
                log.info("No patterns in database. Use /build_patterns to build dataset.");
                return;
            }

            log.info("Loading {} patterns from database...", dbCount);
            long startTime = System.currentTimeMillis();

            // Load only EVALUATED patterns from last N days (they have outcome data)
            LocalDateTime since = LocalDateTime.now().minusDays(cacheDays);
            List<HistoricalPatternEntity> entities = patternRepository.findRecentPatterns(since);

            List<HistoricalPattern> loadedPatterns = new ArrayList<>(entities.size());
            for (HistoricalPatternEntity entity : entities) {
                // Skip unevaluated patterns (no outcome data)
                if (!entity.isEvaluated() || entity.getMaxProfitPct() == null || entity.getHoursToMax() == null) {
                    continue;
                }
                loadedPatterns.add(toPattern(entity));
            }

            patternsLock.writeLock().lock();
            try {
                patterns.clear();
                patterns.addAll(loadedPatterns);
            } finally {
                patternsLock.writeLock().unlock();
            }

            initialized.set(true);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Loaded {} patterns into memory (last {} days) in {}ms",
                    loadedPatterns.size(), cacheDays, duration);

        } catch (Exception e) {
            log.error("Error loading patterns from database: {}", e.getMessage(), e);
        }
    }

    /**
     * Convert HistoricalPatternEntity to HistoricalPattern DTO.
     */
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

    /**
     * Convert MarketSnapshot to HistoricalPatternEntity.
     */
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

    /**
     * Get count of patterns in database.
     */
    public long getDbPatternCount() {
        return patternRepository.countPatterns();
    }

    /**
     * Get the latest pattern time from database.
     */
    public Optional<LocalDateTime> getLatestPatternTime() {
        return patternRepository.findMaxCandleTime();
    }

    /**
     * Resume pattern building from last pattern up to latest indicator.
     * Builds patterns immediately without waiting for future candles.
     * Evaluation of outcomes is done separately.
     * Idempotent - safe to run multiple times.
     *
     * @return ResumeResult with statistics
     */
    public ResumeResult resumePatternBuilding() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return new ResumeResult(false, 0, 0, 0, "Build already in progress");
        }

        long startTimeMs = System.currentTimeMillis();
        log.info("=== PATTERN RESUME: Starting ===");

        try {
            // Step 1: Find latest indicator timestamp (source of truth)
            Optional<LocalDateTime> latestIndicatorOpt = indicatorRepository.findMaxOpenTime();
            if (latestIndicatorOpt.isEmpty()) {
                log.warn("No indicators in database");
                return new ResumeResult(false, 0, 0, 0, "No indicators in database");
            }
            LocalDateTime latestIndicator = latestIndicatorOpt.get();

            // Step 2: Find latest pattern timestamp
            Optional<LocalDateTime> latestPatternOpt = patternRepository.findMaxCandleTime();
            LocalDateTime lastPatternTime = latestPatternOpt.orElse(LocalDateTime.MIN);

            log.info("Latest indicator: {}, Latest pattern: {}",
                    latestIndicator, latestPatternOpt.orElse(null));

            // Step 3: Build patterns up to latest indicator (no waiting for future candles)
            if (!lastPatternTime.isBefore(latestIndicator)) {
                log.info("Patterns are up to date (no gap to fill)");
                return new ResumeResult(true, 0, 0, System.currentTimeMillis() - startTimeMs, "Already up to date");
            }

            // Count indicators to process
            long indicatorsToProcess = indicatorRepository.countIndicatorsAfter(lastPatternTime);
            log.info("Indicators to process: {}", indicatorsToProcess);

            // Step 4: Process in batches
            int totalBuilt = 0;
            int totalSkipped = 0;
            LocalDateTime cursor = lastPatternTime;
            int batchNum = 0;

            while (true) {
                batchNum++;
                // Fetch batch of indicators after cursor
                List<Indicator15mEntity> indicatorBatch = indicatorRepository.findIndicatorsAfter(cursor, BATCH_SIZE);

                if (indicatorBatch.isEmpty()) {
                    break;
                }

                // Determine candle range needed for this batch (only need LOOKBACK for building)
                LocalDateTime batchStart = indicatorBatch.get(0).getOpenTime()
                        .minusMinutes(LOOKBACK_CANDLES * 15L);
                LocalDateTime batchEnd = indicatorBatch.get(indicatorBatch.size() - 1).getOpenTime();

                // Fetch candles for this batch
                List<Candle15mEntity> candles = candleRepository.findCandlesBetween(batchStart, batchEnd);

                if (candles.size() < LOOKBACK_CANDLES) {
                    log.warn("Not enough candles for batch {}: {} candles", batchNum, candles.size());
                    break;
                }

                // Build index map for fast candle lookup
                java.util.Map<LocalDateTime, Integer> candleIndex = new java.util.HashMap<>();
                for (int i = 0; i < candles.size(); i++) {
                    candleIndex.put(candles.get(i).getOpenTime(), i);
                }

                // Build patterns for this batch
                List<HistoricalPatternEntity> batchPatterns = new ArrayList<>();

                for (Indicator15mEntity indicator : indicatorBatch) {
                    LocalDateTime candleTime = indicator.getOpenTime();

                    // Skip if pattern already exists (idempotent)
                    if (patternRepository.existsByCandleTime(candleTime)) {
                        totalSkipped++;
                        continue;
                    }

                    Integer idx = candleIndex.get(candleTime);
                    if (idx == null || idx < LOOKBACK_CANDLES) {
                        totalSkipped++;
                        continue;
                    }

                    // Get history candles for snapshot calculation
                    List<Candle15mEntity> historyCandles = candles.subList(idx - LOOKBACK_CANDLES, idx + 1);

                    // Calculate snapshot from candles
                    MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);
                    if (snapshot == null) {
                        totalSkipped++;
                        continue;
                    }

                    String strategyId = strategyTracker.generateStrategyId(snapshot);

                    // Build pattern WITHOUT outcome (evaluated=false)
                    HistoricalPatternEntity entity = toEntityUnevaluated(candleTime, snapshot, strategyId);
                    batchPatterns.add(entity);
                    totalBuilt++;
                }

                // Persist batch immediately
                if (!batchPatterns.isEmpty()) {
                    patternRepository.saveAll(batchPatterns);
                    log.info("Batch {}: built {} patterns, skipped {}", batchNum, batchPatterns.size(), totalSkipped);
                }

                // Move cursor to last processed indicator
                cursor = indicatorBatch.get(indicatorBatch.size() - 1).getOpenTime();

                // If we processed fewer than batch size, we're done
                if (indicatorBatch.size() < BATCH_SIZE) {
                    break;
                }
            }

            // Refresh in-memory cache
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

    /**
     * Result of resume pattern building operation.
     */
    public record ResumeResult(
            boolean success,
            int patternsBuilt,
            int patternsSkipped,
            long executionTimeMs,
            String message
    ) {}

    /**
     * Evaluate unevaluated patterns that have enough future data.
     * Calculates max_profit_pct and hours_to_max for patterns older than 24h.
     *
     * @return number of patterns evaluated
     */
    @Transactional
    public int evaluatePatterns() {
        log.info("=== PATTERN EVALUATION: Starting ===");
        long startTimeMs = System.currentTimeMillis();

        // Patterns can be evaluated if they're at least FUTURE_CANDLES * 15min old
        LocalDateTime maxEvaluableTime = LocalDateTime.now().minusMinutes(FUTURE_CANDLES * 15L);

        List<HistoricalPatternEntity> unevaluated = patternRepository.findUnevaluatedPatternsBefore(maxEvaluableTime);

        if (unevaluated.isEmpty()) {
            log.info("No patterns to evaluate");
            return 0;
        }

        log.info("Evaluating {} patterns", unevaluated.size());

        int evaluated = 0;
        for (HistoricalPatternEntity pattern : unevaluated) {
            try {
                LocalDateTime candleTime = pattern.getCandleTime();
                LocalDateTime endTime = candleTime.plusMinutes(FUTURE_CANDLES * 15L);

                // Get the entry candle
                List<Candle15mEntity> entryCandles = candleRepository.findCandlesBetween(candleTime, candleTime);
                if (entryCandles.isEmpty()) {
                    continue;
                }
                BigDecimal entryPrice = entryCandles.get(0).getClosePrice();

                // Get future candles
                List<Candle15mEntity> futureCandles = candleRepository.findCandlesBetween(
                        candleTime.plusMinutes(15), endTime);

                if (futureCandles.size() < FUTURE_CANDLES - 1) {
                    continue; // Not enough future data yet
                }

                // Calculate max profit
                BigDecimal maxPrice = entryPrice;
                int hoursToMax = 0;

                for (int j = 0; j < futureCandles.size(); j++) {
                    BigDecimal highPrice = futureCandles.get(j).getHighPrice();
                    if (highPrice.compareTo(maxPrice) > 0) {
                        maxPrice = highPrice;
                        hoursToMax = (j + 1) / 4;
                    }
                }

                BigDecimal maxProfitPct = maxPrice.subtract(entryPrice)
                        .divide(entryPrice, MC)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(SCALE, RoundingMode.HALF_UP);

                // Update pattern
                pattern.setMaxProfitPct(maxProfitPct);
                pattern.setHoursToMax(hoursToMax);
                pattern.setEvaluated(true);
                pattern.setEvaluatedAt(LocalDateTime.now());

                patternRepository.save(pattern);
                evaluated++;

            } catch (Exception e) {
                log.error("Failed to evaluate pattern {}: {}", pattern.getId(), e.getMessage());
            }
        }

        long execTimeMs = System.currentTimeMillis() - startTimeMs;
        log.info("=== PATTERN EVALUATION: Completed - {} evaluated, {}ms ===", evaluated, execTimeMs);

        return evaluated;
    }

    /**
     * Get the latest evaluated pattern time.
     */
    public Optional<LocalDateTime> getLatestEvaluatedPatternTime() {
        return patternRepository.findMaxEvaluatedCandleTime();
    }

    /**
     * Create pattern entity without outcome (for immediate building).
     */
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

    /**
     * Refresh in-memory cache from database.
     */
    public void refreshCache() {
        loadPatternsFromDb();
    }

    /**
     * Incremental build - only process candles that don't have patterns yet.
     * Uses batch processing to avoid OutOfMemoryError.
     */
    @Transactional
    public void buildPatternsIncremental() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return;
        }

        try {
            log.info("Building patterns incrementally (batch mode)...");
            long startTime = System.currentTimeMillis();

            // Find time boundaries
            LocalDateTime lastPatternTime = patternRepository.findMaxCandleTime().orElse(null);
            LocalDateTime maxCandleTime = candleRepository.findMaxOpenTime().orElse(null);
            LocalDateTime minCandleTime = candleRepository.findMinOpenTime().orElse(null);

            if (maxCandleTime == null || minCandleTime == null) {
                log.warn("No candles in database");
                return;
            }

            // Calculate start and end times
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
            int batchNum = 0;

            while (cursor.isBefore(endTime) || cursor.isEqual(endTime)) {
                batchNum++;

                LocalDateTime batchEndTime = cursor.plusMinutes(BATCH_SIZE * 15L);
                if (batchEndTime.isAfter(endTime)) {
                    batchEndTime = endTime;
                }

                // Fetch candles needed for this batch
                LocalDateTime fetchStart = cursor.minusMinutes(LOOKBACK_CANDLES * 15L);
                LocalDateTime fetchEnd = batchEndTime.plusMinutes(FUTURE_CANDLES * 15L);

                List<Candle15mEntity> batchCandles = candleRepository.findCandlesBetween(fetchStart, fetchEnd);

                if (batchCandles.size() < LOOKBACK_CANDLES + FUTURE_CANDLES + 1) {
                    cursor = batchEndTime.plusMinutes(15);
                    continue;
                }

                // Build index for fast lookup
                java.util.Map<LocalDateTime, Integer> candleIndex = new java.util.HashMap<>();
                for (int i = 0; i < batchCandles.size(); i++) {
                    candleIndex.put(batchCandles.get(i).getOpenTime(), i);
                }

                List<HistoricalPatternEntity> batchEntities = new ArrayList<>();

                LocalDateTime processingTime = cursor;
                while (!processingTime.isAfter(batchEndTime)) {
                    // Skip if already exists
                    if (!patternRepository.existsByCandleTime(processingTime)) {
                        Integer idx = candleIndex.get(processingTime);

                        if (idx != null && idx >= LOOKBACK_CANDLES && idx + FUTURE_CANDLES < batchCandles.size()) {
                            Candle15mEntity currentCandle = batchCandles.get(idx);

                            List<Candle15mEntity> historyCandles = batchCandles.subList(idx - LOOKBACK_CANDLES, idx + 1);
                            MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);

                            if (snapshot != null) {
                                List<Candle15mEntity> futureCandles = batchCandles.subList(idx + 1, idx + 1 + FUTURE_CANDLES);

                                BigDecimal entryPrice = currentCandle.getClosePrice();
                                BigDecimal maxPrice = entryPrice;
                                int hoursToMax = 0;

                                for (int j = 0; j < futureCandles.size(); j++) {
                                    BigDecimal highPrice = futureCandles.get(j).getHighPrice();
                                    if (highPrice.compareTo(maxPrice) > 0) {
                                        maxPrice = highPrice;
                                        hoursToMax = (j + 1) / 4;
                                    }
                                }

                                BigDecimal maxProfitPct = maxPrice.subtract(entryPrice)
                                        .divide(entryPrice, MC)
                                        .multiply(BigDecimal.valueOf(100))
                                        .setScale(SCALE, RoundingMode.HALF_UP);

                                String strategyId = strategyTracker.generateStrategyId(snapshot);
                                HistoricalPatternEntity entity = toEntity(currentCandle.getOpenTime(), snapshot, strategyId, maxProfitPct, hoursToMax);
                                batchEntities.add(entity);
                                built++;
                            }
                        }
                    }

                    processingTime = processingTime.plusMinutes(15);
                }

                // Save batch
                if (!batchEntities.isEmpty()) {
                    patternRepository.saveAll(batchEntities);
                    log.info("Batch {}: saved {} patterns (total: {})", batchNum, batchEntities.size(), built);
                }

                // Clear and move cursor
                batchCandles.clear();
                batchEntities.clear();
                System.gc();

                cursor = batchEndTime.plusMinutes(15);
            }

            // Reload cache
            loadPatternsFromDb();

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("Incremental build complete: {} new patterns in {}s", built, duration);

        } catch (Exception e) {
            log.error("Error during incremental build: {}", e.getMessage(), e);
        } finally {
            buildInProgress.set(false);
        }
    }
}
