package com.oddscanner.parser.fonbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.oddscanner.parser.AbstractBookmakerParser;
import com.oddscanner.parser.RawEvent;
import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class FonbetParser extends AbstractBookmakerParser {

    private static final String BASE_URL = "https://fon.bet";

    private final EventRepository eventRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<Integer, String> dynamicLeagueCache = new ConcurrentHashMap<>();
    private boolean leagueCacheLoaded = false;
    private String cachedApiBaseUrl = null;
    private long lastApiBaseUrlFetch = 0;

    private static final Map<Integer, String> SPORT_MAPPING = new HashMap<>();

    static {
        SPORT_MAPPING.put(1, "Футбол");
        SPORT_MAPPING.put(2, "Хоккей");
        SPORT_MAPPING.put(3, "Баскетбол");
        SPORT_MAPPING.put(4, "Теннис");
        SPORT_MAPPING.put(5, "Волейбол");
        SPORT_MAPPING.put(6, "MMA/Бокс");
        SPORT_MAPPING.put(7, "Киберспорт");
    }

    public FonbetParser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        super(meterRegistry, eventRepository);
        this.eventRepository = eventRepository;
    }

    @Override
    public String getName() {
        return "Fonbet";
    }

    public List<RawEvent> doParse() throws Exception {
        List<RawEvent> events = new ArrayList<>();

        String apiBaseUrl = discoverApiBaseUrl();
        if (apiBaseUrl == null) {
            log.error("[Fonbet] Не удалось определить API базовый URL");
            return events;
        }

        String apiUrl = apiBaseUrl + "/ma/events/listBase?lang=ru&scopeMarket=1600&version=" + System.currentTimeMillis();
        String response = fetchApiData(apiUrl);

        if (response == null) {
            log.error("[Fonbet] Не удалось получить данные от API");
            return events;
        }

        events = parseApiResponse(response);

        if (!events.isEmpty()) {
            log.debug("[Fonbet] Привязано коэффициентов к {} событиям",
                    events.stream().filter(e -> !e.markets().isEmpty()).count());

            eventRepository.saveEvents("FONBET", events);

            Set<String> activeExternalIds = events.stream()
                    .map(RawEvent::externalId)
                    .collect(Collectors.toSet());

            eventRepository.markInactiveEvents("FONBET", activeExternalIds);

            log.debug("[Fonbet] Сохранено {} событий", events.size());
        }

        return events;
    }

    private List<RawEvent> parseApiResponse(String jsonResponse) {
        List<RawEvent> events = new ArrayList<>();
        Map<Integer, List<RawEvent.RawMarket>> marketsByEventId = new HashMap<>();

        try {
            JsonNode root = mapper.readTree(jsonResponse);

            // Парсим коэффициенты из customFactors
            JsonNode customFactorsNode = root.get("customFactors");
            if (customFactorsNode != null && customFactorsNode.isArray()) {
                log.debug("[Fonbet] Найдено {} событий с коэффициентами", customFactorsNode.size());
                parseCustomFactors(customFactorsNode, marketsByEventId);
            }

            JsonNode eventsNode = findEventsNode(root);
            if (eventsNode != null && eventsNode.isArray() && !eventsNode.isEmpty()) {
                log.debug("[Fonbet] Найдено {} событий", eventsNode.size());
            }

            if (!leagueCacheLoaded) {
                Map<Integer, String> freshMapping = buildLeagueMapping(root);
                dynamicLeagueCache.putAll(freshMapping);
                leagueCacheLoaded = true;
                log.debug("[Fonbet] Загружено {} лиг", dynamicLeagueCache.size());
            }

            if (eventsNode == null || !eventsNode.isArray()) {
                log.error("[Fonbet] Не найден массив событий");
                return events;
            }

            for (JsonNode eventNode : eventsNode) {
                try {
                    if (eventNode.has("level") && eventNode.get("level").asInt() != 1) {
                        continue;
                    }

                    RawEvent event = parseEventNode(eventNode, root);
                    if (event != null) {
                        Integer eventId = getIntField(eventNode, "id");
                        List<RawEvent.RawMarket> markets = marketsByEventId.get(eventId);

                        if (markets != null && !markets.isEmpty()) {
                            event = new RawEvent(
                                    event.externalId(),
                                    event.sportName(),
                                    event.leagueName(),
                                    event.team1(),
                                    event.team2(),
                                    event.startsAt(),
                                    markets,
                                    event.eventUrl()
                            );
                        }

                        events.add(event);
                    }
                } catch (Exception e) {
                    // Подавляем
                }
            }

            log.debug("[Fonbet] Спарсено {} событий, {} с коэффициентами",
                    events.size(),
                    events.stream().filter(e -> !e.markets().isEmpty()).count());

        } catch (Exception e) {
            log.error("[Fonbet] Ошибка парсинга: {}", e.getMessage());
        }

        return events;
    }

    private void parseCustomFactors(JsonNode customFactorsNode, Map<Integer, List<RawEvent.RawMarket>> marketsByEventId) {
        for (JsonNode customFactor : customFactorsNode) {
            Integer eventId = getIntField(customFactor, "e");
            if (eventId == null) continue;

            JsonNode factorsArray = customFactor.get("factors");
            if (factorsArray == null || !factorsArray.isArray()) continue;

            Map<String, Map<String, RawEvent.RawOutcome>> outcomesByKey = new LinkedHashMap<>();

            for (JsonNode factorNode : factorsArray) {
                Integer factorId = getIntField(factorNode, "f");
                BigDecimal odds = getBigDecimalField(factorNode, "v");

                if (factorId == null || odds == null || odds.compareTo(BigDecimal.ZERO) <= 0) continue;

                String marketType = getMarketTypeByFactorId(factorId);
                String outcomeName = getOutcomeNameByFactorId(factorId, factorNode);
                String pt = getStringField(factorNode, "pt");

                String key = marketType + "|" + (pt != null ? pt : "");

                outcomesByKey
                        .computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .merge(outcomeName, new RawEvent.RawOutcome(outcomeName, odds),
                                (existing, newOutcome) ->
                                        existing.odds().compareTo(newOutcome.odds()) >= 0 ? existing : newOutcome
                        );
            }

            List<RawEvent.RawMarket> markets = new ArrayList<>();
            for (Map.Entry<String, Map<String, RawEvent.RawOutcome>> entry : outcomesByKey.entrySet()) {
                List<RawEvent.RawOutcome> outcomes = new ArrayList<>(entry.getValue().values());
                if (!outcomes.isEmpty()) {
                    String marketType = entry.getKey().split("\\|")[0];
                    markets.add(new RawEvent.RawMarket(marketType, outcomes));
                }
            }

            if (!markets.isEmpty()) {
                marketsByEventId.put(eventId, markets);
            }
        }
    }

    private String getMarketTypeByFactorId(Integer factorId) {
        if (factorId == 921 || factorId == 922 || factorId == 923 ||
                factorId == 924 || factorId == 925 || factorId == 1571) {
            return "1X2";
        }
        if (isHandicap1Factor(factorId) || isHandicap2Factor(factorId)) return "Фора";
        if (isTotalFactor(factorId) || isTotalUnderFactor(factorId)) return "Тотал";
        return "Рынок_" + factorId;
    }

    private String getOutcomeNameByFactorId(Integer factorId, JsonNode factorNode) {
        String pt = getStringField(factorNode, "pt");

        if (pt == null) {
            Integer p = getIntField(factorNode, "p");
            if (p != null && p != 0) {
                double value = p / 100.0;
                pt = value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
            }
        }

        if (isTotalFactor(factorId)) {
            return pt != null ? "ТБ " + pt : "ТБ";
        }
        if (isTotalUnderFactor(factorId)) {
            return pt != null ? "ТМ " + pt : "ТМ";
        }

        if (isHandicap1Factor(factorId)) {
            if (pt != null) {
                return "Ф1 (" + pt + ")";
            }
            return "Ф1";
        }
        if (isHandicap2Factor(factorId)) {
            if (pt != null) {
                return "Ф2 (" + pt + ")";
            }
            return "Ф2";
        }

        return switch (factorId) {
            case 921 -> "П1";
            case 922 -> "Ничья";
            case 923 -> "П2";
            case 924 -> "1X";
            case 925 -> "X2";
            case 1571 -> "12";
            default ->
                 "Исход_" + factorId;
        };
    }

    private boolean isTotalFactor(Integer id) {
        return id == 930 || id == 974 || id == 978 || id == 1696 || id == 1727 ||
                id == 1730 || id == 1733 || id == 1736 || id == 1809 || id == 1812 ||
                id == 1848 || id == 1854 || id == 1873 ||
                id == 917 || id == 1747 || id == 1750 || id == 1753 ||
                id == 3024 || id == 3027 || id == 3030 ||
                id == 10409 || id == 9962;
    }

    private boolean isTotalUnderFactor(Integer id) {
        return id == 931 || id == 976 || id == 980 || id == 1697 || id == 1728 ||
                id == 1731 || id == 1734 || id == 1737 || id == 1810 || id == 1813 ||
                id == 1849 || id == 1871 || id == 1874 ||
                id == 918 || id == 1748 || id == 1751 || id == 1754 ||
                id == 3025 || id == 3028 || id == 3031 ||
                id == 10410 || id == 9961;
    }

    private boolean isHandicap1Factor(Integer id) {
        return id == 927 || id == 910 || id == 989 || id == 1569 || id == 1672 ||
                id == 1677 || id == 1845 ||
                id == 2421 || id == 2424 || id == 2427 || id == 2430;
    }

    private boolean isHandicap2Factor(Integer id) {
        return id == 928 || id == 912 || id == 991 || id == 1572 || id == 1675 ||
                id == 1678 || id == 1846 ||
                id == 2422 || id == 2425 || id == 2428 || id == 2431;
    }

    private JsonNode findEventsNode(JsonNode root) {
        String[] possiblePaths = {"events", "data", "items", "list", "result"};

        for (String path : possiblePaths) {
            JsonNode node = root.get(path);
            if (node != null && node.isArray() && !node.isEmpty()) {
                return node;
            }
        }

        if (root.isArray() && !root.isEmpty()) {
            return root;
        }

        return null;
    }

    private RawEvent parseEventNode(JsonNode eventNode, JsonNode root) {
        String eventId = String.valueOf(getIntField(eventNode, "id"));
        if (eventId == null || "null".equals(eventId)) {
            return null;
        }

        String homeTeam = parseHomeTeam(eventNode);
        String awayTeam = parseAwayTeam(eventNode);

        if (homeTeam == null || awayTeam == null) {
            return null;
        }

        homeTeam = cleanTeamName(homeTeam);
        awayTeam = cleanTeamName(awayTeam);

        LocalDateTime startTime = parseStartTime(eventNode);
        String league = resolveLeagueName(eventNode, root);
        String sport = getSportName(eventNode, root);

        // Генерируем правильную ссылку с sportId
        Integer sportId = getIntField(eventNode, "sportId");
        String sportAlias = getSportAlias(eventNode, root);

        String eventUrl = null;
        if (sportId != null) {
            // Формат: /sports/{sportAlias}/{sportId}/{eventId}
            eventUrl = BASE_URL + "/sports/" + sportAlias + "/" + sportId + "/" + eventId;
        }

        return new RawEvent(
                eventId,
                sport,
                league,
                homeTeam,
                awayTeam,
                startTime != null ? startTime : LocalDateTime.now().plusDays(7),
                new ArrayList<>(),
                eventUrl
        );
    }

    private String getSportAlias(JsonNode eventNode, JsonNode root) {
        Integer sportId = getIntField(eventNode, "sportId");
        if (sportId == null) return "sport";

        JsonNode sports = root.get("sports");
        if (sports != null && sports.isArray()) {
            for (JsonNode sport : sports) {
                Integer id = getIntField(sport, "id");
                if (id != null && id.equals(sportId)) {
                    String alias = getStringField(sport, "alias");
                    if (alias != null) {
                        return alias;
                    }

                    Integer parentId = getIntField(sport, "parentId");
                    if (parentId != null) {
                        return getSportAliasById(parentId, sports);
                    }
                }
            }
        }

        Integer rootKind = getIntField(eventNode, "rootKind");
        return getSportAliasById(rootKind, sports);
    }

    private String getSportAliasById(Integer id, JsonNode sports) {
        if (id == null || sports == null) return "sport";

        for (JsonNode sport : sports) {
            Integer sportId = getIntField(sport, "id");
            if (sportId != null && sportId.equals(id)) {
                String alias = getStringField(sport, "alias");
                if (alias != null) {
                    return alias;
                }
            }
        }

        return "sport";
    }

    private String parseHomeTeam(JsonNode eventNode) {
        String team = getStringField(eventNode, "team1", "homeTeam", "home", "participant1", "homeName");
        if (team != null) return team;

        JsonNode participants = eventNode.get("participants");
        if (participants != null && participants.isArray() && participants.size() >= 2) {
            return getStringField(participants.get(0), "name", "title");
        }

        return null;
    }

    private String parseAwayTeam(JsonNode eventNode) {
        String team = getStringField(eventNode, "team2", "awayTeam", "away", "participant2", "awayName");
        if (team != null) return team;

        JsonNode participants = eventNode.get("participants");
        if (participants != null && participants.isArray() && participants.size() >= 2) {
            return getStringField(participants.get(1), "name", "title");
        }

        return null;
    }

    private LocalDateTime parseStartTime(JsonNode eventNode) {
        JsonNode startTimeNode = eventNode.get("startTime");
        if (startTimeNode != null && startTimeNode.isNumber()) {
            long timestamp = startTimeNode.asLong();
            return LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(timestamp),
                    ZoneId.systemDefault()
            );
        }

        String timeStr = getStringField(eventNode, "date", "kickoff", "startDate");
        if (timeStr != null) {
            try {
                return LocalDateTime.parse(timeStr);
            } catch (Exception e) {
                // Подавляем
            }
        }
        return null;
    }

    private String resolveLeagueName(JsonNode eventNode, JsonNode root) {
        Integer sportId = getIntField(eventNode, "sportId");
        if (sportId == null) return "Неизвестная лига";

        if (!leagueCacheLoaded) {
            buildLeagueMapping(root);
        }

        if (dynamicLeagueCache.containsKey(sportId)) {
            return dynamicLeagueCache.get(sportId);
        }

        return "Неизвестная лига";
    }

    private String getSportName(JsonNode eventNode, JsonNode root) {
        Integer sportId = getIntField(eventNode, "sportId");
        if (sportId == null) return "Спорт";

        if (!leagueCacheLoaded) {
            buildLeagueMapping(root);
        }

        String leagueName = dynamicLeagueCache.get(sportId);
        if (leagueName != null) {
            return detectSportByLeague(leagueName);
        }

        Integer rootKind = getIntField(eventNode, "rootKind");
        if (rootKind != null && SPORT_MAPPING.containsKey(rootKind)) {
            return SPORT_MAPPING.get(rootKind);
        }

        return "Спорт";
    }

    private String detectSportByLeague(String leagueName) {
        String lower = leagueName.toLowerCase();

        if (lower.contains("fc 2") || lower.contains("fc2") ||
                lower.contains("fifa") || lower.contains("esportsbattle") ||
                lower.contains("esports") || lower.contains("h2h liga")) {
            return "Киберспорт (Футбол)";
        }
        if (lower.contains("nba 2k") ||
                lower.contains("nba2k") || lower.contains("шорт-хоккей")) {
            return "Киберспорт";
        }
        if (lower.contains("itf") || lower.contains("atp") ||
                lower.contains("wta") || lower.contains("теннис")) {
            return "Теннис";
        }
        if (lower.contains("nhl") || lower.contains("кхл") ||
                lower.contains("хоккей") || lower.contains("hockey")) {
            return "Хоккей";
        }
        if (lower.contains("nba") || lower.contains("euroleague") ||
                lower.contains("баскетбол") || lower.contains("basketball")) {
            return "Баскетбол";
        }
        if (lower.contains("волейбол") || lower.contains("volleyball")) {
            return "Волейбол";
        }
        if (lower.contains("mma") || lower.contains("ufc") ||
                lower.contains("бокс") || lower.contains("boxing")) {
            return "MMA/Бокс";
        }

        return "Футбол";
    }

    private Integer getIntField(JsonNode node, String... fieldNames) {
        if (node == null) return null;
        for (String fieldName : fieldNames) {
            JsonNode field = node.get(fieldName);
            if (field != null && !field.isMissingNode() && field.isNumber()) {
                return field.asInt();
            }
        }
        return null;
    }

    private BigDecimal getBigDecimalField(JsonNode node, String... fieldNames) {
        if (node == null) return null;
        for (String fieldName : fieldNames) {
            JsonNode field = node.get(fieldName);
            if (field != null && !field.isMissingNode() && field.isNumber()) {
                return BigDecimal.valueOf(field.asDouble());
            }
        }
        return null;
    }

    private String getStringField(JsonNode node, String... fieldNames) {
        if (node == null) return null;
        for (String fieldName : fieldNames) {
            JsonNode field = node.get(fieldName);
            if (field != null && !field.isMissingNode() && field.isTextual()) {
                String value = field.asText();
                if (value != null && !value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String cleanTeamName(String name) {
        if (name == null) return null;
        name = name.replaceAll("\\([^)]*\\)", "").trim();
        name = name.replaceAll("\\s+", " ").trim();
        return name;
    }

    private String decompressGzip(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
             java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bis)) {
            return new String(gis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Map<Integer, String> buildLeagueMapping(JsonNode response) {
        Map<Integer, String> leagueMap = new HashMap<>();

        JsonNode sportsArray = response.get("sports");
        if (sportsArray != null && sportsArray.isArray()) {
            for (JsonNode node : sportsArray) {
                Integer id = getIntField(node, "id");
                String name = getStringField(node, "name");

                if (id != null && name != null) {
                    leagueMap.put(id, name);
                }
            }
        }

        JsonNode tournamentInfos = response.get("tournamentInfos");
        if (tournamentInfos != null && tournamentInfos.isArray()) {
            for (JsonNode node : tournamentInfos) {
                Integer id = getIntField(node, "id");
                String caption = getStringField(node, "caption");

                if (id != null && caption != null && !leagueMap.containsKey(id)) {
                    leagueMap.put(id, caption);
                }
            }
        }

        return leagueMap;
    }

    private static final String[] KNOWN_API_URLS = {
            "https://line-lb54-w.bk6bba-resources.com"
    };

    private String discoverApiBaseUrl() {
        if (cachedApiBaseUrl != null && System.currentTimeMillis() - lastApiBaseUrlFetch < 300_000) {
            return cachedApiBaseUrl;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--disable-blink-features=AutomationControlled", "--no-sandbox")));

            Page page = browser.newPage();
            page.setExtraHTTPHeaders(Map.of(
                    "Accept-Language", "ru-RU,ru;q=0.9",
                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ));

            List<String> apiUrls = new ArrayList<>();

            page.onResponse(response -> {
                String url = response.url();
                if (url.contains("/ma/events/list") && url.contains("bk6bba-resources")) {
                    try {
                        java.net.URI uri = new java.net.URI(url);
                        String baseUrl = uri.getScheme() + "://" + uri.getHost();
                        if (!apiUrls.contains(baseUrl)) {
                            apiUrls.add(baseUrl);
                        }
                    } catch (Exception ignored) {}
                }
            });

            // КЛЮЧЕВОЕ: используем route.abort() чтобы не ждать загрузку страницы
            // Просто открываем и сразу закрываем через 10 сек
            page.navigate(BASE_URL, new Page.NavigateOptions()
                    .setTimeout(30_000)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));

            // Если navigate не выбросил exception — ждём ещё 5 сек для API-запросов
            page.waitForTimeout(5_000);

            page.close();
            browser.close();

            if (!apiUrls.isEmpty()) {
                cachedApiBaseUrl = apiUrls.getFirst();
                lastApiBaseUrlFetch = System.currentTimeMillis();
                log.debug("[Fonbet] API базовый URL: {}", cachedApiBaseUrl);
                return cachedApiBaseUrl;
            }

        } catch (Exception e) {
            // Timeout от navigate — это НОРМАЛЬНО, API URL мог уже быть перехвачен
            log.warn("[Fonbet] Navigate timeout (это нормально): {}", e.getMessage());
        }

        // Если Playwright не сработал — fallback на известные URL
        log.warn("[Fonbet] Playwright не нашёл URL, пробуем известные...");
        for (String url : KNOWN_API_URLS) {
            try {
                String testUrl = url + "/ma/events/listBase?lang=ru&scopeMarket=1600&version=" + System.currentTimeMillis();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(testUrl))
                        .header("Accept", "*/*")
                        .header("User-Agent", "Mozilla/5.0")
                        .timeout(java.time.Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() == 200 && response.body().length > 100) {
                    cachedApiBaseUrl = url;
                    lastApiBaseUrlFetch = System.currentTimeMillis();
                    log.debug("[Fonbet] API базовый URL (fallback): {}", url);
                    return url;
                }
            } catch (Exception ignored) {}
        }

        log.error("[Fonbet] Не удалось определить API URL");
        return null;
    }
    private String fetchApiData(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "*/*")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .header("Origin", BASE_URL)
                .header("Referer", BASE_URL + "/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 200) {
            byte[] body = response.body();
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");

            if ("gzip".equals(contentEncoding) || "deflate".equals(contentEncoding)) {
                return decompressGzip(body);
            }
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        return null;
    }
}