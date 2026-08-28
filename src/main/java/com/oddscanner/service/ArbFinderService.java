package com.oddscanner.service;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.Result;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArbFinderService {

    private final DSLContext dsl;
    private final MatchComparator matchComparator;
    private final OutcomeNormalizer normalizer;

    public List<ArbitrageOpportunity> findArbitrages() {
        log.info(" Запуск поиска вилок (оптимизированный режим)...");
        long start = System.currentTimeMillis();

        // Сортировка по времени обязательна для работы break по isTimeTooFar
        Result<EventsRecord> events = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.STATUS.eq("SCHEDULED"))
                .orderBy(Tables.EVENTS.START_TIME.asc())
                .fetch();

        log.info("📊 Загружено {} событий для анализа", events.size());

        List<ArbitrageOpportunity> opportunities = new ArrayList<>();
        int checkedPairs = 0;

        for (int i = 0; i < events.size(); i++) {
            EventsRecord e1 = events.get(i);

            if (e1.getLeague() == null || e1.getLeague().isBlank()) continue;

            for (int j = i + 1; j < events.size(); j++) {
                EventsRecord e2 = events.get(j);

                // ОПТИМИЗАЦИЯ 1: Если время слишком далеко - прерываем внутренний цикл
                // Но делаем исключение для пар с участием Winline, так как у него кривое время
                boolean isWinlinePair =
                        ("WINLINE".equals(e1.getBookmakerId()) || "WINLINE".equals(e2.getBookmakerId()));

                // Используем matchComparator.isSameMatch как замену isTimeTooFar,
                // но здесь нам нужна именно проверка времени.
                // Поскольку isTimeTooFar удален, реализуем простую проверку прямо здесь:
                long diffMinutes = Math.abs(Duration.between(e1.getStartTime(), e2.getStartTime()).toMinutes());

                if (!isWinlinePair && diffMinutes > 30) {
                    break;
                }

                // ОПТИМИЗАЦИЯ 2: Если лиги разные - пропускаем
                if (!Objects.equals(normalizeLeagueForCompare(e1.getLeague()), normalizeLeagueForCompare(e2.getLeague()))) {
                    continue;
                }

                // Проверяем совпадение команд и разных букмекеров
                if (matchComparator.isSameMatch(e1, e2) && !e1.getBookmakerId().equals(e2.getBookmakerId())) {
                    checkMarketsUniversal(e1, e2, opportunities);
                }

                checkedPairs++;
                if (checkedPairs % 5000 == 0) {
                    log.debug(" Проверено пар: {}, найдено вилок: {}", checkedPairs, opportunities.size());
                }
            }
        }

        long duration = System.currentTimeMillis() - start;
        log.info("✅ Поиск завершен за {} мс. Найдено {} вилок из {} проверенных пар.",
                duration, opportunities.size(), checkedPairs);

        return opportunities;
    }

    // Вспомогательный метод для сравнения лиг (можно вынести в отдельный класс позже)
    private String normalizeLeagueForCompare(String league) {
        if (league == null) return "";
        return league.toLowerCase()
                .replaceAll("[^a-zа-яё0-9]", "")
                .trim();
    }

    private void checkMarketsUniversal(EventsRecord e1, EventsRecord e2, List<ArbitrageOpportunity> list) {
        Map<String, Map<String, BigDecimal>> m1 = getNormalizedEventMap(e1.getId());
        Map<String, Map<String, BigDecimal>> m2 = getNormalizedEventMap(e2.getId());

        // Логируем только если у обоих есть хоть какие-то рынки после нормализации
        if (!m1.isEmpty() && !m2.isEmpty()) {
            log.debug("🔎 Сравниваем: {} vs {} | Лига: {} | Рынки F: {} | Рынки W: {}",
                    e1.getHomeTeam(), e2.getHomeTeam(), e1.getLeague(), m1.keySet(), m2.keySet());
        } else {
            // Если рынков нет - скорее всего парсер не смог их распарсить
            log.trace("️ Пустые рынки для пары: {} ({}) vs {} ({})",
                    e1.getHomeTeam(), e1.getBookmakerId(), e2.getHomeTeam(), e2.getBookmakerId());
        }

        for (String type : m1.keySet()) {
            if (m2.containsKey(type)) {
                double margin = calculateMarginByType(type, m1.get(type), m2.get(type));

                // Показываем все матчи с маржой близкой к вилке (< 1.05)
                if (margin < 1.05) {
                    log.info("🎯 Близкая ситуация ({}) для {} vs {}: Margin={}",
                            type, e1.getHomeTeam(), e2.getHomeTeam(), String.format("%.4f", margin));
                }

                if (margin < 1.0) {
                    addArb(list, e1, e2, type, margin);
                }
            }
        }
    }

    private double calculateMarginByType(String type, Map<String, BigDecimal> b1, Map<String, BigDecimal> b2) {
        return switch (type) {
            case OutcomeNormalizer.TYPE_1X2 -> calc1x2(b1, b2);
            case OutcomeNormalizer.TYPE_TOTAL -> calcOppositePairs(b1, b2, "OVER_", "UNDER_");
            case OutcomeNormalizer.TYPE_HANDICAP -> calcOppositeHandicaps(b1, b2);
            default -> 1.0;
        };
    }

    private double calc1x2(Map<String, BigDecimal> b1, Map<String, BigDecimal> b2) {
        BigDecimal k1 = max(b1.get("1"), b2.get("1"));
        BigDecimal kX = max(b1.get("X"), b2.get("X"));
        BigDecimal k2 = max(b1.get("2"), b2.get("2"));
        if (k1 == null || kX == null || k2 == null) return 1.0;
        return 1.0 / k1.doubleValue() + 1.0 / kX.doubleValue() + 1.0 / k2.doubleValue();
    }

    private double calcOppositePairs(Map<String, BigDecimal> b1, Map<String, BigDecimal> b2, String prefix1, String prefix2) {
        double minMargin = 1.0;
        for (String key : b1.keySet()) {
            if (key.startsWith(prefix1)) {
                String val = key.replace(prefix1, "");
                String opposite = prefix2 + val;

                BigDecimal o1 = b1.get(key);
                BigDecimal o2 = b2.get(opposite);

                if (o1 != null && o2 != null) {
                    double m = 1.0 / o1.doubleValue() + 1.0 / o2.doubleValue();
                    minMargin = Math.min(minMargin, m);
                }
            }
        }
        return minMargin;
    }

    private double calcOppositeHandicaps(Map<String, BigDecimal> b1, Map<String, BigDecimal> b2) {
        double minMargin = 1.0;
        for (String k1 : b1.keySet()) {
            for (String k2 : b2.keySet()) {
                if (isOppositeHandicapKeys(k1, k2)) {
                    BigDecimal o1 = b1.get(k1);
                    BigDecimal o2 = b2.get(k2);
                    if (o1 != null && o2 != null) {
                        double m = 1.0 / o1.doubleValue() + 1.0 / o2.doubleValue();
                        minMargin = Math.min(minMargin, m);
                    }
                }
            }
        }
        return minMargin;
    }

    private Map<String, Map<String, BigDecimal>> getNormalizedEventMap(Long eventId) {
        Result<Record3<String, String, BigDecimal>> records = dsl
                .select(Tables.MARKETS.MARKET_TYPE, Tables.OUTCOMES.OUTCOME_NAME, Tables.OUTCOMES.ODDS)
                .from(Tables.MARKETS)
                .join(Tables.OUTCOMES).on(Tables.MARKETS.ID.eq(Tables.OUTCOMES.MARKET_ID))
                .where(Tables.MARKETS.EVENT_ID.eq(eventId))
                .and(Tables.OUTCOMES.IS_ACTIVE.eq(true))
                .fetch();

        Map<String, Map<String, BigDecimal>> result = new HashMap<>();
        for (var r : records) {
            OutcomeNormalizer.MarketKey key = normalizer.normalize(
                    r.get(Tables.MARKETS.MARKET_TYPE),
                    r.get(Tables.OUTCOMES.OUTCOME_NAME)
            );

            if (key != null) {
                result.computeIfAbsent(key.type(), k -> new HashMap<>())
                        .merge(key.outcome(), r.get(Tables.OUTCOMES.ODDS), BigDecimal::max);
            }
        }
        return result;
    }

    private boolean isOppositeHandicapKeys(String k1, String k2) {
        try {
            String[] p1 = k1.split("_");
            String[] p2 = k2.split("_");
            if (p1.length < 2 || p2.length < 2) return false;

            int t1 = Integer.parseInt(p1[0].replace("H", ""));
            int t2 = Integer.parseInt(p2[0].replace("H", ""));

            double v1 = Double.parseDouble(p1[1]);
            double v2 = Double.parseDouble(p2[1]);

            return (t1 != t2) && (Math.abs(v1 + v2) < 0.01);
        } catch (Exception e) {
            return false;
        }
    }

    private void addArb(List<ArbitrageOpportunity> list, EventsRecord e1, EventsRecord e2, String market, double margin) {
        double profit = (1.0 / margin - 1) * 100;
        list.add(new ArbitrageOpportunity(e1, e2, market, Math.round(profit * 100.0) / 100.0));
    }

    private BigDecimal max(BigDecimal... vals) {
        return Arrays.stream(vals).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
    }

    public record ArbitrageOpportunity(EventsRecord e1, EventsRecord e2, String market, double profit) {
    }
}