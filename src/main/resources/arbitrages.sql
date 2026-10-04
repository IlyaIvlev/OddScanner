WITH matched AS (
    SELECT
        f.id as f_event_id, w.id as w_event_id,
        f.home_team || ' vs ' || f.away_team as fonbet_match,
        w.home_team || ' vs ' || w.away_team as winline_match,
        f.start_time
    FROM events f
    JOIN events w
      ON (LOWER(f.home_team) = LOWER(w.home_team) AND LOWER(f.away_team) = LOWER(w.away_team)
        OR (LOWER(f.home_team) LIKE '%' || LOWER(w.home_team) || '%' AND LENGTH(w.home_team) > 4
            AND LOWER(f.away_team) LIKE '%' || LOWER(w.away_team) || '%' AND LENGTH(w.away_team) > 4))
    JOIN bookmakers bf ON f.bookmaker_id = bf.id
    JOIN bookmakers bw ON w.bookmaker_id = bw.id
    WHERE bf.code = 'FONBET' AND bw.code = 'WINLINE'
      AND f.status = 'SCHEDULED' AND w.status = 'SCHEDULED'
      AND f.start_time > NOW()
),
fonbet_odds AS (
    SELECT m.event_id, o.outcome_name, o.odds FROM markets m JOIN outcomes o ON m.id = o.market_id
    WHERE m.market_type = '1X2' AND m.event_id IN (SELECT f_event_id FROM matched)
),
winline_odds AS (
    SELECT m.event_id, o.outcome_name, o.odds FROM markets m JOIN outcomes o ON m.id = o.market_id
    WHERE m.market_type = '1X2' AND m.event_id IN (SELECT w_event_id FROM matched)
)
SELECT
    m.fonbet_match, m.winline_match, m.start_time,
    GREATEST(f1.odds, w1.odds) as best_p1,
    CASE WHEN f1.odds >= w1.odds THEN 'Fonbet' ELSE 'Winline' END as p1_from,
    GREATEST(fx.odds, wx.odds) as best_x,
    CASE WHEN fx.odds >= wx.odds THEN 'Fonbet' ELSE 'Winline' END as x_from,
    GREATEST(f2.odds, w2.odds) as best_p2,
    CASE WHEN f2.odds >= w2.odds THEN 'Fonbet' ELSE 'Winline' END as p2_from,
    ROUND((1.0/GREATEST(f1.odds,w1.odds) + 1.0/GREATEST(fx.odds,wx.odds) + 1.0/GREATEST(f2.odds,w2.odds))*100, 2) as margin_pct,
    ROUND((1.0/(1.0/GREATEST(f1.odds,w1.odds) + 1.0/GREATEST(fx.odds,wx.odds) + 1.0/GREATEST(f2.odds,w2.odds))-1)*100, 2) as profit_pct,
    f.event_url as fonbet_url, w.event_url as winline_url
FROM matched m
JOIN fonbet_odds f1 ON f1.event_id = m.f_event_id AND f1.outcome_name = 'П1'
JOIN winline_odds w1 ON w1.event_id = m.w_event_id AND w1.outcome_name = 'П1'
JOIN fonbet_odds fx ON fx.event_id = m.f_event_id AND fx.outcome_name IN ('Х','Ничья')
JOIN winline_odds wx ON wx.event_id = m.w_event_id AND wx.outcome_name IN ('Х','Ничья')
JOIN fonbet_odds f2 ON f2.event_id = m.f_event_id AND f2.outcome_name = 'П2'
JOIN winline_odds w2 ON w2.event_id = m.w_event_id AND w2.outcome_name = 'П2'
JOIN events f ON f.id = m.f_event_id
JOIN events w ON w.id = m.w_event_id
WHERE (1.0/GREATEST(f1.odds,w1.odds) + 1.0/GREATEST(fx.odds,wx.odds) + 1.0/GREATEST(f2.odds,w2.odds)) < 1.0
ORDER BY margin_pct ASC;