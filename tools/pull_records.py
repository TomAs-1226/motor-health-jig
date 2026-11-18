#!/usr/bin/env python3
"""Copy the jig's test records off the roboRIO into records/, then rebuild the summaries.

    python tools/pull_records.py                 # try the radio, USB and mDNS addresses
    python tools/pull_records.py --host 172.22.11.2
    python tools/pull_records.py --from-dir E:/motor-health/tests   # a copy made some other way
    python tools/pull_records.py --no-pull       # just rebuild the summaries

Records are never deleted from the roboRIO, and a file already in records/tests/ is never
overwritten, so running this twice is harmless. Afterwards records/ is ready to commit:

    records/tests/<time>_SN-<serial>.json   every test, as the jig wrote it
    records/motors.csv                      one row per motor: its newest test
    records/history.csv                     one row per test
    records/README.md                       the motors table, readable on GitHub
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

TEAM = 5805
REMOTE_DIR = "/home/lvuser/motor-health/tests"
ROOT = Path(__file__).resolve().parent.parent
RECORDS = ROOT / "records"
TESTS = RECORDS / "tests"

SEED = ROOT / "src" / "main" / "deploy" / "motor-numbers.json"

COLUMNS = [
    "number", "serial", "canId", "name", "model", "motorType", "firmware", "hardwareRev",
    "bootloaderRev", "manufactured", "proLicensed", "licenses", "label", "verdict", "score",
    "testedAt", "projectedFreeRpm12V", "freeCurrentA", "coastHalfS", "kvRpsPerV", "tempRiseC",
    "summary", "file",
]
IDENTITY = ["serial", "canId", "name", "model", "firmware", "hardwareRev", "bootloaderRev",
            "manufactured", "proLicensed", "licenses"]


def default_hosts() -> list[str]:
    te, am = divmod(TEAM, 100)
    return [f"10.{te}.{am}.2", "172.22.11.2", f"roboRIO-{TEAM}-FRC.local"]


def pull(hosts: list[str], legacy: bool, bind: str | None) -> Path | None:
    """scp the remote tests/ into a temp dir. Returns it, or None if no host answered."""
    tmp = Path(tempfile.mkdtemp(prefix="motor-health-"))
    for host in hosts:
        print(f"Trying lvuser@{host} ... (the password is empty: just press Enter if asked)")
        cmd = ["scp", "-o", "ConnectTimeout=5", "-o", "StrictHostKeyChecking=accept-new"]
        if bind:
            cmd += ["-o", f"BindAddress={bind}"]
        if legacy:
            cmd.append("-O")
        cmd += [f"lvuser@{host}:{REMOTE_DIR}/*.json", str(tmp)]
        if subprocess.run(cmd).returncode == 0:
            print(f"Copied from {host}.")
            return tmp
    shutil.rmtree(tmp, ignore_errors=True)
    return None


def import_from(src: Path) -> int:
    TESTS.mkdir(parents=True, exist_ok=True)
    added = 0
    for f in sorted(src.glob("*.json")):
        dest = TESTS / f.name
        if dest.exists():
            continue
        try:
            json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            print(f"  skipped {f.name}: {e}")
            continue
        shutil.copy2(f, dest)
        added += 1
        print(f"  + {f.name}")
    return added


def load() -> list[dict]:
    out = []
    for f in sorted(TESTS.glob("*.json")):
        try:
            r = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            print(f"  unreadable {f.name}: {e}")
            continue
        r["_file"] = f"tests/{f.name}"
        out.append(r)
    return out


def row(r: dict) -> dict:
    m = r.get("motor", {})
    res = r.get("result", {})
    x = res.get("metrics", {})
    return {
        "number": r.get("motorNumber") or "",
        **{k: m.get(k, "") for k in IDENTITY},
        "motorType": m.get("motorType", ""),
        "label": r.get("label", ""),
        "verdict": res.get("verdict", ""),
        "score": res.get("score", ""),
        "testedAt": r.get("testedAt", ""),
        "projectedFreeRpm12V": fmt(x.get("projectedFreeRpm12V"), 0),
        "freeCurrentA": fmt(x.get("freeCurrentA"), 2),
        "coastHalfS": fmt(x.get("coastHalfS"), 2),
        "kvRpsPerV": fmt(x.get("kvRpsPerV"), 3),
        "tempRiseC": fmt(x.get("tempRiseC"), 1),
        "summary": res.get("summary", ""),
        "file": r["_file"],
    }


def fmt(v, places: int) -> str:
    return "" if v is None else f"{v:.{places}f}"


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(rows)


def summarize(records: list[dict]) -> None:
    records.sort(key=lambda r: r.get("testedAtMs", 0))
    history = [row(r) for r in records]
    newest: dict[str, dict] = {}
    for r in records:
        m = r.get("motor", {})
        key = m.get("serial") or f"no-serial:{r['_file']}"
        newest[key] = r
    motors = [row(r) for r in newest.values()]
    # Hand-numbered motors with no jig test yet still get a row.
    tested = {m["serial"] for m in motors}
    seed = json.loads(SEED.read_text(encoding="utf-8")).get("motors", []) if SEED.exists() else []
    for s in seed:
        if s.get("serial") in tested:
            continue
        motors.append({**{c: "" for c in COLUMNS}, **{k: s.get(k, "") for k in IDENTITY},
                       "number": s.get("number", ""), "verdict": "not tested",
                       "summary": s.get("note", "")})
    numbered = {s.get("serial"): s.get("number") for s in seed}
    for m in motors:
        if not m["number"] and m["serial"] in numbered:
            m["number"] = numbered[m["serial"]]
    motors.sort(key=lambda x: (x["number"] == "", int(x["number"]) if str(x["number"]).isdigit() else 0, x["serial"]))

    write_csv(RECORDS / "history.csv", history)
    write_csv(RECORDS / "motors.csv", motors)

    counts = {}
    for m in motors:
        counts[m["verdict"]] = counts.get(m["verdict"], 0) + 1
    tests_per = {}
    for h in history:
        tests_per[h["serial"]] = tests_per.get(h["serial"], 0) + 1

    lines = [
        "# Motor health records",
        "",
        "Generated by `tools/pull_records.py` from `tests/`. Don't edit by hand; edit the jig or the",
        "script and regenerate.",
        "",
        f"{len(motors)} motors, {len(history)} tests. "
        + ", ".join(f"{v or 'unknown'}: {n}" for v, n in sorted(counts.items())),
        "",
        "The `#` is only the team's order. The serial is the motor's identity; `motors.csv` has every",
        "identity field (Tuner name, bootloader, licences).",
        "",
        "| # | Serial | CAN id | Model | Type | Firmware | HW rev | Made | Verdict | Score | Free speed @12 V | Free current | Coast ½ | Tests | Last tested | Notes |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for m in motors:
        lines.append(
            "| {num} | {serial} | {can} | {model} | {type} | {fw} | {hw} | {made} | **{verdict}** | {score} | {rpm} | {amps} | {coast} | {n} | [{date}]({file}) | {summary} |".format(
                num=f"**{m['number']}**" if m["number"] else "",
                serial=f"`{m['serial']}`" if m["serial"] else "_unknown_",
                can=m["canId"],
                model=str(m["model"]).replace("|", "/"),
                type=m["motorType"].replace("_Integrated", ""),
                fw=str(m["firmware"]).replace(" (Phoenix 6)", ""),
                hw=m["hardwareRev"],
                made=m["manufactured"],
                verdict=m["verdict"],
                score=m["score"],
                rpm=f"{m['projectedFreeRpm12V']} rpm" if m["projectedFreeRpm12V"] else "",
                amps=f"{m['freeCurrentA']} A" if m["freeCurrentA"] else "",
                coast=f"{m['coastHalfS']} s" if m["coastHalfS"] else "",
                n=tests_per.get(m["serial"], 0) if m["serial"] else 1,
                date=m["testedAt"][:10] or "-",
                file=m["file"] or ".",
                summary=m["summary"].replace("|", "/"),
            )
        )
    (RECORDS / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote records/motors.csv, history.csv and README.md: {len(motors)} motors, {len(history)} tests.")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", action="append", help="roboRIO address (repeatable)")
    p.add_argument("--from-dir", type=Path, help="import JSON records from this folder instead of scp")
    p.add_argument("--no-pull", action="store_true", help="only rebuild the summaries")
    p.add_argument("--legacy-scp", action="store_true", help="pass -O to scp, for old SSH servers")
    p.add_argument("--bind", metavar="LAPTOP_IP",
                   help="send from this laptop address, e.g. the Ethernet adapter's 169.254.x.x when"
                        " Tailscale has taken the 169.254.0.0/16 route")
    a = p.parse_args()

    RECORDS.mkdir(exist_ok=True)
    if a.from_dir:
        print(f"Imported {import_from(a.from_dir)} new record(s) from {a.from_dir}.")
    elif not a.no_pull:
        tmp = pull(a.host or default_hosts(), a.legacy_scp, a.bind)
        if tmp is None:
            print("Could not reach the roboRIO. Is the laptop on its radio or USB? "
                  "Records there are safe; nothing was changed here.", file=sys.stderr)
            return 1
        try:
            print(f"Imported {import_from(tmp)} new record(s).")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    summarize(load())
    return 0


if __name__ == "__main__":
    sys.exit(main())
