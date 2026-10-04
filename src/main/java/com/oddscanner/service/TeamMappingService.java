package com.oddscanner.service;

import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;  // ← ИСПРАВЛЕНО: было javax.annotation
import java.util.HashMap;
import java.util.Map;

@Service
public class TeamMappingService {

    private final Map<String, String> codeToRussian = new HashMap<>();
    private final Map<String, String> russianToCode = new HashMap<>();

    @PostConstruct
    public void init() {
        // Polymarket code -> Русское название (Fonbet)
        codeToRussian.put("FRA", "Франция");
        codeToRussian.put("ESP", "Испания");
        codeToRussian.put("ENG", "Англия");
        codeToRussian.put("ARG", "Аргентина");
        codeToRussian.put("BRA", "Бразилия");
        codeToRussian.put("GER", "Германия");
        codeToRussian.put("ITA", "Италия");
        codeToRussian.put("POR", "Португалия");
        codeToRussian.put("NED", "Нидерланды");
        codeToRussian.put("BEL", "Бельгия");
        codeToRussian.put("CRO", "Хорватия");
        codeToRussian.put("SUI", "Швейцария");
        codeToRussian.put("MAR", "Марокко");
        codeToRussian.put("NOR", "Норвегия");
        codeToRussian.put("URU", "Уругвай");
        codeToRussian.put("COL", "Колумбия");
        codeToRussian.put("JPN", "Япония");
        codeToRussian.put("KOR", "Южная Корея");
        codeToRussian.put("MEX", "Мексика");
        codeToRussian.put("USA", "США");
        codeToRussian.put("CAN", "Канада");
        codeToRussian.put("AUS", "Австралия");
        codeToRussian.put("EGY", "Египет");
        codeToRussian.put("TUN", "Тунис");
        codeToRussian.put("ALG", "Алжир");
        codeToRussian.put("NGA", "Нигерия");
        codeToRussian.put("SEN", "Сенегал");
        codeToRussian.put("GHA", "Гана");
        codeToRussian.put("CMR", "Камерун");
        codeToRussian.put("CIV", "Кот-д'Ивуар");
        codeToRussian.put("ZAF", "ЮАР");
        codeToRussian.put("NZL", "Новая Зеландия");
        codeToRussian.put("CRC", "Коста-Рика");
        codeToRussian.put("PAN", "Панама");
        codeToRussian.put("ECU", "Эквадор");
        codeToRussian.put("PER", "Перу");
        codeToRussian.put("CHI", "Чили");
        codeToRussian.put("PAR", "Парагвай");
        codeToRussian.put("BOL", "Боливия");
        codeToRussian.put("VEN", "Венесуэла");
        codeToRussian.put("RUS", "Россия");
        codeToRussian.put("UKR", "Украина");
        codeToRussian.put("POL", "Польша");
        codeToRussian.put("CZE", "Чехия");
        codeToRussian.put("SVK", "Словакия");
        codeToRussian.put("HUN", "Венгрия");
        codeToRussian.put("ROU", "Румыния");
        codeToRussian.put("BUL", "Болгария");
        codeToRussian.put("SRB", "Сербия");
        codeToRussian.put("BIH", "Босния и Герцеговина");
        codeToRussian.put("MNE", "Черногория");
        codeToRussian.put("ALB", "Албания");
        codeToRussian.put("MKD", "Северная Македония");
        codeToRussian.put("GRE", "Греция");
        codeToRussian.put("TUR", "Турция");
        codeToRussian.put("SCO", "Шотландия");
        codeToRussian.put("WAL", "Уэльс");
        codeToRussian.put("NIR", "Северная Ирландия");
        codeToRussian.put("IRL", "Ирландия");
        codeToRussian.put("ISL", "Исландия");
        codeToRussian.put("FIN", "Финляндия");
        codeToRussian.put("SWE", "Швеция");
        codeToRussian.put("DEN", "Дания");
        codeToRussian.put("AUT", "Австрия");

        // Обратный маппинг
        codeToRussian.forEach((code, russian) -> russianToCode.put(russian, code));
    }

    public String toRussian(String code) {
        if (code == null) return null;
        return codeToRussian.getOrDefault(code.toUpperCase(), code);
    }

    public String toCode(String russianName) {
        if (russianName == null) return null;
        return russianToCode.get(russianName);
    }

    public boolean isSameTeam(String team1, String team2) {
        if (team1 == null || team2 == null) return false;

        if (team1.equalsIgnoreCase(team2)) return true;

        String russian = toRussian(team1);
        if (russian != null && russian.equalsIgnoreCase(team2)) return true;

        String code = toCode(team1);
        if (code != null && code.equalsIgnoreCase(team2)) return true;

        return false;
    }
}