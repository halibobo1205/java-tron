#!/usr/bin/env python3
"""Resolve one SEMANTIC JDWP anchor to a jar line number.

Called only by anchor.sh, which owns the descriptor registry and the documentation of what each
anchor means. See the header of anchor.sh for the design; the short version:

  stage 1  read the CURRENT working-tree source, mask comments and literals, brace-match the body
           of the named METHOD (every overload), and require the descriptor to select EXACTLY ONE
           statement. Zero or two is a hard error -- an ambiguous anchor is never silently picked.

  stage 2  read the CURRENT jar's LineNumberTable for that method plus its compiler-synthesized
           lambda bodies, and solve for the single line offset `delta` between source and jar such
           that every jar line lands on a real code line of that same source body and the anchor
           lands on an actual table entry. Breakpoints are emitted in JAR coordinates, which is the
           only coordinate system jdb understands, so an edit ABOVE the method does not move them.

Input is entirely through environment variables (HSA_*). Output is exactly one line on stdout:

    OK class=<fqcn> jarline=<n> srcline=<n> delta=<d> src=<repo-relative> owners=<a,b>
    FAIL descriptor=<name> <reason>

The process always exits 0; the caller reads the first token. This keeps a resolution failure
distinguishable from a crash of this script, which the caller reports separately.

This script only READS production source. It never writes to it.
"""

import bisect
import os
import re
import subprocess
import sys

NAME = os.environ["HSA_NAME"]
JAR = os.environ["HSA_JAR"]
FILE = os.environ["HSA_FILE"]
REL = os.environ["HSA_REL"]
CLS = os.environ["HSA_CLASS"]
METHOD = os.environ["HSA_METHOD"]
AFTER = os.environ.get("HSA_AFTER", "")
MATCH = os.environ["HSA_MATCH"]


def fail(msg):
    sys.stdout.write("FAIL descriptor=%s %s\n" % (NAME, msg))
    sys.exit(0)


def mask(text):
    """Return (nocomment, skeleton).

    nocomment: comments blanked, string literals kept -- what the descriptor regexes see, so a
               match can never land inside a comment.
    skeleton:  comments AND literal contents blanked -- what brace matching sees, so a brace
               inside a string or a comment can never shift a method body.

    Both keep the original length and every newline, so line numbers stay exact.
    """
    n = len(text)
    nc = list(text)
    sk = list(text)

    def blank(i, both):
        if i < n and text[i] != "\n":
            sk[i] = " "
            if both:
                nc[i] = " "

    i = 0
    while i < n:
        c = text[i]
        d = text[i + 1] if i + 1 < n else ""
        if c == "/" and d == "/":
            while i < n and text[i] != "\n":
                blank(i, True)
                i += 1
            continue
        if c == "/" and d == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                blank(i, True)
                i += 1
            if i < n:
                blank(i, True)
                blank(i + 1, True)
                i += 2
            continue
        if text[i:i + 3] == '"""':
            blank(i, False)
            blank(i + 1, False)
            blank(i + 2, False)
            i += 3
            while i < n and text[i:i + 3] != '"""':
                blank(i, False)
                i += 1
            if i < n:
                blank(i, False)
                blank(i + 1, False)
                blank(i + 2, False)
                i += 3
            continue
        if c == '"' or c == "'":
            quote = c
            blank(i, False)
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\":
                    blank(i, False)
                    i += 1
                    if i < n:
                        blank(i, False)
                        i += 1
                    continue
                blank(i, False)
                i += 1
            if i < n:
                blank(i, False)
                i += 1
            continue
        i += 1
    return "".join(nc), "".join(sk)


def match_brace(s, start, opener, closer):
    """Index of the closer that balances the opener at `start`, or None."""
    depth = 0
    i = start
    n = len(s)
    while i < n:
        if s[i] == opener:
            depth += 1
        elif s[i] == closer:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def main():
    try:
        with open(FILE, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except Exception as exc:                                  # pragma: no cover - I/O guard
        fail("cannot read %s: %s" % (REL, exc))

    nocomment, skeleton = mask(text)
    lines_nc = nocomment.split("\n")
    starts = [0]
    for idx, ch in enumerate(skeleton):
        if ch == "\n":
            starts.append(idx + 1)

    def line_of(pos):
        return bisect.bisect_right(starts, pos)

    # ------------------------------------------------------- stage 1: locate the source method
    decl = re.compile(r"(?<![A-Za-z0-9_$.])" + re.escape(METHOD) + r"\s*\(")
    throws = re.compile(r"\s*throws\s+[A-Za-z0-9_$.,\s]*\{")
    bodies = []
    for m in decl.finditer(skeleton):
        close = match_brace(skeleton, m.end() - 1, "(", ")")
        if close is None:
            continue
        k = close + 1
        while k < len(skeleton) and skeleton[k] in " \t\r\n":
            k += 1
        if k < len(skeleton) and skeleton[k] == "{":
            body_open = k
        else:
            tail = throws.match(skeleton, close + 1)
            if not tail:
                continue                      # a call site, an abstract declaration, not a body
            body_open = tail.end() - 1
        body_close = match_brace(skeleton, body_open, "{", "}")
        if body_close is None:
            continue
        bodies.append((line_of(m.start()), line_of(body_open), line_of(body_close)))

    if not bodies:
        fail("no method named '%s' declared in %s" % (METHOD, REL))

    if AFTER:
        after_hits = []
        for index, (_decl, low, high) in enumerate(bodies):
            for num in range(low, high + 1):
                if re.search(AFTER, lines_nc[num - 1]):
                    after_hits.append((index, num))
        if len(after_hits) != 1:
            fail("'after' regex %s matched %d lines inside %s.%s (need exactly 1)%s"
                 % (AFTER, len(after_hits), CLS, METHOD,
                    "" if not after_hits else " at " + ",".join(str(h[1]) for h in after_hits)))
        first, after_line = after_hits[0]
        window = [(first, num) for num in range(after_line + 1, bodies[first][2] + 1)]
        scope = "%s.%s after line %d" % (CLS, METHOD, after_line)
    else:
        window = [(index, num)
                  for index, (_decl, low, high) in enumerate(bodies)
                  for num in range(low, high + 1)]
        scope = "%s.%s" % (CLS, METHOD)

    hits = [(index, num) for (index, num) in window if re.search(MATCH, lines_nc[num - 1])]
    if len(hits) != 1:
        fail("'match' regex %s matched %d lines inside %s (need exactly 1)%s"
             % (MATCH, len(hits), scope,
                "" if not hits else " at " + ",".join(str(h[1]) for h in hits)))
    body_index, src_line = hits[0]
    _decl_line, body_low, body_high = bodies[body_index]

    code_lines = set()
    for num in range(body_low, body_high + 1):
        if lines_nc[num - 1].strip():
            code_lines.add(num)

    # ------------------------------------------------------------ stage 2: cross-check the jar
    try:
        dump = subprocess.run(["javap", "-p", "-l", "-cp", JAR, CLS],
                              capture_output=True, text=True, check=True).stdout
    except Exception as exc:
        fail("javap -p -l failed for %s in %s (%s)" % (CLS, os.path.basename(JAR), exc))

    tables = {}
    current = None
    line_re = re.compile(r"^\s+line (\d+): (\d+)$")
    for raw in dump.split("\n"):
        if raw.startswith("  ") and not raw.startswith("   ") and "(" in raw:
            head = raw.split("(")[0].strip().split()
            current = (head[-1].split(".")[-1] if head else "", raw.strip())
            tables.setdefault(current, set())
            continue
        hit = line_re.match(raw)
        if hit and current is not None:
            tables[current].add(int(hit.group(1)))

    lambda_re = re.compile(r"^lambda\$" + re.escape(METHOD) + r"\$\d+$")
    kin = [key for key in tables if key[0] == METHOD or lambda_re.match(key[0])]
    if not kin:
        fail("class %s in the jar has no method '%s' (renamed or removed?)" % (CLS, METHOD))

    jar_lines = set()
    for key in kin:
        jar_lines |= tables[key]
    if not jar_lines:
        fail("the jar's %s.%s has an empty LineNumberTable (compiled without -g:lines?)"
             % (CLS, METHOD))

    def valid(delta):
        """True when `srcline = jarline + delta` lines the compiled code up with THIS body.

        Judged per compiled method, never over the union. `kin` can legitimately span several
        source bodies: overloads share a name, and their lambdas are numbered class-wide
        (`doWork(int)` and `doWork(String)` yield `lambda$doWork$0` and `lambda$doWork$1` with
        nothing to say which overload each came from). Unioning their tables and demanding every
        line fall inside one body refuses a perfectly good anchor.

        So each compiled method must be in exactly one of three states:
          * entirely on real code lines of this body  -> it IS this body, and may carry the anchor
          * entirely outside this body                -> a different overload; not our concern
          * partially overlapping this body           -> the body was rewritten, not merely
                                                         shifted. Refuse: no offset can be right.
        """
        anchored = False
        for key in kin:
            shifted = {jar_line + delta for jar_line in tables[key]}
            if not shifted:
                continue
            if shifted <= code_lines:
                if src_line in shifted:
                    anchored = True
            elif any(body_low <= s <= body_high for s in shifted):
                return False
        return anchored

    good = [d for d in sorted({src_line - jl for jl in jar_lines} | {0}) if valid(d)]
    if not good:
        fail("jar and source disagree about %s.%s beyond a line shift: jar table spans %d-%d, "
             "source body spans %d-%d, anchor statement at source line %d -- rebuild the jar "
             "(./gradlew :framework:buildFullNodeJar)"
             % (CLS, METHOD, min(jar_lines), max(jar_lines), body_low, body_high, src_line))
    if 0 in good:
        delta = 0                       # jar and source agree exactly; the identity always wins
    elif len(good) == 1:
        delta = good[0]
    else:
        fail("ambiguous source/jar line offset for %s.%s: %s all fit; refusing to guess"
             % (CLS, METHOD, ",".join(str(d) for d in good)))

    jar_line = src_line - delta
    owners = sorted({key[0] for key in kin if jar_line in tables[key]})
    if not owners:
        fail("internal: jar line %d has no owning method in %s" % (jar_line, CLS))

    sys.stdout.write("OK class=%s jarline=%d srcline=%d delta=%d src=%s owners=%s\n"
                     % (CLS, jar_line, src_line, delta, REL, ",".join(owners)))


main()
