package com.oddscanner.service;

import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OutcomeNormalizer {

    public static final String TYPE_1X2 = "1X2";
    public static final String TYPE_TOTAL = "TOTAL";
    public static final String TYPE_HANDICAP = "HANDICAP";

    public MarketKey normalize(String rawMarketType, String rawOutcomeName) {
        if (rawMarketType == null || rawOutcomeName == null) return null;

        String lowerType = rawMarketType.toLowerCase().trim();
        String lowerName = rawOutcomeName.toLowerCase().trim();

        // --- 1. Основной исход (1X2) ---
        // Поддерживаем разные названия: "1X2", "Main", "Основной исход", "Исход"
        if (lowerType.equals("1x2") ||
                lowerType.contains("main") ||
                lowerType.contains("основн") ||
                lowerType.equals("исход")) {

            String std = map1x2(lowerName);
            if (std != null) return new MarketKey(TYPE_1X2, std);
        }

        // --- 2. Тоталы ---
        if (lowerType.contains("total") || lowerType.contains("тотал")) {
            String std = mapTotal(rawOutcomeName);
            if (std != null) return new MarketKey(TYPE_TOTAL, std);
        }

        // --- 3. Форы ---
        if (lowerType.contains("handicap") || lowerType.contains("фора")) {
            String std = mapHandicap(rawOutcomeName);
            if (std != null) return new MarketKey(TYPE_HANDICAP, std);
        }

        return null;
    }

    private String map1x2(String name) {
        // Более широкие паттерны
        if (name.matches(".*(п1|^1$|home|победа 1|win 1).*")) return "1";
        if (name.matches(".*(х|ничья|draw|^x$|tie).*")) return "X";
        if (name.matches(".*(п2|^2$|^3$|away|победа 2|win 2).*")) return "2";
        return null;
    }

    private String mapTotal(String name) {
        // Ищем число (например, 2.5, 3, 2.75)
        Pattern p = Pattern.compile("(\\d+\\.?\\d*)");
        Matcher m = p.matcher(name);
        if (m.find()) {
            String val = m.group(1);
            String upperName = name.toUpperCase();

            // Проверяем на ТБ/Over или ТМ/Under
            boolean isOver = upperName.matches(".*(ТБ|OVER|БОЛЬШЕ|MORE).*");
            boolean isUnder = upperName.matches(".*(ТМ|UNDER|МЕНЬШЕ|LESS).*");

            if (isOver) return "OVER_" + val;
            if (isUnder) return "UNDER_" + val;

            // Если не указано направление, но есть число, иногда букмекеры дают два исхода подряд.
            // Но лучше вернуть null, чтобы не гадать.
        }
        return null;
    }

    private String mapHandicap(String name) {
        // Паттерн: Ф1 (-1.5), H1 -1.5, Handicap 1 (-1.5)
        // Группа 1: номер команды (1 или 2)
        // Группа 2: значение форы (-1.5, +1.5 и т.д.)
        Pattern p = Pattern.compile("(?:Ф|H|Team)?\\s*(\\d)\\s*[-–]?\\s*\\(([-+]?\\d+\\.?\\d*)\\)");
        Matcher m = p.matcher(name);

        if (!m.find()) {
            // Альтернативный формат: просто число с плюсом/минусом, если контекст понятен
            // Но это опасно, лучше строго искать привязку к команде
            return null;
        }

        String teamNum = m.group(1);
        String value = m.group(2);

        // Нормализуем значение: убираем плюс, оставляем минус
        if (value.startsWith("+")) {
            value = value.substring(1);
        }

        return "H" + teamNum + "_" + value;
    }

    public record MarketKey(String type, String outcome) {}
}