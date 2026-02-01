CREATE TABLE IF NOT EXISTS btc_candle_15m (
    open_time TIMESTAMP PRIMARY KEY,
    open_price  DECIMAL(18,8) NOT NULL,
    high_price  DECIMAL(18,8) NOT NULL,
    low_price   DECIMAL(18,8) NOT NULL,
    close_price DECIMAL(18,8) NOT NULL,
    volume      DECIMAL(18,8) NOT NULL,
    close_time  TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_btc_candle_15m_close_time ON btc_candle_15m(close_time);

CREATE TABLE IF NOT EXISTS btc_indicator_15m (
    open_time   TIMESTAMP PRIMARY KEY REFERENCES btc_candle_15m(open_time),
    ema_50      DECIMAL(18,8),
    ema_200     DECIMAL(18,8),
    rsi_14      DECIMAL(10,4),
    atr_14      DECIMAL(18,8),
    bb_upper    DECIMAL(18,8),
    bb_middle   DECIMAL(18,8),
    bb_lower    DECIMAL(18,8),
    avg_volume_20 DECIMAL(18,8),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS strategy_stats (
    strategy_name       VARCHAR(100) PRIMARY KEY,
    total_predictions   INTEGER DEFAULT 0,
    successful_predictions INTEGER DEFAULT 0,
    failed_predictions  INTEGER DEFAULT 0,
    score               DECIMAL(10,2) DEFAULT 0,
    weight              DECIMAL(5,4) DEFAULT 0.5,
    success_rate        DECIMAL(5,2) DEFAULT 50,
    degradation_alerted BOOLEAN DEFAULT FALSE,
    last_updated        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS pending_prediction (
    id                  BIGSERIAL PRIMARY KEY,
    strategy_id         VARCHAR(100) NOT NULL,
    entry_price         DECIMAL(18,8) NOT NULL,
    predicted_profit_pct DECIMAL(10,4) NOT NULL,
    predicted_hours     INTEGER NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    evaluate_at         TIMESTAMP NOT NULL,
    evaluated           BOOLEAN DEFAULT FALSE,
    success             BOOLEAN,
    actual_max_price    DECIMAL(18,8),
    actual_profit_pct   DECIMAL(10,4)
);

CREATE INDEX IF NOT EXISTS idx_pending_prediction_evaluate ON pending_prediction(evaluate_at, evaluated);

CREATE TABLE IF NOT EXISTS historical_pattern (
    id                  BIGSERIAL PRIMARY KEY,
    candle_time         TIMESTAMP NOT NULL,
    strategy_id         VARCHAR(100),
    rsi                 DECIMAL(10,4),
    ema_50              DECIMAL(18,8),
    ema_200             DECIMAL(18,8),
    volume_change_pct   DECIMAL(10,4),
    price_change_1h     DECIMAL(10,4),
    price_change_4h     DECIMAL(10,4),
    max_profit_pct      DECIMAL(10,4),
    hours_to_max        INTEGER,
    evaluated           BOOLEAN DEFAULT FALSE,
    evaluated_at        TIMESTAMP,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_historical_pattern_candle UNIQUE (candle_time)
);

CREATE INDEX IF NOT EXISTS idx_historical_pattern_candle_time ON historical_pattern(candle_time);
CREATE INDEX IF NOT EXISTS idx_historical_pattern_strategy ON historical_pattern(strategy_id);
CREATE INDEX IF NOT EXISTS idx_historical_pattern_evaluated ON historical_pattern(evaluated);

CREATE TABLE IF NOT EXISTS alert_history (
    id                      BIGSERIAL PRIMARY KEY,
    alert_time              TIMESTAMP NOT NULL,
    snapshot_group_id       VARCHAR(36),
    strategy_id             VARCHAR(100) NOT NULL,
    base_probability        DECIMAL(10,4),
    final_probability       DECIMAL(10,4),
    strategy_weight         DECIMAL(5,4),
    historical_factor       DECIMAL(5,4),
    current_price           DECIMAL(18,8) NOT NULL,
    predicted_profit_pct    DECIMAL(10,4) NOT NULL,
    target_price            DECIMAL(18,8) NOT NULL,
    predicted_hours         INTEGER NOT NULL,
    matched_patterns        INTEGER,
    rsi                     DECIMAL(10,4),
    ema_trend               VARCHAR(10),
    volume_bucket           VARCHAR(10),
    evaluate_at             TIMESTAMP NOT NULL,
    evaluated               BOOLEAN DEFAULT FALSE,
    success                 BOOLEAN,
    actual_max_price        DECIMAL(18,8),
    actual_profit_pct       DECIMAL(10,4),
    evaluated_at            TIMESTAMP,
    sent_to_telegram        BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_alert_history_time ON alert_history(alert_time);
CREATE INDEX IF NOT EXISTS idx_alert_history_evaluate ON alert_history(evaluate_at, evaluated);
CREATE INDEX IF NOT EXISTS idx_alert_history_strategy ON alert_history(strategy_id);
CREATE INDEX IF NOT EXISTS idx_alert_history_snapshot ON alert_history(snapshot_group_id);

CREATE TABLE IF NOT EXISTS market_regime (
    id                  BIGSERIAL PRIMARY KEY,
    timestamp           TIMESTAMP NOT NULL,
    regime_type         VARCHAR(20) NOT NULL,
    confidence          DOUBLE PRECISION NOT NULL,
    matched_conditions  INTEGER,
    total_conditions    INTEGER
);

CREATE INDEX IF NOT EXISTS idx_regime_timestamp ON market_regime(timestamp);
CREATE INDEX IF NOT EXISTS idx_regime_type ON market_regime(regime_type);
