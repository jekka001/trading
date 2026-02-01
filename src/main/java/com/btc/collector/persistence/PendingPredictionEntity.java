package com.btc.collector.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "pending_prediction")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingPredictionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", length = 100, nullable = false)
    private String strategyId;

    @Column(name = "entry_price", precision = 18, scale = 8, nullable = false)
    private BigDecimal entryPrice;

    @Column(name = "predicted_profit_pct", precision = 10, scale = 4, nullable = false)
    private BigDecimal predictedProfitPct;

    @Column(name = "predicted_hours", nullable = false)
    private int predictedHours;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public static PendingPredictionEntity create(String strategyId, BigDecimal entryPrice,
                                                  BigDecimal predictedProfitPct, int predictedHours) {
        LocalDateTime now = LocalDateTime.now();
        return PendingPredictionEntity.builder()
                .strategyId(strategyId)
                .entryPrice(entryPrice)
                .predictedProfitPct(predictedProfitPct)
                .predictedHours(predictedHours)
                .createdAt(now)
                .evaluateAt(now.plusHours(predictedHours))
                .evaluated(false)
                .build();
    }
}
