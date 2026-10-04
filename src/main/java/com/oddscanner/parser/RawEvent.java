package com.oddscanner.parser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record RawEvent(
        String externalId,
        String sportName,
        String leagueName,
        String team1,
        String team2,
        LocalDateTime startsAt,
        List<RawMarket> markets,
        String eventUrl  // новое поле

) {
    public record RawMarket(
            String marketType, // например, "1X2", "Total"
            List<RawOutcome> outcomes
    ) {}

    public record RawOutcome(
            String name, // например, "П1", "Х", "П2"
            BigDecimal odds
    ) {}
}