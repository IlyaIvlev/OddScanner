package com.oddscanner.service;

import com.oddscanner.generated.tables.records.EventsRecord;
import org.springframework.stereotype.Component;

@Component
public class MatchComparator {

    public boolean isSameMatch(EventsRecord e1, EventsRecord e2) {
        // 1. Сначала проверяем лигу (грубая нормализация)
        String l1 = normalizeLeague(e1.getLeague());
        String l2 = normalizeLeague(e2.getLeague());

        if (!l1.equals(l2)) {
            return false;
        }

        // 2. Сравниваем команды без учета времени
        String h1 = normalizeTeam(e1.getHomeTeam());
        String a1 = normalizeTeam(e1.getAwayTeam());
        String h2 = normalizeTeam(e2.getHomeTeam());
        String a2 = normalizeTeam(e2.getAwayTeam());

        // Прямое совпадение или зеркальное
        return (h1.equals(h2) && a1.equals(a2)) || (h1.equals(a2) && a1.equals(h2));
    }

    private String normalizeLeague(String league) {
        if (league == null) return "";
        return league.toLowerCase()
                .replaceAll("[^a-zа-яё0-9]", "")
                .trim();
    }

    private String normalizeTeam(String team) {
        if (team == null) return "";
        return team.toLowerCase()
                .replaceAll("\\b(fc|cf|sc|club|team|united|utd|womens?|women|men's?|men|фк|ск|жк|мфк)\\b", "")
                .replaceAll("[^a-zа-яё0-9]", "")
                .trim();
    }
}