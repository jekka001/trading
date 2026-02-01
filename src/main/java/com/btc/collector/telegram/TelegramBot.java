package com.btc.collector.telegram;

import com.btc.collector.analysis.AlertEvaluationService;
import com.btc.collector.analysis.IndicatorLookupService;
import com.btc.collector.analysis.PatternAnalyzer;
import com.btc.collector.analysis.PredictionEvaluationService;
import com.btc.collector.analysis.SignalAlertService;
import com.btc.collector.analysis.StrategyEvaluator;
import com.btc.collector.analysis.StrategyStats;
import com.btc.collector.analysis.StrategyTracker;
import com.btc.collector.persistence.AlertHistoryEntity;
import com.btc.collector.persistence.Indicator15mEntity;
import com.btc.collector.service.CandleSyncService;
import com.btc.collector.service.FullSyncService;
import com.btc.collector.service.IndicatorCalculationService;
import com.btc.collector.strategy.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getNumberInstance(Locale.US);
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Kyiv");

    private final CandleSyncService candleSyncService;
    private final IndicatorCalculationService indicatorService;
    private final PatternAnalyzer patternAnalyzer;
    private final SignalAlertService signalAlertService;
    private final StrategyTracker strategyTracker;
    private final PredictionEvaluationService predictionEvaluationService;
    private final StrategyEvaluator strategyEvaluator;
    private final IndicatorLookupService indicatorLookupService;
    private final AlertEvaluationService alertEvaluationService;
    private final FullSyncService fullSyncService;
    private final BacktestService backtestService;
    private final StrategyRankingEngine rankingEngine;
    private final MarketRegimeDetector regimeDetector;
    private final MLDatasetExporter mlExporter;
    private final StrategyMetricsService metricsService;
    private final StrategyRegistry strategyRegistry;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.alert.chat-id:}")
    private String alertChatId;

    public TelegramBot(CandleSyncService candleSyncService,
                       IndicatorCalculationService indicatorService,
                       PatternAnalyzer patternAnalyzer,
                       SignalAlertService signalAlertService,
                       StrategyTracker strategyTracker,
                       PredictionEvaluationService predictionEvaluationService,
                       StrategyEvaluator strategyEvaluator,
                       IndicatorLookupService indicatorLookupService,
                       AlertEvaluationService alertEvaluationService,
                       FullSyncService fullSyncService,
                       BacktestService backtestService,
                       StrategyRankingEngine rankingEngine,
                       MarketRegimeDetector regimeDetector,
                       MLDatasetExporter mlExporter,
                       StrategyMetricsService metricsService,
                       StrategyRegistry strategyRegistry) {
        this.candleSyncService = candleSyncService;
        this.indicatorService = indicatorService;
        this.patternAnalyzer = patternAnalyzer;
        this.signalAlertService = signalAlertService;
        this.strategyTracker = strategyTracker;
        this.predictionEvaluationService = predictionEvaluationService;
        this.strategyEvaluator = strategyEvaluator;
        this.indicatorLookupService = indicatorLookupService;
        this.alertEvaluationService = alertEvaluationService;
        this.fullSyncService = fullSyncService;
        this.backtestService = backtestService;
        this.rankingEngine = rankingEngine;
        this.regimeDetector = regimeDetector;
        this.mlExporter = mlExporter;
        this.metricsService = metricsService;
        this.strategyRegistry = strategyRegistry;
    }

    @PostConstruct
    public void init() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            signalAlertService.setTelegramBot(this);
            log.info("Telegram bot registered successfully");
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot: {}", e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String messageText = update.getMessage().getText();
        long chatId = update.getMessage().getChatId();

        log.debug("Received message: {} from chat: {}", messageText, chatId);

        String response = processCommand(messageText);
        sendMessage(chatId, response);
    }

    private String processCommand(String command) {
        String cmd = command.toLowerCase().trim();
        String[] parts = cmd.split("\\s+", 2);
        String baseCmd = parts[0];
        String args = parts.length > 1 ? parts[1] : "";

        return switch (baseCmd) {
            case "/start" -> handleStart();
            case "/status" -> handleStatus();
            case "/sync" -> handleSync();
            case "/sync_all" -> handleSyncAll();
            case "/ind_status" -> handleIndStatus();
            case "/ind_last" -> handleIndLast();
            case "/ind_recalc" -> handleIndRecalc();
            case "/ind_resume" -> handleIndResume();
            case "/build_patterns" -> handleBuildPatterns();
            case "/build_patterns_inc" -> handleBuildPatternsIncremental();
            case "/signal_status" -> handleSignalStatus();
            case "/reset_cooldown" -> handleResetCooldown();
            case "/strategies" -> handleStrategies();
            case "/predictions" -> handlePredictions();
            case "/eval_status" -> handleEvalStatus();
            case "/alerts" -> handleAlerts();
            case "/full_sync" -> handleFullSync();
            case "/state" -> handleState();
            case "/pattern_resume" -> handlePatternResume();
            case "/pattern_eval" -> handlePatternEval();
            // Stage 4: ML/Backtest/Adaptive commands
            case "/backtest" -> handleBacktest(args);
            case "/ranking" -> handleRanking();
            case "/regime" -> handleRegime();
            case "/regime_history" -> handleRegimeHistory();
            case "/metrics" -> handleMetrics(args);
            case "/ml_export" -> handleMlExport(args);
            default -> handleUnknown();
        };
    }

    private String handleStart() {
        return """
                BTC Collector Bot

                Candle commands:
                /status - Show database status
                /sync - Fetch latest candles
                /sync_all - Load all historical data

                Indicator commands:
                /ind_status - Show indicator status
                /ind_last - Show latest indicator values
                /ind_recalc - Recalculate all indicators
                /ind_resume - Resume calculation

                Signal commands:
                /build_patterns - Full rebuild of patterns
                /build_patterns_inc - Incremental build (faster)
                /pattern_resume - Resume pattern building
                /pattern_eval - Evaluate pattern outcomes
                /signal_status - Show signal analyzer status
                /reset_cooldown - Reset alert cooldown
                /strategies - Show strategy stats
                /predictions - Show pending predictions
                /eval_status - Show evaluation system status
                /alerts - Show alert history and stats

                Pipeline:
                /full_sync - Run full pipeline (sync+indicators+patterns)
                /state - Show system state summary

                ML & Backtesting:
                /backtest [strategyId] - Backtest strategy
                /ranking - Show strategy rankings
                /regime - Show current market regime
                /regime_history - Show regime change history
                /metrics [strategyId] - Show strategy metrics
                /ml_export [type] - Export ML dataset (patterns/alerts/combined)

                Status: ONLINE
                """;
    }

    private String handleStatus() {
        long candleCount = candleSyncService.getCandleCount();
        Optional<LocalDateTime> latestCandle = candleSyncService.getLatestCandleTime();
        Optional<LocalDateTime> earliestCandle = candleSyncService.getEarliestCandleTime();
        boolean initialLoadInProgress = candleSyncService.isInitialLoadInProgress();

        StringBuilder sb = new StringBuilder();
        sb.append("BTC Collector\n\n");
        sb.append("Candles in DB: ").append(NUMBER_FORMAT.format(candleCount)).append("\n");

        if (earliestCandle.isPresent()) {
            sb.append("Earliest candle: ").append(toLocalTime(earliestCandle.get()).format(DATE_FORMAT)).append("\n");
        }

        if (latestCandle.isPresent()) {
            sb.append("Latest candle: ").append(toLocalTime(latestCandle.get()).format(DATE_FORMAT)).append("\n");
        }

        sb.append("Scheduler: ACTIVE\n");

        if (initialLoadInProgress) {
            sb.append("\nInitial load: IN PROGRESS");
        }

        return sb.toString();
    }

    private LocalDateTime toLocalTime(LocalDateTime utcTime) {
        return utcTime.atZone(ZoneId.of("UTC")).withZoneSameInstant(LOCAL_ZONE).toLocalDateTime();
    }

    private String handleSync() {
        if (candleSyncService.isInitialLoadInProgress()) {
            return "Initial load in progress. Please wait.";
        }

        executor.submit(() -> {
            int count = candleSyncService.updateLatest();
            log.info("Manual sync completed: {} new candles", count);
        });

        return "Sync started. New candles will be fetched.";
    }

    private String handleSyncAll() {
        if (candleSyncService.isInitialLoadInProgress()) {
            return "Initial load already in progress.";
        }

        executor.submit(() -> {
            candleSyncService.initialLoad();
            log.info("Initial load completed via Telegram command");
        });

        return """
                Initial load started.
                This may take a while (downloading years of data).
                Use /status to monitor progress.
                """;
    }

    private String handleUnknown() {
        return "Unknown command. Use /start to see available commands.";
    }

    private String handleIndStatus() {
        long candleCount = candleSyncService.getCandleCount();
        long indicatorCount = indicatorService.getIndicatorCount();
        Optional<LocalDateTime> latestIndicator = indicatorService.getLatestIndicatorTime();
        boolean calcInProgress = indicatorService.isCalcInProgress();

        long lookbackSkipped = 200; // MIN_CANDLES_REQUIRED - 1
        long expectedIndicators = Math.max(0, candleCount - lookbackSkipped);
        long missing = expectedIndicators - indicatorCount;

        StringBuilder sb = new StringBuilder();
        sb.append("Indicators\n\n");
        sb.append("Candles in DB: ").append(NUMBER_FORMAT.format(candleCount)).append("\n");
        sb.append("Indicators in DB: ").append(NUMBER_FORMAT.format(indicatorCount)).append("\n");
        sb.append("Skipped (lookback): ").append(lookbackSkipped).append("\n");

        if (missing > 0) {
            sb.append("Missing: ").append(NUMBER_FORMAT.format(missing)).append("\n");
        }

        if (latestIndicator.isPresent()) {
            sb.append("Latest: ").append(toLocalTime(latestIndicator.get()).format(DATE_FORMAT)).append("\n");
        }

        sb.append("Scheduler: ACTIVE\n");

        if (calcInProgress) {
            sb.append("\nCalculation: IN PROGRESS");
        }

        return sb.toString();
    }

    private String handleIndLast() {
        Optional<Indicator15mEntity> lastOpt = indicatorService.getLatestIndicator();

        if (lastOpt.isEmpty()) {
            return "No indicators calculated yet.";
        }

        Indicator15mEntity ind = lastOpt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("Latest Indicator\n");
        sb.append("Time: ").append(toLocalTime(ind.getOpenTime()).format(DATE_FORMAT)).append("\n\n");
        sb.append("EMA 50: ").append(formatPrice(ind.getEma50())).append("\n");
        sb.append("EMA 200: ").append(formatPrice(ind.getEma200())).append("\n");
        sb.append("RSI 14: ").append(ind.getRsi14()).append("\n");
        sb.append("ATR 14: ").append(formatPrice(ind.getAtr14())).append("\n");
        sb.append("BB Upper: ").append(formatPrice(ind.getBbUpper())).append("\n");
        sb.append("BB Middle: ").append(formatPrice(ind.getBbMiddle())).append("\n");
        sb.append("BB Lower: ").append(formatPrice(ind.getBbLower())).append("\n");
        sb.append("Avg Vol 20: ").append(formatVolume(ind.getAvgVolume20()));

        return sb.toString();
    }

    private String handleIndRecalc() {
        if (indicatorService.isCalcInProgress()) {
            return "Calculation already in progress.";
        }

        executor.submit(() -> {
            indicatorService.recalculateAll();
            log.info("Indicator recalculation completed via Telegram");
        });

        return "Full recalculation started (existing deleted).\nUse /ind_status to monitor progress.";
    }

    private String handleIndResume() {
        if (indicatorService.isCalcInProgress()) {
            return "Calculation already in progress.";
        }

        executor.submit(() -> {
            indicatorService.resumeCalculation();
            log.info("Indicator resume completed via Telegram");
        });

        return "Resume calculation started.\nUse /ind_status to monitor progress.";
    }

    private String formatPrice(java.math.BigDecimal price) {
        if (price == null) return "N/A";
        return NUMBER_FORMAT.format(price.doubleValue());
    }

    private String formatVolume(java.math.BigDecimal volume) {
        if (volume == null) return "N/A";
        return NUMBER_FORMAT.format(volume.doubleValue());
    }

    private String handleBuildPatterns() {
        if (patternAnalyzer.isBuildInProgress()) {
            return "Pattern build already in progress.";
        }

        executor.submit(() -> {
            patternAnalyzer.buildPatternDataset();
            log.info("Pattern build completed via Telegram");
        });

        return "Building historical patterns (full rebuild)...\nThis may take a while.\nPatterns will be saved to DB.\nUse /signal_status to monitor.";
    }

    private String handleBuildPatternsIncremental() {
        if (patternAnalyzer.isBuildInProgress()) {
            return "Pattern build already in progress.";
        }

        executor.submit(() -> {
            patternAnalyzer.buildPatternsIncremental();
            log.info("Incremental pattern build completed via Telegram");
        });

        return "Building patterns incrementally...\nOnly new candles will be processed.\nUse /signal_status to monitor.";
    }

    private String handlePatternResume() {
        if (patternAnalyzer.isBuildInProgress()) {
            return "Pattern build already in progress.";
        }

        executor.submit(() -> {
            log.info("Pattern resume started via Telegram");
            PatternAnalyzer.ResumeResult result = patternAnalyzer.resumePatternBuilding();

            if (result.success()) {
                log.info("Pattern resume completed: {} built, {} skipped, {}ms",
                        result.patternsBuilt(), result.patternsSkipped(), result.executionTimeMs());
            } else {
                log.error("Pattern resume failed: {}", result.message());
            }
        });

        return """
                Pattern resume started.

                Behavior:
                - Builds missing patterns from last pattern to latest indicator
                - Skips existing patterns (idempotent)
                - Uses indicators as source of truth
                - Processes in batches

                Use /signal_status to monitor progress.
                """;
    }

    private String handlePatternEval() {
        if (patternAnalyzer.isBuildInProgress()) {
            return "Pattern build in progress. Please wait.";
        }

        executor.submit(() -> {
            log.info("Pattern evaluation started via Telegram");
            int evaluated = patternAnalyzer.evaluatePatterns();
            log.info("Pattern evaluation completed: {} patterns evaluated", evaluated);
        });

        return """
                Pattern evaluation started.

                Behavior:
                - Evaluates patterns older than 24h
                - Calculates actual profit using future candles
                - Updates max_profit_pct and hours_to_max

                Use /state to see latest evaluated time.
                """;
    }

    private String handleSignalStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Signal Analyzer\n\n");

        sb.append("Patterns in memory: ").append(NUMBER_FORMAT.format(patternAnalyzer.getPatternCount())).append("\n");
        sb.append("Patterns in DB: ").append(NUMBER_FORMAT.format(patternAnalyzer.getDbPatternCount())).append("\n");
        sb.append("Initialized: ").append(patternAnalyzer.isInitialized() ? "YES" : "NO").append("\n");

        LocalDateTime lastAlert = signalAlertService.getLastAlertTime();
        if (lastAlert != null) {
            sb.append("Last alert: ").append(lastAlert.format(DATE_FORMAT)).append("\n");
        } else {
            sb.append("Last alert: Never\n");
        }

        if (patternAnalyzer.isBuildInProgress()) {
            sb.append("\nBuild: IN PROGRESS");
        }

        return sb.toString();
    }

    private String handleResetCooldown() {
        signalAlertService.resetCooldown();
        return "Alert cooldown reset. Next signal can be sent immediately.";
    }

    private String handleStrategies() {
        var strategies = strategyTracker.getAllStrategies();

        if (strategies.isEmpty()) {
            return "No strategies tracked yet.\nStrategies are created when signals are analyzed.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Strategy Stats\n\n");

        for (StrategyStats stats : strategies) {
            sb.append(stats.getStrategyId()).append("\n");
            sb.append("  Rate: ").append(stats.getSuccessRate()).append("% | ");
            sb.append("Weight: ").append(stats.getWeight()).append("\n");
            sb.append("  Total: ").append(stats.getTotalPredictions());
            sb.append(" (").append(stats.getSuccessfulPredictions()).append("/");
            sb.append(stats.getFailedPredictions()).append(")\n");
            if (stats.isDegraded()) {
                sb.append("  ⚠️ DEGRADED\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String handlePredictions() {
        long pendingCount = predictionEvaluationService.getPendingCount();

        StringBuilder sb = new StringBuilder();
        sb.append("Predictions\n\n");
        sb.append("Pending evaluations: ").append(pendingCount).append("\n");
        sb.append("Strategies tracked: ").append(strategyTracker.getStrategyCount()).append("\n");
        sb.append("\nPredictions are evaluated automatically when their time window expires.");

        return sb.toString();
    }

    private String handleEvalStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Evaluation System\n\n");

        sb.append("History mode: ").append(strategyEvaluator.isHistoryEnabled() ? "ENABLED" : "DISABLED").append("\n");
        sb.append("Indicator cache: ").append(indicatorLookupService.getCacheSize()).append(" entries\n");
        sb.append("Patterns loaded: ").append(NUMBER_FORMAT.format(patternAnalyzer.getPatternCount())).append("\n");
        sb.append("Strategies tracked: ").append(strategyTracker.getStrategyCount()).append("\n");

        long indicatorCount = indicatorService.getIndicatorCount();
        sb.append("Historical indicators: ").append(NUMBER_FORMAT.format(indicatorCount)).append("\n");

        sb.append("\nHistory mode combines pattern matching with\n");
        sb.append("recent indicator trends for better signals.");

        return sb.toString();
    }

    private String handleAlerts() {
        StringBuilder sb = new StringBuilder();
        sb.append("Signal History\n\n");

        // Signal counts
        long total = alertEvaluationService.getTotalSignalCount();
        long sentToTelegram = alertEvaluationService.getSentToTelegramCount();
        long successful = alertEvaluationService.getSuccessfulCount();
        long failed = alertEvaluationService.getFailedCount();
        long pending = alertEvaluationService.getPendingEvaluationCount();
        Double successRate = alertEvaluationService.getOverallSuccessRate();

        sb.append("Signals:\n");
        sb.append("  Total: ").append(total).append("\n");
        sb.append("  Sent to Telegram: ").append(sentToTelegram).append("\n");
        sb.append("  Not sent (low prob): ").append(total - sentToTelegram).append("\n");

        sb.append("\nEvaluation:\n");
        sb.append("  Successful: ").append(successful).append("\n");
        sb.append("  Failed: ").append(failed).append("\n");
        sb.append("  Pending: ").append(pending).append("\n");
        if (successRate != null) {
            sb.append("  Success rate: ").append(String.format("%.1f", successRate)).append("%\n");
        }

        // Recent signals
        var recentAlerts = alertEvaluationService.getRecentAlerts(5);
        if (!recentAlerts.isEmpty()) {
            sb.append("\nRecent Signals:\n");
            for (AlertHistoryEntity alert : recentAlerts) {
                sb.append("\n");
                sb.append(alert.getAlertTime().format(DATE_FORMAT));
                sb.append(alert.isSentToTelegram() ? " 📤" : " 📝").append("\n");
                sb.append("  Strategy: ").append(alert.getStrategyId()).append("\n");
                sb.append("  Prob: ").append(alert.getFinalProbability()).append("% | ");
                sb.append("Profit: +").append(alert.getPredictedProfitPct()).append("%\n");
                if (alert.isEvaluated()) {
                    String status = alert.getSuccess() != null && alert.getSuccess() ? "✅" : "❌";
                    sb.append("  Result: ").append(status).append(" +")
                            .append(alert.getActualProfitPct()).append("%\n");
                } else {
                    sb.append("  Status: ⏳ PENDING\n");
                }
            }
        } else {
            sb.append("\nNo signals recorded yet.");
        }

        sb.append("\n📤 = sent to Telegram | 📝 = saved only");

        return sb.toString();
    }

    private String handleFullSync() {
        if (fullSyncService.isRunning()) {
            return "Full sync already in progress. Please wait.";
        }

        if (candleSyncService.isInitialLoadInProgress()) {
            return "Initial candle load in progress. Please wait.";
        }

        if (indicatorService.isCalcInProgress()) {
            return "Indicator calculation in progress. Please wait.";
        }

        if (patternAnalyzer.isBuildInProgress()) {
            return "Pattern build in progress. Please wait.";
        }

        // Run full sync in background
        executor.submit(() -> {
            log.info("Starting full sync pipeline via Telegram command");

            FullSyncService.SyncResult result = fullSyncService.executeFullSync(null);

            // Log final result
            if (result.success()) {
                log.info("Full sync completed successfully");
            } else {
                log.error("Full sync failed: {}", result.message());
            }
        });

        return """
                Full sync pipeline started.

                Steps:
                1. Sync candles from exchange
                2. Calculate/resume indicators
                3. Build patterns (immediate)
                4. Evaluate patterns (outcomes)

                This runs in background.
                Use /state to monitor progress.
                """;
    }

    private String handleState() {
        long startTime = System.currentTimeMillis();

        // Fetch counts from database
        long candleCount = candleSyncService.getCandleCount();
        long indicatorCount = indicatorService.getIndicatorCount();
        long patternCount = patternAnalyzer.getDbPatternCount();

        // Fetch latest timestamps (stored as UTC in database)
        Optional<LocalDateTime> latestCandle = candleSyncService.getLatestCandleTime();
        Optional<LocalDateTime> latestIndicator = indicatorService.getLatestIndicatorTime();
        Optional<LocalDateTime> latestPattern = patternAnalyzer.getLatestPatternTime();

        long execTime = System.currentTimeMillis() - startTime;

        StringBuilder sb = new StringBuilder();
        sb.append("System State\n\n");

        // Counts
        sb.append("Candles: ").append(NUMBER_FORMAT.format(candleCount)).append("\n");
        sb.append("Indicators: ").append(NUMBER_FORMAT.format(indicatorCount)).append(" (+200 warmup)\n");
        sb.append("Patterns: ").append(NUMBER_FORMAT.format(patternCount)).append(" (+200 warmup)\n");

        sb.append("\n");

        // Latest evaluated pattern
        Optional<LocalDateTime> latestEvaluated = patternAnalyzer.getLatestEvaluatedPatternTime();

        // Latest timestamps (converted to local time)
        sb.append("Latest candle:    ").append(formatStateTime(latestCandle)).append("\n");
        sb.append("Latest indicator: ").append(formatStateTime(latestIndicator)).append("\n");
        sb.append("Latest pattern:   ").append(formatStateTime(latestPattern)).append("\n");
        sb.append("Latest evaluated: ").append(formatStateTime(latestEvaluated)).append(" (-24h)\n");

        // Warnings for stale data
        String warnings = checkStaleness(latestCandle, latestIndicator, latestPattern);
        if (!warnings.isEmpty()) {
            sb.append("\n").append(warnings);
        }

        sb.append("\nQuery: ").append(execTime).append("ms");

        return sb.toString();
    }

    private String formatStateTime(Optional<LocalDateTime> utcTime) {
        if (utcTime.isEmpty()) {
            return "N/A";
        }
        return toLocalTime(utcTime.get()).format(DATE_FORMAT);
    }

    private String checkStaleness(Optional<LocalDateTime> candle,
                                   Optional<LocalDateTime> indicator,
                                   Optional<LocalDateTime> pattern) {
        StringBuilder warnings = new StringBuilder();

        if (candle.isEmpty()) return "";

        LocalDateTime candleTime = candle.get();

        // Check if indicators are behind candles
        if (indicator.isPresent() && indicator.get().isBefore(candleTime)) {
            warnings.append("⚠️ Indicators behind candles\n");
        }

        // Check if patterns are behind indicators (should be equal now)
        if (indicator.isPresent() && pattern.isPresent()) {
            if (pattern.get().isBefore(indicator.get().minusMinutes(30))) {
                warnings.append("⚠️ Patterns behind (run /pattern_resume)\n");
            }
        }

        // Check if latest candle is too old (more than 30 minutes from now in UTC)
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        if (candleTime.isBefore(nowUtc.minusMinutes(30))) {
            warnings.append("⚠️ Latest candle >30min old\n");
        }

        return warnings.toString();
    }

    // ==================== Stage 4: ML/Backtest/Adaptive Commands ====================

    private String handleBacktest(String args) {
        if (args.isEmpty()) {
            // List available strategies
            var strategies = backtestService.getAvailableStrategies();
            if (strategies.isEmpty()) {
                return "No strategies available for backtesting.\nBuild patterns first with /pattern_resume";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Available strategies for backtest:\n\n");
            strategies.stream().limit(10).forEach(s -> sb.append("• ").append(s).append("\n"));
            if (strategies.size() > 10) {
                sb.append("... and ").append(strategies.size() - 10).append(" more\n");
            }
            sb.append("\nUsage: /backtest RSI_MID_EMA_BULL_VOL_MED");
            return sb.toString();
        }

        String strategyId = args.toUpperCase().trim();
        try {
            BacktestService.BacktestResult result = backtestService.backtest(strategyId);
            return result.toFormattedString();
        } catch (Exception e) {
            log.error("Backtest error: {}", e.getMessage());
            return "Backtest failed: " + e.getMessage();
        }
    }

    private String handleRanking() {
        // Update rankings
        rankingEngine.forceUpdate();

        return rankingEngine.getRankingSummary();
    }

    private String handleRegime() {
        MarketRegimeResult result = regimeDetector.getCurrentRegime();
        LocalDateTime lastDetection = regimeDetector.getLastDetectionTime();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 Market Regime\n\n");
        sb.append("Current: ").append(result.getRegime().getDisplayName()).append("\n");
        sb.append("Confidence: ").append(result.getConfidencePercent()).append("\n");
        sb.append("Matched: ").append(result.getMatchedConditions())
                .append("/").append(result.getTotalConditions()).append(" conditions\n\n");

        sb.append("Description: ").append(result.getRegime().getDescription()).append("\n\n");

        sb.append("Signal multiplier: ").append(String.format("%.1f", result.getRegime().getMultiplier())).append("\n");
        sb.append("Effective: ").append(String.format("%.2f", result.getEffectiveMultiplier())).append("\n\n");

        // Show active strategies for this regime
        var activeStrategies = strategyRegistry.getStrategiesForRegime(result.getRegime()).stream()
                .limit(5)
                .toList();

        if (!activeStrategies.isEmpty()) {
            sb.append("Active Strategies:\n");
            for (var s : activeStrategies) {
                sb.append("• ").append(s.getHumanReadableName()).append("\n");
            }
        }

        if (lastDetection != null) {
            sb.append("\nLast detected: ").append(toLocalTime(lastDetection).format(DATE_FORMAT));
        }

        return sb.toString();
    }

    private String handleRegimeHistory() {
        var history = regimeDetector.getRegimeChanges(10);

        if (history.isEmpty()) {
            return "No regime history available.\nRegime is persisted every 15 minutes.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 Regime History (Last 10 Changes)\n\n");

        for (var entity : history) {
            sb.append(toLocalTime(entity.getTimestamp()).format(DATE_FORMAT)).append("\n");
            sb.append("  ").append(entity.getRegimeType().getDisplayName());
            sb.append(" (").append(String.format("%.0f%%", entity.getConfidence() * 100)).append(")\n");
        }

        // Show regime distribution
        sb.append("\nRegime Distribution (24h):\n");
        var distribution = regimeDetector.getRegimeHistory(96); // 24h
        long trend = distribution.stream().filter(r -> r.getRegimeType() == MarketRegime.TREND).count();
        long range = distribution.stream().filter(r -> r.getRegimeType() == MarketRegime.RANGE).count();
        long highVol = distribution.stream().filter(r -> r.getRegimeType() == MarketRegime.HIGH_VOLATILITY).count();
        long total = distribution.size();

        if (total > 0) {
            sb.append("• TREND: ").append(String.format("%.0f%%", 100.0 * trend / total)).append("\n");
            sb.append("• RANGE: ").append(String.format("%.0f%%", 100.0 * range / total)).append("\n");
            sb.append("• HIGH_VOL: ").append(String.format("%.0f%%", 100.0 * highVol / total)).append("\n");
        }

        return sb.toString();
    }

    private String handleMetrics(String args) {
        if (args.isEmpty()) {
            // Show top strategies by metrics
            var topStrategies = metricsService.getTopStrategies(5);

            if (topStrategies.isEmpty()) {
                return "No strategy metrics available yet.\nNeed evaluated signals for metrics.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Top Strategy Metrics\n\n");

            for (StrategyMetrics m : topStrategies) {
                sb.append("#").append(m.getRank()).append(" ").append(m.getStrategyId()).append("\n");
                sb.append("   Win: ").append(m.getWinRate()).append("% | ");
                sb.append("EV: ").append(m.getExpectedValue()).append("% | ");
                sb.append("Signals: ").append(m.getEvaluatedSignals()).append("\n");
            }

            sb.append("\nUsage: /metrics RSI_MID_EMA_BULL_VOL_MED");
            return sb.toString();
        }

        String strategyId = args.toUpperCase().trim();
        StrategyMetrics metrics = metricsService.calculateMetrics(strategyId);

        if (metrics.getTotalSignals() == 0) {
            return "No data for strategy: " + strategyId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Strategy Metrics: ").append(strategyId).append("\n\n");

        sb.append("Signals:\n");
        sb.append("  Total: ").append(metrics.getTotalSignals()).append("\n");
        sb.append("  Evaluated: ").append(metrics.getEvaluatedSignals()).append("\n");
        sb.append("  Successful: ").append(metrics.getSuccessfulSignals()).append("\n");
        sb.append("  Failed: ").append(metrics.getFailedSignals()).append("\n\n");

        sb.append("Performance:\n");
        sb.append("  Win Rate: ").append(metrics.getWinRate()).append("%\n");
        sb.append("  Avg Profit: ").append(metrics.getAvgProfitPct()).append("%\n");
        sb.append("  Avg Loss: ").append(metrics.getAvgLossPct()).append("%\n");
        sb.append("  Expected Value: ").append(metrics.getExpectedValue()).append("%\n");
        sb.append("  Profit Factor: ").append(metrics.getProfitFactor()).append("\n");
        sb.append("  Max Drawdown: ").append(metrics.getMaxDrawdown()).append("%\n\n");

        sb.append("Ranking:\n");
        sb.append("  Rank: #").append(metrics.getRank()).append("\n");
        sb.append("  Score: ").append(metrics.getScore()).append("\n");

        if (metrics.getFirstSignal() != null) {
            sb.append("\nFirst signal: ").append(metrics.getFirstSignal().format(DATE_FORMAT));
        }

        return sb.toString();
    }

    private String handleMlExport(String args) {
        String exportType = args.isEmpty() ? "combined" : args.toLowerCase().trim();

        // Show stats first
        String stats = mlExporter.getDatasetStatistics();

        String filePath;
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        try {
            MLDatasetExporter.ExportResult result = switch (exportType) {
                case "patterns" -> {
                    filePath = "ml_patterns_" + timestamp + ".csv";
                    yield mlExporter.exportPatternsDataset(filePath);
                }
                case "alerts" -> {
                    filePath = "ml_alerts_" + timestamp + ".csv";
                    yield mlExporter.exportAlertsDataset(filePath);
                }
                case "combined" -> {
                    filePath = "ml_combined_" + timestamp + ".csv";
                    yield mlExporter.exportCombinedDataset(filePath);
                }
                default -> {
                    yield null;
                }
            };

            if (result == null) {
                return "Unknown export type: " + exportType + "\n\nAvailable: patterns, alerts, combined\n\n" + stats;
            }

            return result.toFormattedString() + "\n\n" + stats;

        } catch (Exception e) {
            log.error("ML export error: {}", e.getMessage(), e);
            return "Export failed: " + e.getMessage() + "\n\n" + stats;
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message: {}", e.getMessage());
        }
    }

    /**
     * Send message to configured alert chat.
     * Used by schedulers and services to send notifications.
     */
    public void sendMessage(String text) {
        if (alertChatId == null || alertChatId.isEmpty()) {
            log.warn("Alert chat ID not configured, cannot send message");
            return;
        }

        SendMessage message = new SendMessage();
        message.setChatId(alertChatId);
        message.setText(text);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to alert chat: {}", e.getMessage());
        }
    }
}
