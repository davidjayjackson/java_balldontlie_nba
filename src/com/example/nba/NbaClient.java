package com.example.nba;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Thin client over the balldontlie NBA REST API (https://api.balldontlie.io).
 *
 * <ul>
 *   <li>Uses only the JDK: {@link HttpURLConnection} for I/O and the
 *       hand-rolled {@link Json} parser, so no third-party jars are bundled
 *       (avoids classloader conflicts inside the LibreOffice-embedded JVM).</li>
 *   <li>The API key is never hardcoded and never passed through a cell
 *       formula. It is resolved, in order, from: the {@code balldontlie.apiKey}
 *       Java system property, the {@code BALLDONTLIE_API_KEY} environment
 *       variable, or {@code ~/.config/libreoffice-nba/balldontlie.properties}
 *       ({@code apiKey=...}). See the README.</li>
 *   <li>Sent as the raw {@code Authorization} header value (no "Bearer"
 *       prefix) - balldontlie has required a key on every request since
 *       July 2026.</li>
 *   <li>HTTP 429 / 5xx responses are retried with bounded exponential
 *       backoff (honoring a numeric {@code Retry-After} header when
 *       present); other non-200 responses fail immediately.</li>
 *   <li>A simple global throttle spaces outgoing requests out, since the
 *       free tier only allows a few requests per minute.</li>
 * </ul>
 */
final class NbaClient {

    private static final String BASE = "https://api.balldontlie.io/nba/v1";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 1000;
    // Measured live: the free tier enforces 5 requests/minute (x-ratelimit-limit: 5).
    // 12s is the exact steady-state spacing for that; pad slightly for safety.
    private static final long MIN_REQUEST_INTERVAL_MS = 13000;

    private static final Object THROTTLE_LOCK = new Object();
    private static long lastRequestAt = 0;

    private NbaClient() {
    }

    // ------------------------------------------------------------------ //
    // API key resolution                                                 //
    // ------------------------------------------------------------------ //

    /**
     * Resolve the balldontlie API key, or {@code null} if none is configured.
     *
     * @param explicit an API key supplied directly as a formula argument; if
     *                 non-blank it wins outright, bypassing every other
     *                 mechanism below.
     */
    static String resolveApiKey(String explicit) {
        if (isSet(explicit)) {
            return explicit.trim();
        }
        String k = System.getProperty("balldontlie.apiKey");
        if (isSet(k)) {
            return k.trim();
        }
        k = System.getenv("BALLDONTLIE_API_KEY");
        if (isSet(k)) {
            return k.trim();
        }
        File propsFile = new File(System.getProperty("user.home"),
                ".config/libreoffice-nba/balldontlie.properties");
        if (propsFile.isFile()) {
            Properties p = new Properties();
            InputStream in = null;
            try {
                in = new FileInputStream(propsFile);
                p.load(in);
                k = p.getProperty("apiKey");
                if (isSet(k)) {
                    return k.trim();
                }
            } catch (IOException ignored) {
                // fall through to null
            } finally {
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) { }
                }
            }
        }
        return null;
    }

    private static boolean isSet(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ------------------------------------------------------------------ //
    // HTTP + pagination                                                   //
    // ------------------------------------------------------------------ //

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e); // never happens
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static void throttle() {
        synchronized (THROTTLE_LOCK) {
            long wait = lastRequestAt + MIN_REQUEST_INTERVAL_MS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    /** GET a fully-formed URL, retrying on 429/5xx with bounded exponential backoff. */
    private static String httpGet(String url, String apiKey) throws IOException {
        long backoff = INITIAL_BACKOFF_MS;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", apiKey);

                int status = conn.getResponseCode();
                if (status == 200) {
                    return readAll(conn.getInputStream());
                }

                String body = readAll(conn.getErrorStream());
                if (status == 429 || status >= 500) {
                    long retryAfter = retryAfterMillis(conn, backoff);
                    lastFailure = new IOException("balldontlie returned HTTP " + status
                            + (body.isEmpty() ? "" : ": " + body));
                    if (attempt < MAX_ATTEMPTS) {
                        try {
                            Thread.sleep(retryAfter);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw lastFailure;
                        }
                        backoff *= 2;
                        continue;
                    }
                    throw lastFailure;
                }

                throw new IOException("balldontlie returned HTTP " + status
                        + (body.isEmpty() ? "" : ": " + body));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw lastFailure != null ? lastFailure
                : new IOException("balldontlie request failed after " + MAX_ATTEMPTS + " attempts");
    }

    private static long retryAfterMillis(HttpURLConnection conn, long fallback) {
        String header = conn.getHeaderField("Retry-After");
        if (header != null) {
            try {
                return Long.parseLong(header.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // not a numeric seconds value; fall through
            }
        }
        return fallback;
    }

    /** Format a JSON-parsed cursor/id number without a trailing ".0". */
    private static String numStr(Object n) {
        if (n instanceof Double) {
            double d = (Double) n;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(n);
    }

    /**
     * Fetch every page of a cursor-paginated endpoint (up to {@code maxPages}
     * pages), concatenating each page's {@code data} array.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fetchAllPages(String baseUrl, int maxPages, String apiKey)
            throws IOException {
        List<Map<String, Object>> all = new ArrayList<Map<String, Object>>();
        String cursor = null;
        for (int page = 0; page < maxPages; page++) {
            String url = baseUrl;
            if (cursor != null) {
                url += (baseUrl.indexOf('?') >= 0 ? "&" : "?") + "cursor=" + enc(cursor);
            }
            Object root = Json.parse(httpGet(url, apiKey));
            if (!(root instanceof Map)) {
                throw new IOException("Unexpected balldontlie response shape");
            }
            Map<String, Object> map = (Map<String, Object>) root;
            Object data = map.get("data");
            if (data instanceof List) {
                for (Object o : (List<Object>) data) {
                    if (o instanceof Map) {
                        all.add((Map<String, Object>) o);
                    }
                }
            }
            Object meta = map.get("meta");
            String next = null;
            if (meta instanceof Map) {
                Object nc = ((Map<String, Object>) meta).get("next_cursor");
                if (nc != null) {
                    next = numStr(nc);
                }
            }
            if (next == null || next.isEmpty()) {
                break;
            }
            cursor = next;
        }
        return all;
    }

    // ------------------------------------------------------------------ //
    // Endpoints                                                           //
    // ------------------------------------------------------------------ //

    static List<Map<String, Object>> teams(String apiKey) throws IOException {
        return fetchAllPages(BASE + "/teams?per_page=100", 3, apiKey);
    }

    static List<Map<String, Object>> playersSearch(String name, String apiKey) throws IOException {
        return fetchAllPages(BASE + "/players?search=" + enc(name) + "&per_page=100", 1, apiKey);
    }

    static List<Map<String, Object>> gamesByDate(String isoDate, String apiKey) throws IOException {
        return fetchAllPages(BASE + "/games?dates[]=" + enc(isoDate) + "&per_page=100", 2, apiKey);
    }

    static List<Map<String, Object>> gamesBySeason(String season, Long teamId, String apiKey) throws IOException {
        StringBuilder u = new StringBuilder(BASE).append("/games?seasons[]=").append(enc(season)).append("&per_page=100");
        if (teamId != null) {
            u.append("&team_ids[]=").append(teamId);
        }
        return fetchAllPages(u.toString(), 3, apiKey);
    }

    static List<Map<String, Object>> standings(String season, String apiKey) throws IOException {
        return fetchAllPages(BASE + "/standings?season=" + enc(season), 1, apiKey);
    }

    static List<Map<String, Object>> statsByGame(String gameId, String apiKey) throws IOException {
        return fetchAllPages(BASE + "/stats?game_ids[]=" + enc(gameId) + "&per_page=100", 2, apiKey);
    }

    static List<Map<String, Object>> statsBySeasonPlayer(String season, String playerId, String apiKey)
            throws IOException {
        return fetchAllPages(BASE + "/stats?seasons[]=" + enc(season) + "&player_ids[]=" + enc(playerId)
                + "&per_page=100", 5, apiKey);
    }
}
