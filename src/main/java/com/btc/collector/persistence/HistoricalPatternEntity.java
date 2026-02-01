package com.btc.collector.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "historical_pattern",
       uniqueConstraints = @UniqueConstraint(columnNames = "candle_time"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalPatternEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candle_time", nullable = false)
    private LocalDateTime candleTime;

    @Column(name = "strategy_id", length = 100)
    private String strategyId;

    @Column(name = "rsi", precision = 10, scale = 4)
    private BigDecimal rsi;

    @Column(name = "ema_50", precision = 18, scale = 8)
    private BigDecimal ema50;

    @Column(name = "ema_200", precision = 18, scale = 8)
    private BigDecimal ema200;

    @Column(name = "volume_change_pct", precision = 10, scale = 4)
    private BigDecimal volumeChangePct;

    @Column(name = "price_change_1h", precision = 10, scale = 4)
    private BigDecimal priceChange1h;

    @Column(name = "price_change_4h", precision = 10, scale = 4)
    private BigDecimal priceChange4h;

    @Column(name = "max_profit_pct", precision = 10, scale = 4)
    private BigDecimal maxProfitPct;

    @Column(name = "hours_to_max")
    private Integer hoursToMax;

    @Column(name = "evaluated")
    private boolean evaluated;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * Check if this pattern was profitable (>0% gain).
     */
    public boolean isProfitable() {
        return maxProfitPct != null && maxProfitPct.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Check if EMA50 is below EMA200 (bearish).
     */
    public boolean isEma50BelowEma200() {
        if (ema50 == null || ema200 == null) return false;
        return ema50.compareTo(ema200) < 0;
    }
}
