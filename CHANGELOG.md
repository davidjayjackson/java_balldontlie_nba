# Changelog

All notable changes to the balldontlie NBA LibreOffice Calc add-in are
documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-07-15

First release. A Java UNO add-in (`com.sun.star.sheet.AddIn`) exposing
[balldontlie](https://www.balldontlie.io/) NBA data as LibreOffice Calc
worksheet functions, packaged as `NBA.oxt`.

### Added
- Worksheet functions: `NBA_TEAMID`, `NBA_TEAMS`, `NBA_PLAYERSEARCH`,
  `NBA_PLAYERID`, `NBA_GAMES`, `NBA_SCORE`, `NBA_STANDINGS`, `NBA_TEAMRANK`,
  `NBA_PLAYERSTAT`, `NBA_BOXSCORE`, `NBA_LASTERROR`, `NBA_CACHECLEAR`.
- Non-blocking cell functions: a shared, TTL'd, background-refreshed cache
  (`NbaCache`) so no formula ever blocks on network I/O. Functions return
  `#LOADING`, `#NO_API_KEY`, `#NOT_FOUND`, or `#ERR` sentinels instead of
  throwing; `NBA_LASTERROR()` surfaces the last failure's detail.
- Three-tier API key resolution — `balldontlie.apiKey` system property,
  `BALLDONTLIE_API_KEY` environment variable, or
  `~/.config/libreoffice-nba/balldontlie.properties` — never hardcoded and
  never a formula argument (balldontlie requires a key on every request as
  of July 2026, sent as the raw `Authorization` header value).
- Bounded exponential backoff on HTTP 429/5xx (honoring `Retry-After`) plus
  a global request throttle, respecting the free tier's rate limit.
- Cursor-based pagination (`meta.next_cursor`) across all list endpoints.
- `CompatibilityName` set for every function in `CalcAddIns.xcu`, so
  formulas survive an XLS/XLSX save-as/reopen round trip.
- Build pipeline (`build.sh`: `unoidl-write` → `javamaker` →
  `javac --release 8` → `jar` → `.oxt`) and headless smoke tests
  (`tools/test_nba.py` with no key configured, `tools/test_nba_live.py`
  against a real key).
- MIT license.

### Notes
- Pure JDK implementation: `HttpURLConnection` + a hand-rolled JSON parser,
  no third-party jars. Compiled to Java 8 bytecode so it runs on the JRE 8
  LibreOffice accepts by default.
- `NBA_PLAYERSTAT` computes a simple mean across a player's games in the
  season (percentage fields are not attempt-weighted).
- Verified live against the real balldontlie API: `NBA_TEAMID`, `NBA_TEAMS`,
  `NBA_PLAYERID`, `NBA_PLAYERSEARCH`, `NBA_GAMES`, and `NBA_SCORE` all work
  on a free-tier key; `NBA_STANDINGS`/`NBA_TEAMRANK`/`NBA_PLAYERSTAT`/
  `NBA_BOXSCORE` return `#ERR` (HTTP 401) until upgrading, since `/standings`
  and `/stats` are paid-tier endpoints. Also confirmed live: the real rate
  limit is 5 req/min (tighter than initially assumed; the client throttle
  was tuned to match), and balldontlie's `search` param only substring-
  matches a single name field, so `NBA_PLAYERID`/`NBA_PLAYERSEARCH` now
  search on the last word of a multi-word query and rank full matches first.
