#!/usr/bin/env python3
"""One command collects every reproduction; contract failures are intentional on the baseline."""
from pathlib import Path
import subprocess

here = Path(__file__).resolve().parent
failed = False
for script in ("run_java.py", "firefox_downloads.py"):
    result = subprocess.run(["rtk", "proxy", "python3", str(here / script)])
    failed |= result.returncode != 0
    print(f"{script}: exit {result.returncode}", flush=True)
print("Evidence saved in bug-investigation/evidence/. FAIL denotes expected versus actual product behavior; inspect logs for setup errors.")
raise SystemExit(int(failed))
