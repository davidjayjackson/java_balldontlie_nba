package com.example.nba;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.sun.star.lang.Locale;
import com.sun.star.lang.XSingleComponentFactory;
import com.sun.star.lib.uno.helper.Factory;
import com.sun.star.lib.uno.helper.WeakBase;
import com.sun.star.registry.XRegistryKey;
import com.sun.star.uno.Any;

/**
 * LibreOffice Calc add-in exposing balldontlie NBA worksheet functions.
 *
 * <p>Implements the custom {@link XNba} interface plus the standard add-in
 * plumbing ({@code com.sun.star.sheet.XAddIn}, {@code XServiceName},
 * {@code XServiceInfo}). Function display names, descriptions and
 * per-argument help live in config/CalcAddIns.xcu; the {@code XAddIn}
 * accessors below return the programmatic names as a safe fallback.
 *
 * <p>Unlike a typical UNO add-in, functions here never throw. Every function
 * resolves through {@link NbaCache} (see that class for the non-blocking
 * cache + background-fetch pattern) and returns either the requested data or
 * one of four sentinel strings understood by the user: {@code #LOADING},
 * {@code #NO_API_KEY}, {@code #NOT_FOUND}, {@code #ERR} (detail via
 * {@link #nbaLastError()}).
 */
public final class NbaImpl extends WeakBase
        implements XNba,
                   com.sun.star.sheet.XAddIn,
                   com.sun.star.lang.XServiceName,
                   com.sun.star.lang.XServiceInfo {

    /** Implementation name: must match the AddInInfo node in CalcAddIns.xcu. */
    private static final String IMPLEMENTATION_NAME = "com.example.nba.NbaImpl";

    /** The one service that marks this component as a Calc add-in. */
    private static final String ADDIN_SERVICE = "com.sun.star.sheet.AddIn";

    private static final String[] SERVICE_NAMES = { ADDIN_SERVICE, IMPLEMENTATION_NAME };

    /** Current locale (tracked for XLocalizable; metadata is English-only here). */
    private Locale locale = new Locale("en", "US", "");

    // ------------------------------------------------------------------ //
    // Sentinels + cache TTLs                                             //
    // ------------------------------------------------------------------ //

    private static final String LOADING = "#LOADING";
    private static final String NO_API_KEY = "#NO_API_KEY";
    private static final String NOT_FOUND = "#NOT_FOUND";
    private static final String ERR = "#ERR";

    private static final long TTL_TEAMS = 24L * 3600 * 1000;      // 24h
    private static final long TTL_STANDINGS = 3600L * 1000;       // 1h
    private static final long TTL_GAMES = 5L * 60 * 1000;         // 5min
    private static final long TTL_STATS = TTL_GAMES;              // 5min
    private static final long TTL_PLAYERS = 6L * 3600 * 1000;     // 6h

    // ------------------------------------------------------------------ //
    // XNba - the actual worksheet functions                              //
    // ------------------------------------------------------------------ //

    public Object nbaTeamId(String nameOrAbbrev, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        final String query = trim(nameOrAbbrev);
        if (query.isEmpty()) return NOT_FOUND;

        NbaCache.Result r = NbaCache.get(teamsKey(apiKey), TTL_TEAMS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.teams(apiKey); }
        });
        if (LOADING.equals(status(r))) return LOADING;
        if (ERR.equals(status(r))) return ERR;

        Map<String, Object> team = matchTeam(cast(r.data), query);
        return team == null ? NOT_FOUND : team.get("id");
    }

    public Object[][] nbaTeams(Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);

        NbaCache.Result r = NbaCache.get(teamsKey(apiKey), TTL_TEAMS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.teams(apiKey); }
        });
        if (LOADING.equals(status(r))) return sentinelTable(LOADING);
        if (ERR.equals(status(r))) return sentinelTable(ERR);

        List<Map<String, Object>> teams = cast(r.data);
        if (teams.isEmpty()) return sentinelTable(NOT_FOUND);
        Object[][] out = new Object[teams.size()][7];
        for (int i = 0; i < teams.size(); i++) {
            Map<String, Object> t = teams.get(i);
            out[i][0] = t.get("id");
            out[i][1] = str(t.get("abbreviation"));
            out[i][2] = str(t.get("city"));
            out[i][3] = str(t.get("conference"));
            out[i][4] = str(t.get("division"));
            out[i][5] = str(t.get("full_name"));
            out[i][6] = str(t.get("name"));
        }
        return out;
    }

    public Object[][] nbaPlayerSearch(String name, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);
        final String query = trim(name);
        if (query.isEmpty()) return sentinelTable(NOT_FOUND);
        final String term = apiSearchTerm(query);
        String key = playersKey(term, apiKey);

        NbaCache.Result r = NbaCache.get(key, TTL_PLAYERS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.playersSearch(term, apiKey); }
        });
        if (LOADING.equals(status(r))) return sentinelTable(LOADING);
        if (ERR.equals(status(r))) return sentinelTable(ERR);

        List<Map<String, Object>> players = rankPlayers(cast(r.data), query);
        if (players.isEmpty()) return sentinelTable(NOT_FOUND);
        Object[][] out = new Object[players.size()][5];
        for (int i = 0; i < players.size(); i++) {
            Map<String, Object> p = players.get(i);
            out[i][0] = p.get("id");
            out[i][1] = str(p.get("first_name"));
            out[i][2] = str(p.get("last_name"));
            out[i][3] = str(p.get("position"));
            out[i][4] = str(nested(p, "team").get("abbreviation"));
        }
        return out;
    }

    public Object nbaPlayerId(String fullName, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        final String query = trim(fullName);
        if (query.isEmpty()) return NOT_FOUND;
        final String term = apiSearchTerm(query);
        String key = playersKey(term, apiKey);

        NbaCache.Result r = NbaCache.get(key, TTL_PLAYERS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.playersSearch(term, apiKey); }
        });
        if (LOADING.equals(status(r))) return LOADING;
        if (ERR.equals(status(r))) return ERR;

        List<Map<String, Object>> players = rankPlayers(cast(r.data), query);
        return players.isEmpty() ? NOT_FOUND : players.get(0).get("id");
    }

    /**
     * balldontlie's {@code search} param substring-matches a single name
     * field; a natural "First Last" query matches nothing. Search on the
     * most distinguishing token (the last word) and rank results afterward.
     */
    private static String apiSearchTerm(String query) {
        String[] parts = query.trim().split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : query.trim();
    }

    /** Prefer results whose full name contains every token of the original query. */
    private static List<Map<String, Object>> rankPlayers(List<Map<String, Object>> players, String query) {
        String[] tokens = query.trim().toLowerCase().split("\\s+");
        List<Map<String, Object>> full = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> rest = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> p : players) {
            String name = (str(p.get("first_name")) + " " + str(p.get("last_name"))).toLowerCase();
            boolean allMatch = true;
            for (String t : tokens) {
                if (!name.contains(t)) {
                    allMatch = false;
                    break;
                }
            }
            (allMatch ? full : rest).add(p);
        }
        full.addAll(rest);
        return full;
    }

    public Object[][] nbaGames(String dateOrSeason, Object teamIdArg, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);
        final String ds = trim(dateOrSeason);
        if (ds.isEmpty()) return sentinelTable(NOT_FOUND);
        final boolean isDate = ds.matches("\\d{4}-\\d{2}-\\d{2}");
        final Long teamId = toLong(optDouble(teamIdArg));

        String key = (isDate ? "games:date=" : "games:season=") + ds
                + (teamId != null ? ";team=" + teamId : "") + ";k=" + keyTag(apiKey);

        NbaCache.Result r = NbaCache.get(key, TTL_GAMES, new NbaCache.Fetcher() {
            public Object fetch() throws Exception {
                return isDate ? NbaClient.gamesByDate(ds, apiKey) : NbaClient.gamesBySeason(ds, teamId, apiKey);
            }
        });
        if (LOADING.equals(status(r))) return sentinelTable(LOADING);
        if (ERR.equals(status(r))) return sentinelTable(ERR);

        List<Map<String, Object>> games = cast(r.data);
        if (isDate && teamId != null) {
            List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> g : games) {
                Long home = toLong(nested(g, "home_team").get("id"));
                Long away = toLong(nested(g, "visitor_team").get("id"));
                if (teamId.equals(home) || teamId.equals(away)) filtered.add(g);
            }
            games = filtered;
        }
        if (games.isEmpty()) return sentinelTable(NOT_FOUND);

        Object[][] out = new Object[games.size()][6];
        for (int i = 0; i < games.size(); i++) {
            Map<String, Object> g = games.get(i);
            out[i][0] = dateOnly(g.get("date"));
            out[i][1] = str(nested(g, "home_team").get("abbreviation"));
            out[i][2] = g.get("home_team_score");
            out[i][3] = str(nested(g, "visitor_team").get("abbreviation"));
            out[i][4] = g.get("visitor_team_score");
            out[i][5] = str(g.get("status"));
        }
        return out;
    }

    public Object nbaScore(String season, String abbrev, Object nthArg, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        final String seasonTrim = trim(season);
        final String abbrevTrim = trim(abbrev);
        if (seasonTrim.isEmpty() || abbrevTrim.isEmpty()) return NOT_FOUND;

        NbaCache.Result teamsR = NbaCache.get(teamsKey(apiKey), TTL_TEAMS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.teams(apiKey); }
        });
        if (LOADING.equals(status(teamsR))) return LOADING;
        if (ERR.equals(status(teamsR))) return ERR;

        Map<String, Object> team = matchTeam(cast(teamsR.data), abbrevTrim);
        if (team == null) return NOT_FOUND;
        final Long teamId = toLong(team.get("id"));
        String teamAbbrev = str(team.get("abbreviation"));

        String key = "games:season=" + seasonTrim + ";team=" + teamId + ";k=" + keyTag(apiKey);
        NbaCache.Result gamesR = NbaCache.get(key, TTL_GAMES, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.gamesBySeason(seasonTrim, teamId, apiKey); }
        });
        if (LOADING.equals(status(gamesR))) return LOADING;
        if (ERR.equals(status(gamesR))) return ERR;

        List<Map<String, Object>> games = new ArrayList<Map<String, Object>>(cast(gamesR.data));
        if (games.isEmpty()) return NOT_FOUND;
        Collections.sort(games, new java.util.Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return str(b.get("date")).compareTo(str(a.get("date"))); // descending
            }
        });

        Double nthNum = optDouble(nthArg);
        int nth = nthNum == null ? 1 : Math.max(1, nthNum.intValue());
        if (nth > games.size()) return NOT_FOUND;
        Map<String, Object> g = games.get(nth - 1);

        boolean isHome = teamId.equals(toLong(nested(g, "home_team").get("id")));
        double homeScore = toDouble(g.get("home_team_score"));
        double awayScore = toDouble(g.get("visitor_team_score"));
        double teamScore = isHome ? homeScore : awayScore;
        double oppScore = isHome ? awayScore : homeScore;
        String oppAbbrev = isHome
                ? str(nested(g, "visitor_team").get("abbreviation"))
                : str(nested(g, "home_team").get("abbreviation"));
        String result = teamScore > oppScore ? "W" : (teamScore < oppScore ? "L" : "T");

        return dateOnly(g.get("date")) + " " + teamAbbrev + " " + fmtNum(teamScore)
                + " - " + fmtNum(oppScore) + " " + oppAbbrev + " (" + result + ")";
    }

    public Object[][] nbaStandings(String season, Object conferenceArg, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);
        final String seasonTrim = trim(season);
        if (seasonTrim.isEmpty()) return sentinelTable(NOT_FOUND);
        final String conf = optString(conferenceArg);

        String key = standingsKey(seasonTrim, apiKey);
        NbaCache.Result r = NbaCache.get(key, TTL_STANDINGS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.standings(seasonTrim, apiKey); }
        });
        if (LOADING.equals(status(r))) return sentinelTable(LOADING);
        if (ERR.equals(status(r))) return sentinelTable(ERR);

        List<Map<String, Object>> rows = cast(r.data);
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            if (conf == null || conf.equalsIgnoreCase(str(rowConference(row)))) {
                filtered.add(row);
            }
        }
        if (filtered.isEmpty()) return sentinelTable(NOT_FOUND);

        Object[][] out = new Object[filtered.size()][7];
        for (int i = 0; i < filtered.size(); i++) {
            Map<String, Object> row = filtered.get(i);
            Map<String, Object> team = nested(row, "team");
            out[i][0] = str(rowConference(row));
            out[i][1] = str(firstNonNull(row, "division", "conf_division"));
            if (out[i][1].equals("")) out[i][1] = str(team.get("division"));
            out[i][2] = str(team.get("abbreviation"));
            out[i][3] = row.get("wins");
            out[i][4] = row.get("losses");
            out[i][5] = firstNonNull(row, "win_pct", "win_percentage", "winning_percentage");
            out[i][6] = firstNonNull(row, "conference_rank", "conf_rank", "playoff_rank", "rank");
        }
        return out;
    }

    public Object nbaTeamRank(String season, String abbrev, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        final String seasonTrim = trim(season);
        final String abbrevTrim = trim(abbrev);
        if (seasonTrim.isEmpty() || abbrevTrim.isEmpty()) return NOT_FOUND;

        String key = standingsKey(seasonTrim, apiKey);
        NbaCache.Result r = NbaCache.get(key, TTL_STANDINGS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.standings(seasonTrim, apiKey); }
        });
        if (LOADING.equals(status(r))) return LOADING;
        if (ERR.equals(status(r))) return ERR;

        for (Map<String, Object> row : cast(r.data)) {
            Map<String, Object> team = nested(row, "team");
            if (abbrevTrim.equalsIgnoreCase(str(team.get("abbreviation")))) {
                Object rank = firstNonNull(row, "conference_rank", "conf_rank", "playoff_rank", "rank");
                return rank == null ? NOT_FOUND : rank;
            }
        }
        return NOT_FOUND;
    }

    public Object nbaPlayerStat(String season, Object idArg, String statKey, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        final String seasonTrim = trim(season);
        final String key2 = trim(statKey);
        Double idNum = optDouble(idArg);
        if (seasonTrim.isEmpty() || key2.isEmpty() || idNum == null) return NOT_FOUND;
        final String playerId = fmtNum(idNum);

        String key = "stats:season=" + seasonTrim + ";player=" + playerId + ";k=" + keyTag(apiKey);
        NbaCache.Result r = NbaCache.get(key, TTL_STATS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.statsBySeasonPlayer(seasonTrim, playerId, apiKey); }
        });
        if (LOADING.equals(status(r))) return LOADING;
        if (ERR.equals(status(r))) return ERR;

        List<Map<String, Object>> games = cast(r.data);
        if (games.isEmpty()) return NOT_FOUND;
        double sum = 0;
        int count = 0;
        for (Map<String, Object> g : games) {
            Double v = parseStatValue(g.get(key2), key2);
            if (v != null) {
                sum += v;
                count++;
            }
        }
        return count == 0 ? NOT_FOUND : (Object) Double.valueOf(sum / count);
    }

    public Object[][] nbaBoxScore(Object gameIdArg, Object apiKeyArg) {
        final String apiKey = NbaClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);
        Double gidNum = optDouble(gameIdArg);
        if (gidNum == null) return sentinelTable(NOT_FOUND);
        final String gameId = fmtNum(gidNum);

        String key = "stats:game=" + gameId + ";k=" + keyTag(apiKey);
        NbaCache.Result r = NbaCache.get(key, TTL_STATS, new NbaCache.Fetcher() {
            public Object fetch() throws Exception { return NbaClient.statsByGame(gameId, apiKey); }
        });
        if (LOADING.equals(status(r))) return sentinelTable(LOADING);
        if (ERR.equals(status(r))) return sentinelTable(ERR);

        List<Map<String, Object>> rows = cast(r.data);
        if (rows.isEmpty()) return sentinelTable(NOT_FOUND);
        Object[][] out = new Object[rows.size()][11];
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> player = nested(row, "player");
            Map<String, Object> team = nested(row, "team");
            out[i][0] = (str(player.get("first_name")) + " " + str(player.get("last_name"))).trim();
            out[i][1] = str(team.get("abbreviation"));
            out[i][2] = row.get("min");
            out[i][3] = row.get("pts");
            out[i][4] = row.get("reb");
            out[i][5] = row.get("ast");
            out[i][6] = row.get("stl");
            out[i][7] = row.get("blk");
            out[i][8] = row.get("fg_pct");
            out[i][9] = row.get("fg3_pct");
            out[i][10] = row.get("ft_pct");
        }
        return out;
    }

    public String nbaLastError() {
        return NbaCache.lastError();
    }

    public double nbaCacheClear() {
        return NbaCache.clear();
    }

    // ------------------------------------------------------------------ //
    // Domain helpers                                                      //
    // ------------------------------------------------------------------ //

    private static String status(NbaCache.Result r) {
        if (NbaCache.Result.LOADING.equals(r.status)) return LOADING;
        if (NbaCache.Result.ERROR.equals(r.status)) return ERR;
        return NbaCache.Result.READY;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private static Map<String, Object> nested(Map<String, Object> row, String field) {
        Object o = row.get(field);
        if (o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            return m;
        }
        return Collections.emptyMap();
    }

    private static Object rowConference(Map<String, Object> row) {
        Object c = row.get("conference");
        return c != null ? c : nested(row, "team").get("conference");
    }

    private static Object firstNonNull(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static Map<String, Object> matchTeam(List<Map<String, Object>> teams, String query) {
        String q = query.trim().toLowerCase();
        for (Map<String, Object> t : teams) {
            if (q.equals(strLower(t.get("abbreviation")))) return t;
        }
        for (Map<String, Object> t : teams) {
            if (q.equals(strLower(t.get("full_name")))
                    || q.equals(strLower(t.get("name")))
                    || q.equals(strLower(t.get("city")))) {
                return t;
            }
        }
        return null;
    }

    private static String strLower(Object o) {
        return o == null ? "" : String.valueOf(o).trim().toLowerCase();
    }

    private static String dateOnly(Object o) {
        String s = str(o);
        int t = s.indexOf('T');
        return t > 0 ? s.substring(0, t) : s;
    }

    /** Parse a raw stat field value to a Double for averaging, honoring the "MM:SS" minutes format. */
    private static Double parseStatValue(Object raw, String statKey) {
        if (raw == null) return null;
        if ("min".equals(statKey) && raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) return null;
            int c = s.indexOf(':');
            if (c >= 0) {
                try {
                    double mins = Double.parseDouble(s.substring(0, c));
                    double secs = Double.parseDouble(s.substring(c + 1));
                    return mins + secs / 60.0;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try {
            String s = String.valueOf(raw).trim();
            return s.isEmpty() ? null : Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Object[][] sentinelTable(String sentinel) {
        return new Object[][] { { sentinel } };
    }

    private static String fmtNum(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    /**
     * A short, stable fingerprint of an API key, appended to cache keys so
     * that two different keys (e.g. a formula-supplied key overriding the
     * environment) never share cached data or a cached error/cooldown state.
     */
    private static String keyTag(String apiKey) {
        return Integer.toHexString(apiKey.hashCode());
    }

    private static String teamsKey(String apiKey) {
        return "teams;k=" + keyTag(apiKey);
    }

    private static String playersKey(String term, String apiKey) {
        return "players:search=" + term.toLowerCase() + ";k=" + keyTag(apiKey);
    }

    private static String standingsKey(String season, String apiKey) {
        return "standings:season=" + season + ";k=" + keyTag(apiKey);
    }

    // ------------------------------------------------------------------ //
    // Argument / value helpers                                           //
    // ------------------------------------------------------------------ //

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** Unwrap a 1x1 matrix (a single-cell reference may arrive as Object[][]). */
    private static Object scalar(Object arg) {
        if (arg instanceof Object[][]) {
            Object[][] m = (Object[][]) arg;
            return (m.length > 0 && m[0].length > 0) ? m[0][0] : null;
        }
        return arg;
    }

    /** Interpret an optional string argument; VOID/empty -> null. */
    private static String optString(Object arg) {
        arg = scalar(arg);
        if (arg == null || arg instanceof Any) {
            return null; // omitted argument arrives as VOID Any
        }
        String s = String.valueOf(arg).trim();
        return s.isEmpty() ? null : s;
    }

    /** Interpret an optional numeric argument; VOID/empty/non-numeric -> null. */
    private static Double optDouble(Object arg) {
        arg = scalar(arg);
        if (arg == null || arg instanceof Any) {
            return null;
        }
        if (arg instanceof Number) {
            return ((Number) arg).doubleValue();
        }
        String s = String.valueOf(arg).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Double d) {
        return d == null ? null : Long.valueOf(d.longValue());
    }

    private static Long toLong(Object o) {
        if (o instanceof Number) return Long.valueOf(((Number) o).longValue());
        if (o instanceof String) {
            try {
                return Long.valueOf((long) Double.parseDouble(((String) o).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).trim());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    // ------------------------------------------------------------------ //
    // XAddIn - function metadata                                         //
    //                                                                    //
    // Calc uses getDisplayFunctionName() as the AUTHORITATIVE display    //
    // (formula) name; CalcAddIns.xcu only supplies wizard help. So these //
    // must map programmatic <-> display names explicitly, or the cell    //
    // formula (=NBA_TEAMS()) resolves to #NAME?.                        //
    // ------------------------------------------------------------------ //

    /** { programmatic, display } for every exposed function. */
    private static final String[][] FUNCS = {
        { "nbaTeamId",       "NBA_TEAMID" },
        { "nbaTeams",        "NBA_TEAMS" },
        { "nbaPlayerSearch", "NBA_PLAYERSEARCH" },
        { "nbaPlayerId",     "NBA_PLAYERID" },
        { "nbaGames",        "NBA_GAMES" },
        { "nbaScore",        "NBA_SCORE" },
        { "nbaStandings",    "NBA_STANDINGS" },
        { "nbaTeamRank",     "NBA_TEAMRANK" },
        { "nbaPlayerStat",   "NBA_PLAYERSTAT" },
        { "nbaBoxScore",     "NBA_BOXSCORE" },
        { "nbaLastError",    "NBA_LASTERROR" },
        { "nbaCacheClear",   "NBA_CACHECLEAR" },
    };

    private static String funcDescription(String prog) {
        if ("nbaTeamId".equals(prog)) return "Resolves a team name or abbreviation to its numeric team id.";
        if ("nbaTeams".equals(prog)) return "Lists every NBA team as a spillable array.";
        if ("nbaPlayerSearch".equals(prog)) return "Searches players by name; returns a spillable array of matches.";
        if ("nbaPlayerId".equals(prog)) return "Resolves a player's full name to their numeric player id (best match).";
        if ("nbaGames".equals(prog)) return "Lists games for a date or a season, optionally filtered to one team.";
        if ("nbaScore".equals(prog)) return "Returns the most recent (or Nth most recent) game result for a team in a season.";
        if ("nbaStandings".equals(prog)) return "Returns league standings for a season as a spillable array.";
        if ("nbaTeamRank".equals(prog)) return "Returns a team's conference rank in a season.";
        if ("nbaPlayerStat".equals(prog)) return "Returns a player's season-average value for a given stat.";
        if ("nbaBoxScore".equals(prog)) return "Returns the per-player box score for a game as a spillable array.";
        if ("nbaLastError".equals(prog)) return "Returns the most recent fetch error message, for diagnostics.";
        if ("nbaCacheClear".equals(prog)) return "Clears every cached response; returns the number of entries cleared.";
        return "";
    }

    private static final String ARG_KEY = "api_key";

    private static String[] argNames(String prog) {
        if ("nbaTeamId".equals(prog)) return new String[] { "name_or_abbrev", ARG_KEY };
        if ("nbaTeams".equals(prog)) return new String[] { ARG_KEY };
        if ("nbaPlayerSearch".equals(prog)) return new String[] { "name", ARG_KEY };
        if ("nbaPlayerId".equals(prog)) return new String[] { "full_name", ARG_KEY };
        if ("nbaGames".equals(prog)) return new String[] { "date_or_season", "team_id", ARG_KEY };
        if ("nbaScore".equals(prog)) return new String[] { "season", "abbrev", "nth", ARG_KEY };
        if ("nbaStandings".equals(prog)) return new String[] { "season", "conference", ARG_KEY };
        if ("nbaTeamRank".equals(prog)) return new String[] { "season", "abbrev", ARG_KEY };
        if ("nbaPlayerStat".equals(prog)) return new String[] { "season", "id", "stat_key", ARG_KEY };
        if ("nbaBoxScore".equals(prog)) return new String[] { "game_id", ARG_KEY };
        return new String[0];
    }

    private static final String ARG_KEY_DESC =
        "Optional. balldontlie API key for this call; overrides the environment "
        + "(system property, BALLDONTLIE_API_KEY, or properties file) when supplied. "
        + "May reference a cell.";

    private static String[] argDescriptions(String prog) {
        if ("nbaTeamId".equals(prog)) {
            return new String[] { "Team full name, city, name, or abbreviation, e.g. \"Lakers\" or \"LAL\".", ARG_KEY_DESC };
        }
        if ("nbaTeams".equals(prog)) {
            return new String[] { ARG_KEY_DESC };
        }
        if ("nbaPlayerSearch".equals(prog)) {
            return new String[] { "A full or partial player name.", ARG_KEY_DESC };
        }
        if ("nbaPlayerId".equals(prog)) {
            return new String[] { "Player name to search for, e.g. \"LeBron James\".", ARG_KEY_DESC };
        }
        if ("nbaGames".equals(prog)) {
            return new String[] {
                "An ISO date \"YYYY-MM-DD\" (games on that date) or a 4-digit season year \"YYYY\" (games in that season).",
                "Optional. A numeric team id from NBA_TEAMID to filter to (season mode).",
                ARG_KEY_DESC,
            };
        }
        if ("nbaScore".equals(prog)) {
            return new String[] {
                "4-digit season year, e.g. \"2024\".",
                "Team abbreviation, e.g. \"LAL\".",
                "Optional. 1 = most recent game (default), 2 = second most recent, etc.",
                ARG_KEY_DESC,
            };
        }
        if ("nbaStandings".equals(prog)) {
            return new String[] {
                "4-digit season year, e.g. \"2024\".",
                "Optional. \"East\" or \"West\" to filter to one conference.",
                ARG_KEY_DESC,
            };
        }
        if ("nbaTeamRank".equals(prog)) {
            return new String[] { "4-digit season year, e.g. \"2024\".", "Team abbreviation, e.g. \"LAL\".", ARG_KEY_DESC };
        }
        if ("nbaPlayerStat".equals(prog)) {
            return new String[] {
                "4-digit season year, e.g. \"2024\".",
                "Numeric player id (from NBA_PLAYERID).",
                "One of: pts, reb, ast, stl, blk, turnover, pf, fgm, fga, fg_pct, fg3m, fg3a, fg3_pct, ftm, fta, ft_pct, oreb, dreb, min.",
                ARG_KEY_DESC,
            };
        }
        if ("nbaBoxScore".equals(prog)) {
            return new String[] { "Numeric game id.", ARG_KEY_DESC };
        }
        return new String[0];
    }

    public String getProgrammaticFuntionName(String displayName) {
        for (String[] f : FUNCS) {
            if (f[1].equals(displayName)) return f[0];
        }
        return "";
    }

    public String getDisplayFunctionName(String programmaticName) {
        for (String[] f : FUNCS) {
            if (f[0].equals(programmaticName)) return f[1];
        }
        return "";
    }

    public String getFunctionDescription(String programmaticName) {
        return funcDescription(programmaticName);
    }

    public String getDisplayArgumentName(String programmaticName, int argument) {
        String[] a = argNames(programmaticName);
        return (argument >= 0 && argument < a.length) ? a[argument] : "";
    }

    public String getArgumentDescription(String programmaticName, int argument) {
        String[] a = argDescriptions(programmaticName);
        return (argument >= 0 && argument < a.length) ? a[argument] : "";
    }

    public String getProgrammaticCategoryName(String programmaticName) {
        return "Add-In";
    }

    public String getDisplayCategoryName(String programmaticName) {
        return "Add-In";
    }

    // ------------------------------------------------------------------ //
    // XLocalizable (inherited via XAddIn)                                //
    // ------------------------------------------------------------------ //

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public Locale getLocale() {
        return locale;
    }

    // ------------------------------------------------------------------ //
    // XServiceName / XServiceInfo                                        //
    // ------------------------------------------------------------------ //

    public String getServiceName() {
        return IMPLEMENTATION_NAME;
    }

    public String getImplementationName() {
        return IMPLEMENTATION_NAME;
    }

    public boolean supportsService(String service) {
        for (String s : SERVICE_NAMES) {
            if (s.equals(service)) return true;
        }
        return false;
    }

    public String[] getSupportedServiceNames() {
        return SERVICE_NAMES.clone();
    }

    // ------------------------------------------------------------------ //
    // UNO component registration entry points                           //
    // ------------------------------------------------------------------ //

    public static XSingleComponentFactory __getComponentFactory(String implName) {
        if (IMPLEMENTATION_NAME.equals(implName)) {
            return Factory.createComponentFactory(NbaImpl.class, SERVICE_NAMES);
        }
        return null;
    }

    public static boolean __writeRegistryServiceInfo(XRegistryKey regKey) {
        return Factory.writeRegistryServiceInfo(IMPLEMENTATION_NAME, SERVICE_NAMES, regKey);
    }
}
