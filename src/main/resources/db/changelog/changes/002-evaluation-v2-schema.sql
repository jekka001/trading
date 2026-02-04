--liquibase formatted sql

--changeset btc-collector:8 author:system
--comment: Add confidence evaluation fields to alert_history table
ALTER TABLE alert_history ADD COLUMN confidence_score INTEGER;
ALTER TABLE alert_history ADD COLUMN confidence_evaluated BOOLEAN DEFAULT FALSE;

--changeset btc-collector:9 author:system
--comment: Add Rate2.0 fields to strategy_stats table
ALTER TABLE strategy_stats ADD COLUMN confidence_positive INTEGER DEFAULT 0;
ALTER TABLE strategy_stats ADD COLUMN confidence_negative INTEGER DEFAULT 0;
ALTER TABLE strategy_stats ADD COLUMN confidence_neutral INTEGER DEFAULT 0;
ALTER TABLE strategy_stats ADD COLUMN confidence_score INTEGER DEFAULT 0;
ALTER TABLE strategy_stats ADD COLUMN rate2 DECIMAL(5,2);

--changeset btc-collector:10 author:system
--comment: Add profit USD field to alert_history table
ALTER TABLE alert_history ADD COLUMN actual_profit_usd DECIMAL(10,4);

--changeset btc-collector:11 author:system
--comment: Add profit evaluation fields to strategy_stats table
ALTER TABLE strategy_stats ADD COLUMN total_pnl_usd DECIMAL(12,4) DEFAULT 0;
ALTER TABLE strategy_stats ADD COLUMN profit_trades_count INTEGER DEFAULT 0;
ALTER TABLE strategy_stats ADD COLUMN sum_profit_pct DECIMAL(12,4) DEFAULT 0;
