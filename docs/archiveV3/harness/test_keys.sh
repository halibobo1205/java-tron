#!/usr/bin/env bash
# Config/key regression only: never starts a node or contacts a network.
set -euo pipefail
harness="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$harness/lib.sh"
. "$harness/scenario-common.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/archive-key-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT
jar="${ARCHIVE_HARNESS_JAR:-$harness/../../../framework/build/libs/FullNode.jar}"
hs_bind_addr_helper "$jar" "$work/classes"
javac -nowarn -cp "$jar" -d "$work/classes" "$harness/java/HarnessKeysTest.java"
table="$work/classes/archive-test-keys.tsv"
cp "$table" "$work/original.tsv"
java -cp "$work/classes:$jar" HarnessKeysTest "$table"

# Rebinding and a fresh shell reopening the same run must preserve all identities.
hs_bind_addr_helper "$jar" "$work/classes"
/bin/bash -euo pipefail -c '. "$1/lib.sh"; hs_bind_addr_helper "$2" "$3"' \
  _ "$harness" "$jar" "$work/classes"
cmp "$table" "$work/original.tsv"
/bin/bash -euo pipefail -c '. "$1/lib.sh"; hs_bind_addr_helper "$2" "$3"' \
  _ "$harness" "$jar" "$work/independent"
if cmp -s "$table" "$work/independent/archive-test-keys.tsv"; then
  hs_die "independent runs reused keys"
fi

HS_RUN_DIR="$work/nodes"
HS_SCENARIO_NAME=smoke
AH_SCENARIO_SLUG=smoke
HS_PORT_BASE=21000
# Only render configs here; real scenarios retain the actual listener preflight.
hs_port_free() { return 0; }
mkdir -p "$HS_RUN_DIR"
for count in 1 27; do
  HS_CFG_WITNESS_COUNT="$count"
  node="$(hs_new_node "node-$count" 0)"
  java -cp "$work/classes:$jar" HarnessKeysTest "$table" "$node/node.conf" "$count" 1 "$count"
  cp "$node/node.conf" "$work/before.conf"
  hs_write_node_config "$node"
  cmp <(sed '/^#/d' "$node/node.conf") <(sed '/^#/d' "$work/before.conf")

  AH_CLASSES_DIR="$work/classes"
  AH_JAR="$jar"
  ah_conf_reset
  ah_write_node_conf "$work/template-$count.conf"
  java -cp "$work/classes:$jar" HarnessKeysTest "$table" "$work/template-$count.conf" "$count" 1 "$count"
done

HS_CFG_LOCAL_WITNESS_FIRST=1
HS_CFG_LOCAL_WITNESS_LAST=26
node="$(hs_new_node source 1)"
java -cp "$work/classes:$jar" HarnessKeysTest "$table" "$node/node.conf" 27 1 26
HS_CFG_LOCAL_WITNESS_FIRST=27
HS_CFG_LOCAL_WITNESS_LAST=27
node="$(hs_new_node follower 2)"
java -cp "$work/classes:$jar" HarnessKeysTest "$table" "$node/node.conf" 27 27 27

# An incomplete saved key table must fail instead of regenerating identities on restart.
mkdir -p "$work/broken"
printf 'incomplete\n' >"$work/broken/archive-test-keys.tsv"
if /bin/bash -euo pipefail -c '. "$1/lib.sh"; hs_bind_addr_helper "$2" "$3"' \
    _ "$harness" "$jar" "$work/broken" >"$work/broken.log" 2>&1; then
  hs_die "corrupt key table accepted"
fi
grep -q 'invalid test key table row' "$work/broken.log"
echo HARNESS_KEYS_OK
