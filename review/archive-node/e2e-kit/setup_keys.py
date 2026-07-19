#!/usr/bin/env python3
"""Generate fresh private-chain keys and render private-archive.conf.

Run once before node.sh start. Requires: pip install tronpy
"""
import json
import os

from tronpy.keys import PrivateKey

BASE = os.path.dirname(os.path.abspath(__file__))
keys = {}
for name in ("witness", "alice"):
    pk = PrivateKey(os.urandom(32))
    keys[name] = {
        "priv": pk.hex(),
        "addr_b58": pk.public_key.to_base58check_address(),
        "addr_hex41": pk.public_key.to_hex_address(),
    }
json.dump(keys, open(os.path.join(BASE, "keys.json"), "w"), indent=1)

tpl = open(os.path.join(BASE, "private-archive.conf.template")).read()
conf = (tpl.replace("{witness_b58}", keys["witness"]["addr_b58"])
           .replace("{alice_b58}", keys["alice"]["addr_b58"])
           .replace("{witness_priv}", keys["witness"]["priv"]))
open(os.path.join(BASE, "private-archive.conf"), "w").write(conf)
print("keys.json + private-archive.conf generated")
print("witness:", keys["witness"]["addr_b58"])
print("alice  :", keys["alice"]["addr_b58"])
