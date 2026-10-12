"""Turn `perf stat -x,` CSV into a flat JSON dict {event: value}. Unsupported/uncounted events map to null.
Usage: python parse_perf.py perf-stat.csv > perf.json"""
import json
import sys


def parse(text):
    out = {}
    for line in text.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        f = line.split(",")
        if len(f) < 3 or not f[2]:
            continue
        ev = f[2]
        try:
            v = float(f[0]) if "." in f[0] else int(f[0])
        except ValueError:  # "<not supported>", "<not counted>"
            v = None
        out[ev] = v if out.get(ev) is None else out[ev] + (v or 0)  # repeated lines (per-cpu): sum
    return out


if __name__ == "__main__":
    print(json.dumps(parse(open(sys.argv[1]).read()), indent=1))
