#!/usr/bin/env python3
"""Extract "car-model replay fixtures" from CarDeck runtime logs.

Usage:
    python3 tools/extract_car_fixtures.py <log file or directory>... [-o app/src/test/resources/carplay/fixtures]

Output: each log → <car model>__<log name>.jsonl
  First line is meta (car model/source/round overview), followed by events per layer:
  - bt layer: {"layer":"bt","round":N,"dir":"rx|tx","hex":"…"} (RAW rx/tx concatenated per round)
  - tunnel layer: {"layer":"tunnel","dir":"rx","id":…,"hex":"…"} (CSM body of IAPLINK rx)
                  {"layer":"tunnel","dir":"tx","id":…} (tunnel tx has only message IDs — the golden sequence)

Round boundaries: start (receiver connected in / connected to … RFCOMM established) → end (LINK closed/SESSION failed/
SESSION ended/keep-alive ended). Only rounds containing 0x1d01 are kept (rounds that never reached identification have no replay value),
with outcome and duration (the replay tests use these to control duration and quotas).

Fixtures contain real addresses/hotspot names — the directory is gitignored by default; committing one requires a separate review (see the README in that directory).

Note: the log-matching regexes below accept both the original Chinese log text and the translated English text,
so logs from older and newer builds both work.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

LINE_RE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\.(\d\d\d)\] (.*)$")
RAW_RX_RE = re.compile(r"^RAW rx: ((?:[0-9a-f]{2} ?)+)$")
RAW_TX_RE = re.compile(r"^RAW tx: ((?:[0-9a-f]{2} ?)+)$")
LINK_CLOSE_RE = re.compile(r"LINK closed|SESSION 失败|SESSION 结束|保活结束|SESSION failed|SESSION ended|keep-alive ended")
ROUND_START_RE = re.compile(r"接收端已连入|已连入 .+ 的无线 iAP2 服务|receiver connected name=|connected to .+\'s wireless iAP2 service")
IDENT_RE = re.compile(r"iap2 rx=0x1d01 (?:车机身份|head unit identity) name=(.*?) model=(.*?) manufacturer=(.*)$")
NSD_MODEL_RE = re.compile(r"NSD: TXT .*model=(\S+?),")
TUNNEL_RX_RE = re.compile(r"^IAPLINK: rx 0x([0-9a-f]+) \(.*\) \d+B hex=((?:[0-9a-f]{2} ?)+)")
TUNNEL_TX_RE = re.compile(r"^IAPLINK: (?:隧道|tunnel) tx 0x([0-9a-f]+)")
PHASE1_RE = re.compile(r"Phase 1 完成|Phase 1 done")


def ms_of(m: re.Match) -> int:
    h, mi, s, ms = (int(m.group(i)) for i in range(1, 5))
    return ((h * 60 + mi) * 60 + s) * 1000 + ms


def car_slug(text: str) -> str:
    ident = IDENT_RE.search(text)
    if ident:
        parts = [p.lower() for p in ident.groups()]
        return re.sub(r"[^a-z0-9]+", "_", "_".join(parts)).strip("_")[:48]
    nsd = NSD_MODEL_RE.search(text)
    if nsd:
        return re.sub(r"[^a-z0-9]+", "_", nsd.group(1).lower()).strip("_")[:48]
    return "unknown"


class Round:
    def __init__(self, index: int, start_ms: int):
        self.index = index
        self.start_ms = start_ms
        self.end_ms = start_ms
        self.rx = bytearray()
        self.tx = bytearray()
        self.ids: list[str] = []  # CSM message IDs seen in this round (used for identification checks)
        self.phase1 = False
        self.ident_text = ""


def extract(log_path: Path) -> dict | None:
    rounds: list[Round] = []
    current: Round | None = None
    tunnel_rx: list[dict] = []
    tunnel_tx: list[str] = []
    car_id = "unknown"

    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LINE_RE.match(line)
        if not m:
            continue
        body = m.group(5).strip()
        now = ms_of(m)

        ident = IDENT_RE.search(body)
        if ident:
            car_id = car_slug(body)

        if ROUND_START_RE.search(body):
            current = Round(len(rounds), now)
            rounds.append(current)
        if current is None:
            # Log starts mid-way: still group into an anonymous round
            current = Round(0, now)
            rounds.append(current)
        current.end_ms = now

        if TUNNEL_RX_RE.match(body):
            t = TUNNEL_RX_RE.match(body)
            tunnel_rx.append(
                {"layer": "tunnel", "dir": "rx", "id": int(t.group(1), 16), "hex": t.group(2).replace(" ", "")}
            )
        else:
            t = TUNNEL_TX_RE.match(body)
            if t:
                tunnel_tx.append(t.group(1))

        rx = RAW_RX_RE.match(body)
        tx = RAW_TX_RE.match(body)
        if rx:
            data = bytes.fromhex(rx.group(1).replace(" ", ""))
            # RAW dump budget truncation can leave half lines (seen in practice: a whole line reduced to "ff"): frames/markers are at least 6B,
            # shorter fragments are dropped to avoid polluting the replay byte stream
            if len(data) >= 6:
                current.rx.extend(data)
                current.end_ms = now
        elif tx:
            data = bytes.fromhex(tx.group(1).replace(" ", ""))
            if len(data) >= 6:
                current.tx.extend(data)
                current.end_ms = now
        if PHASE1_RE.search(body) and current is not None:
            current.phase1 = True
            current.ident_text = body

    keep = []
    for r in rounds:
        if not r.rx:
            continue
        ident = IDENT_RE.search(r.ident_text) or IDENT_RE.search("")  # per-round ident taken from that round's body
        raw = bytes(r.rx)
        # RAW dump budget truncates by bytes (512B in old logs): the cut often lands mid-frame — trim
        # the trailing partial frame at a frame boundary, and record whether the identification frame (complete 0x1D01 CSM) survived. Rounds
        # with a truncated identification frame can only do link-layer replay (the engine stalls waiting on checksums for partial frames); only rounds with a complete one get full assertions.
        trimmed, truncated, has_ident, has_syn = trim_to_frame_boundary(raw)
        if not ("1d01" in raw.hex() or r.phase1):
            continue  # Never reached identification: no replay value (pure negotiation-failure rounds are covered by link-layer unit tests)
        if not has_syn:
            continue  # Not even one SYN with valid checksums (wreckage round): can't even drive the link layer, no replay value
        keep.append(
            {
                "layer": "bt",
                "round": r.index,
                "outcome": "phase1" if r.phase1 else "identified",
                "durationMs": r.end_ms - r.start_ms,
                "rxTruncated": truncated,
                "rxHasIdent": has_ident,
                "rxHex": trimmed.hex(),
                "txHex": bytes(r.tx).hex(),
            }
        )
    if not keep and not tunnel_rx:
        return None
    return {"car": car_id, "source": log_path.name, "btRounds": keep, "tunnelRx": tunnel_rx, "tunnelTxIds": tunnel_tx}


MARKER = bytes.fromhex("ff550200ee10")


def trim_to_frame_boundary(data: bytes) -> tuple[bytes, bool, bool]:
    """Return (trimmed byte stream, whether trimming happened, whether the intact frames contain a 0x1D01 CSM).

    "Complete frame length" does not mean "intact bytes": the RAW dump budget truncates by bytes and can splice the
    tails of different reads into the same stream, forging frames whose length happens to be complete — their payload checksum fails, and the engine
    silently drops them per the protocol (found in replay debugging 2026-10-20: the root cause of replay stopping at 0x1D00). So each frame is checked
    with the same algorithm as the engine (9B header sums to 0; payload + trailing checksum sums to 0), and everything from the first failing
    frame onward is trimmed — the remaining stream is guaranteed to be accepted frame by frame by the engine.
    """
    off = 0
    has_ident = False
    has_syn = False
    while off < len(data):
        if data[off : off + 6] == MARKER:
            off += 6
            continue
        if off + 4 <= len(data) and data[off] == 0xFF and data[off + 1] == 0x5A:
            frame_len = (data[off + 2] << 8) | data[off + 3]
            if frame_len < 9 or off + frame_len > len(data):
                break  # Incomplete length (budget truncation point)
            frame = data[off : off + frame_len]
            if sum(frame[:9]) & 0xFF != 0:
                break  # Header checksum failed: a spliced, forged frame
            if frame_len > 9 and sum(frame[9:frame_len]) & 0xFF != 0:
                break  # Payload checksum failed: same as above
            if frame_len > 9:
                payload = frame[9 : frame_len - 1]
                if frame[4] & 0x80:  # ctl has the SYN bit (sync packet carrying the session table)
                    has_syn = True
                if payload[:2] == b"\x40\x40" and payload[4:6] == b"\x1d\x01":
                    has_ident = True
            off += frame_len
            continue
        break  # Unrecognized bytes: stop (conservatively drop the tail)
    return data[:off], off < len(data), has_ident, has_syn


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("-o", "--out", default="app/src/test/resources/carplay/fixtures")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for raw_path in args.inputs:
        p = Path(raw_path)
        files = [p] if p.is_file() else sorted(p.glob("CarDeck-log-*.txt")) if p.is_dir() else []
        for f in files:
            data = extract(f)
            if data is None:
                print(f"skip (nothing replayable): {f.name}")
                continue
            target = out_dir / f"{data['car']}__{f.stem}.jsonl"
            with target.open("w", encoding="utf-8") as w:
                w.write(json.dumps({"meta": data["meta"] if "meta" in data else {k: data[k] for k in ("car", "source")}}, ensure_ascii=False) + "\n")
                for r in data["btRounds"]:
                    w.write(json.dumps(r, ensure_ascii=False) + "\n")
                for e in data["tunnelRx"]:
                    w.write(json.dumps(e, ensure_ascii=False) + "\n")
                for tid in data["tunnelTxIds"]:
                    w.write(json.dumps({"layer": "tunnel", "dir": "tx", "id": int(tid, 16)}, ensure_ascii=False) + "\n")
            print(f"wrote {target.name} (bt rounds {len(data['btRounds'])}, tunnel rx {len(data['tunnelRx'])})")
            written += 1
    print(f"done: {written} fixture(s) → {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
