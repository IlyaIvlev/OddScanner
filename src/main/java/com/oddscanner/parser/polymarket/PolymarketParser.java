package com.oddscanner.parser.polymarket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oddscanner.parser.AbstractBookmakerParser;
import com.oddscanner.parser.RawEvent;
import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PolymarketParser extends AbstractBookmakerParser {

    private static final String API_BASE_URL = "https://gamma-api.polymarket.com";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final Pattern FULL_TEAMS_PATTERN = Pattern.compile(
            "([A-Z][a-zA-Z\\s\\-']+?)\\s+vs\\.?\\s+([A-Z][a-zA-Z\\s\\-']+?)(?:\\s+-|\\s*$|\\s+\\?)"
    );
    private static final Pattern SHORT_TEAMS_PATTERN = Pattern.compile(
            "([A-Z]{2,3})\\s+vs\\.?\\s+([A-Z]{2,3})"
    );

    public PolymarketParser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        super(meterRegistry, eventRepository);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "Polymarket";
    }

    @Override
    public List<RawEvent> doParse() throws Exception {
        List<RawEvent> allEvents = new ArrayList<>();
        int skippedCount = 0;

        // 1. Получаем теги
        List<String> sportsSlugs = fetchSportsSlugs();
        if (sportsSlugs.isEmpty()) {
            log.warn("[Polymarket] Не удалось получить теги динамически, используем fallback");
            sportsSlugs = getFallbackSlugs();
        } else {
            log.info("[Polymarket] Получено {} спортивных тегов", sportsSlugs.size());
        }

        Set<String> processedEventIds = new HashSet<>();

        // 2. Проходим по тегам
        for (String slug : sportsSlugs) {
            try {
                String url = API_BASE_URL + "/events?limit=100&tag_slug=" + slug + "&closed=false&active=true&order=endDate&ascending=true";
                JsonNode eventsArray = fetchJsonArray(url);

                if (eventsArray != null && eventsArray.isArray()) {
                    for (JsonNode eventNode : eventsArray) {
                        String eventId = eventNode.path("id").asText();
                        if (processedEventIds.contains(eventId)) continue;
                        processedEventIds.add(eventId);

                        // ИСПРАВЛЕНИЕ: parseEvent возвращает RawEvent или null
                        RawEvent event = parseEvent(eventNode);

                        if (event != null) {
                            allEvents.add(event);
                        } else {
                            skippedCount++;
                            if (log.isTraceEnabled()) {
                                log.trace("[Polymarket] Skip event: {}", eventId);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[Polymarket] Ошибка запроса тега {}: {}", slug, e.getMessage());
            }
        }

        // 3. Дедупликация
        allEvents = allEvents.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(RawEvent::externalId, e -> e, (a, b) -> a),
                        map -> new ArrayList<>(map.values())
                ));

        log.info("[Polymarket] Итого: {} событий распарсено, {} пропущено", allEvents.size(), skippedCount);

        if (!allEvents.isEmpty()) {
            eventRepository.saveEvents("Polymarket", allEvents);
            Set<String> activeExternalIds = allEvents.stream()
                    .map(RawEvent::externalId)
                    .collect(Collectors.toSet());
            eventRepository.markInactiveEvents("Polymarket", activeExternalIds);
            log.info("[Polymarket] Сохранено {} событий", allEvents.size());
        } else {
            log.warn("[Polymarket] Не найдено подходящих событий");
        }

        return allEvents;
    }

    /**
     * Парсит одно событие. Возвращает RawEvent если всё ок, null если событие не подходит.
     */
    private RawEvent parseEvent(JsonNode eventNode) {
        String eventId = eventNode.path("id").asText();
        String title = eventNode.path("title").asText();
        boolean closed = eventNode.path("closed").asBoolean(false);
        boolean active = eventNode.path("active").asBoolean(true);

        if (closed || !active) {
            return null;
        }

        LocalDateTime endTime = parseEndDate(eventNode);
        LocalDateTime now = LocalDateTime.now();
        if (endTime != null && (endTime.isBefore(now) || endTime.isAfter(now.plusDays(30)))) {
            return null;
        }

        String[] teams = extractTeams(title);

        JsonNode marketsNode = eventNode.path("markets");
        if (!marketsNode.isArray() || marketsNode.isEmpty()) {
            return null;
        }

        List<RawEvent.RawMarket> parsedMarkets = new ArrayList<>();

        for (JsonNode marketNode : marketsNode) {
            if (!marketNode.path("active").asBoolean(false) || marketNode.path("closed").asBoolean(false)) {
                continue;
            }

            List<String> outcomes = parseJsonArray(marketNode.path("outcomes"));
            List<String> prices = findPrices(marketNode);

            if (outcomes.size() != prices.size() || outcomes.isEmpty()) {
                continue;
            }

            RawEvent.RawMarket market = parseMarket(outcomes, prices);
            if (market != null) {
                parsedMarkets.add(market);
            }
        }

        if (parsedMarkets.isEmpty()) {
            return null;
        }

        String team1 = teams != null ? teams[0] : "Unknown";
        String team2 = teams != null ? teams[1] : "Unknown";

        if (teams == null && !parsedMarkets.isEmpty()) {
            RawEvent.RawMarket first = parsedMarkets.getFirst();
            if ("Moneyline".equals(first.marketType()) && first.outcomes().size() == 2) {
                team1 = first.outcomes().get(0).name();
                team2 = first.outcomes().get(1).name();
            }
        }

        String url = "https://polymarket.com/event/" + eventNode.path("slug").asText();
        LocalDateTime startTime = endTime != null ? endTime : now.plusDays(1);

        return new RawEvent(
                eventId, "Sport", "Polymarket",
                team1, team2, startTime, parsedMarkets, url
        );
    }

    private String[] extractTeams(String text) {
        if (text == null) return null;
        Matcher matcher = FULL_TEAMS_PATTERN.matcher(text);
        if (matcher.find()) {
            return new String[]{matcher.group(1).trim(), matcher.group(2).trim()};
        }
        matcher = SHORT_TEAMS_PATTERN.matcher(text);
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return null;
    }

    private RawEvent.RawMarket parseMarket(List<String> outcomes, List<String> prices) {

        // 1X2
        if (outcomes.size() == 3) {
            int drawIndex = -1;
            for (int i = 0; i < outcomes.size(); i++) {
                String o = outcomes.get(i).toLowerCase();
                if (o.equals("draw") || o.equals("ничья") || o.equals("tie")) {
                    drawIndex = i;
                    break;
                }
            }
            if (drawIndex != -1) {
                List<RawEvent.RawOutcome> marketOutcomes = new ArrayList<>();
                for (int i = 0; i < outcomes.size(); i++) {
                    double price = Double.parseDouble(prices.get(i));
                    if (price > 0.001 && price < 0.999) {
                        marketOutcomes.add(new RawEvent.RawOutcome(outcomes.get(i), convertPrice(price)));
                    }
                }
                if (marketOutcomes.size() == 3) {
                    return new RawEvent.RawMarket("1X2", marketOutcomes);
                }
            }
        }

        if (outcomes.size() == 2) {
            String o1 = outcomes.get(0);
            String o2 = outcomes.get(1);
            String o1Low = o1.toLowerCase();
            String o2Low = o2.toLowerCase();

            boolean isYesNo = (o1Low.equals("yes") && o2Low.equals("no"))
                    || (o1Low.equals("no") && o2Low.equals("yes"));
            boolean isOverUnder = (o1Low.equals("over") && o2Low.equals("under"))
                    || (o1Low.equals("under") && o2Low.equals("over"));

            // Total (Over/Under)
            if (isOverUnder) {
                double price1 = Double.parseDouble(prices.get(0));
                double price2 = Double.parseDouble(prices.get(1));
                if (price1 > 0.001 && price1 < 0.999 && price2 > 0.001 && price2 < 0.999) {
                    List<RawEvent.RawOutcome> marketOutcomes = new ArrayList<>();
                    if (o1Low.equals("over")) {
                        marketOutcomes.add(new RawEvent.RawOutcome("Over", convertPrice(price1)));
                        marketOutcomes.add(new RawEvent.RawOutcome("Under", convertPrice(price2)));
                    } else {
                        marketOutcomes.add(new RawEvent.RawOutcome("Over", convertPrice(price2)));
                        marketOutcomes.add(new RawEvent.RawOutcome("Under", convertPrice(price1)));
                    }
                    return new RawEvent.RawMarket("Total", marketOutcomes);
                }
            }

            // Moneyline (названия команд — KBO, NPB, CPBL, NFL и т.д.)
            if (!isYesNo && !isOverUnder) {
                double price1 = Double.parseDouble(prices.get(0));
                double price2 = Double.parseDouble(prices.get(1));
                // Расширенный фильтр: принимаем любые цены от 0.001 до 0.999
                if (price1 > 0.001 && price1 < 0.999 && price2 > 0.001 && price2 < 0.999) {
                    List<RawEvent.RawOutcome> marketOutcomes = new ArrayList<>();
                    marketOutcomes.add(new RawEvent.RawOutcome(o1, convertPrice(price1)));
                    marketOutcomes.add(new RawEvent.RawOutcome(o2, convertPrice(price2)));
                    return new RawEvent.RawMarket("Moneyline", marketOutcomes);
                }
            }

            // MatchWinner (Yes/No)
            if (isYesNo) {
                double price1 = Double.parseDouble(prices.get(0));
                double price2 = Double.parseDouble(prices.get(1));
                if (price1 > 0.001 && price1 < 0.999 && price2 > 0.001 && price2 < 0.999) {
                    List<RawEvent.RawOutcome> marketOutcomes = new ArrayList<>();
                    marketOutcomes.add(new RawEvent.RawOutcome(o1, convertPrice(price1)));
                    marketOutcomes.add(new RawEvent.RawOutcome(o2, convertPrice(price2)));
                    return new RawEvent.RawMarket("MatchWinner", marketOutcomes);
                }
            }
        }

        return null;
    }

    private List<String> fetchSportsSlugs() {
        try {
            String url = API_BASE_URL + "/sports";
            JsonNode sportsArray = fetchJsonArray(url);
            if (sportsArray != null && sportsArray.isArray()) {
                List<String> slugs = new ArrayList<>();
                for (JsonNode sport : sportsArray) {
                    // ИСПРАВЛЕНИЕ: читаем поле "sport" из ответа API
                    if (sport.has("sport")) {
                        slugs.add(sport.get("sport").asText());
                    }
                }
                if (!slugs.isEmpty()) {
                    return slugs.stream().distinct().collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.debug("[Polymarket] Ошибка получения тегов: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<String> getFallbackSlugs() {
        return List.of("sports", "soccer", "football", "nba", "nfl", "tennis",
                "nhl", "mlb", "ufc", "boxing", "mma", "hockey", "baseball", "cricket");
    }

    private JsonNode fetchJsonArray(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            if (json.isArray()) return json;
            if (json.isObject()) {
                if (json.has("data") && json.get("data").isArray()) return json.get("data");
                if (json.has("events") && json.get("events").isArray()) return json.get("events");
            }
        }
        return null;
    }

    private List<String> findPrices(JsonNode marketNode) {
        String[] fields = {"outcomePrices", "outcome_prices", "prices", "clobTokenPrices"};
        for (String field : fields) {
            JsonNode node = marketNode.path(field);
            if (!node.isMissingNode() && !node.isNull()) {
                return parseJsonArray(node);
            }
        }
        return new ArrayList<>();
    }

    private BigDecimal convertPrice(double price) {
        if (price <= 0.001) return new BigDecimal("1000.00");
        if (price >= 0.999) return new BigDecimal("1.00");
        return BigDecimal.valueOf(1.0 / price).setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> parseJsonArray(JsonNode node) {
        try {
            if (node.isTextual()) {
                return objectMapper.readValue(node.asText(), new TypeReference<>() {});
            } else if (node.isArray()) {
                return objectMapper.convertValue(node, new TypeReference<>() {});
            }
        } catch (Exception e) {
            // Ignore
        }
        return new ArrayList<>();
    }

    private LocalDateTime parseEndDate(JsonNode node) {
        String dateStr = node.path("endDate").asText(null);
        if (dateStr != null && !dateStr.isEmpty()) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(dateStr), ZoneId.systemDefault());
            } catch (Exception e) {
                // Ignore
            }
        }
        return null;
    }
}