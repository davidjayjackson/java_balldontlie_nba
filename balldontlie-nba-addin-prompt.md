# Claude Code Prompt: LibreOffice Calc Add-In for NBA Data (balldontlie API)

## Goal

Build a free, open-source (MIT License) LibreOffice Calc add-in that exposes NBA
teams, games, standings, player-search, and player/game statistics as spreadsheet
functions, sourced from the **balldontlie** API. The add-in must run inside the
LibreOffice-embedded JVM and follow the same architecture as my prior Calc add-ins.

## Critical API facts (verified July 2026 — do not assume the old keyless behavior)

- balldontlie **now requires a free API key** on every request. There is no keyless
  access anymore. The key is passed as an HTTP header:
  `Authorization: <API_KEY>` (raw key, **no** `Bearer` prefix).
- Base URL: `https://api.balldontlie.io`
- NBA endpoints are versioned under `/nba/v1/` (plus a few legacy `/v1/` paths).
  Assume `/nba/v1/` and make the version path a constant so it's easy to change.
- Cursor-based pagination via a `meta.next_cursor` field; request the next page with
  `?cursor=NEXT_CURSOR`. `per_page` defaults to 25, max 100.
- Free tier has generous but real rate limits (roughly a few requests/min on free,
  higher on paid). The add-in must handle HTTP 429 gracefully with backoff.
- Data range: 1946–current.

### Endpoints to support
- `GET /nba/v1/teams` — all teams (id, full_name, abbreviation, conference, division, city, name)
- `GET /nba/v1/players?search=<name>` — player search (cursor-paginated)
- `GET /nba/v1/games?dates[]=YYYY-MM-DD` and `?seasons[]=YYYY&team_ids[]=<id>` — games/scores
- `GET /nba/v1/standings?season=YYYY` — standings
- `GET /nba/v1/stats?game_ids[]=<id>` and `?seasons[]=YYYY&player_ids[]=<id>` — per-game box stats
- (Optional, guard behind a note that it may need a paid tier) `GET /nba/v1/season_averages/...`

Query-array params use the PHP-style `key[]=value` repeated-key convention. Multiple
values = repeat the key. URL-encode everything.

## API key handling

Cell functions must never contain the key as a literal. Resolve the key in this order:

1. Java system property `balldontlie.apiKey`
2. Environment variable `BALLDONTLIE_API_KEY`
3. A properties file at `~/.config/libreoffice-nba/balldontlie.properties`
   containing `apiKey=...`

If no key is found, functions return a clear sentinel string like
`#NO_API_KEY` (not an exception, not a silent blank) so the user sees what's wrong
in the cell. Document all three mechanisms in the README.

## Architecture constraints (LibreOffice-embedded JVM)

These are hard requirements from prior add-ins — do not deviate:

- **Java 8 source/target.** No records, no `var`, no `java.net.http.HttpClient`,
  no `switch` expressions, no text blocks. Use `HttpURLConnection` for networking.
- **Zero third-party dependencies.** No Jackson/Gson/Apache HTTP. Hand-roll a
  minimal JSON parser (or a small tolerant tokenizer sufficient for these responses)
  and manual string handling. Bundled deps risk classloader conflicts inside the
  LibreOffice JVM.
- **Add-In only pattern** (implement `com.sun.star.sheet.XAddIn` +
  `XServiceName`/`XServiceInfo`), registered via `.oxt`.
- **`CalcAddIn.xcu` MUST set `CompatibilityName`** for every function. Missing this
  causes silent data loss when the sheet is saved as XLS/XLSX and reopened — this is
  a recurring critical bug. Verify each function has both the programmatic name and a
  `CompatibilityName`.
- **Non-blocking cell functions.** Cell functions must never block on network I/O.
  Use the cache + background-fetch + sentinel-return pattern:
  - On first call for a given key, return a sentinel like `#LOADING` (or the cached
    stale value if present), and kick off an async fetch on a background thread.
  - When the fetch completes, store into the cache; the value appears on the next
    recalc (F9 / Ctrl+Shift+F9). Document this recalc behavior in the README.
  - Cache keyed by the full normalized request (endpoint + sorted params). Include a
    configurable TTL (default: scores/games 5 min, standings 1 hr, teams 24 hr).
  - Thread-safe cache (e.g. `ConcurrentHashMap`) with a bounded size / simple eviction.
  - Deduplicate in-flight requests so identical concurrent calls fetch once.
- Handle HTTP 429 and 5xx with bounded exponential backoff on the background thread;
  never spin the UI. Surface persistent failures as `#ERR` sentinels with a way to
  see the last error (e.g. a `NBA_LASTERROR()` function).

## Functions to expose (all with CompatibilityName set)

Design them to be spreadsheet-ergonomic. Where a call returns multiple rows/cols,
return a 2-D array (sequence-of-sequences) so it spills into a range. Provide
single-value convenience functions too.

- `NBA_TEAMID(teamName_or_abbrev)` → numeric team id
- `NBA_TEAMS()` → 2-D table of all teams
- `NBA_PLAYERSEARCH(name)` → 2-D table: id, first, last, position, team abbrev
- `NBA_PLAYERID(fullName)` → single best-match player id (document the match rule)
- `NBA_GAMES(dateOrSeason, [teamId])` → 2-D table of games w/ scores & status
- `NBA_SCORE(season, teamAbbrev, [nthMostRecent])` → single most-recent (or Nth) result string
- `NBA_STANDINGS(season, [conference])` → 2-D standings table
- `NBA_TEAMRANK(season, teamAbbrev)` → single conference rank
- `NBA_PLAYERSTAT(season, playerId, statKey)` → single season-agg or per-game stat
  (statKey ∈ pts, reb, ast, stl, blk, fg_pct, fg3_pct, ft_pct, min, etc.)
- `NBA_BOXSCORE(gameId)` → 2-D per-player box score for a game
- `NBA_LASTERROR()` → last error message string (diagnostics)
- `NBA_CACHECLEAR()` → clears cache, returns count cleared (a "recalc now" helper)

All functions need XML descriptions and per-parameter descriptions in `CalcAddIn.xcu`
so they show up in the Function Wizard.

## Build & packaging

- Plain Java 8, dependency-free. Prefer a simple `build.sh` (javac + jar + zip into
  `.oxt`) over Maven, matching my prior add-ins — but if a minimal `pom.xml` is
  cleaner for reproducibility, that's acceptable as long as it pulls **no runtime
  deps** and targets Java 8.
- Produce a working `.oxt` installable via `unopkg add` / Extension Manager.
- Include the LibreOffice URE/UNO jars on the compile classpath but **do not bundle**
  them (they're provided by the host).
- Cross-platform: Slackware, Debian, Ubuntu, Windows. No shell-specific assumptions in
  runtime code; keep `build.sh` POSIX and note the Windows build steps in the README.

## Deliverables

1. Full source tree (Java 8), including the hand-rolled JSON parser.
2. `CalcAddIn.xcu` with every function + CompatibilityName + wizard descriptions.
3. `description.xml`, `manifest.xml`, and the rest of the `.oxt` skeleton.
4. `build.sh` (and optional minimal `pom.xml`) that produces the `.oxt`.
5. `README.md` covering: API-key setup (all 3 mechanisms), install, the async/recalc
   behavior (why a cell shows `#LOADING` then resolves on recalc), rate-limit notes,
   the free-tier caveat, function reference with examples, and cross-platform build.
6. `LICENSE` (MIT).

## Working process (prompt-first, then code)

Before writing code, briefly confirm the architecture decisions above and flag any
tradeoff you'd resolve differently — especially: (a) JSON parser scope (how tolerant
it needs to be for these specific responses), (b) sentinel-string set and how errors
surface, (c) cache TTL defaults and eviction policy, (d) how array-returning functions
behave when the wizard/user places them in a single cell vs a range. Once those are
settled, generate the complete, compiling add-in.
