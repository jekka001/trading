package com.btc.collector.strategy;

import com.btc.collector.persistence.AlertHistoryEntity;
import com.btc.collector.persistence.AlertHistoryRepository;
import com.btc.collector.persistence.HistoricalPatternEntity;
import com.btc.collector.persistence.HistoricalPatternRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service for exporting ML-ready datasets from historical data.
 * Prepares data for future machine learning models.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MLDatasetExporter {

    private final HistoricalPatternRepository patternRepository;
    private final AlertHistoryRepository alertRepository;
    private final MarketRegimeDetector regimeDetector;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Export result containing file path and statistics.
     */
    public record ExportResult(
            String filePath,
            int recordsExported,
            int featuresCount,
            long executionTimeMs
    ) {
        public String toFormattedString() {
            return String.format(
                    "ML Dataset Export Complete\n" +
                    "File: %s\n" +
                    "Records: %d\n" +
                    "Features: %d\n" +
                    "Time: %dms",
                    filePath, recordsExported, featuresCount, executionTimeMs
            );
        }
    }

    /**
     * Export patterns dataset for ML training.
     * Includes all evaluated patterns with features and outcomes.
     */
    public ExportResult exportPatternsDataset(String filePath) throws IOException {
        long startTime = System.currentTimeMillis();

        List<HistoricalPatternEntity> patterns = patternRepository.findAllOrderByCandleTimeAsc().stream()
                .filter(HistoricalPatternEntity::isEvaluated)
                .filter(p -> p.getMaxProfitPct() != null)
                .toList();

        int featuresCount = 15; // Number of features

        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            // Write header
            writer.println(String.join(",",
                    "timestamp",
                    "strategy_id",
                    "rsi",
                    "ema_50",
                    "ema_200",
                    "ema_distance_pct",
                    "ema_trend",
                    "volume_change_pct",
                    "price_change_1h",
                    "price_change_4h",
                    "rsi_bucket",
                    "volume_bucket",
                    "max_profit_pct",
                    "hours_to_max",
                    "is_profitable"
            ));

            // Write data rows
            for (HistoricalPatternEntity pattern : patterns) {
                writer.println(formatPatternRow(pattern));
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("Exported {} patterns to {} in {}ms", patterns.size(), filePath, executionTime);

        return new ExportResult(filePath, patterns.size(), featuresCount, executionTime);
    }

    /**
     * Export alerts dataset for ML training.
     * Includes all evaluated alerts with predictions and outcomes.
     */
    public ExportResult exportAlertsDataset(String filePath) throws IOException {
        long startTime = System.currentTimeMillis();

        List<AlertHistoryEntity> alerts = alertRepository.findAll().stream()
                .filter(AlertHistoryEntity::isEvaluated)
                .filter(a -> a.getActualProfitPct() != null)
                .toList();

        int featuresCount = 18; // Number of features

        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            // Write header
            writer.println(String.join(",",
                    "timestamp",
                    "strategy_id",
                    "base_probability",
                    "final_probability",
                    "strategy_weight",
                    "historical_factor",
                    "current_price",
                    "predicted_profit_pct",
                    "predicted_hours",
                    "matched_patterns",
                    "rsi",
                    "ema_trend",
                    "volume_bucket",
                    "sent_to_telegram",
                    "actual_profit_pct",
                    "target_reached",
                    "actual_vs_predicted_diff",
                    "is_successful"
            ));

            // Write data rows
            for (AlertHistoryEntity alert : alerts) {
                writer.println(formatAlertRow(alert));
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("Exported {} alerts to {} in {}ms", alerts.size(), filePath, executionTime);

        return new ExportResult(filePath, alerts.size(), featuresCount, executionTime);
    }

    /**
     * Export combined dataset with all features.
     */
    public ExportResult exportCombinedDataset(String filePath) throws IOException {
        long startTime = System.currentTimeMillis();

        List<AlertHistoryEntity> alerts = alertRepository.findAll().stream()
                .filter(AlertHistoryEntity::isEvaluated)
                .filter(a -> a.getActualProfitPct() != null)
                .toList();

        int featuresCount = 22; // Combined features

        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            // Write header
            writer.println(String.join(",",
                    // Timestamp
                    "timestamp",
                    // Strategy info
                    "strategy_id",
                    "strategy_type",
                    // Input features
                    "rsi",
                    "rsi_bucket",
                    "ema_trend",
                    "volume_bucket",
                    "current_price",
                    // Model predictions
                    "base_probability",
                    "final_probability",
                    "strategy_weight",
                    "historical_factor",
                    "predicted_profit_pct",
                    "predicted_hours",
                    "matched_patterns",
                    // Market context
                    "market_regime",
                    // Outcomes
                    "actual_profit_pct",
                    "actual_vs_predicted",
                    "target_reached",
                    "is_successful",
                    // Metadata
                    "sent_to_telegram",
                    "snapshot_group_id"
            ));

            // Write data rows
            for (AlertHistoryEntity alert : alerts) {
                writer.println(formatCombinedRow(alert));
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("Exported {} combined records to {} in {}ms", alerts.size(), filePath, executionTime);

        return new ExportResult(filePath, alerts.size(), featuresCount, executionTime);
    }

    /**
     * Format pattern as CSV row.
     */
    private String formatPatternRow(HistoricalPatternEntity pattern) {
        double emaDistancePct = 0;
        String emaTrend = "UNKNOWN";

        if (pattern.getEma50() != null && pattern.getEma200() != null) {
            double ema50 = pattern.getEma50().doubleValue();
            double ema200 = pattern.getEma200().doubleValue();
            double midPrice = (ema50 + ema200) / 2;
            if (midPrice > 0) {
                emaDistancePct = Math.abs(ema50 - ema200) / midPrice * 100;
            }
            emaTrend = ema50 > ema200 ? "BULL" : "BEAR";
        }

        String rsiBucket = "MID";
        if (pattern.getRsi() != null) {
            double rsi = pattern.getRsi().doubleValue();
            rsiBucket = rsi < 30 ? "LOW" : (rsi > 70 ? "HIGH" : "MID");
        }

        String volumeBucket = "MED";
        if (pattern.getVolumeChangePct() != null) {
            double vol = pattern.getVolumeChangePct().doubleValue();
            volumeBucket = vol > 50 ? "HIGH" : (vol < -30 ? "LOW" : "MED");
        }

        boolean isProfitable = pattern.getMaxProfitPct() != null &&
                pattern.getMaxProfitPct().doubleValue() > 0;

        return String.join(",",
                pattern.getCandleTime().format(TIMESTAMP_FORMAT),
                escapeCSV(pattern.getStrategyId()),
                formatDouble(pattern.getRsi()),
                formatDouble(pattern.getEma50()),
                formatDouble(pattern.getEma200()),
                formatDouble(emaDistancePct),
                emaTrend,
                formatDouble(pattern.getVolumeChangePct()),
                formatDouble(pattern.getPriceChange1h()),
                formatDouble(pattern.getPriceChange4h()),
                rsiBucket,
                volumeBucket,
                formatDouble(pattern.getMaxProfitPct()),
                String.valueOf(pattern.getHoursToMax() != null ? pattern.getHoursToMax() : 0),
                String.valueOf(isProfitable ? 1 : 0)
        );
    }

    /**
     * Format alert as CSV row.
     */
    private String formatAlertRow(AlertHistoryEntity alert) {
        boolean targetReached = alert.isTargetReached();
        double actualVsPredicted = 0;
        if (alert.getActualProfitPct() != null && alert.getPredictedProfitPct() != null) {
            actualVsPredicted = alert.getActualProfitPct().doubleValue() -
                    alert.getPredictedProfitPct().doubleValue();
        }

        return String.join(",",
                alert.getAlertTime().format(TIMESTAMP_FORMAT),
                escapeCSV(alert.getStrategyId()),
                formatDouble(alert.getBaseProbability()),
                formatDouble(alert.getFinalProbability()),
                formatDouble(alert.getStrategyWeight()),
                formatDouble(alert.getHistoricalFactor()),
                formatDouble(alert.getCurrentPrice()),
                formatDouble(alert.getPredictedProfitPct()),
                String.valueOf(alert.getPredictedHours()),
                String.valueOf(alert.getMatchedPatterns() != null ? alert.getMatchedPatterns() : 0),
                formatDouble(alert.getRsi()),
                escapeCSV(alert.getEmaTrend()),
                escapeCSV(alert.getVolumeBucket()),
                String.valueOf(alert.isSentToTelegram() ? 1 : 0),
                formatDouble(alert.getActualProfitPct()),
                String.valueOf(targetReached ? 1 : 0),
                formatDouble(actualVsPredicted),
                String.valueOf(Boolean.TRUE.equals(alert.getSuccess()) ? 1 : 0)
        );
    }

    /**
     * Format combined row with all features.
     */
    private String formatCombinedRow(AlertHistoryEntity alert) {
        String strategyType = "HYBRID";
        // Could look up from registry if needed

        String rsiBucket = "MID";
        if (alert.getRsi() != null) {
            double rsi = alert.getRsi().doubleValue();
            rsiBucket = rsi < 30 ? "LOW" : (rsi > 70 ? "HIGH" : "MID");
        }

        String marketRegime = "RANGING";
        // Could calculate from historical data

        boolean targetReached = alert.isTargetReached();
        double actualVsPredicted = 0;
        if (alert.getActualProfitPct() != null && alert.getPredictedProfitPct() != null) {
            actualVsPredicted = alert.getActualProfitPct().doubleValue() -
                    alert.getPredictedProfitPct().doubleValue();
        }

        return String.join(",",
                alert.getAlertTime().format(TIMESTAMP_FORMAT),
                escapeCSV(alert.getStrategyId()),
                strategyType,
                formatDouble(alert.getRsi()),
                rsiBucket,
                escapeCSV(alert.getEmaTrend()),
                escapeCSV(alert.getVolumeBucket()),
                formatDouble(alert.getCurrentPrice()),
                formatDouble(alert.getBaseProbability()),
                formatDouble(alert.getFinalProbability()),
                formatDouble(alert.getStrategyWeight()),
                formatDouble(alert.getHistoricalFactor()),
                formatDouble(alert.getPredictedProfitPct()),
                String.valueOf(alert.getPredictedHours()),
                String.valueOf(alert.getMatchedPatterns() != null ? alert.getMatchedPatterns() : 0),
                marketRegime,
                formatDouble(alert.getActualProfitPct()),
                formatDouble(actualVsPredicted),
                String.valueOf(targetReached ? 1 : 0),
                String.valueOf(Boolean.TRUE.equals(alert.getSuccess()) ? 1 : 0),
                String.valueOf(alert.isSentToTelegram() ? 1 : 0),
                escapeCSV(alert.getSnapshotGroupId())
        );
    }

    /**
     * Format double value for CSV.
     */
    private String formatDouble(Object value) {
        if (value == null) return "0";
        if (value instanceof Number) {
            return String.format("%.6f", ((Number) value).doubleValue());
        }
        return value.toString();
    }

    /**
     * Escape CSV value.
     */
    private String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Get dataset statistics without exporting.
     */
    public String getDatasetStatistics() {
        long evaluatedPatterns = patternRepository.countEvaluated();
        long evaluatedAlerts = alertRepository.findAll().stream()
                .filter(AlertHistoryEntity::isEvaluated)
                .count();

        long profitablePatterns = patternRepository.findAll().stream()
                .filter(HistoricalPatternEntity::isEvaluated)
                .filter(p -> p.getMaxProfitPct() != null && p.getMaxProfitPct().doubleValue() > 0)
                .count();

        long successfulAlerts = alertRepository.countSuccessful();

        return String.format(
                "ML Dataset Statistics:\n" +
                "Patterns: %d evaluated (%d profitable, %.1f%%)\n" +
                "Alerts: %d evaluated (%d successful, %.1f%%)",
                evaluatedPatterns,
                profitablePatterns,
                evaluatedPatterns > 0 ? (profitablePatterns * 100.0 / evaluatedPatterns) : 0,
                evaluatedAlerts,
                successfulAlerts,
                evaluatedAlerts > 0 ? (successfulAlerts * 100.0 / evaluatedAlerts) : 0
        );
    }
}
