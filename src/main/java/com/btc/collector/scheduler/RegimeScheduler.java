package com.btc.collector.scheduler;

import com.btc.collector.persistence.MarketRegimeEntity;
import com.btc.collector.persistence.MarketRegimeRepository;
import com.btc.collector.strategy.MarketRegime;
import com.btc.collector.strategy.MarketRegimeDetector;
import com.btc.collector.strategy.MarketRegimeResult;
import com.btc.collector.telegram.TelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Scheduler for market regime detection.
 * Runs every 15 minutes aligned with candle close.
 *
 * Flow:
 * 1. Candles saved (by CandleSyncService)
 * 2. Indicators calculated (by IndicatorScheduler)
 * 3. MarketRegimeDetector runs (this scheduler)
 * 4. Regime saved to DB
 * 5. Telegram notified if regime changed
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RegimeScheduler {

    private final MarketRegimeDetector regimeDetector;
    private final MarketRegimeRepository regimeRepository;
    private final TelegramBot telegramBot;

    private MarketRegime lastNotifiedRegime = null;

    /**
     * Detect and persist market regime every 15 minutes.
     * Runs at 1, 16, 31, 46 minutes past the hour (shortly after candle close).
     */
    @Scheduled(cron = "0 1,16,31,46 * * * *")
    public void detectAndPersistRegime() {
        try {
            log.info("Running scheduled regime detection...");

            // Detect and persist regime
            MarketRegimeResult result = regimeDetector.detectAndPersist();

            log.info("Regime detection complete: {} (confidence: {}%)",
                    result.getRegime(),
                    String.format("%.0f", result.getConfidence() * 100));

            // Check if regime changed and notify
            if (lastNotifiedRegime != null && lastNotifiedRegime != result.getRegime()) {
                notifyRegimeChange(lastNotifiedRegime, result);
            }

            lastNotifiedRegime = result.getRegime();

        } catch (Exception e) {
            log.error("Error during regime detection: {}", e.getMessage(), e);
        }
    }

    /**
     * Notify Telegram about regime change.
     */
    private void notifyRegimeChange(MarketRegime oldRegime, MarketRegimeResult newResult) {
        StringBuilder message = new StringBuilder();
        message.append("📊 Market Regime Changed\n\n");
        message.append("From: ").append(oldRegime.getDisplayName()).append("\n");
        message.append("To: ").append(newResult.getRegime().getDisplayName()).append("\n");
        message.append("Confidence: ").append(newResult.getConfidencePercent()).append("\n\n");

        // Add regime description
        message.append(newResult.getRegime().getDescription()).append("\n\n");

        // Add multiplier info
        message.append("Signal multiplier: ").append(String.format("%.1f", newResult.getRegime().getMultiplier()));

        try {
            telegramBot.sendMessage(message.toString());
            log.info("Sent regime change notification: {} → {}", oldRegime, newResult.getRegime());
        } catch (Exception e) {
            log.error("Failed to send regime change notification: {}", e.getMessage());
        }
    }

    /**
     * Initialize last regime from database on startup.
     */
    @Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE)
    public void initializeLastRegime() {
        try {
            Optional<MarketRegimeEntity> lastRegime = regimeRepository.findTopByOrderByTimestampDesc();
            if (lastRegime.isPresent()) {
                lastNotifiedRegime = lastRegime.get().getRegimeType();
                log.info("Initialized last regime from DB: {}", lastNotifiedRegime);
            }
        } catch (Exception e) {
            log.warn("Could not initialize last regime: {}", e.getMessage());
        }
    }
}
