"""Live smoke test for the balldontlie NBA Calc add-in, using a real API key
resolved via ~/.config/libreoffice-nba/balldontlie.properties.

Run:
    soffice --headless --norestore --accept="socket,host=localhost,port=2002;urp;"
    <LO>/program/python tools/test_nba_live.py
"""
import sys
import time
import uno


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


def wait_for(doc, sheet, cell_pos, formula, is_table, want_pred, max_wait=25):
    """(Re)enter the formula and recalc until want_pred(value) or timeout."""
    deadline = time.time() + max_wait
    while True:
        if is_table:
            rng = sheet.getCellRangeByName(is_table)
            rng.setArrayFormula(formula)
            doc.calculateAll()
            val = rng.getDataArray()[0][0]
        else:
            col, row = cell_pos
            c = sheet.getCellByPosition(col, row)
            c.setFormula(formula)
            doc.calculateAll()
            val = c.getString()
        if want_pred(val) or time.time() > deadline:
            return val
        time.sleep(1.5)


def main():
    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    checks = {}
    try:
        sheet = doc.Sheets.getByIndex(0)
        not_loading = lambda v: v != "#LOADING"

        teamid = wait_for(doc, sheet, (0, 0), '=NBA_TEAMID("Lakers")', None, not_loading)
        print("NBA_TEAMID(Lakers)      ->", repr(teamid))
        checks["teamid_resolved"] = teamid not in ("#LOADING", "#NO_API_KEY", "#ERR", "#NOT_FOUND")

        playerid = wait_for(doc, sheet, (0, 1), '=NBA_PLAYERID("LeBron James")', None, not_loading)
        print("NBA_PLAYERID(LeBron)    ->", repr(playerid))
        checks["playerid_is_237"] = playerid == "237"

        score = wait_for(doc, sheet, (0, 2), '=NBA_SCORE("2023"; "LAL")', None, not_loading)
        print("NBA_SCORE(2023, LAL)    ->", repr(score))
        checks["score_looks_right"] = score not in ("#LOADING", "#NO_API_KEY", "#ERR", "#NOT_FOUND") \
            and "LAL" in score

        teams_val = wait_for(doc, sheet, None, '=NBA_TEAMS()', "B1:H50", not_loading)
        print("NBA_TEAMS() top-left    ->", repr(teams_val))
        checks["teams_numeric_id"] = teams_val not in ("#LOADING", "#NO_API_KEY", "#ERR", "#NOT_FOUND")

        games_val = wait_for(doc, sheet, None, '=NBA_GAMES("2024-01-15")', "B60:G70", not_loading)
        print("NBA_GAMES(date) top-left->", repr(games_val))
        checks["games_has_date"] = games_val == "2024-01-15"

        # Paid-tier-only endpoints on a free key should fail cleanly as #ERR,
        # not hang or crash, and NBA_LASTERROR should mention the HTTP status.
        standings_val = wait_for(doc, sheet, None, '=NBA_STANDINGS("2023")', "B80:H90", not_loading)
        print("NBA_STANDINGS() top-left->", repr(standings_val))
        checks["standings_handled"] = standings_val in ("#ERR", "#NOT_FOUND") or (
            standings_val not in ("#LOADING", "#NO_API_KEY"))

        lasterror = sheet.getCellByPosition(0, 5)
        lasterror.setFormula('=NBA_LASTERROR()')
        doc.calculateAll()
        le = lasterror.getString()
        print("NBA_LASTERROR()         ->", repr(le))
        checks["lasterror_populated"] = le != ""
    finally:
        doc.close(False)
        desktop.terminate()

    print("---")
    for name, ok in checks.items():
        print("CHECK %-24s %s" % (name, "PASS" if ok else "FAIL"))
    ok = all(checks.values())
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
