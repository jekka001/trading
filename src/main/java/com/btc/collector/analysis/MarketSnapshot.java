package com.btc.collector.analysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketSnapshot {

    private LocalDateTime timestamp;
    private BigDecimal price;

    // EMAs
    private BigDecimal ema50;
    private BigDecimal ema200;

    // RSI
    private BigDecimal rsi;

    // MACD
    private BigDecimal macdLine;
    private BigDecimal signalLine;
    private BigDecimal macdHistogram;

    // Volume
    private BigDecimal volumeChangePct;

    // Price changes
    private BigDecimal priceChange1h;   // 4 candles
    private BigDecimal priceChange4h;   // 16 candles
    private BigDecimal priceChange24h;  // 96 candles

    public boolean isEma50BelowEma200() {
        if (ema50 == null || ema200 == null) return false;
        return ema50.compareTo(ema200) < 0;
    }

    public VolumeBucket getVolumeBucket() {
        if (volumeChangePct == null) return VolumeBucket.MEDIUM;

        double pct = volumeChangePct.doubleValue();
        if (pct < -20) return VolumeBucket.LOW;
        if (pct > 50) return VolumeBucket.HIGH;
        return VolumeBucket.MEDIUM;
    }

    public enum VolumeBucket {
        LOW, MEDIUM, HIGH
    }
}
