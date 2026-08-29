package com.oddscanner.service;

import com.oddscanner.generated.tables.records.EventsRecord;
import org.springframework.stereotype.Component;

@Component
public class MatchComparator {

    public boolean isSameMatch(EventsRecord e1, EventsRecord e2) {
        String h1 = normalize(e1.getHomeTeam());
        String a1 = normalize(e1.getAwayTeam());
        String h2 = normalize(e2.getHomeTeam());
        String a2 = normalize(e2.getAwayTeam());

        // Прямое совпадение
        if (h1.equals(h2) && a1.equals(a2)) return true;

        // Обратное совпадение (редко, но бывает)
        if (h1.equals(a2) && a1.equals(h2)) return true;

        return false;
    }

    private String normalize(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("[^a-zа-яё0-9]", "") // Удаляем всё, кроме букв и цифр
                .trim();
    }
}