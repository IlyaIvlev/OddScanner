package com.oddscanner.dto;

import java.math.BigDecimal;

public record ArbitrageOpportunityDto(
        String matchName,
        String marketType,
        double margin,       // Маржа (например, 0.985)
        double profitPercent,// Доходность в процентах (например, 11.53)
        OutcomeInfo outcome1,
        OutcomeInfo outcomeX,
        OutcomeInfo outcome2
) {
    /**
     * Информация по конкретному исходу для арбитражной ставки
     */
    public record OutcomeInfo(
            String outcomeName,   // "1", "X" или "2"
            BigDecimal odds,      // Коэффициент
            String bookmakerName, // Название букмекера, у которого берем этот кэф
            String eventUrl,      // Ссылка на событие у этого букмекера
            double stakeAmount    // Рекомендуемая сумма ставки (при банке 100)
    ) {}
}