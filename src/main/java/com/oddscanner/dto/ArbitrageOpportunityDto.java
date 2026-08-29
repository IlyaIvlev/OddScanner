package com.oddscanner.dto;

import java.math.BigDecimal;

public record ArbitrageOpportunityDto(
        String matchName,
        String marketType,
        double margin,
        String bookmaker1,
        BigDecimal odds1,
        String eventUrl1,
        String bookmaker2,
        BigDecimal odds2,
        String eventUrl2
) {}