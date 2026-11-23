#!/usr/bin/env python3
"""Extract the const D={...} object from the source HTML into the Android asset."""
import json, re, sys
from pathlib import Path
if len(sys.argv) != 2:
    raise SystemExit("Usage: python tools/extract_data.py path/to/BF_Discount_Scanner.html")
source = Path(sys.argv[1]).read_text(encoding="utf-8")
match = re.search(r"const\s+D\s*=\s*(\{.*?\})\s*;\s*(?:\n|$)", source, re.S)
if not match:
    raise SystemExit("Could not find the const D={...}; data object")
data = json.loads(match.group(1))
target = Path("app/src/main/assets/discounts.json")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print("Wrote {:,} rows to {}".format(len(data["rows"]), target))
