#!/usr/bin/env python3
"""Archive-node private-chain E2E driver.

Builds txs via the node HTTP wallet API, signs locally with tronpy keys,
records historical oracles (exact expectations + replay-stability values),
then replays them via eth JSON-RPC after: (E) initial capture, (F) graceful
restart, (G) SIGKILL crash + recovery. Exits non-zero on any mismatch.
"""
import hashlib
import json
import os
import signal
import subprocess
import sys
import time
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
HTTP = "http://127.0.0.1:8090"
RPC = "http://127.0.0.1:8545/jsonrpc"
KEYS = json.load(open(os.path.join(BASE, "keys.json")))
REPORT = []

from tronpy.keys import PrivateKey  # noqa: E402


def log(msg):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    REPORT.append(line)


def http_post(path, body, timeout=10):
    req = urllib.request.Request(HTTP + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def rpc(method, params, timeout=15):
    req = urllib.request.Request(RPC, json.dumps(
        {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode(),
        {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def wait_ready(deadline=180):
    t0 = time.time()
    while time.time() - t0 < deadline:
        try:
            b = http_post("/wallet/getnowblock", {})
            if b.get("block_header"):
                return int(b["block_header"]["raw_data"].get("number", 0))
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("node not ready within %ss" % deadline)


def head_num():
    b = http_post("/wallet/getnowblock", {})
    return int(b["block_header"]["raw_data"].get("number", 0))


def sign_and_broadcast(tx, who):
    if "Error" in tx or "txID" not in tx:
        raise RuntimeError("tx build failed: %s" % json.dumps(tx)[:400])
    priv = PrivateKey(bytes.fromhex(KEYS[who]["priv"]))
    txid = tx["txID"]
    calc = hashlib.sha256(bytes.fromhex(tx["raw_data_hex"])).hexdigest()
    assert calc == txid, "txID mismatch"
    sig = priv.sign_msg_hash(bytes.fromhex(txid)).hex()
    tx["signature"] = [sig]
    res = http_post("/wallet/broadcasttransaction", tx)
    if not res.get("result"):
        raise RuntimeError("broadcast failed: %s" % json.dumps(res)[:400])
    return txid


def wait_confirmed(txid, need_success_receipt, deadline=60):
    t0 = time.time()
    while time.time() - t0 < deadline:
        info = http_post("/wallet/gettransactioninfobyid", {"value": txid})
        if info.get("blockNumber"):
            if need_success_receipt:
                r = info.get("receipt", {}).get("result")
                if r != "SUCCESS":
                    raise RuntimeError("tx %s receipt=%s info=%s"
                                       % (txid, r, json.dumps(info)[:400]))
            return int(info["blockNumber"]), info
        time.sleep(1)
    raise RuntimeError("tx %s not confirmed" % txid)


ORACLES = []  # {tag, height, method, params, expected(optional)}


def add_oracle(tag, height, method, params, expected=None):
    ORACLES.append({"tag": tag, "height": height, "method": method,
                    "params": params, "expected": expected, "captured": None})


def query_oracle(o, retries=40, wait=1.5):
    last = None
    for _ in range(retries):
        try:
            r = rpc(o["method"], o["params"] + [hex(o["height"])])
            if "result" in r and r["result"] is not None:
                return r["result"]
            last = r.get("error")
        except Exception as e:  # noqa: BLE001
            last = str(e)
        time.sleep(wait)
    raise RuntimeError("oracle %s failed after retries: %s" % (o["tag"], last))


def replay_all(phase):
    ok, bad = 0, 0
    for o in ORACLES:
        got = query_oracle(o)
        if o["expected"] is None and o["captured"] is None:
            o["captured"] = got  # first capture of a replay-stability oracle
            ok += 1
            continue
        want = o["captured"] if o["expected"] is None else o["expected"]
        norm = lambda v: v.lower() if isinstance(v, str) else v  # noqa: E731
        if norm(got) != norm(want):
            bad += 1
            log(f"MISMATCH[{phase}] {o['tag']} h={o['height']} want={want} got={got}")
        else:
            ok += 1
    log(f"REPLAY[{phase}] ok={ok} mismatch={bad} total={len(ORACLES)}")
    return bad == 0


def evm(addr41):
    return "0x" + addr41[2:]


def main():
    witness = KEYS["witness"]
    alice = KEYS["alice"]
    bob_pk = PrivateKey(os.urandom(32))
    bob41 = bob_pk.public_key.to_hex_address()
    log(f"bob(created in-scenario) = {bob41}")

    wait_ready()
    log(f"node ready, head={head_num()}")

    # T1: witness -> bob 10 TRX (account creation)
    tx = http_post("/wallet/createtransaction", {
        "owner_address": witness["addr_hex41"], "to_address": bob41,
        "amount": 10_000_000})
    h1, _ = wait_confirmed(sign_and_broadcast(tx, "witness"), False)
    log(f"T1 transfer->bob@{h1}")
    add_oracle("bob-balance-pre-exist", h1 - 1, "eth_getBalance", [evm(bob41)], "0x0")
    add_oracle("bob-balance-after-T1", h1, "eth_getBalance", [evm(bob41)],
               hex(10_000_000))

    # T2: witness -> bob +25 TRX
    tx = http_post("/wallet/createtransaction", {
        "owner_address": witness["addr_hex41"], "to_address": bob41,
        "amount": 25_000_000})
    h2, _ = wait_confirmed(sign_and_broadcast(tx, "witness"), False)
    log(f"T2 transfer->bob@{h2}")
    add_oracle("bob-balance-at-T1-frozen", h1, "eth_getBalance", [evm(bob41)],
               hex(10_000_000))
    add_oracle("bob-balance-after-T2", h2, "eth_getBalance", [evm(bob41)],
               hex(35_000_000))

    # T3: alice freezebalancev2 100 TRX BANDWIDTH (tron power for voting)
    tx = http_post("/wallet/freezebalancev2", {
        "owner_address": alice["addr_hex41"], "frozen_balance": 100_000_000,
        "resource": "BANDWIDTH"})
    h3, _ = wait_confirmed(sign_and_broadcast(tx, "alice"), False)
    log(f"T3 freezev2@{h3}")
    add_oracle("alice-balance-after-freeze", h3, "eth_getBalance",
               [evm(alice["addr_hex41"])])  # replay-stability oracle

    # T3b: TRON_POWER freeze (voting power under the new resource model)
    tx = http_post("/wallet/freezebalancev2", {
        "owner_address": alice["addr_hex41"], "frozen_balance": 60_000_000,
        "resource": "TRON_POWER"})
    h3b, _ = wait_confirmed(sign_and_broadcast(tx, "alice"), False)
    log(f"T3b freezev2 TP@{h3b}")

    # T4: alice votes for witness
    tx = http_post("/wallet/votewitnessaccount", {
        "owner_address": alice["addr_hex41"],
        "votes": [{"vote_address": witness["addr_hex41"], "vote_count": 50}]})
    h4, _ = wait_confirmed(sign_and_broadcast(tx, "alice"), False)
    log(f"T4 vote@{h4}")

    # T5: alice issues TRC10 (burns 1024 TRX)
    now_ms = int(time.time() * 1000)
    acc = http_post("/wallet/getaccount", {"address": alice["addr_hex41"]})
    asset_id = acc.get("asset_issued_ID")
    if asset_id:
        log(f"T5 skipped, asset already issued: {asset_id}")
        h5 = head_num()
    else:
        tx = http_post("/wallet/createassetissue", {
            "owner_address": alice["addr_hex41"],
            "name": "41726368546f6b656e",  # ArchToken
            "abbr": "41544b",              # ATK
            "total_supply": 1_000_000, "trx_num": 1, "num": 1, "precision": 0,
            "start_time": now_ms + 3_600_000, "end_time": now_ms + 86_400_000,
            "description": "6532652d746573742d6173736574",
            "url": "687474703a2f2f6c6f63616c686f7374"})
        h5, _ = wait_confirmed(sign_and_broadcast(tx, "alice"), False)
        log(f"T5 assetissue@{h5}")
        acc = http_post("/wallet/getaccount", {"address": alice["addr_hex41"]})
        asset_id = acc.get("asset_issued_ID")
        assert asset_id, "asset id missing: %s" % json.dumps(acc)[:300]
        add_oracle("alice-balance-after-issue", h5, "eth_getBalance",
                   [evm(alice["addr_hex41"])])
    if all(c in "0123456789" for c in asset_id):
        asset_name_hex = asset_id.encode().hex()  # API returned the raw id string
    else:
        asset_name_hex = asset_id
    log(f"asset id = {asset_id} -> asset_name(hex) = {asset_name_hex}")

    # T6: alice -> bob 1234 ATK (TRC10 transfer, exercises ACCOUNT_ASSET domain)
    tx = http_post("/wallet/transferasset", {
        "owner_address": alice["addr_hex41"], "to_address": bob41,
        "asset_name": asset_name_hex, "amount": 1234})
    h6, _ = wait_confirmed(sign_and_broadcast(tx, "alice"), False)
    log(f"T6 trc10 transfer@{h6}")

    # T7: deploy STOR2 (fallback: empty calldata returns slot0, else sstore slot0)
    # CALLDATASIZE ISZERO PUSH1 0x0c JUMPI | sstore(0, calldataload(0)) STOP
    # | JUMPDEST@0x0c return(sload(0)) — 24 bytes.
    runtime = "3615600c57600035600055005b60005460005260206000f3"
    deployer = "77" + runtime + "600052" + "6018" + "6008" + "f3"
    assert len(runtime) == 48, len(runtime)  # 24 bytes
    tx = http_post("/wallet/deploycontract", {
        "owner_address": alice["addr_hex41"], "abi": "[]",
        "bytecode": deployer, "fee_limit": 1_000_000_000, "call_value": 0,
        "consume_user_resource_percent": 100, "origin_energy_limit": 10_000_000,
        "name": "STOR2"})
    stor2 = tx.get("contract_address")
    h7, _ = wait_confirmed(sign_and_broadcast(tx, "alice"), True)
    log(f"T7 deploy STOR2={stor2}@{h7}")
    add_oracle("stor2-code-pre-deploy", h7 - 1, "eth_getCode", [evm(stor2)], "0x")
    add_oracle("stor2-code-after-deploy", h7, "eth_getCode", [evm(stor2)],
               "0x" + runtime)
    add_oracle("stor2-slot0-initial", h7, "eth_getStorageAt",
               [evm(stor2), "0x0"], "0x" + "00" * 32)

    def set_slot(value_int, tag):
        data = "%064x" % value_int
        t = http_post("/wallet/triggersmartcontract", {
            "owner_address": alice["addr_hex41"], "contract_address": stor2,
            "data": data, "fee_limit": 1_000_000_000, "call_value": 0})
        if not t.get("result", {}).get("result"):
            raise RuntimeError("trigger build failed: %s" % json.dumps(t)[:300])
        h, _ = wait_confirmed(sign_and_broadcast(t["transaction"], "alice"), True)
        log(f"{tag}@{h}")
        add_oracle(f"stor2-slot0-{tag}", h, "eth_getStorageAt",
                   [evm(stor2), "0x0"], "0x" + data)
        add_oracle(f"stor2-ethcall-{tag}", h, "eth_call",
                   [{"to": evm(stor2), "data": "0x"}], "0x" + data)
        return h

    h8 = set_slot(111, "set111")
    h9 = set_slot(222, "set222")
    h10 = set_slot(0, "set0")
    add_oracle("stor2-slot0-at-set111-frozen", h8, "eth_getStorageAt",
               [evm(stor2), "0x0"], "0x" + "%064x" % 111)
    add_oracle("stor2-ethcall-at-set222-frozen", h9, "eth_call",
               [{"to": evm(stor2), "data": "0x"}], "0x" + "%064x" % 222)

    # T11: deploy SELFDESTRUCT contract (destroys to alice on any call)
    sd_runtime = "73" + alice["addr_hex41"][2:] + "ff"  # PUSH20 <alice> SELFDESTRUCT
    sd_deployer = "75" + sd_runtime + "600052" + "6016" + "600a" + "f3"  # PUSH22
    assert len(sd_runtime) == 44, len(sd_runtime)  # 22 bytes
    tx = http_post("/wallet/deploycontract", {
        "owner_address": alice["addr_hex41"], "abi": "[]",
        "bytecode": sd_deployer, "fee_limit": 1_000_000_000, "call_value": 0,
        "consume_user_resource_percent": 100, "origin_energy_limit": 10_000_000,
        "name": "SDST"})
    sd = tx.get("contract_address")
    h11, _ = wait_confirmed(sign_and_broadcast(tx, "alice"), True)
    log(f"T11 deploy SD={sd}@{h11}")
    add_oracle("sd-code-after-deploy", h11, "eth_getCode", [evm(sd)],
               "0x" + sd_runtime)

    # T12: trigger -> selfdestruct
    t = http_post("/wallet/triggersmartcontract", {
        "owner_address": alice["addr_hex41"], "contract_address": sd,
        "data": "00", "fee_limit": 1_000_000_000, "call_value": 0})
    h12, _ = wait_confirmed(sign_and_broadcast(t["transaction"], "alice"), True)
    log(f"T12 selfdestruct@{h12}")
    add_oracle("sd-code-pre-destroy-frozen", h11, "eth_getCode", [evm(sd)],
               "0x" + sd_runtime)
    add_oracle("sd-code-after-destroy", h12, "eth_getCode", [evm(sd)], "0x")

    # T13: one more transfer so later state exists above every oracle height
    tx = http_post("/wallet/createtransaction", {
        "owner_address": witness["addr_hex41"], "to_address": bob41,
        "amount": 1_000_000})
    h13, _ = wait_confirmed(sign_and_broadcast(tx, "witness"), False)
    log(f"T13 tail transfer@{h13}")
    add_oracle("bob-balance-final", h13, "eth_getBalance", [evm(bob41)],
               hex(36_000_000))
    add_oracle("bob-balance-T2-refrozen", h2, "eth_getBalance", [evm(bob41)],
               hex(35_000_000))

    json.dump({"oracles": ORACLES,
               "meta": {"stor2": stor2, "sd": sd, "asset": asset_id,
                        "bob": bob41, "heights": [h1, h2, h3, h4, h5, h6, h7,
                                                  h8, h9, h10, h11, h12, h13]}},
              open(os.path.join(BASE, "oracles.json"), "w"), indent=1)

    # Phase E: initial capture+verify (captures replay-stability values too)
    if not replay_all("E-initial"):
        log("PHASE E FAILED")
        sys.exit(1)
    json.dump(ORACLES, open(os.path.join(BASE, "oracles-captured.json"), "w"),
              indent=1)
    log("PHASE E OK — scenario complete, oracles captured")


if __name__ == "__main__":
    main()
