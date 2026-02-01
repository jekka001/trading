package com.btc.collector.service;

import com.btc.collector.binance.BinanceClient;
import com.btc.collector.binance.CandleDTO;
import com.btc.collector.persistence.Candle15mEntity;
import com.btc.collector.persistence.Candle15mRepository;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class CandleSyncService {

    private static final long FIFTEEN_MINUTES_MS = 15 * 60 * 1000L;
    private static final long BTCUSDT_START_TIME = 1502942400000L; // August 17, 2017 - BTCUSDT listing date
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 5000L;

    private final BinanceClient binanceClient;
    private final Candle15mRepository repository;
    private final RateLimiter rateLimiter;

    private final AtomicBoolean initialLoadInProgress = new AtomicBoolean(false);

    public CandleSyncService(BinanceClient binanceClient, Candle15mRepository repository) {
        this.binanceClient = binanceClient;
        this.repository = repository;
        // Binance allows 1200 requests per minute = 20 requests per second
        this.rateLimiter = RateLimiter.create(10.0);
    }

    public void initialLoad() {
        if (!initialLoadInProgress.compareAndSet(false, true)) {
            log.warn("Initial load already in progress, skipping...");
            return;
        }

        try {
            log.info("Starting FULL initial load from 2017...");

            long startTime = BTCUSDT_START_TIME; // Always start from 2017

            int totalSaved = 0;
            int batchCount = 0;

            while (true) {
                List<CandleDTO> candles = fetchWithRetry(startTime);

                if (candles.isEmpty()) {
                    log.info("No more candles to fetch");
                    break;
                }

                int saved = saveCandles(candles);
                totalSaved += saved;
                batchCount++;

                CandleDTO lastCandle = candles.get(candles.size() - 1);
                startTime = BinanceClient.dateTimeToMillis(lastCandle.getOpenTime()) + FIFTEEN_MINUTES_MS;

                if (batchCount % 10 == 0) {
                    log.info("Progress: {} batches, {} candles saved, last: {}",
                            batchCount, totalSaved, lastCandle.getOpenTime());
                }

                if (candles.size() < 1000) {
                    log.info("Reached latest available candle");
                    break;
                }

                rateLimiter.acquire();
            }

            log.info("Initial load completed. Total batches: {}, Total saved: {}", batchCount, totalSaved);

        } catch (Exception e) {
            log.error("Error during initial load: {}", e.getMessage(), e);
        } finally {
            initialLoadInProgress.set(false);
        }
    }

    private List<CandleDTO> fetchWithRetry(long startTime) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<CandleDTO> candles = binanceClient.fetchCandles(startTime);
                if (!candles.isEmpty() || attempt == MAX_RETRIES) {
                    return candles;
                }
                log.warn("Empty response, retry {}/{}", attempt, MAX_RETRIES);
            } catch (Exception e) {
                log.warn("Fetch failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    log.error("Max retries reached, returning empty list");
                    return List.of();
                }
            }

            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
        return List.of();
    }

    public int updateLatest() {
        log.info("Starting update of latest candles...");

        try {
            Long startTime = null;

            Optional<LocalDateTime> maxOpenTime = repository.findMaxOpenTime();
            if (maxOpenTime.isPresent()) {
                startTime = BinanceClient.dateTimeToMillis(maxOpenTime.get());
                log.info("Fetching candles from: {}", maxOpenTime.get());
            }

            List<CandleDTO> candles = binanceClient.fetchCandles(startTime);

            if (candles.isEmpty()) {
                log.info("No new candles available");
                return 0;
            }

            int saved = saveCandles(candles);
            log.info("Updated {} new candles", saved);
            return saved;

        } catch (Exception e) {
            log.error("Error updating latest candles: {}", e.getMessage());
            return 0;
        }
    }

    private int saveCandles(List<CandleDTO> candles) {
        int savedCount = 0;

        for (CandleDTO dto : candles) {
            try {
                if (!repository.existsByOpenTime(dto.getOpenTime())) {
                    Candle15mEntity entity = Candle15mEntity.builder()
                            .openTime(dto.getOpenTime())
                            .openPrice(dto.getOpenPrice())
                            .highPrice(dto.getHighPrice())
                            .lowPrice(dto.getLowPrice())
                            .closePrice(dto.getClosePrice())
                            .volume(dto.getVolume())
                            .closeTime(dto.getCloseTime())
                            .build();

                    repository.save(entity);
                    savedCount++;
                }
            } catch (DataIntegrityViolationException e) {
                // Duplicate key - ignore (expected behavior)
                log.trace("Candle already exists: {}", dto.getOpenTime());
            } catch (Exception e) {
                log.error("Error saving candle {}: {}", dto.getOpenTime(), e.getMessage());
            }
        }

        return savedCount;
    }

    public long getCandleCount() {
        return repository.count();
    }

    public Optional<LocalDateTime> getLatestCandleTime() {
        return repository.findMaxOpenTime();
    }

    public Optional<LocalDateTime> getEarliestCandleTime() {
        return repository.findMinOpenTime();
    }

    public boolean isInitialLoadInProgress() {
        return initialLoadInProgress.get();
    }
}
