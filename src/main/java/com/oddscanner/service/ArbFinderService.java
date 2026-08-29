package com.oddscanner.service;

import com.oddscanner.dto.ArbitrageOpportunityDto;
import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.Result;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArbFinderService {

    private final DSLContext dsl;

    public List<ArbitrageOpportunityDto> findArbitrages() {
        log.info("🔍 Запуск поиска вилок...");
        long start = System.currentTimeMillis();

        Result<EventsRecord> events = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.STATUS.eq("SCHEDULED"))
                .fetch();

        log.info("📊 Загружено {} событий", events.size());

        // Группируем события по нормализованному названию матча
        Map<String, List<EventsRecord>> matchGroups = events.stream()
                .collect(Collectors.groupingBy(e -> normalizeMatchName(e.getHomeTeam(), e.getAwayTeam())));

        List<ArbitrageOpportunityDto> opportunities = new ArrayList<>();

        for (List<EventsRecord> group : matchGroups.values()) {
            if (group.size() < 2) continue;

            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    EventsRecord e1 = group.get(i);
                    EventsRecord e2 = group.get(j);

                    // Пропускаем одного букмекера
                    if (Objects.equals(e1.getBookmakerId(), e2.getBookmakerId())) continue;

                    checkMarkets(e1, e2, opportunities);
                }
            }
        }

        long duration = System.currentTimeMillis() - start;
        log.info("✅ Поиск завершен за {} мс. Найдено {} вилок.", duration, opportunities.size());
        return opportunities;
    }

    private void checkMarkets(EventsRecord e1, EventsRecord e2, List<ArbitrageOpportunityDto> list) {
        Map<String, Map<String, BigDecimal>> m1 = getEventOdds(e1.getId());
        Map<String, Map<String, BigDecimal>> m2 = getEventOdds(e2.getId());

        for (String marketType : m1.keySet()) {
            if (!m2.containsKey(marketType)) continue;

            Map<String, BigDecimal> odds1 = m1.get(marketType);
            Map<String, BigDecimal> odds2 = m2.get(marketType);

            if ("1X2".equals(marketType)) {
                BigDecimal k1 = max(odds1.get("1"), odds2.get("1"));
                BigDecimal kX = max(odds1.get("X"), odds2.get("X"));
                BigDecimal k2 = max(odds1.get("2"), odds2.get("2"));

                if (k1 != null && kX != null && k2 != null) {
                    double margin = 1.0 / k1.doubleValue() + 1.0 / kX.doubleValue() + 1.0 / k2.doubleValue();

                    if (margin < 1.0) {
                        double profit = (1.0 / margin - 1) * 100;

                        // ВАЖНО: Передаем ВСЕ 9 параметров, включая URL
                        list.add(new ArbitrageOpportunityDto(
                                e1.getHomeTeam() + " vs " + e1.getAwayTeam(),
                                marketType,
                                Math.round(profit * 100.0) / 100.0,
                                getBookmakerName(e1.getBookmakerId()),
                                k1,
                                e1.getEventUrl(),   // <-- Добавлено
                                getBookmakerName(e2.getBookmakerId()),
                                k2,
                                e2.getEventUrl()    // <-- Добавлено
                        ));
                    }
                }
            }
        }
    }

    private Map<String, Map<String, BigDecimal>> getEventOdds(Long eventId) {
        Result<Record3<String, String, BigDecimal>> records = dsl
                .select(Tables.MARKETS.MARKET_TYPE, Tables.OUTCOMES.OUTCOME_NAME, Tables.OUTCOMES.ODDS)
                .from(Tables.MARKETS)
                .join(Tables.OUTCOMES).on(Tables.MARKETS.ID.eq(Tables.OUTCOMES.MARKET_ID))
                .where(Tables.MARKETS.EVENT_ID.eq(eventId))
                .and(Tables.OUTCOMES.IS_ACTIVE.eq(true))
                .fetch();

        Map<String, Map<String, BigDecimal>> result = new HashMap<>();
        for (var r : records) {
            String type = r.get(Tables.MARKETS.MARKET_TYPE);
            String outcome = r.get(Tables.OUTCOMES.OUTCOME_NAME);
            BigDecimal odds = r.get(Tables.OUTCOMES.ODDS);

            String normalizedOutcome = switch (outcome.toUpperCase()) {
                case "П1", "1", "HOME" -> "1";
                case "Х", "X", "DRAW" -> "X";
                case "П2", "2", "AWAY" -> "2";
                default -> null;
            };

            if (normalizedOutcome != null && "1X2".equals(type)) {
                result.computeIfAbsent(type, k -> new HashMap<>())
                        .merge(normalizedOutcome, odds, BigDecimal::max);
            }
        }
        return result;
    }

    private String normalizeMatchName(String home, String away) {
        String h = home.toLowerCase().replaceAll("[^a-zа-яё0-9]", "").trim();
        String a = away.toLowerCase().replaceAll("[^a-zа-яё0-9]", "").trim();
        return h.compareTo(a) <= 0 ? h + "|" + a : a + "|" + h;
    }

    private BigDecimal max(BigDecimal... vals) {
        return Arrays.stream(vals)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private String getBookmakerName(Long bookmakerId) {
        if (bookmakerId == null) return "Unknown";
        return dsl.select(Tables.BOOKMAKERS.NAME)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.ID.eq(bookmakerId))
                .fetchOneInto(String.class);
    }
}