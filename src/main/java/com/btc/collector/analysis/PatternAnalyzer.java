package com.btc.collector.analysis;

import com.btc.collector.persistence.Candle15mEntity;
import com.btc.collector.persistence.Candle15mRepository;
import com.btc.collector.persistence.HistoricalPatternEntity;
import com.btc.collector.persistence.HistoricalPatternRepository;
import com.btc.collector.persistence.Indicator15mEntity;
import com.btc.collector.persistence.Indicator15mRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

/**
 * DB-driven pattern analyzer for ultra-low-memory environments (500MB).
 *
 * DESIGN PRINCIPLES:
 * 1. Process ONE pattern at a time - no batching, no accumulation
 * 2. Each pattern is its own transaction - memory freed automatically
 * 3. DB is source of truth - no in-memory caches during build
 * 4. Constant memory usage regardless of dataset size
 * 5. Slower but stable - no OOM, no GC pressure
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

    // Progress logging interval
    private static final int LOG_INTERVAL = 1000;

    private final Candle15mRepository candleRepository;
    private final HistoricalPatternRepository patternRepository;
    private final Indicator15mRepository indicatorRepository;
    private final IndicatorEngine indicatorEngine;
    private final StrategyTracker strategyTracker;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${pattern.cache.days:30}")
    private int cacheDays;

    // In-memory cache - loaded ONLY after build completes, never during
    private final List<HistoricalPattern> patterns = new ArrayList<>();
    private final ReentrantReadWriteLock patternsLock = new ReentrantReadWriteLock();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean buildInProgress = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        loadPatternsFromDb();
    }

    /**
     * Delete all patterns in a separate transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteAllPatternsTransactional() {
        patternRepository.deleteAllPatterns();
    }

    /**
     * DB-DRIVEN PATTERN BUILD
     *
     * Processes one candle at a time:
     * 1. Load minimal data from DB
     * 2. Calculate snapshot + profit
     * 3. Save immediately
     * 4. Release all references
     *
     * Memory usage is constant regardless of dataset size.
     */
    public void buildPatternDataset() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return;
        }

        try {
            log.info("=== DB-DRIVEN PATTERN BUILD (constant memory) ===");
            long startTime = System.currentTimeMillis();

            long totalCandles = candleRepository.count();
            if (totalCandles < LOOKBACK_CANDLES + FUTURE_CANDLES) {
                log.warn("Not enough candles to build patterns: {}", totalCandles);
                return;
            }

            // Clear existing patterns (in its own transaction)
            log.info("Clearing existing patterns from database...");
            deleteAllPatternsTransactional();

            // Get time boundaries
            LocalDateTime minTime = candleRepository.findMinOpenTime().orElse(null);
            LocalDateTime maxTime = candleRepository.findMaxOpenTime().orElse(null);

            if (minTime == null || maxTime == null) {
                log.warn("Cannot determine candle time range");
                return;
            }

            // Calculate processing range
            LocalDateTime cursor = minTime.plusMinutes(LOOKBACK_CANDLES * 15L);
            LocalDateTime endTime = maxTime.minusMinutes(FUTURE_CANDLES * 15L);

            // Estimate total patterns
            long totalMinutes = java.time.Duration.between(cursor, endTime).toMinutes();
            long estimatedPatterns = totalMinutes / 15;

            log.info("Processing range: {} to {}", cursor, endTime);
            log.info("Estimated patterns: ~{}", estimatedPatterns);

            int built = 0;
            int skipped = 0;
            int errors = 0;

            // Process ONE candle at a time
            while (!cursor.isAfter(endTime)) {
                try {
                    boolean success = processSinglePattern(cursor);
                    if (success) {
                        built++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    errors++;
                    if (errors <= 10) {
                        log.warn("Error processing {}: {}", cursor, e.getMessage());
                    }
                }

                // Progress logging
                if ((built + skipped) % LOG_INTERVAL == 0) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    double patternsPerSec = built > 0 ? (double) built / elapsed : 0;
                    log.info("Progress: {} built, {} skipped, {} errors | {:.1f} patterns/sec",
                            built, skipped, errors, patternsPerSec);
                }

                // Move to next candle
                cursor = cursor.plusMinutes(15);
            }

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("=== BUILD COMPLETE ===");
            log.info("Built: {}, Skipped: {}, Errors: {}", built, skipped, errors);
            log.info("Duration: {}s ({:.1f} patterns/sec)", duration, (double) built / Math.max(duration, 1));

            // Load cache AFTER build completes
            log.info("Loading cache from DB...");
            loadPatternsFromDb();

        } catch (Exception e) {
            log.error("Fatal error during pattern build: {}", e.getMessage(), e);
        } finally {
            buildInProgress.set(false);
        }
    }

    /**
     * Process a SINGLE pattern - isolated transaction.
     *
     * Each call:
     * 1. Loads minimal data from DB
     * 2. Computes everything
     * 3. Saves result
     * 4. Transaction commits, memory freed
     *
     * @return true if pattern was built, false if skipped
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processSinglePattern(LocalDateTime candleTime) {
        // Skip if already exists
        if (patternRepository.existsByCandleTime(candleTime)) {
            return false;
        }

        // Load ONLY the candles we need (LOOKBACK + 1 for history)
        List<Candle15mEntity> historyCandles = candleRepository.findLastNCandles(candleTime, LOOKBACK_CANDLES + 1);

        if (historyCandles.size() < LOOKBACK_CANDLES + 1) {
            return false;
        }

        // Reverse to chronological order (findLastNCandles returns DESC)
        Collections.reverse(historyCandles);

        // Calculate snapshot
        MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);

        // Release history immediately
        historyCandles.clear();
        historyCandles = null;

        if (snapshot == null) {
            return false;
        }

        // Get entry candle price
        Optional<Candle15mEntity> entryOpt = candleRepository.findById(candleTime);
        if (entryOpt.isEmpty()) {
            return false;
        }
        BigDecimal entryPrice = entryOpt.get().getClosePrice();

        // Load future candles for profit calculation
        LocalDateTime futureStart = candleTime.plusMinutes(15);
        LocalDateTime futureEnd = candleTime.plusMinutes(FUTURE_CANDLES * 15L);
        List<Candle15mEntity> futureCandles = candleRepository.findCandlesBetween(futureStart, futureEnd);

        if (futureCandles.size() < FUTURE_CANDLES - 10) { // Allow some tolerance
            futureCandles.clear();
            return false;
        }

        // Calculate max profit
        BigDecimal maxPrice = entryPrice;
        int hoursToMax = 0;

        for (int i = 0; i < futureCandles.size(); i++) {
            BigDecimal highPrice = futureCandles.get(i).getHighPrice();
            if (highPrice.compareTo(maxPrice) > 0) {
                maxPrice = highPrice;
                hoursToMax = (i + 1) / 4;
            }
        }

        // Release future candles
        futureCandles.clear();
        futureCandles = null;

        BigDecimal maxProfitPct = maxPrice.subtract(entryPrice)
                .divide(entryPrice, MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);

        // Generate strategy ID
        String strategyId = strategyTracker.generateStrategyId(snapshot);

        // Build and save entity
        HistoricalPatternEntity entity = HistoricalPatternEntity.builder()
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
                .evaluated(true)
                .evaluatedAt(LocalDateTime.now())
                .build();

        patternRepository.save(entity);

        // Clear Hibernate persistence context to free memory
        entityManager.flush();
        entityManager.clear();

        return true;
    }

    /**
     * Incremental build - only new patterns.
     * Same DB-driven approach, just different range.
     */
    public void buildPatternsIncremental() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return;
        }

        try {
            log.info("=== INCREMENTAL BUILD (DB-driven) ===");
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

            log.info("Building from {} to {}", cursor, endTime);

            int built = 0;
            int skipped = 0;

            while (!cursor.isAfter(endTime)) {
                try {
                    boolean success = processSinglePattern(cursor);
                    if (success) {
                        built++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    log.debug("Skip {}: {}", cursor, e.getMessage());
                    skipped++;
                }

                if ((built + skipped) % LOG_INTERVAL == 0 && built > 0) {
                    log.info("Progress: {} built, {} skipped", built, skipped);
                }

                cursor = cursor.plusMinutes(15);
            }

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("=== INCREMENTAL COMPLETE: {} built, {} skipped in {}s ===", built, skipped, duration);

            loadPatternsFromDb();

        } catch (Exception e) {
            log.error("Error during incremental build: {}", e.getMessage(), e);
        } finally {
            buildInProgress.set(false);
        }
    }

    /**
     * Resume pattern building from last indicator.
     */
    public ResumeResult resumePatternBuilding() {
        if (!buildInProgress.compareAndSet(false, true)) {
            log.warn("Pattern build already in progress");
            return new ResumeResult(false, 0, 0, 0, "Build already in progress");
        }

        long startTimeMs = System.currentTimeMillis();
        log.info("=== PATTERN RESUME (DB-driven) ===");

        try {
            Optional<LocalDateTime> latestIndicatorOpt = indicatorRepository.findMaxOpenTime();
            if (latestIndicatorOpt.isEmpty()) {
                return new ResumeResult(false, 0, 0, 0, "No indicators in database");
            }
            LocalDateTime latestIndicator = latestIndicatorOpt.get();

            Optional<LocalDateTime> latestPatternOpt = patternRepository.findMaxCandleTime();

            log.info("Latest indicator: {}, Latest pattern: {}", latestIndicator, latestPatternOpt.orElse(null));

            // Determine starting cursor
            LocalDateTime cursor;
            if (latestPatternOpt.isPresent()) {
                LocalDateTime lastPatternTime = latestPatternOpt.get();
                if (!lastPatternTime.isBefore(latestIndicator)) {
                    return new ResumeResult(true, 0, 0, System.currentTimeMillis() - startTimeMs, "Already up to date");
                }
                cursor = lastPatternTime.plusMinutes(15);
            } else {
                // No patterns exist - start from earliest candle + LOOKBACK
                Optional<LocalDateTime> minCandleOpt = candleRepository.findMinOpenTime();
                if (minCandleOpt.isEmpty()) {
                    return new ResumeResult(false, 0, 0, 0, "No candles in database");
                }
                cursor = minCandleOpt.get().plusMinutes(LOOKBACK_CANDLES * 15L);
                log.info("No patterns exist, starting from: {}", cursor);
            }

            int built = 0;
            int skipped = 0;

            while (!cursor.isAfter(latestIndicator)) {
                try {
                    boolean success = processSinglePatternUnevaluated(cursor);
                    if (success) {
                        built++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                }

                if ((built + skipped) % LOG_INTERVAL == 0 && built > 0) {
                    log.info("Resume progress: {} built, {} skipped", built, skipped);
                }

                cursor = cursor.plusMinutes(15);
            }

            loadPatternsFromDb();

            long execTimeMs = System.currentTimeMillis() - startTimeMs;
            log.info("=== RESUME COMPLETE: {} built, {} skipped, {}ms ===", built, skipped, execTimeMs);

            return new ResumeResult(true, built, skipped, execTimeMs, "Success");

        } catch (Exception e) {
            log.error("Resume failed: {}", e.getMessage(), e);
            return new ResumeResult(false, 0, 0, System.currentTimeMillis() - startTimeMs, e.getMessage());
        } finally {
            buildInProgress.set(false);
        }
    }

    /**
     * Process single pattern WITHOUT future evaluation (for resume).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processSinglePatternUnevaluated(LocalDateTime candleTime) {
        if (patternRepository.existsByCandleTime(candleTime)) {
            return false;
        }

        List<Candle15mEntity> historyCandles = candleRepository.findLastNCandles(candleTime, LOOKBACK_CANDLES + 1);

        if (historyCandles.size() < LOOKBACK_CANDLES + 1) {
            return false;
        }

        Collections.reverse(historyCandles);
        MarketSnapshot snapshot = indicatorEngine.calculate(historyCandles);
        historyCandles.clear();

        if (snapshot == null) {
            return false;
        }

        String strategyId = strategyTracker.generateStrategyId(snapshot);

        HistoricalPatternEntity entity = HistoricalPatternEntity.builder()
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

        patternRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        return true;
    }

    /**
     * Evaluate unevaluated patterns one at a time.
     */
    @Transactional
    public int evaluatePatterns() {
        log.info("=== PATTERN EVALUATION (DB-driven) ===");
        long startTimeMs = System.currentTimeMillis();

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
                boolean success = evaluateSinglePattern(pattern.getCandleTime());
                if (success) {
                    evaluated++;
                }
            } catch (Exception e) {
                log.debug("Failed to evaluate {}: {}", pattern.getCandleTime(), e.getMessage());
            }

            if (evaluated % LOG_INTERVAL == 0 && evaluated > 0) {
                log.info("Evaluation progress: {}/{}", evaluated, unevaluated.size());
            }
        }

        unevaluated.clear();

        long execTimeMs = System.currentTimeMillis() - startTimeMs;
        log.info("=== EVALUATION COMPLETE: {} evaluated in {}ms ===", evaluated, execTimeMs);

        return evaluated;
    }

    /**
     * Evaluate a single pattern.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean evaluateSinglePattern(LocalDateTime candleTime) {
        Optional<HistoricalPatternEntity> patternOpt = patternRepository.findByCandleTime(candleTime);
        if (patternOpt.isEmpty() || patternOpt.get().isEvaluated()) {
            return false;
        }

        HistoricalPatternEntity pattern = patternOpt.get();

        Optional<Candle15mEntity> entryOpt = candleRepository.findById(candleTime);
        if (entryOpt.isEmpty()) {
            return false;
        }
        BigDecimal entryPrice = entryOpt.get().getClosePrice();

        LocalDateTime futureStart = candleTime.plusMinutes(15);
        LocalDateTime futureEnd = candleTime.plusMinutes(FUTURE_CANDLES * 15L);
        List<Candle15mEntity> futureCandles = candleRepository.findCandlesBetween(futureStart, futureEnd);

        if (futureCandles.size() < FUTURE_CANDLES - 10) {
            futureCandles.clear();
            return false;
        }

        BigDecimal maxPrice = entryPrice;
        int hoursToMax = 0;

        for (int i = 0; i < futureCandles.size(); i++) {
            BigDecimal highPrice = futureCandles.get(i).getHighPrice();
            if (highPrice.compareTo(maxPrice) > 0) {
                maxPrice = highPrice;
                hoursToMax = (i + 1) / 4;
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
        entityManager.flush();
        entityManager.clear();

        return true;
    }

    /**
     * Update with new candle - single pattern.
     */
    @Transactional
    public void updateWithNewCandle() {
        if (!initialized.get()) {
            return;
        }

        try {
            // Find the candle that now has 24h of future data
            LocalDateTime targetTime = LocalDateTime.now().minusMinutes(FUTURE_CANDLES * 15L);

            // Round to 15-minute boundary
            int minute = targetTime.getMinute();
            minute = (minute / 15) * 15;
            targetTime = targetTime.withMinute(minute).withSecond(0).withNano(0);

            if (patternRepository.existsByCandleTime(targetTime)) {
                return;
            }

            boolean success = processSinglePattern(targetTime);
            if (success) {
                // Reload just this pattern into cache
                Optional<HistoricalPatternEntity> entityOpt = patternRepository.findByCandleTime(targetTime);
                if (entityOpt.isPresent() && entityOpt.get().isEvaluated()) {
                    HistoricalPattern pattern = toPattern(entityOpt.get());
                    patternsLock.writeLock().lock();
                    try {
                        patterns.add(pattern);
                        log.debug("Added pattern to cache, total: {}", patterns.size());
                    } finally {
                        patternsLock.writeLock().unlock();
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error updating patterns: {}", e.getMessage());
        }
    }

    // ==================== READ METHODS ====================

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

    public Optional<LocalDateTime> getLatestPatternTime() {
        return patternRepository.findMaxCandleTime();
    }

    public Optional<LocalDateTime> getLatestEvaluatedPatternTime() {
        return patternRepository.findMaxEvaluatedCandleTime();
    }

    public void refreshCache() {
        loadPatternsFromDb();
    }

    // ==================== PRIVATE HELPERS ====================

    private void loadPatternsFromDb() {
        try {
            long dbCount = patternRepository.countPatterns();
            if (dbCount == 0) {
                log.info("No patterns in database. Use /build_patterns to build dataset.");
                initialized.set(true);
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

            patternsLock.writeLock().lock();
            try {
                patterns.clear();
                patterns.addAll(loadedPatterns);
            } finally {
                patternsLock.writeLock().unlock();
            }

            initialized.set(true);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Loaded {} patterns into cache (DB has {}) in {}ms", loadedPatterns.size(), dbCount, duration);

        } catch (Exception e) {
            log.error("Error loading patterns: {}", e.getMessage(), e);
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

    // ==================== RESULT RECORD ====================

    public record ResumeResult(
            boolean success,
            int patternsBuilt,
            int patternsSkipped,
            long executionTimeMs,
            String message
    ) {}
}
