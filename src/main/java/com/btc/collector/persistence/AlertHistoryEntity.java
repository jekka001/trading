package com.btc.collector.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "alert_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_time", nullable = false)
    private LocalDateTime alertTime;

    @Column(name = "snapshot_group_id", length = 36)
    private String snapshotGroupId;

    @Column(name = "strategy_id", length = 100, nullable = false)
    private String strategyId;

    @Column(name = "base_probability", precision = 10, scale = 4)
    private BigDecimal baseProbability;

    @Column(name = "final_probability", precision = 10, scale = 4)
    private BigDecimal finalProbability;

    @Column(name = "strategy_weight", precision = 5, scale = 4)
    private BigDecimal strategyWeight;

    @Column(name = "historical_factor", precision = 5, scale = 4)
    private BigDecimal historicalFactor;

    @Column(name = "current_price", precision = 18, scale = 8, nullable = false)
    private BigDecimal currentPrice;

    @Column(name = "predicted_profit_pct", precision = 10, scale = 4, nullable = false)
    private BigDecimal predictedProfitPct;

    @Column(name = "target_price", precision = 18, scale = 8, nullable = false)
    private BigDecimal targetPrice;

    @Column(name = "predicted_hours", nullable = false)
    private int predictedHours;

    @Column(name = "matched_patterns")
    private Integer matchedPatterns;

    @Column(name = "rsi", precision = 10, scale = 4)
    private BigDecimal rsi;

    @Column(name = "ema_trend", length = 10)
    private String emaTrend;

    @Column(name = "volume_bucket", length = 10)
    private String volumeBucket;

    @Column(name = "evaluate_at", nullable = false)
    private LocalDateTime evaluateAt;

    @Column(name = "evaluated")
    private boolean evaluated;

    @Column(name = "success")
    private Boolean success;

    @Column(name = "actual_max_price", precision = 18, scale = 8)
    private BigDecimal actualMaxPrice;

    @Column(name = "actual_profit_pct", precision = 10, scale = 4)
    private BigDecimal actualProfitPct;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @Column(name = "sent_to_telegram")
    private boolean sentToTelegram;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    @Column(name = "confidence_evaluated")
    private boolean confidenceEvaluated;

    @Column(name = "actual_profit_usd", precision = 10, scale = 4)
    private BigDecimal actualProfitUsd;

    /**
     * Check if the alert achieved the target.
     */
    public boolean isTargetReached() {
        if (actualMaxPrice == null || targetPrice == null) return false;
        return actualMaxPrice.compareTo(targetPrice) >= 0;
    }
}
