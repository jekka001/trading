package com.btc.collector.analysis;

import com.btc.collector.persistence.Indicator15mEntity;
import com.btc.collector.persistence.Indicator15mRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for looking up historical indicators from btc_indicator_15m table.
 * Provides caching for recent indicators to speed up live evaluation.
 */
@Service
@Slf4j
public class IndicatorLookupService {

    private static final int CACHE_SIZE = 200; // Cache last 200 indicators (~50 hours of 15m data)

    private final Indicator15mRepository repository;

    // Cache for recent indicators (ordered by time DESC)
    private final List<Indicator15mEntity> recentCache = new ArrayList<>();
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

    // Quick lookup by timestamp
    private final ConcurrentHashMap<LocalDateTime, Indicator15mEntity> timestampIndex = new ConcurrentHashMap<>();

    public IndicatorLookupService(Indicator15mRepository repository) {
        this.repository = repository;
        initializeCache();
    }

    private void initializeCache() {
        try {
            List<Indicator15mEntity> recent = repository.findRecentIndicators(CACHE_SIZE);
            cacheLock.writeLock().lock();
            try {
                recentCache.clear();
                recentCache.addAll(recent);
                timestampIndex.clear();
                for (Indicator15mEntity indicator : recent) {
                    timestampIndex.put(indicator.getOpenTime(), indicator);
                }
            } finally {
                cacheLock.writeLock().unlock();
            }
            log.info("Indicator cache initialized with {} entries", recent.size());
        } catch (Exception e) {
            log.error("Failed to initialize indicator cache: {}", e.getMessage());
        }
    }

    /**
     * Refresh cache with latest indicators.
     * Called after new indicators are calculated.
     */
    public void refreshCache() {
        initializeCache();
    }

    /**
     * Add a single indicator to cache (for real-time updates).
     */
    public void addToCache(Indicator15mEntity indicator) {
        cacheLock.writeLock().lock();
        try {
            // Add to front (most recent)
            recentCache.add(0, indicator);
            timestampIndex.put(indicator.getOpenTime(), indicator);

            // Trim cache if needed
            while (recentCache.size() > CACHE_SIZE) {
                Indicator15mEntity removed = recentCache.remove(recentCache.size() - 1);
                timestampIndex.remove(removed.getOpenTime());
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Get indicator by exact timestamp.
     * First checks cache, then falls back to database.
     */
    public Optional<Indicator15mEntity> getByTimestamp(LocalDateTime timestamp) {
        // Check cache first
        Indicator15mEntity cached = timestampIndex.get(timestamp);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Fall back to database
        return repository.findByOpenTime(timestamp);
    }

    /**
     * Get last N indicators up to a given timestamp.
     * Returns in chronological order (oldest first).
     */
    public List<Indicator15mEntity> getLastN(LocalDateTime timestamp, int count) {
        cacheLock.readLock().lock();
        try {
            // Try to serve from cache
            List<Indicator15mEntity> result = new ArrayList<>();
            for (Indicator15mEntity indicator : recentCache) {
                if (!indicator.getOpenTime().isAfter(timestamp)) {
                    result.add(indicator);
                    if (result.size() >= count) {
                        break;
                    }
                }
            }

            if (result.size() >= count) {
                // Reverse to chronological order
                Collections.reverse(result);
                return result;
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        // Fall back to database
        List<Indicator15mEntity> dbResult = repository.findLastNIndicators(timestamp, count);
        Collections.reverse(dbResult); // Convert DESC to ASC
        return dbResult;
    }

    /**
     * Get the most recent N indicators.
     * Returns in chronological order (oldest first).
     */
    public List<Indicator15mEntity> getRecentIndicators(int count) {
        cacheLock.readLock().lock();
        try {
            if (recentCache.size() >= count) {
                List<Indicator15mEntity> result = new ArrayList<>(recentCache.subList(0, count));
                Collections.reverse(result);
                return result;
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        // Fall back to database
        List<Indicator15mEntity> dbResult = repository.findRecentIndicators(count);
        Collections.reverse(dbResult);
        return dbResult;
    }

    /**
     * Get indicators between two timestamps.
     * Returns in chronological order.
     */
    public List<Indicator15mEntity> getIndicatorsBetween(LocalDateTime startTime, LocalDateTime endTime) {
        return repository.findIndicatorsBetween(startTime, endTime);
    }

    /**
     * Convert stored indicator entity to MarketSnapshot for analysis.
     */
    public MarketSnapshot toMarketSnapshot(Indicator15mEntity indicator) {
        return MarketSnapshot.builder()
                .timestamp(indicator.getOpenTime())
                .ema50(indicator.getEma50())
                .ema200(indicator.getEma200())
                .rsi(indicator.getRsi14())
                .build();
    }

    /**
     * Get cache statistics for monitoring.
     */
    public int getCacheSize() {
        cacheLock.readLock().lock();
        try {
            return recentCache.size();
        } finally {
            cacheLock.readLock().unlock();
        }
    }
}
