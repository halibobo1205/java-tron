#!/usr/bin/env bash
#
# anchor.sh -- SEMANTIC JDWP anchor resolution for the archive fault harness.
#
# ==============================================================================================
# WHY THIS FILE EXISTS
# ==============================================================================================
# JDWP breakpoints are placed by CLASS:LINE. A line number is not a stable name for a statement:
# inserting one line anywhere above a method silently moves every anchor inside it, and the
# breakpoint then suspends the JVM at the wrong statement. The harness used to detect that with a
# javap guard and DEGRADE the window to a probabilistic randomized-timing loop -- honest, but it
# means a window stops being deterministically tested exactly when the code under test changed,
# which is the worst possible moment to stop testing it.
#
# This resolver names a statement SEMANTICALLY and derives the line number at run time:
#
#     class + source method + (optional) "after this statement" + "the statement matching R"
#
# It resolves in two stages (implemented in anchor_resolve.py) and refuses to guess:
#
#   STAGE 1 (source).  Parse the CURRENT working-tree source with comments and string/char
#                      literals masked, brace-match the body of the named METHOD (all overloads),
#                      and locate the statement. `after` must match EXACTLY ONCE across all
#                      bodies; `match` must then match EXACTLY ONCE in the region after it inside
#                      that same body. Zero or two matches is a HARD ERROR, never a silent pick.
#
#   STAGE 2 (jar).     Run `javap -p -l` on the jar UNDER TEST and read the LineNumberTable of the
#                      named method plus its compiler-synthesized lambda bodies
#                      (`lambda$<method>$N`) -- a statement containing a lambda is compiled into
#                      two methods and BOTH are legitimate locations for it. Then solve for the
#                      single line offset `delta` between working-tree source and jar:
#
#                          jarline = srcline - delta
#
#                      A delta is valid only when every compiled method sits cleanly on one side
#                      of the line: each maps EITHER entirely onto real (non-blank, non-comment)
#                      code lines of this source body, OR entirely outside it -- that second case
#                      being a different overload, since overloads share a name and their lambdas
#                      are numbered class-wide. A method that PARTIALLY overlaps the body means
#                      the body was rewritten rather than shifted, and no offset can be right.
#                      The anchor itself must land on an actual table entry of a method belonging
#                      to this body. delta = 0 (jar and source agree exactly) always wins;
#                      otherwise exactly one delta must be valid or the anchor is rejected as
#                      ambiguous.
#
# The breakpoint is placed in JAR COORDINATES, which is the only coordinate system jdb
# understands. That is what makes an anchor survive an edit above the method without a rebuild:
# the statement moved in the source, the compiled statement did not, and stage 2 measures the
# difference instead of being confused by it.
#
# What still (correctly) degrades: the method was renamed or removed, the statement was deleted or
# duplicated, the descriptor became ambiguous, the body itself was rewritten so no consistent
# offset exists, or javap/python3 is unavailable. Every failure names the descriptor that failed.
#
# ==============================================================================================
# PUBLIC API
# ==============================================================================================
#   hs_anchor_names                    -- all registered descriptor names, one per line
#   hs_anchor_describe <name>          -- one-line human description of what it anchors to
#   hs_anchor_resolve  <name> [jar]    -- prints EXACTLY ONE line and returns 0/1:
#         OK class=<fqcn> jarline=<n> srcline=<n> delta=<d> src=<repo-rel> owners=<a,b>
#         FAIL descriptor=<name> <reason ...>
#     The result is PRINTED rather than published in globals because every caller runs this
#     inside $( ), where a global assignment is discarded (see README section 9).
#   hs_anchor_field <line> <key>       -- pull one key=value token out of an OK line
#   hs_anchor_owner_matches <owners-csv> <method> -- 0 when jdb's reported method is an owner
#
# CLI (re-check anchors without running a scenario -- this is the drift check):
#   ./anchor.sh                    resolve every descriptor, print a table, exit 1 if any failed
#   ./anchor.sh km.w5 cfk.flush    resolve just those
#   ./anchor.sh --list             names + descriptions only
#   ./anchor.sh --selftest         prove the resolver still REFUSES ambiguous/absent descriptors
#                                  (a resolver that silently picked the first candidate would
#                                  make every scenario pass, so this is asserted, not assumed)
#
# No production source is modified. It is only READ.

HS_ANCHOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
HS_ANCHOR_PY="$HS_ANCHOR_DIR/anchor_resolve.py"

# ------------------------------------------------------------------------------- environment

# hs_anchor_repo_root -- repo root. Deliberately independent of lib.sh so the CLI works before
# (or entirely without) hs_init, which is the point of being able to re-resolve anchors cheaply.
hs_anchor_repo_root() {
  local root=""
  if declare -F hs_repo_root >/dev/null 2>&1; then
    root="$(hs_repo_root 2>/dev/null)" || root=""
  fi
  [ -n "$root" ] || root="$(cd "$HS_ANCHOR_DIR/../../.." >/dev/null 2>&1 && pwd)"
  printf '%s\n' "$root"
}

# hs_anchor_default_jar -- the same jar lib.sh would test, honouring both override spellings.
hs_anchor_default_jar() {
  local override="${ARCHIVE_HARNESS_JAR:-${FULLNODE_JAR:-}}"
  if [ -n "$override" ]; then
    printf '%s\n' "$override"
    return 0
  fi
  printf '%s/framework/build/libs/FullNode.jar\n' "$(hs_anchor_repo_root)"
}

# --------------------------------------------------------------------------- the descriptors
#
# ONE registry, shared by every scenario, so a production rename is fixed in exactly one place.
#
# Each descriptor states the semantic point its window exists to catch. Read the window comment
# in the scenario before changing one: the breakpoint suspends the JVM *before* the matched
# statement executes, so `match` names the FIRST thing that must NOT have happened yet.

HS_ANCHOR_ALL="km.w2 km.w3 km.w4 km.w5 km.w6 cfk.flush"

hs_anchor_names() {
  local n
  for n in $HS_ANCHOR_ALL; do printf '%s\n' "$n"; done
}

# hs_anchor_spec <name> -- populate HSA_*; return 1 for an unknown name.
# HSA_AFTER is always assigned (possibly empty) so callers can run under `set -u`.
hs_anchor_spec() {
  HSA_SRC=""; HSA_CLASS=""; HSA_METHOD=""; HSA_AFTER=""; HSA_MATCH=""; HSA_DESC=""
  case "$1" in
    km.w2)
      # Window "journal put -> canonical commit": the archive journal is already durable, the
      # canonical revoking session has NOT been committed. Anchor = the canonical commit that
      # follows the journal write inside pushBlock. `after` is what makes it the *archive*
      # commit rather than any other tmpSession.commit() the method might grow later.
      HSA_SRC="framework/src/main/java/org/tron/core/db/Manager.java"
      HSA_CLASS="org.tron.core.db.Manager"
      HSA_METHOD="pushBlock"
      HSA_AFTER='archiveJournalToken = journalArchiveBlockOnlyOrFailStop\('
      HSA_MATCH='tmpSession\.commit\(\);'
      HSA_DESC="pushBlock: the tmpSession.commit() that follows the archive journal write"
      ;;
    km.w3)
      # Window "canonical commit -> ack": the canonical session is committed, the journal has NOT
      # been acknowledged. Anchor = the acknowledgement that follows the canonical commit.
      HSA_SRC="framework/src/main/java/org/tron/core/db/Manager.java"
      HSA_CLASS="org.tron.core.db.Manager"
      HSA_METHOD="pushBlock"
      HSA_AFTER='tmpSession\.commit\(\);'
      HSA_MATCH='acknowledgeArchiveJournalOrFailStop\('
      HSA_DESC="pushBlock: the journal acknowledgement that follows the canonical commit"
      ;;
    km.w4)
      # Window "ack -> publish": the journal is acknowledged, nothing has been published. Anchor =
      # the solidified-publish call that follows the acknowledgement.
      HSA_SRC="framework/src/main/java/org/tron/core/db/Manager.java"
      HSA_CLASS="org.tron.core.db.Manager"
      HSA_METHOD="pushBlock"
      HSA_AFTER='acknowledgeArchiveJournalOrFailStop\('
      HSA_MATCH='publishArchiveSolidifiedOrFailStop\('
      HSA_DESC="pushBlock: the solidified publish that follows the journal acknowledgement"
      ;;
    km.w5)
      # Window "mid publish batch": index, temporal rows, marker, cursor and journal delete are
      # all staged into one UnifiedArchivePublish batch and the preflight snapshot is released;
      # the atomic RocksDB write has NOT run. Anchor = the call site of db.publishBlockAtomically.
      #
      # The enclosing method is publishBlockLocked(), NOT publishBlock(): publishBlock() only
      # takes the publication lock and delegates. Naming publishBlock() here is what kept this
      # window permanently degraded -- the javap guard correctly saw that the resolved line lives
      # in a different method and refused the anchor rather than breaking in the wrong place.
      HSA_SRC="chainbase/src/main/java/org/tron/core/archive/UnifiedArchiveBackend.java"
      HSA_CLASS="org.tron.core.archive.UnifiedArchiveBackend"
      HSA_METHOD="publishBlockLocked"
      HSA_AFTER=''
      HSA_MATCH='db\.publishBlockAtomically\('
      HSA_DESC="publishBlockLocked: the call site of db.publishBlockAtomically (the atomic batch)"
      ;;
    km.w6)
      # Window "genesis commitToRoot -> COMMITTED marker": canonical genesis state is committed to
      # the root stores, the COMMITTED marker has NOT been persisted. Anchor = the marker write
      # that follows commitToRoot().
      HSA_SRC="framework/src/main/java/org/tron/core/db/Manager.java"
      HSA_CLASS="org.tron.core.db.Manager"
      HSA_METHOD="initGenesis"
      HSA_AFTER='genesisSession\.commitToRoot\(\);'
      HSA_MATCH='saveArchiveGenesisCommitComplete\('
      HSA_DESC="initGenesis: the COMMITTED marker write that follows genesisSession.commitToRoot()"
      ;;
    cfk.flush)
      # Catch-up batch flush: createCheckpoint() has made the batched checkpoint durable and
      # refresh() has NOT applied it. Anchor = the refresh() that follows createCheckpoint().
      # The scenario also inspects `this.flushCount` / `this.maxFlushCount` at this breakpoint,
      # which is only meaningful before refresh() resets the batch.
      HSA_SRC="chainbase/src/main/java/org/tron/core/db2/core/SnapshotManager.java"
      HSA_CLASS="org.tron.core.db2.core.SnapshotManager"
      HSA_METHOD="flush"
      HSA_AFTER='createCheckpoint\(\);'
      HSA_MATCH='refresh\(\);'
      HSA_DESC="flush: the refresh() that follows the durable createCheckpoint()"
      ;;
    *) return 1 ;;
  esac
  return 0
}

hs_anchor_describe() {
  hs_anchor_spec "$1" || { printf 'unknown anchor descriptor: %s\n' "$1"; return 1; }
  printf '%s\n' "$HSA_DESC"
}

# ------------------------------------------------------------------------------- resolution

# hs_anchor_resolve <name> [jar] -- see the PUBLIC API block above.
hs_anchor_resolve() {
  local name="$1" jar="${2:-}" root src out first
  if ! hs_anchor_spec "$name"; then
    printf 'FAIL descriptor=%s unknown anchor descriptor\n' "$name"
    return 1
  fi
  [ -n "$jar" ] || jar="${HS_JAR:-}"
  [ -n "$jar" ] || jar="$(hs_anchor_default_jar)"
  if [ ! -f "$jar" ]; then
    printf 'FAIL descriptor=%s no jar under test at %s\n' "$name" "$jar"
    return 1
  fi
  if [ ! -f "$HS_ANCHOR_PY" ]; then
    printf 'FAIL descriptor=%s resolver missing at %s\n' "$name" "$HS_ANCHOR_PY"
    return 1
  fi
  root="$(hs_anchor_repo_root)"
  src="$root/$HSA_SRC"
  if [ ! -f "$src" ]; then
    printf 'FAIL descriptor=%s source file missing: %s\n' "$name" "$HSA_SRC"
    return 1
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    printf 'FAIL descriptor=%s python3 is required to resolve a semantic anchor\n' "$name"
    return 1
  fi
  if ! command -v javap >/dev/null 2>&1; then
    printf 'FAIL descriptor=%s javap is required to cross-check the anchor against the jar\n' \
      "$name"
    return 1
  fi

  out="$(
    HSA_NAME="$name" HSA_JAR="$jar" HSA_FILE="$src" HSA_REL="$HSA_SRC" \
    HSA_CLASS="$HSA_CLASS" HSA_METHOD="$HSA_METHOD" \
    HSA_AFTER="$HSA_AFTER" HSA_MATCH="$HSA_MATCH" \
    python3 "$HS_ANCHOR_PY" 2>&1
  )"
  # Pick the verdict line rather than line 1: python can prefix stderr warnings (deprecations,
  # site noise) and a blind `head -1` would swallow the real answer and report "no verdict".
  first="$(printf '%s\n' "$out" | grep -m1 -E '^(OK|FAIL) ' || true)"

  case "$first" in
    OK\ *)   printf '%s\n' "$first"; return 0 ;;
    FAIL\ *) printf '%s\n' "$first"; return 1 ;;
    *)
      printf 'FAIL descriptor=%s resolver produced no verdict: %s\n' \
        "$name" "$(printf '%s' "$out" | tr '\n' ' ' | cut -c1-300)"
      return 1 ;;
  esac
}

# hs_anchor_field <resolve-line> <key> -- echo the value of one key=value token.
hs_anchor_field() {
  local line="$1" key="$2" tok
  for tok in $line; do
    case "$tok" in
      "$key"=*) printf '%s\n' "${tok#*=}"; return 0 ;;
    esac
  done
  return 1
}

# hs_anchor_owner_matches <owners-csv> <method-reported-by-jdb>
#
# 0 when the method jdb reported is one of the methods the JAR says owns the anchored line. A
# statement holding a lambda compiles into two methods (the enclosing one and lambda$M$N) and
# jdb may report either -- both ARE that statement. Anything else means the breakpoint landed
# somewhere the anchor never described, which is a hard error, not a pass.
hs_anchor_owner_matches() {
  local owners="$1" seen="$2" one old_ifs
  old_ifs="$IFS"
  IFS=','
  for one in $owners; do
    if [ "$one" = "$seen" ]; then
      IFS="$old_ifs"
      return 0
    fi
  done
  IFS="$old_ifs"
  return 1
}

# hs_anchor_assert_hit <jdb-transcript> <owners-csv> <jarline>
#
# THIRD guard (the runtime one). Stages 1 and 2 are static; this reads what jdb actually
# reported and requires it to be the anchored statement: the right method AND the right line.
# Prints nothing and returns 0 on success; prints the reason and returns 1 otherwise, because
# every caller runs this inside $( ) where a global would be discarded.
hs_anchor_assert_hit() {
  local log="$1" owners="$2" want="$3" hit seen got one old_ifs
  hit="$(grep -m1 'Breakpoint hit' "$log" 2>/dev/null)"
  if [ -z "$hit" ]; then
    printf "no 'Breakpoint hit' line in %s\n" "$log"
    return 1
  fi
  # jdb prints:  Breakpoint hit: "thread=main", <fqcn>.<method>(), line=2,006 bci=915
  # The line number carries locale grouping separators, hence the tr below.
  seen="$(printf '%s\n' "$hit" | sed -n 's/.*\.\([A-Za-z0-9_$]*\)(), *line=.*/\1/p')"
  if [ -n "$seen" ]; then
    if ! hs_anchor_owner_matches "$owners" "$seen"; then
      printf 'breakpoint fired in %s(), but the jar says line %s belongs to {%s}: %s\n' \
        "$seen" "$want" "$owners" "$hit"
      return 1
    fi
  else
    # jdb's message format changed. Fall back to a substring test so a cosmetic change in the
    # debugger cannot turn every successful hit into a harness error.
    old_ifs="$IFS"
    IFS=','
    for one in $owners; do
      case "$hit" in
        *".$one("*) seen="$one"; break ;;
      esac
    done
    IFS="$old_ifs"
    if [ -z "$seen" ]; then
      printf 'breakpoint did not fire in any of {%s}: %s\n' "$owners" "$hit"
      return 1
    fi
  fi
  got="$(printf '%s\n' "$hit" | sed -n 's/.*line=\([0-9,]*\).*/\1/p' | tr -d ',')"
  if [ -n "$got" ] && [ "$got" != "$want" ]; then
    printf 'breakpoint fired at line %s but the anchor resolved to line %s: %s\n' \
      "$got" "$want" "$hit"
    return 1
  fi
  return 0
}

# ----------------------------------------------------------------------------------- selftest
#
# The resolver's whole value is that it REFUSES rather than guesses. That property is easy to
# lose in a later refactor and impossible to notice from a green scenario run -- a resolver that
# silently picked the first of two candidates would still make every window pass. So it is
# asserted here, against the real production sources, in about a second.

# hs_anchor_probe <label> <src-rel> <class> <method> <after> <match> -- resolve an AD-HOC
# descriptor that is deliberately NOT in the registry. Selftest use only.
hs_anchor_probe() {
  local label="$1" rel="$2" cls="$3" method="$4" after="$5" match="$6"
  local jar out
  jar="${HS_JAR:-$(hs_anchor_default_jar)}"
  out="$(
    HSA_NAME="$label" HSA_JAR="$jar" HSA_FILE="$(hs_anchor_repo_root)/$rel" HSA_REL="$rel" \
    HSA_CLASS="$cls" HSA_METHOD="$method" HSA_AFTER="$after" HSA_MATCH="$match" \
    python3 "$HS_ANCHOR_PY" 2>&1
  )"
  printf '%s\n' "$out" | grep -m1 -E '^(OK|FAIL) ' || printf 'FAIL %s no verdict\n' "$label"
}

# Defined at file scope, not nested inside hs_anchor_selftest: a function defined inside another
# function is still global in bash, so nesting would only hide that fact behind a generic name.
HS_ANCHOR_ST_RC=0
hs_anchor_st_expect_fail() { # <what> <resolve-output>
  case "$2" in
    FAIL\ *) printf 'ok   %s -> refused: %s\n' "$1" "${2#FAIL }" ;;
    *)
      printf 'FAIL %s -> resolver ACCEPTED what it must refuse: %s\n' "$1" "$2"
      HS_ANCHOR_ST_RC=1 ;;
  esac
}

hs_anchor_selftest() {
  local out mgr="framework/src/main/java/org/tron/core/db/Manager.java"
  local cls=org.tron.core.db.Manager
  HS_ANCHOR_ST_RC=0

  printf 'selftest: the resolver must refuse, not guess\n\n'

  out="$(hs_anchor_probe st.zero "$mgr" "$cls" pushBlock '' 'noSuchStatementExists\(')"
  hs_anchor_st_expect_fail "zero matches" "$out"

  # Two identical statements inside pushBlock: the classic "head -1 would have picked one" case.
  out="$(hs_anchor_probe st.two "$mgr" "$cls" pushBlock '' 'logger\.info\(SAVE_BLOCK')"
  hs_anchor_st_expect_fail "two matches in one method" "$out"

  out="$(hs_anchor_probe st.two_after "$mgr" "$cls" pushBlock 'logger\.info\(SAVE_BLOCK' 'return;')"
  hs_anchor_st_expect_fail "ambiguous 'after'" "$out"

  out="$(hs_anchor_probe st.nomethod "$mgr" "$cls" thisMethodDoesNotExist '' 'return;')"
  hs_anchor_st_expect_fail "method absent from source" "$out"

  # Proves comments are masked: this text exists in Manager.java ONLY inside a // comment.
  out="$(hs_anchor_probe st.comment "$mgr" "$cls" pushBlock '' 'clear ownerAddressSet')"
  hs_anchor_st_expect_fail "match that exists only inside a comment" "$out"

  # Resolves cleanly in stage 1 (initGenesis + a unique statement) so the ONLY thing that can
  # reject it is stage 2 failing to find the class in the jar.
  out="$(hs_anchor_probe st.nojar "$mgr" org.tron.core.db.NoSuchArchiveClass initGenesis \
         'genesisSession\.commitToRoot\(\);' 'saveArchiveGenesisCommitComplete\(')"
  hs_anchor_st_expect_fail "class absent from the jar (stage 2)" "$out"

  printf '\nselftest: every registered descriptor must resolve\n\n'
  local n res
  for n in $HS_ANCHOR_ALL; do
    res="$(hs_anchor_resolve "$n")"
    case "$res" in
      OK\ *) printf 'ok   %-10s %s\n' "$n" "${res#OK }" ;;
      *)     printf 'FAIL %-10s %s\n' "$n" "$res"; HS_ANCHOR_ST_RC=1 ;;
    esac
  done

  printf '\n'
  if [ "$HS_ANCHOR_ST_RC" -eq 0 ]; then
    printf 'ANCHOR_SELFTEST_OK\n'
  else
    printf 'ANCHOR_SELFTEST_FAIL\n'
  fi
  return "$HS_ANCHOR_ST_RC"
}

# ------------------------------------------------------------------------------------ CLI

hs_anchor_cli() {
  local names="" n line rc=0 jar
  case "${1:-}" in
    --list)
      for n in $HS_ANCHOR_ALL; do
        printf '%-10s %s\n' "$n" "$(hs_anchor_describe "$n")"
      done
      return 0 ;;
    --selftest)
      hs_anchor_selftest
      return $? ;;
    -h|--help)
      sed -n '2,68p' "$HS_ANCHOR_DIR/anchor.sh"
      return 0 ;;
  esac
  if [ "$#" -gt 0 ]; then
    names="$*"
  else
    names="$HS_ANCHOR_ALL"
  fi
  jar="${HS_JAR:-$(hs_anchor_default_jar)}"
  printf 'repo : %s\n' "$(hs_anchor_repo_root)"
  printf 'jar  : %s\n\n' "$jar"
  printf '%-10s %-8s %-8s %-6s %s\n' NAME JARLINE SRCLINE DELTA CLASS.OWNERS
  printf '%-10s %-8s %-8s %-6s %s\n' '---------' '-------' '-------' '-----' '------------'
  for n in $names; do
    line="$(hs_anchor_resolve "$n" "$jar")" || rc=1
    case "$line" in
      OK\ *)
        printf '%-10s %-8s %-8s %-6s %s.%s\n' \
          "$n" \
          "$(hs_anchor_field "$line" jarline)" \
          "$(hs_anchor_field "$line" srcline)" \
          "$(hs_anchor_field "$line" delta)" \
          "$(hs_anchor_field "$line" class)" \
          "$(hs_anchor_field "$line" owners)" ;;
      *)
        printf '%-10s %s\n' "$n" "$line" ;;
    esac
  done
  printf '\nwhat each anchor points at:\n'
  for n in $names; do
    printf '  %-10s %s\n' "$n" "$(hs_anchor_describe "$n")"
  done
  [ "$rc" -eq 0 ] || printf '\nat least one descriptor did not resolve\n'
  return "$rc"
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  hs_anchor_cli "$@"
  exit $?
fi
