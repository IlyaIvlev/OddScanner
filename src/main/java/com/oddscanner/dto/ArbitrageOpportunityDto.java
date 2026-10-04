package com.oddscanner.dto;

import java.math.BigDecimal;

public record ArbitrageOpportunityDto(
        String matchName,
        String marketType,
        double margin,
        double profitPercent,
        OutcomeInfo outcome1,
        OutcomeInfo outcomeX,
        OutcomeInfo outcome2
) {
    public record OutcomeInfo(
            String outcomeName,
            BigDecimal odds,
            String bookmakerName,
            String eventUrl,
            double stakeAmount
    ) {}
}