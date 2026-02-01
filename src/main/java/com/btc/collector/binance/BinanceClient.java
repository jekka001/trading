package com.btc.collector.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class BinanceClient {

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "15m";
    private static final int DEFAULT_LIMIT = 1000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${binance.api.base-url}")
    private String baseUrl;

    @Value("${binance.api.klines-endpoint}")
    private String klinesEndpoint;

    public List<CandleDTO> fetchCandles(Long startTime, int limit) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + klinesEndpoint)
                    .queryParam("symbol", SYMBOL)
                    .queryParam("interval", INTERVAL)
                    .queryParam("limit", Math.min(limit, DEFAULT_LIMIT));

            if (startTime != null) {
                builder.queryParam("startTime", startTime);
            }

            String url = builder.toUriString();
            log.debug("Fetching candles from: {}", url);

            String response = restTemplate.getForObject(url, String.class);

            if (response == null || response.isBlank()) {
                log.warn("Empty response from Binance");
                return Collections.emptyList();
            }

            return parseResponse(response);

        } catch (RestClientException e) {
            log.error("Error fetching candles from Binance: {}", e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Unexpected error fetching candles: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<CandleDTO> fetchCandles(Long startTime) {
        return fetchCandles(startTime, DEFAULT_LIMIT);
    }

    public List<CandleDTO> fetchLatestCandles() {
        return fetchCandles(null, DEFAULT_LIMIT);
    }

    private List<CandleDTO> parseResponse(String response) {
        List<CandleDTO> candles = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(response);

            if (root.isArray()) {
                for (JsonNode node : root) {
                    CandleDTO candle = CandleDTO.builder()
                            .openTime(millisToDateTime(node.get(0).asLong()))
                            .openPrice(new BigDecimal(node.get(1).asText()))
                            .highPrice(new BigDecimal(node.get(2).asText()))
                            .lowPrice(new BigDecimal(node.get(3).asText()))
                            .closePrice(new BigDecimal(node.get(4).asText()))
                            .volume(new BigDecimal(node.get(5).asText()))
                            .closeTime(millisToDateTime(node.get(6).asLong()))
                            .build();
                    candles.add(candle);
                }
            }

        } catch (Exception e) {
            log.error("Error parsing Binance response: {}", e.getMessage());
        }

        return candles;
    }

    private LocalDateTime millisToDateTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    public static long dateTimeToMillis(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
