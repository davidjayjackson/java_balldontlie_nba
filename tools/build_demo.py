"""Generate test/nba_demo.ods using the installed balldontlie NBA add-in,
built around the Boston Celtics.

Run against a headless LibreOffice that has a balldontlie API key resolvable
(env var, system property, or ~/.config/libreoffice-nba/balldontlie.properties)
and is listening on a UNO socket; see docs/INSTALL.md. Produces a spreadsheet
whose cells contain live NBA_* formulas, with values already computed.

Formulas are entered and resolved one distinct-cache-key group at a time
(rather than all at once) because the add-in throttles outgoing balldontlie
requests to ~13s apart to respect the free tier's 5 req/min limit -- firing
every formula simultaneously would queue them all behind that single-file
throttle and blow past any reasonable per-cell wait budget.

Functions that need a paid balldontlie plan (standings, stats, box scores)
are included for reference too -- on a free-tier key they'll show #ERR,
which is correct, documented behavior (see NBA_LASTERROR in the sheet).

The sheet also demonstrates the optional trailing api_key argument, using a
NBA_TEAMID call that reads its key from a cell. That cell holds an obvious
placeholder ("YOUR_API_KEY_HERE"), never a real key -- this file is public,
so a real key baked into a cell would be a credential leak the moment it's
committed. The placeholder is intentionally invalid, so the demo correctly
shows #ERR (the argument really did override the environment and get
rejected), not a silent fallback success.
"""
import os
import sys
import time
import uno
from com.sun.star.beans import PropertyValue

BOS_GAME_ID = 1037597  # 2023-10-25, Celtics @ Knicks (season "2023" = 2023-24)


def connect(port=2002, tries=60):
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext(
        "com.sun.star.bridge.UnoUrlResolver", local)
    url = "uno:socket,host=localhost,port=%d;urp;StarOffice.ComponentContext" % port
    last = None
    for _ in range(tries):
        try:
            return resolver.resolve(url)
        except Exception as e:
            last = e
            time.sleep(0.5)
    raise SystemExit("could not connect to LibreOffice: %s" % last)


def main():
    out_path = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "test", "nba_demo.ods"))
    out_url = uno.systemPathToFileUrl(out_path)

    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    try:
        sh = doc.Sheets.getByIndex(0)
        sh.Name = "Celtics Demo"

        def put(col, row, value):
            sh.getCellByPosition(col, row).setString(value)

        def formula(col, row, f):
            c = sh.getCellByPosition(col, row)
            c.setFormula(f)
            return c

        def array_formula(c0, r0, c1, r1, f):
            rng = sh.getCellRangeByPosition(c0, r0, c1, r1)
            rng.setArrayFormula(f)
            return rng

        def settle(cell_or_range, max_wait=60, poll=2.0):
            """Recalc until the top-left value moves off #LOADING, or timeout."""
            deadline = time.time() + max_wait
            top_left = cell_or_range.getCellByPosition(0, 0) if hasattr(cell_or_range, "getCellByPosition") else cell_or_range
            doc.calculateAll()
            while top_left.getString() == "#LOADING" and time.time() < deadline:
                time.sleep(poll)
                doc.calculateAll()
            return top_left.getString()

        put(0, 0, "balldontlie NBA Calc Add-In - demo (Boston Celtics)")
        put(0, 1, "Configure a balldontlie API key (see README), then recalc with Ctrl+Shift+F9.")
        put(0, 2, "#LOADING means a background fetch just started - recalc again once it settles.")
        put(0, 3, "See the bottom of this sheet for the optional api_key argument (cell-referenced).")

        put(0, 4, "Function")
        put(1, 4, "Live result")
        put(2, 4, "Formula")
        row = {}
        r = 5
        for key in ("teamid", "score1", "score2", "teamrank", "playerid", "playerstat", "lasterror"):
            row[key] = r
            r += 1

        labels = {
            "teamid": "NBA_TEAMID",
            "score1": "NBA_SCORE (most recent, 2023-24)",
            "score2": "NBA_SCORE (2nd most recent)",
            "teamrank": "NBA_TEAMRANK  [needs paid tier]",
            "playerid": "NBA_PLAYERID",
            "playerstat": "NBA_PLAYERSTAT pts  [needs paid tier]",
            "lasterror": "NBA_LASTERROR",
        }
        for key, label in labels.items():
            put(0, row[key], label)

        # --- Phase 1: NBA_TEAMID -> triggers the "teams" fetch. Wait for it. ---
        f = '=NBA_TEAMID("Boston Celtics")'
        put(2, row["teamid"], f)
        c = formula(1, row["teamid"], f)
        print("teamid ->", settle(c))

        # --- Phase 2: NBA_SCORE (most recent) -> triggers the season/team
        # games fetch ("teams" is already cached). Wait for it. ---
        f = '=NBA_SCORE("2023";"BOS")'
        put(2, row["score1"], f)
        c = formula(1, row["score1"], f)
        print("score1 ->", settle(c))

        # --- Phase 3: 2nd-most-recent score reuses the same cached games
        # fetch from phase 2 -- should resolve immediately. ---
        f = '=NBA_SCORE("2023";"BOS";2)'
        put(2, row["score2"], f)
        c = formula(1, row["score2"], f)
        print("score2 ->", settle(c, max_wait=15))

        # --- Phase 4: NBA_GAMES array formula reuses the same cached games
        # fetch -- should resolve immediately, no new network call. ---
        put(0, 14, "NBA_GAMES (array formula, BOS 2023-24 season, first 15 rows shown)")
        games_header = 15
        for i, h in enumerate(["date", "home", "home_score", "away", "away_score", "status"]):
            put(i, games_header, h)
        games_first = games_header + 1
        games_f = '=NBA_GAMES("2023";NBA_TEAMID("Boston Celtics"))'
        games_rng = array_formula(0, games_first, 5, games_first + 14, games_f)  # A..F, 15 rows
        print("games ->", settle(games_rng, max_wait=15))
        put(0, games_first + 16, "{" + games_f + "}  (select range, Ctrl+Shift+Enter)")

        # --- Phase 5: NBA_PLAYERID -> triggers the "players:search=tatum"
        # fetch. Wait for it. ---
        f = '=NBA_PLAYERID("Jayson Tatum")'
        put(2, row["playerid"], f)
        c = formula(1, row["playerid"], f)
        print("playerid ->", settle(c))

        # --- Phase 6: NBA_PLAYERSEARCH array reuses the same cached search. ---
        r = games_first + 18
        put(0, r, 'NBA_PLAYERSEARCH (array formula, search="Tatum")')
        ps_header = r + 1
        for i, h in enumerate(["id", "first_name", "last_name", "position", "team"]):
            put(i, ps_header, h)
        ps_first = ps_header + 1
        ps_f = '=NBA_PLAYERSEARCH("Tatum")'
        ps_rng = array_formula(0, ps_first, 4, ps_first + 4, ps_f)  # A..E, 5 rows
        print("playersearch ->", settle(ps_rng, max_wait=15))
        put(0, ps_first + 6, "{" + ps_f + "}  (select range, Ctrl+Shift+Enter)")

        # --- Phase 7: NBA_TEAMRANK -> triggers the "standings" fetch
        # (fails fast with #ERR on a free-tier key: /standings is paid-tier). ---
        f = '=NBA_TEAMRANK("2023";"BOS")'
        put(2, row["teamrank"], f)
        c = formula(1, row["teamrank"], f)
        print("teamrank ->", settle(c, max_wait=30))

        # --- Phase 8: NBA_STANDINGS array reuses the cached (failed)
        # standings lookup from phase 7 -- no new network call. ---
        r = ps_first + 8
        put(0, r, "NBA_STANDINGS (array formula, East)  [needs paid tier - shows #ERR on a free key]")
        st_header = r + 1
        for i, h in enumerate(["conference", "division", "team", "wins", "losses", "win_pct", "conf_rank"]):
            put(i, st_header, h)
        st_first = st_header + 1
        st_f = '=NBA_STANDINGS("2023";"East")'
        st_rng = array_formula(0, st_first, 6, st_first + 4, st_f)  # A..G, 5 rows
        print("standings ->", settle(st_rng, max_wait=20))
        put(0, st_first + 6, "{" + st_f + "}  (select range, Ctrl+Shift+Enter)")

        # --- Phase 9: NBA_PLAYERSTAT -> triggers the "stats:season+player"
        # fetch (fails fast with #ERR: /stats is paid-tier). NBA_PLAYERID
        # is already cached from phase 5, so it resolves instantly here. ---
        f = '=NBA_PLAYERSTAT("2023";NBA_PLAYERID("Jayson Tatum");"pts")'
        put(2, row["playerstat"], f)
        c = formula(1, row["playerstat"], f)
        print("playerstat ->", settle(c, max_wait=30))

        # --- Phase 10: NBA_BOXSCORE -> a *different* stats cache key
        # ("stats:game=...") so it needs its own fetch (also #ERR). ---
        r = st_first + 8
        put(0, r, "NBA_BOXSCORE (array formula, game %d, BOS @ NYK 2023-10-25)  [needs paid tier - shows #ERR on a free key]" % BOS_GAME_ID)
        bx_header = r + 1
        for i, h in enumerate(["player", "team", "min", "pts", "reb", "ast", "stl", "blk", "fg_pct", "fg3_pct", "ft_pct"]):
            put(i, bx_header, h)
        bx_first = bx_header + 1
        bx_f = "=NBA_BOXSCORE(%d)" % BOS_GAME_ID
        bx_rng = array_formula(0, bx_first, 10, bx_first + 9, bx_f)  # A..K, 10 rows
        print("boxscore ->", settle(bx_rng, max_wait=30))
        put(0, bx_first + 11, "{" + bx_f + "}  (select range, Ctrl+Shift+Enter)")

        # --- Phase 11: NBA_LASTERROR, entered last so it reflects the most
        # recent (paid-tier) failure above. ---
        f = '=NBA_LASTERROR()'
        put(2, row["lasterror"], f)
        c = formula(1, row["lasterror"], f)
        doc.calculateAll()
        print("lasterror ->", c.getString())

        # --- Phase 12: the optional trailing api_key argument. The key cell
        # holds an obvious PLACEHOLDER, never a real key -- this file is
        # public, and a real key pasted into a cell would be a credential
        # leak the moment it's committed. The placeholder is intentionally
        # invalid, so this correctly demonstrates the argument overriding
        # the environment (a distinct cache entry, a fresh fetch, and a
        # real #ERR from balldontlie rejecting the bogus key) rather than
        # silently falling back to the environment's real key. Replace the
        # placeholder with your own key to see it succeed. ---
        r = bx_first + 13
        put(0, r, "Using the api_key argument (overrides the environment for one call)")
        r += 1
        put(0, r, "your_api_key -- paste your own balldontlie key here to override the environment")
        key_row = r
        put(1, r, "YOUR_API_KEY_HERE")  # placeholder only; never a real key
        r += 1
        key_cell_addr = "B%d" % (key_row + 1)
        apikey_f = '=NBA_TEAMID("Boston Celtics";%s)' % key_cell_addr
        put(0, r, "NBA_TEAMID with explicit api_key argument (placeholder above -> expect #ERR)")
        put(2, r, apikey_f)
        c = formula(1, r, apikey_f)
        print("apikey arg ->", settle(c, max_wait=30))
        r += 1
        f = '=NBA_LASTERROR()'
        put(0, r, "NBA_LASTERROR (reflects the placeholder-key rejection above)")
        put(2, r, f)
        c = formula(1, r, f)
        doc.calculateAll()
        print("apikey arg lasterror ->", c.getString())

        # Widen columns a little for readability.
        cols = sh.Columns
        for i in range(11):
            cols.getByIndex(i).Width = 4200
        cols.getByIndex(0).Width = 9000

        doc.calculateAll()

        # Save as ODF spreadsheet (calc8 = .ods).
        fn = PropertyValue()
        fn.Name = "FilterName"
        fn.Value = "calc8"
        doc.storeToURL(out_url, (fn,))
        print("wrote", out_path)
    finally:
        doc.close(False)
        desktop.terminate()


if __name__ == "__main__":
    main()
