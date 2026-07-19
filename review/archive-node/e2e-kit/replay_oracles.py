#!/usr/bin/env python3
"""Replay captured oracles against a (re)started node; exit 1 on any mismatch."""
import json
import os
import sys
import time
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
RPC = "http://127.0.0.1:8545/jsonrpc"
HTTP = "http://127.0.0.1:8090"
PHASE = sys.argv[1] if len(sys.argv) > 1 else "replay"


def rpc(method, params, timeout=15):
    req = urllib.request.Request(RPC, json.dumps(
        {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode(),
        {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def wait_ready(deadline=240):
    t0 = time.time()
    while time.time() - t0 < deadline:
        try:
            req = urllib.request.Request(HTTP + "/wallet/getnowblock", b"{}",
                                         {"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=5) as r:
                if json.loads(r.read().decode()).get("block_header"):
                    return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("node not ready")


def main():
    oracles = json.load(open(os.path.join(BASE, "oracles-captured.json")))
    wait_ready()
    ok, bad = 0, 0
    for o in oracles:
        want = o["captured"] if o["expected"] is None else o["expected"]
        got, last = None, None
        for _ in range(40):
            try:
                r = rpc(o["method"], o["params"] + [hex(o["height"])])
                if "result" in r and r["result"] is not None:
                    got = r["result"]
                    break
                last = r.get("error")
            except Exception as e:  # noqa: BLE001
                last = str(e)
            time.sleep(1.5)
        norm = lambda v: v.lower() if isinstance(v, str) else v  # noqa: E731
        if got is None:
            bad += 1
            print(f"MISMATCH[{PHASE}] {o['tag']} h={o['height']}: no result ({last})")
        elif norm(got) != norm(want):
            bad += 1
            print(f"MISMATCH[{PHASE}] {o['tag']} h={o['height']} want={want} got={got}")
        else:
            ok += 1
    print(f"REPLAY[{PHASE}] ok={ok} mismatch={bad} total={len(oracles)}")
    sys.exit(0 if bad == 0 else 1)


if __name__ == "__main__":
    main()
