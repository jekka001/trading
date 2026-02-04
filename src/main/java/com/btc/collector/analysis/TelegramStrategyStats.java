package com.btc.collector.analysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Statistics for a single strategy based ONLY on Telegram alerts.
 * Fully isolated from global strategy statistics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramStrategyStats {

    private String strategyId;

    // Evaluation v1
    private long signalsSent;
    private long successful;
    private long failed;
    private BigDecimal successRate;

    // Confidence Evaluation v2
    private BigDecimal rate2;

    // Profit Evaluation
    private BigDecimal avgProfitPct;
    private BigDecimal totalPnlUsd;
}
