#!/usr/bin/env python3
"""Publishes the relevant lines of Gradle logs as GitHub annotations (readable through the checks API)."""
import os, re, sys
PAT = re.compile(r'^e: |error:|FAILED|What went wrong|Execution failed|Could not |Unresolved|> Task .* FAILED|tests completed|Exception|AssertionError|^\s+at com\.anvex')
def esc(s): return s.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
for path in sys.argv[1:]:
    if not os.path.exists(path): continue
    lines = open(path, errors='replace').read().splitlines()
    keep, seen = [], set()
    for i, l in enumerate(lines):
        if PAT.search(l):
            l = l[:300]
            if l not in seen: seen.add(l); keep.append(l)
    title = f"{os.path.basename(path)}: {len(keep)} relevant line(s)"
    if keep:
        print(f"::error title={title}::" + esc("\n".join(keep[:300])))
    else:
        print(f"::notice title={title}::no errors found; last line: " + esc(lines[-1][:200] if lines else ""))
