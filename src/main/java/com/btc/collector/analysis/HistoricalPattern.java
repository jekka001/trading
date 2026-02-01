package com.btc.collector.analysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalPattern {

    private MarketSnapshot snapshot;

    // What happened in next 24 hours
    private BigDecimal maxProfitPct24h;
    private int hoursToMax;

    public boolean isProfitable() {
        return maxProfitPct24h != null && maxProfitPct24h.compareTo(BigDecimal.ZERO) > 0;
    }
}
