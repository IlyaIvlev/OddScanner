package com.oddscanner.service;

import com.oddscanner.generated.Tables;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ArbitrageService {

    private final DSLContext dsl;
    private final TeamMappingService teamMappingService;

    public ArbitrageService(DSLContext dsl, TeamMappingService teamMappingService) {
        this.dsl = dsl;
        this.teamMappingService = teamMappingService;
    }

    public List<ArbitrageOpportunity> findArbitrages() {
        log.info("[Arbitrage] Начинаю поиск вилок...");

        List<ArbitrageOpportunity> opportunities = new ArrayList<>();

        // Получаем события Polymarket (bookmaker_id = 10)
        Result<Record> polymarketEvents = dsl.select()
                .from(Tables.EVENTS)
                .join(Tables.BOOKMAKERS).on(Tables.BOOKMAKERS.ID.eq(Tables.EVENTS.BOOKMAKER_ID))
                .where(Tables.BOOKMAKERS.CODE.eq("POLYMARKET"))
                .fetch();

        // Получаем события Fonbet (bookmaker_id = 1)
        Result<Record> fonbetEvents = dsl.select()
                .from(Tables.EVENTS)
                .join(Tables.BOOKMAKERS).on(Tables.BOOKMAKERS.ID.eq(Tables.EVENTS.BOOKMAKER_ID))
                .where(Tables.BOOKMAKERS.CODE.eq("FONBET"))
                .fetch();

        log.info("[Arbitrage] Polymarket событий: {}, Fonbet событий: {}",
                polymarketEvents.size(), fonbetEvents.size());

        int comparedCount = 0;

        for (Record pEvent : polymarketEvents) {
            Long pId = pEvent.get(Tables.EVENTS.ID);
            String pHome = pEvent.get(Tables.EVENTS.HOME_TEAM);
            String pAway = pEvent.get(Tables.EVENTS.AWAY_TEAM);
            LocalDateTime pStartTime = pEvent.get(Tables.EVENTS.START_TIME);

            for (Record fEvent : fonbetEvents) {
                Long fId = fEvent.get(Tables.EVENTS.ID);
                String fHome = fEvent.get(Tables.EVENTS.HOME_TEAM);
                String fAway = fEvent.get(Tables.EVENTS.AWAY_TEAM);
                LocalDateTime fStartTime = fEvent.get(Tables.EVENTS.START_TIME);

                comparedCount++;

                if (isSameMatch(pHome, pAway, fHome, fAway, pStartTime, fStartTime)) {
                    List<ArbitrageOpportunity> arbs = findArbitrageForMatch(pId, fId, pHome, pAway);
                    opportunities.addAll(arbs);
                }
            }
        }

        log.info("[Arbitrage] Сравнено пар: {}, найдено вилок: {}", comparedCount, opportunities.size());

        // Логируем найденные вилки
        for (ArbitrageOpportunity arb : opportunities) {
            log.info("[Arbitrage] ✅ ВИЛКА: {} vs {} | {} ({}) + {} ({}) = {}%",
                    arb.getHomeTeam(), arb.getAwayTeam(),
                    arb.getBookmaker1() + ":" + arb.getOutcome1(), arb.getOdds1(),
                    arb.getBookmaker2() + ":" + arb.getOutcome2(), arb.getOdds2(),
                    arb.getProfitPercent());
        }

        return opportunities;
    }

    private boolean isSameMatch(String pHome, String pAway, String fHome, String fAway,
                                LocalDateTime pTime, LocalDateTime fTime) {
        // Проверяем время (разница не больше 2 часов)
        if (Math.abs(pTime.toEpochSecond(java.time.ZoneOffset.UTC) -
                fTime.toEpochSecond(java.time.ZoneOffset.UTC)) > 7200) {
            return false;
        }

        // Прямое совпадение
        boolean directMatch = (pHome.equalsIgnoreCase(fHome) && pAway.equalsIgnoreCase(fAway)) ||
                (pHome.equalsIgnoreCase(fAway) && pAway.equalsIgnoreCase(fHome));

        if (directMatch) return true;

        // Через маппинг
        boolean mappedMatch = (teamMappingService.isSameTeam(pHome, fHome) &&
                teamMappingService.isSameTeam(pAway, fAway)) ||
                (teamMappingService.isSameTeam(pHome, fAway) &&
                        teamMappingService.isSameTeam(pAway, fHome));

        return mappedMatch;
    }

    private List<ArbitrageOpportunity> findArbitrageForMatch(Long pEventId, Long fEventId, String home, String away) {
        List<ArbitrageOpportunity> opportunities = new ArrayList<>();

        var pOdds = dsl.select(Tables.MARKETS.MARKET_TYPE, Tables.OUTCOMES.OUTCOME_NAME, Tables.OUTCOMES.ODDS)
                .from(Tables.OUTCOMES)
                .join(Tables.MARKETS).on(Tables.MARKETS.ID.eq(Tables.OUTCOMES.MARKET_ID))
                .where(Tables.MARKETS.EVENT_ID.eq(pEventId))
                .fetch();

        var fOdds = dsl.select(Tables.MARKETS.MARKET_TYPE, Tables.OUTCOMES.OUTCOME_NAME, Tables.OUTCOMES.ODDS)
                .from(Tables.OUTCOMES)
                .join(Tables.MARKETS).on(Tables.MARKETS.ID.eq(Tables.OUTCOMES.MARKET_ID))
                .where(Tables.MARKETS.EVENT_ID.eq(fEventId))
                .fetch();

        for (var pOdd : pOdds) {
            String pMarketType = pOdd.get(Tables.MARKETS.MARKET_TYPE);
            String pOutcome = pOdd.get(Tables.OUTCOMES.OUTCOME_NAME);
            BigDecimal pOddsValue = pOdd.get(Tables.OUTCOMES.ODDS);

            // ФИЛЬТР: Игнорируем мусорные коэффициенты от суб-рынков (точный счет, первый тайм и т.д.)
            if (pOddsValue.doubleValue() > 10.0 || pOddsValue.doubleValue() < 1.15) {
                continue;
            }
            if (!"1X2".equals(pMarketType) && !"MatchWinner".equals(pMarketType)) continue;

            for (var fOdd : fOdds) {
                String fMarketType = fOdd.get(Tables.MARKETS.MARKET_TYPE);
                String fOutcome = fOdd.get(Tables.OUTCOMES.OUTCOME_NAME);
                BigDecimal fOddsValue = fOdd.get(Tables.OUTCOMES.ODDS);

                if (!"1X2".equals(fMarketType)) continue;

                // ПРАВИЛЬНАЯ ЛОГИКА для Polymarket (Yes/No) и Fonbet (1X2):
                // Yes (Франция выигрывает) противостоит П2 (Испания выигрывает) или Ничья
                // No (Франция НЕ выигрывает) противостоит П1 (Франция выигрывает)
                boolean isOpposite = false;
                if (pOutcome.equalsIgnoreCase("Yes") && (fOutcome.equalsIgnoreCase("П2") || fOutcome.equalsIgnoreCase("Ничья"))) {
                    isOpposite = true;
                } else if (pOutcome.equalsIgnoreCase("No") && fOutcome.equalsIgnoreCase("П1")) {
                    isOpposite = true;
                }

                if (isOpposite) {
                    double profit = calculateProfit(pOddsValue.doubleValue(), fOddsValue.doubleValue());

                    if (profit > 1.0) { // Минимальная прибыль 1%
                        opportunities.add(new ArbitrageOpportunity(
                                home, away, "Polymarket", "Fonbet",
                                pOutcome, fOutcome, pOddsValue, fOddsValue,
                                BigDecimal.valueOf(profit)
                        ));

                        log.info("🎯 НАЙДЕНА ВИЛКА: {} vs {} | {} ({}) + {} ({}) = {}%",
                                home, away, pOutcome, pOddsValue, fOutcome, fOddsValue, profit);
                    }
                }
            }
        }
        return opportunities;
    }


    private boolean isOppositeOutcome(String outcome1, String outcome2) {
        if (outcome1 == null || outcome2 == null) return false;

        String o1 = outcome1.toUpperCase().trim();
        String o2 = outcome2.toUpperCase().trim();

        // Прямое совпадение
        if (o1.equals(o2)) return false;

        // П1 vs П2 (Fonbet)
        if ((o1.equals("П1") && o2.equals("П2")) || (o1.equals("П2") && o2.equals("П1"))) {
            return true;
        }

        // Yes vs No (Polymarket)
        if ((o1.equals("YES") && o2.equals("NO")) || (o1.equals("NO") && o2.equals("YES"))) {
            return true;
        }

        // Да vs Нет (русский)
        if ((o1.equals("ДА") && o2.equals("НЕТ")) || (o1.equals("НЕТ") && o2.equals("ДА"))) {
            return true;
        }

        // Home vs Away
        if ((o1.equals("HOME") && o2.equals("AWAY")) || (o1.equals("AWAY") && o2.equals("HOME"))) {
            return true;
        }

        // 1 vs 2
        if ((o1.equals("1") && o2.equals("2")) || (o1.equals("2") && o2.equals("1"))) {
            return true;
        }

        return false;
    }

    private double calculateProfit(double odds1, double odds2) {
        if (odds1 <= 0 || odds2 <= 0) return 0;

        double impliedProb1 = 1.0 / odds1;
        double impliedProb2 = 1.0 / odds2;
        double totalProb = impliedProb1 + impliedProb2;

        if (totalProb < 1.0) {
            return (1.0 - totalProb) * 100;
        }

        return 0;
    }

    @Data
    @AllArgsConstructor
    public static class ArbitrageOpportunity {
        private String homeTeam;
        private String awayTeam;
        private String bookmaker1;
        private String bookmaker2;
        private String outcome1;
        private String outcome2;
        private BigDecimal odds1;
        private BigDecimal odds2;
        private BigDecimal profitPercent;
    }
}