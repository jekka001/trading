package com.btc.collector.scheduler;

import com.btc.collector.service.CandleSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CandleScheduler {

    private final CandleSyncService candleSyncService;

    // Run 10 seconds after each 15-minute mark to allow Binance to finalize candle
    @Scheduled(cron = "10 */15 * * * *")
    public void syncLatest() {
        log.info("Scheduled sync triggered");

        if (candleSyncService.isInitialLoadInProgress()) {
            log.info("Initial load in progress, skipping scheduled sync");
            return;
        }

        try {
            candleSyncService.updateLatest();
        } catch (Exception e) {
            log.error("Error during scheduled sync: {}", e.getMessage());
        }
    }
}
