package com.btc.collector.analysis;

import com.btc.collector.persistence.AlertHistoryEntity;
import com.btc.collector.persistence.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalAlertService {

    private final AlertHistoryRepository alertHistoryRepository;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Configurable signal thresholds (for Telegram alerts only)
    @Value("${signal.min.probability:50}")
    private BigDecimal minProbability;

    @Value("${signal.min.profit:1.5}")
    private BigDecimal minProfit;

    @Value("${signal.min.samples:30}")
    private int minSamples;

    @Value("${signal.cooldown.hours:4}")
    private int cooldownHours;

    @Value("${telegram.alert.chat-id:}")
    private String alertChatId;

    private LocalDateTime lastAlertTime = null;
    private AbsSender telegramBot;

    public void setTelegramBot(AbsSender bot) {
        this.telegramBot = bot;
    }

    /**
     * Process signal: save ALL signals to database, then optionally send to Telegram.
     * Strategy learning is based on ALL signals, not just those sent to Telegram.
     */
    public boolean checkAndSendAlert(ProbabilityResult result) {
        if (result == null) {
            log.debug("No result to process");
            return false;
        }

        // ALWAYS save signal to database for strategy learning
        AlertHistoryEntity savedAlert = saveSignalToHistory(result, false);
        if (savedAlert == null) {
            log.warn("Failed to save signal to history");
            return false;
        }

        // Check if signal meets Telegram alert conditions
        if (!result.meetsSignalConditions(minProbability, minProfit, minSamples)) {
            log.debug("Signal saved but not sent: prob={}%, profit={}%, samples={} (min: {}%, {}%, {})",
                    result.getProbabilityUpPct(), result.getAvgProfitPct(), result.getMatchedSamplesCount(),
                    minProbability, minProfit, minSamples);
            return false;
        }

        // Check cooldown for Telegram delivery
        if (!isCooldownPassed()) {
            log.info("Signal saved but cooldown not passed. Last alert: {}", lastAlertTime);
            return false;
        }

        // Send to Telegram and update record
        return sendToTelegram(result, savedAlert);
    }

    /**
     * Process aggregated multi-strategy analysis result.
     * Saves ALL strategy results to database (for learning), sends aggregated alert to Telegram.
     *
     * @param aggregated The aggregated analysis result with all strategy evaluations
     * @return true if aggregated alert was sent to Telegram
     */
    public boolean processAggregatedAnalysis(AggregatedAnalysisResult aggregated) {
        if (aggregated == null) {
            log.debug("No aggregated result to process");
            return false;
        }

        String snapshotId = aggregated.getSnapshotId();
        MarketSnapshot snapshot = aggregated.getSnapshot();
        List<AlertHistoryEntity> savedAlerts = new ArrayList<>();

        // Save ALL strategy results to database (for strategy learning)
        for (StrategyAnalysisResult strategyResult : aggregated.getStrategyResults()) {
            if (!strategyResult.hasData()) {
                continue; // Skip strategies with no matched patterns
            }

            AlertHistoryEntity saved = saveStrategyResult(
                    snapshotId, snapshot, strategyResult, false);
            if (saved != null) {
                savedAlerts.add(saved);
            }
        }

        log.info("Saved {} strategy alerts for snapshot {}", savedAlerts.size(), snapshotId);

        // Check if any strategy qualifies for Telegram alert (using boosted probability)
        if (!aggregated.hasQualifyingTelegramStrategy(minProbability, minProfit, minSamples)) {
            log.debug("No strategy meets Telegram alert conditions (after PnL boost). Saved for learning only.");
            return false;
        }

        // Log qualifying strategies with PnL coefficient debug info
        aggregated.getStrategyResults().stream()
                .filter(StrategyAnalysisResult::hasData)
                .filter(r -> r.meetsTelegramConditions(minProbability, minProfit, minSamples))
                .forEach(r -> {
                    log.info("Strategy qualifies for Telegram: strategy={}, totalPnl=${}, pnlCoeff={}, baseProb={}%, boostedProb={}%",
                            r.getStrategyId(),
                            r.getTotalPnl() != null ? r.getTotalPnl() : "N/A",
                            r.getPnlCoefficient(),
                            r.getFinalProbability(),
                            r.getBoostedProbability());
                });

        // Check cooldown
        if (!isCooldownPassed()) {
            log.info("Aggregated signal saved but cooldown not passed. Last alert: {}", lastAlertTime);
            return false;
        }

        // Send aggregated alert to Telegram
        boolean sent = sendAggregatedToTelegram(aggregated);

        // Mark all saved alerts as sent to Telegram
        if (sent) {
            for (AlertHistoryEntity alert : savedAlerts) {
                alert.setSentToTelegram(true);
                alertHistoryRepository.save(alert);
            }
        }

        return sent;
    }

    /**
     * Save individual strategy result to database.
     */
    private AlertHistoryEntity saveStrategyResult(
            String snapshotId,
            MarketSnapshot snapshot,
            StrategyAnalysisResult strategyResult,
            boolean sentToTelegram) {

        try {
            LocalDateTime now = LocalDateTime.now();
            BigDecimal currentPrice = snapshot.getPrice();
            BigDecimal predictedProfitPct = strategyResult.getAvgProfitPct();

            if (currentPrice == null || predictedProfitPct == null) {
                log.warn("Cannot save strategy result: missing price or profit data");
                return null;
            }

            int predictedHours = strategyResult.getAvgHoursToMax() != null
                    ? strategyResult.getAvgHoursToMax().intValue()
                    : 4;

            // Calculate target price
            BigDecimal multiplier = BigDecimal.ONE.add(
                    predictedProfitPct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            BigDecimal targetPrice = currentPrice.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);

            // Determine EMA trend and volume bucket from snapshot
            String emaTrend = snapshot.isEma50BelowEma200() ? "BEARISH" : "BULLISH";
            String volumeBucket = categorizeVolume(snapshot.getVolumeChangePct());

            AlertHistoryEntity alert = AlertHistoryEntity.builder()
                    .alertTime(now)
                    .snapshotGroupId(snapshotId)
                    .strategyId(strategyResult.getStrategyId())
                    .baseProbability(strategyResult.getBaseProbability())
                    .finalProbability(strategyResult.getFinalProbability())
                    .strategyWeight(strategyResult.getStrategyWeight())
                    .historicalFactor(strategyResult.getHistoricalFactor())
                    .currentPrice(currentPrice)
                    .predictedProfitPct(predictedProfitPct)
                    .targetPrice(targetPrice)
                    .predictedHours(predictedHours)
                    .matchedPatterns(strategyResult.getMatchedPatterns())
                    .rsi(snapshot.getRsi())
                    .emaTrend(emaTrend)
                    .volumeBucket(volumeBucket)
                    .evaluateAt(now.plusHours(predictedHours))
                    .evaluated(false)
                    .sentToTelegram(sentToTelegram)
                    .build();

            AlertHistoryEntity saved = alertHistoryRepository.save(alert);
            log.debug("Strategy saved: id={}, strategy={}, prob={}%, profit={}%",
                    saved.getId(), strategyResult.getStrategyId(),
                    strategyResult.getFinalProbability(), predictedProfitPct);

            return saved;

        } catch (Exception e) {
            log.error("Failed to save strategy result: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Send aggregated analysis to Telegram.
     */
    private boolean sendAggregatedToTelegram(AggregatedAnalysisResult aggregated) {
        if (telegramBot == null) {
            log.warn("Telegram bot not set, cannot send alert");
            return false;
        }

        if (alertChatId == null || alertChatId.isEmpty()) {
            log.warn("Alert chat ID not configured");
            return false;
        }

        String message = formatAggregatedMessage(aggregated);

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(alertChatId);
        sendMessage.setText(message);
        sendMessage.setParseMode("HTML");

        try {
            telegramBot.execute(sendMessage);
            lastAlertTime = LocalDateTime.now();
            log.info("Aggregated alert sent to Telegram: {} strategies, avg prob={}%",
                    aggregated.getStrategiesWithData(),
                    aggregated.getWeightedAvgProbability());
            return true;

        } catch (TelegramApiException e) {
            log.error("Failed to send aggregated alert to Telegram: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Format aggregated analysis message for Telegram.
     */
    private String formatAggregatedMessage(AggregatedAnalysisResult aggregated) {
        MarketSnapshot snapshot = aggregated.getSnapshot();
        StrategyAnalysisResult bestStrategy = aggregated.getBestStrategy();

        StringBuilder sb = new StringBuilder();
        sb.append("<b>📈 BTC MULTI-STRATEGY SIGNAL (15m)</b>\n\n");

        // Price info
        if (snapshot != null && snapshot.getPrice() != null) {
            BigDecimal currentPrice = snapshot.getPrice();
            BigDecimal profitMultiplier = BigDecimal.ONE.add(
                    aggregated.getAvgProfitPct().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            BigDecimal expectedPrice = currentPrice.multiply(profitMultiplier)
                    .setScale(2, RoundingMode.HALF_UP);

            sb.append("<b>💰 Price Info:</b>\n");
            sb.append("Current: <b>$").append(formatPrice(currentPrice)).append("</b>\n");
            sb.append("Target: <b>$").append(formatPrice(expectedPrice)).append("</b> (+")
                    .append(aggregated.getAvgProfitPct()).append("%)\n\n");
        }

        // Aggregated signal
        sb.append("<b>📊 Aggregated Signal:</b>\n");
        sb.append("Avg Probability: <b>").append(aggregated.getAvgProbability()).append("%</b>\n");
        sb.append("Weighted Avg: <b>").append(aggregated.getWeightedAvgProbability()).append("%</b>\n");
        sb.append("Avg Profit: <b>+").append(aggregated.getAvgProfitPct()).append("%</b>\n");
        sb.append("Total Patterns: <b>").append(aggregated.getTotalMatchedPatterns()).append("</b>\n");
        sb.append("Strategies: <b>").append(aggregated.getStrategiesWithData()).append("</b>\n\n");

        // Best strategy
        if (bestStrategy != null) {
            sb.append("<b>🏆 Best Strategy:</b>\n");
            sb.append("<code>").append(bestStrategy.getStrategyId()).append("</code>\n");
            sb.append("Probability: ").append(bestStrategy.getFinalProbability()).append("%\n");
            sb.append("Profit: +").append(bestStrategy.getAvgProfitPct()).append("%\n");
            sb.append("Patterns: ").append(bestStrategy.getMatchedPatterns()).append("\n\n");
        }

        // Market indicators
        if (snapshot != null) {
            sb.append("<b>📈 Indicators:</b>\n");
            sb.append("RSI: ").append(snapshot.getRsi()).append("\n");
            sb.append("EMA50 ").append(snapshot.isEma50BelowEma200() ? "&lt;" : "&gt;").append(" EMA200\n");
            sb.append("Volume: ").append(formatSign(snapshot.getVolumeChangePct())).append("%\n\n");
        }

        // Top strategies summary (using boosted probability for Telegram filter)
        List<StrategyAnalysisResult> topStrategies = aggregated.getStrategyResults().stream()
                .filter(StrategyAnalysisResult::hasData)
                .filter(r -> r.meetsTelegramConditions(minProbability, minProfit, minSamples))
                .sorted((a, b) -> {
                    BigDecimal aProb = a.getBoostedProbability() != null ? a.getBoostedProbability() : a.getFinalProbability();
                    BigDecimal bProb = b.getBoostedProbability() != null ? b.getBoostedProbability() : b.getFinalProbability();
                    return bProb.compareTo(aProb);
                })
                .limit(3)
                .toList();

        if (!topStrategies.isEmpty()) {
            sb.append("<b>🔍 Top Strategies:</b>\n");
            for (StrategyAnalysisResult s : topStrategies) {
                sb.append("• <code>").append(s.getStrategyId()).append("</code>: ")
                        .append(s.getFinalProbability()).append("%");
                // Show PnL boost if applied
                if (s.hasPnlBoost()) {
                    sb.append(" → ").append(s.getBoostedProbability()).append("% (PnL+)");
                }
                sb.append(" (").append(s.getMatchedPatterns()).append(" patterns)\n");
            }
        }

        sb.append("\n<i>Time: ").append(LocalDateTime.now().format(DATE_FORMAT)).append("</i>");
        sb.append("\n<i>Snapshot: ").append(aggregated.getSnapshotId()).append("</i>");

        return sb.toString();
    }

    private boolean isCooldownPassed() {
        if (lastAlertTime == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(lastAlertTime.plusHours(cooldownHours));
    }

    /**
     * Save signal to history database.
     * ALL signals are saved regardless of whether they meet alert thresholds.
     */
    private AlertHistoryEntity saveSignalToHistory(ProbabilityResult result, boolean sentToTelegram) {
        try {
            MarketSnapshot snapshot = result.getCurrentSnapshot();
            if (snapshot == null) {
                log.warn("Cannot save signal: missing snapshot");
                return null;
            }

            LocalDateTime now = LocalDateTime.now();
            BigDecimal currentPrice = snapshot.getPrice();
            BigDecimal predictedProfitPct = result.getAvgProfitPct();

            if (currentPrice == null || predictedProfitPct == null) {
                log.warn("Cannot save signal: missing price or profit data");
                return null;
            }

            int predictedHours = result.getAvgHoursToMax() != null
                    ? result.getAvgHoursToMax().intValue()
                    : 4;

            // Calculate target price
            BigDecimal multiplier = BigDecimal.ONE.add(
                    predictedProfitPct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            BigDecimal targetPrice = currentPrice.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);

            // Determine EMA trend
            String emaTrend = snapshot.isEma50BelowEma200() ? "BEARISH" : "BULLISH";

            // Determine volume bucket
            String volumeBucket = categorizeVolume(snapshot.getVolumeChangePct());

            AlertHistoryEntity alert = AlertHistoryEntity.builder()
                    .alertTime(now)
                    .strategyId(result.getStrategyId())
                    .baseProbability(result.getProbabilityUpPct())
                    .finalProbability(result.getWeightedProbability())
                    .strategyWeight(result.getStrategyWeight())
                    .historicalFactor(result.getHistoricalFactor())
                    .currentPrice(currentPrice)
                    .predictedProfitPct(predictedProfitPct)
                    .targetPrice(targetPrice)
                    .predictedHours(predictedHours)
                    .matchedPatterns(result.getMatchedSamplesCount())
                    .rsi(snapshot.getRsi())
                    .emaTrend(emaTrend)
                    .volumeBucket(volumeBucket)
                    .evaluateAt(now.plusHours(predictedHours))
                    .evaluated(false)
                    .sentToTelegram(sentToTelegram)
                    .build();

            AlertHistoryEntity saved = alertHistoryRepository.save(alert);
            log.info("Signal saved: id={}, strategy={}, prob={}%, profit={}%, sentToTelegram={}",
                    saved.getId(), saved.getStrategyId(), result.getProbabilityUpPct(),
                    predictedProfitPct, sentToTelegram);

            return saved;

        } catch (Exception e) {
            log.error("Failed to save signal to history: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean sendToTelegram(ProbabilityResult result, AlertHistoryEntity alert) {
        if (telegramBot == null) {
            log.warn("Telegram bot not set, cannot send alert");
            return false;
        }

        if (alertChatId == null || alertChatId.isEmpty()) {
            log.warn("Alert chat ID not configured");
            return false;
        }

        String message = formatAlertMessage(result);

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(alertChatId);
        sendMessage.setText(message);
        sendMessage.setParseMode("HTML");

        try {
            telegramBot.execute(sendMessage);
            lastAlertTime = LocalDateTime.now();

            // Update alert record to mark as sent to Telegram
            alert.setSentToTelegram(true);
            alertHistoryRepository.save(alert);

            log.info("Alert sent to Telegram: id={}, strategy={}", alert.getId(), alert.getStrategyId());
            return true;

        } catch (TelegramApiException e) {
            log.error("Failed to send alert to Telegram: {}", e.getMessage());
            return false;
        }
    }

    private String categorizeVolume(BigDecimal volumeChangePct) {
        if (volumeChangePct == null) return "NORMAL";
        double change = volumeChangePct.doubleValue();
        if (change > 50) return "HIGH";
        if (change < -30) return "LOW";
        return "NORMAL";
    }

    private String formatAlertMessage(ProbabilityResult result) {
        MarketSnapshot snapshot = result.getCurrentSnapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("<b>📈 BTC BUY SIGNAL (15m)</b>\n\n");

        if (snapshot != null && snapshot.getPrice() != null && result.getAvgProfitPct() != null) {
            BigDecimal currentPrice = snapshot.getPrice();
            BigDecimal profitMultiplier = BigDecimal.ONE.add(
                    result.getAvgProfitPct().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            BigDecimal expectedPrice = currentPrice.multiply(profitMultiplier)
                    .setScale(2, RoundingMode.HALF_UP);

            sb.append("<b>💰 Price Info:</b>\n");
            sb.append("Current Price: <b>$").append(formatPrice(currentPrice)).append("</b>\n");
            sb.append("Target Price: <b>$").append(formatPrice(expectedPrice)).append("</b> (+")
                    .append(result.getAvgProfitPct()).append("%)\n\n");

            log.info("Alert generated: Probability {}%, Current Price {}, Expected Profit Price {}",
                    result.getWeightedProbability(), currentPrice, expectedPrice);
        }

        sb.append("<b>📊 Signal:</b>\n");
        sb.append("Probability: <b>").append(result.getProbabilityUpPct()).append("%</b>");
        if (result.getWeightedProbability() != null) {
            sb.append(" (final: ").append(result.getWeightedProbability()).append("%)");
        }
        sb.append("\n");
        sb.append("Avg profit: <b>+").append(result.getAvgProfitPct()).append("%</b>\n");
        sb.append("Optimal sell: <b>~").append(result.getAvgHoursToMax().intValue()).append(" hours</b>\n\n");

        sb.append("<b>📈 Indicators:</b>\n");
        if (snapshot != null) {
            sb.append("RSI: ").append(snapshot.getRsi()).append("\n");
            sb.append("EMA50 ").append(snapshot.isEma50BelowEma200() ? "&lt;" : "&gt;").append(" EMA200\n");
            sb.append("Volume: ").append(formatSign(snapshot.getVolumeChangePct())).append("%\n");
            sb.append("Price 1h: ").append(formatSign(snapshot.getPriceChange1h())).append("%\n");
            sb.append("Price 4h: ").append(formatSign(snapshot.getPriceChange4h())).append("%\n");
        }

        sb.append("\n<b>🔍 Analysis:</b>\n");
        sb.append("Matched patterns: <b>").append(result.getMatchedSamplesCount()).append("</b>\n");

        if (result.getStrategyId() != null) {
            sb.append("Strategy: <code>").append(result.getStrategyId()).append("</code>\n");
            sb.append("Strategy weight: ").append(result.getStrategyWeight()).append("\n");
            if (result.getHistoricalFactor() != null) {
                sb.append("History factor: ").append(result.getHistoricalFactor()).append("\n");
            }
        }

        sb.append("\n<i>Time: ").append(LocalDateTime.now().format(DATE_FORMAT)).append("</i>");

        return sb.toString();
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        return String.format("%,.2f", price.doubleValue());
    }

    /**
     * Send strategy degradation alert.
     */
    public void sendDegradationAlert(StrategyStats stats) {
        if (telegramBot == null || alertChatId == null || alertChatId.isEmpty()) {
            log.warn("Cannot send degradation alert: bot or chat not configured");
            return;
        }

        String message = formatDegradationAlert(stats);

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(alertChatId);
        sendMessage.setText(message);
        sendMessage.setParseMode("HTML");

        try {
            telegramBot.execute(sendMessage);
            log.info("Degradation alert sent for strategy: {}", stats.getStrategyId());
        } catch (TelegramApiException e) {
            log.error("Failed to send degradation alert: {}", e.getMessage());
        }
    }

    private String formatDegradationAlert(StrategyStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>⚠️ Strategy Degraded</b>\n\n");
        sb.append("Strategy: <code>").append(stats.getStrategyId()).append("</code>\n");
        sb.append("Success rate: ").append(stats.getSuccessRate()).append("%\n");
        sb.append("Total predictions: ").append(stats.getTotalPredictions()).append("\n");
        sb.append("Successful: ").append(stats.getSuccessfulPredictions()).append("\n");
        sb.append("Failed: ").append(stats.getFailedPredictions()).append("\n");
        sb.append("Weight dropped below 5%\n\n");
        sb.append("<i>Recommendation: Consider disabling or reviewing this strategy.</i>");
        return sb.toString();
    }

    private String formatSign(BigDecimal value) {
        if (value == null) return "N/A";
        if (value.compareTo(BigDecimal.ZERO) > 0) {
            return "+" + value;
        }
        return value.toString();
    }

    public LocalDateTime getLastAlertTime() {
        return lastAlertTime;
    }

    public void resetCooldown() {
        lastAlertTime = null;
        log.info("Cooldown reset");
    }
}
