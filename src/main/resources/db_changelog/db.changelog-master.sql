--liquibase formatted sql

--changeset Ilya:v_1.0_id_1.1
--comment: Создание таблицы bookmakers
CREATE TABLE IF NOT EXISTS bookmakers (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    api_base_url VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE bookmakers IS 'Таблица букмекеров';
COMMENT ON COLUMN bookmakers.id IS 'Идентификатор';
COMMENT ON COLUMN bookmakers.code IS 'Код букмекера (FONBET, BET365, MARATHON)';
COMMENT ON COLUMN bookmakers.name IS 'Полное наименование';
COMMENT ON COLUMN bookmakers.api_base_url IS 'Базовый URL API';
COMMENT ON COLUMN bookmakers.is_active IS 'Активен';
COMMENT ON COLUMN bookmakers.created_at IS 'Дата создания';
COMMENT ON COLUMN bookmakers.updated_at IS 'Дата обновления';

--rollback DROP TABLE bookmakers;

--changeset Ilya:v_1.0_id_1.2
--comment: Создание таблицы events
CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    bookmaker_id BIGINT NOT NULL,
    external_id VARCHAR(100) NOT NULL,
    home_team VARCHAR(255) NOT NULL,
    away_team VARCHAR(255) NOT NULL,
    league VARCHAR(255),
    start_time TIMESTAMP NOT NULL,
    status VARCHAR(50) DEFAULT 'SCHEDULED',
    event_url VARCHAR(500),  -- <-- ДОБАВЛЕНО: ссылка на событие
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE events IS 'Таблица событий';
COMMENT ON COLUMN events.bookmaker_id IS 'ID букмекера';
COMMENT ON COLUMN events.external_id IS 'ID события у букмекера';
COMMENT ON COLUMN events.home_team IS 'Хозяева';
COMMENT ON COLUMN events.away_team IS 'Гости';
COMMENT ON COLUMN events.league IS 'Лига';
COMMENT ON COLUMN events.start_time IS 'Время начала';
COMMENT ON COLUMN events.status IS 'Статус события';
COMMENT ON COLUMN events.event_url IS 'Ссылка на событие';  -- <-- ДОБАВЛЕНО

-- Проверка на существование constraint перед созданием
ALTER TABLE events DROP CONSTRAINT IF EXISTS fk_events_bookmaker;
ALTER TABLE events ADD CONSTRAINT fk_events_bookmaker
    FOREIGN KEY (bookmaker_id) REFERENCES bookmakers(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS uq_events_bookmaker_external;
CREATE UNIQUE INDEX uq_events_bookmaker_external ON events(bookmaker_id, external_id);

DROP INDEX IF EXISTS idx_events_league_time;
CREATE INDEX idx_events_league_time ON events(league, start_time);

DROP INDEX IF EXISTS idx_events_status_start;
CREATE INDEX idx_events_status_start ON events(status, start_time);

--rollback DROP TABLE events;

--changeset Ilya:v_1.0_id_1.3
--comment: Создание таблицы odds
CREATE TABLE IF NOT EXISTS odds (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    market_type VARCHAR(50) NOT NULL,
    outcome_name VARCHAR(255) NOT NULL,
    odds_value DECIMAL(10,3) NOT NULL,
    fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE odds IS 'Таблица коэффициентов';
COMMENT ON COLUMN odds.event_id IS 'ID события';
COMMENT ON COLUMN odds.market_type IS 'Тип рынка (1X2, OVER/UNDER и т.д.)';
COMMENT ON COLUMN odds.outcome_name IS 'Исход (Home, Draw, Away)';
COMMENT ON COLUMN odds.odds_value IS 'Значение коэффициента';
COMMENT ON COLUMN odds.fetched_at IS 'Время получения';

ALTER TABLE odds DROP CONSTRAINT IF EXISTS fk_odds_event;
ALTER TABLE odds ADD CONSTRAINT fk_odds_event
    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS idx_odds_event_current;
CREATE INDEX idx_odds_event_current ON odds(event_id, fetched_at DESC);

DROP INDEX IF EXISTS idx_odds_fetched;
CREATE INDEX idx_odds_fetched ON odds(fetched_at);

--rollback DROP TABLE odds;

--changeset Ilya:v_1.0_id_1.4
--comment: Создание таблицы arbitrages
CREATE TABLE IF NOT EXISTS arbitrages (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    bookmaker1_id BIGINT NOT NULL,
    bookmaker2_id BIGINT NOT NULL,
    market_type VARCHAR(50) NOT NULL,
    outcome1 VARCHAR(255) NOT NULL,
    outcome2 VARCHAR(255) NOT NULL,
    odds1 DECIMAL(10,3) NOT NULL,
    odds2 DECIMAL(10,3) NOT NULL,
    profit_percent DECIMAL(5,2) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE arbitrages IS 'Таблица арбитражей';
COMMENT ON COLUMN arbitrages.event_id IS 'ID события';
COMMENT ON COLUMN arbitrages.bookmaker1_id IS 'ID первого букмекера';
COMMENT ON COLUMN arbitrages.bookmaker2_id IS 'ID второго букмекера';
COMMENT ON COLUMN arbitrages.market_type IS 'Тип рынка';
COMMENT ON COLUMN arbitrages.outcome1 IS 'Исход у первого букмекера';
COMMENT ON COLUMN arbitrages.outcome2 IS 'Исход у второго букмекера';
COMMENT ON COLUMN arbitrages.odds1 IS 'Коэффициент первого';
COMMENT ON COLUMN arbitrages.odds2 IS 'Коэффициент второго';
COMMENT ON COLUMN arbitrages.profit_percent IS 'Процент прибыли';
COMMENT ON COLUMN arbitrages.is_active IS 'Активен';

ALTER TABLE arbitrages DROP CONSTRAINT IF EXISTS fk_arbitrages_event;
ALTER TABLE arbitrages ADD CONSTRAINT fk_arbitrages_event
    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE;

ALTER TABLE arbitrages DROP CONSTRAINT IF EXISTS fk_arbitrages_bookmaker1;
ALTER TABLE arbitrages ADD CONSTRAINT fk_arbitrages_bookmaker1
    FOREIGN KEY (bookmaker1_id) REFERENCES bookmakers(id);

ALTER TABLE arbitrages DROP CONSTRAINT IF EXISTS fk_arbitrages_bookmaker2;
ALTER TABLE arbitrages ADD CONSTRAINT fk_arbitrages_bookmaker2
    FOREIGN KEY (bookmaker2_id) REFERENCES bookmakers(id);

DROP INDEX IF EXISTS idx_arbitrages_active;
CREATE INDEX idx_arbitrages_active ON arbitrages(is_active, created_at DESC);

--rollback DROP TABLE arbitrages;

--changeset Ilya:v_1.0_id_1.5
--comment: Вставка дефолтных букмекеров
INSERT INTO bookmakers (code, name, api_base_url) VALUES
    ('FONBET', 'Фонбет', 'https://api.fonbet.ru'),
    ('BET365', 'Bet365', 'https://api.bet365.com'),
    ('MARATHON', 'Марафон', 'https://api.marathonbet.com'),
    ('POLYMARKET', 'Polymarket', 'https://gamma-api.polymarket.com')
ON CONFLICT (code) DO NOTHING;

--rollback DELETE FROM bookmakers WHERE code IN ('FONBET', 'BET365', 'MARATHON');

--changeset Ilya:v_1.0_id_1.6
--comment: Создание таблицы markets
CREATE TABLE IF NOT EXISTS markets (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    market_type VARCHAR(50) NOT NULL,
    market_name VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE markets IS 'Таблица рынков';
COMMENT ON COLUMN markets.event_id IS 'ID события';
COMMENT ON COLUMN markets.market_type IS 'Тип рынка';
COMMENT ON COLUMN markets.market_name IS 'Название рынка';

ALTER TABLE markets DROP CONSTRAINT IF EXISTS fk_markets_event;
ALTER TABLE markets ADD CONSTRAINT fk_markets_event
    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS uq_markets_event_type;
CREATE UNIQUE INDEX uq_markets_event_type ON markets(event_id, market_type);

--rollback DROP TABLE markets;

--changeset Ilya:v_1.0_id_1.7
--comment: Создание таблицы outcomes
CREATE TABLE IF NOT EXISTS outcomes (
    id BIGSERIAL PRIMARY KEY,
    market_id BIGINT NOT NULL,
    outcome_name VARCHAR(255) NOT NULL,
    odds DECIMAL(10,2) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE outcomes IS 'Таблица исходов';
COMMENT ON COLUMN outcomes.market_id IS 'ID рынка';
COMMENT ON COLUMN outcomes.outcome_name IS 'Название исхода';
COMMENT ON COLUMN outcomes.odds IS 'Коэффициент';
COMMENT ON COLUMN outcomes.is_active IS 'Активен';

ALTER TABLE outcomes DROP CONSTRAINT IF EXISTS fk_outcomes_market;
ALTER TABLE outcomes ADD CONSTRAINT fk_outcomes_market
    FOREIGN KEY (market_id) REFERENCES markets(id) ON DELETE CASCADE;

--changeset Ilya:v_1.0_id_1.9
--comment: Добавление букмекера Winline
INSERT INTO bookmakers (code, name, api_base_url, is_active) VALUES
    ('WINLINE', 'Winline', 'https://winline.ru', true)
ON CONFLICT (code) DO NOTHING;

--rollback DELETE FROM bookmakers WHERE code = 'WINLINE';