#!/usr/bin/env python3
"""Summarise and plot benchmark results.

Everything here is computed from results.jsonl files written by the harness; nothing is hard-coded or simulated. Figures
state the number of trials, the commit, the host and how many NUMA nodes the job could use, so a single-node run cannot be
mistaken for evidence about NUMA effects.

    python analysis/plot_results.py results/all results/other-job --out analysis/out
    python analysis/plot_results.py results/all --no-figures          # tables and report only
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import pandas as pd

# two-sided 95% Student-t critical values by degrees of freedom (n-1); 1.96 beyond the table
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
       12: 2.179, 15: 2.131, 20: 2.086, 25: 2.060, 30: 2.042}

CONFIG_KEYS = ["variant", "locality", "ringHome", "offheap"]
SWEEP_BASE = {"partitions": 4, "producers": 1, "consumers": 1, "stages": 1, "ring": 16384, "bytes": 64,
              "wait": "BUSY_SPIN", "work": 0, "mode": "MULTICAST"}
SWEEP_OVERRIDES = {"bytes": {"ring": 4096}}


def t_crit(n: int) -> float:
    if n < 2:
        return float("nan")
    df = n - 1
    if df in T95:
        return T95[df]
    return _interp(df) if df < 30 else 1.96 + (T95[30] - 1.96) * 30 / df


def _interp(df: int) -> float:
    ks = sorted(T95)
    for lo, hi in zip(ks, ks[1:]):
        if lo <= df <= hi:
            f = (df - lo) / (hi - lo)
            return T95[lo] + f * (T95[hi] - T95[lo])
    return 1.96


def load(paths: list[str]) -> tuple[pd.DataFrame, dict]:
    """Read every results.jsonl under the given paths. For duplicate (id, trial) the latest non-skipped record wins."""
    files: list[Path] = []
    for p in map(Path, paths):
        files += [p] if p.is_file() else sorted(p.rglob("results.jsonl"))
    if not files:
        sys.exit("no results.jsonl found under " + ", ".join(paths))
    records = []
    for f in files:
        for i, line in enumerate(f.read_text().splitlines(), 1):
            if not line.strip():
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError as e:
                sys.exit(f"{f}:{i}: invalid JSON ({e})")
            r["_source"] = str(f)
            records.append(r)
    df = pd.json_normalize(records, sep=".")
    stats = {"files": [str(f) for f in files], "records": len(df),
             "status": df["status"].value_counts().to_dict() if "status" in df else {}}
    # latest record per (id, trial) wins, but a skipped record never overrides a real one
    df["_order"] = range(len(df))
    real = df[df["status"] != "skipped"]
    skipped = df[df["status"] == "skipped"]
    skipped = skipped[~skipped.set_index(["id", "trial"]).index.isin(real.set_index(["id", "trial"]).index)]
    df = pd.concat([real, skipped]).sort_values("_order").drop_duplicates(["id", "trial"], keep="last")
    return df, stats


def mean_ci(values: pd.Series) -> tuple[float, float, float, int]:
    v = values.dropna().astype(float)
    n = len(v)
    if n == 0:
        return (float("nan"),) * 3 + (0,)
    m = v.mean()
    if n == 1:
        return m, float("nan"), float("nan"), 1
    sd = v.std(ddof=1)
    return m, sd, t_crit(n) * sd / math.sqrt(n), n


def summarise(ok: pd.DataFrame, column: str, extra_group: list[str] | None = None) -> pd.DataFrame:
    rows = []
    keys = ["workloadKey"] + [f"config.{k}" for k in CONFIG_KEYS] + (extra_group or [])
    for key, g in ok.groupby(keys, dropna=False):
        m, sd, ci, n = mean_ci(g[column])
        rows.append(dict(zip(keys, key)) | {"n": n, "mean": m, "std": sd, "ci95": ci, "min": g[column].min(), "max": g[column].max()})
    return pd.DataFrame(rows)


def ratio_table(summary: pd.DataFrame, baseline: dict, label: str, lower_is_better: bool = False) -> pd.DataFrame:
    """Per workloadKey: each configuration's mean divided by the baseline configuration's mean, with an approximate 95% CI."""
    out = []
    for wk, g in summary.groupby("workloadKey"):
        mask = pd.Series(True, index=g.index)
        for k, v in baseline.items():
            mask &= g[f"config.{k}"] == v
        base = g[mask]
        if base.empty or not base["mean"].iloc[0] or math.isnan(base["mean"].iloc[0]):
            continue
        b = base.iloc[0]
        for _, r in g.iterrows():
            ratio = r["mean"] / b["mean"]
            rel = math.sqrt((r["ci95"] / r["mean"]) ** 2 + (b["ci95"] / b["mean"]) ** 2) if r["n"] > 1 and b["n"] > 1 else float("nan")
            lo, hi = ratio * (1 - rel), ratio * (1 + rel)
            better = (hi < 1) if lower_is_better else (lo > 1)
            worse = (lo > 1) if lower_is_better else (hi < 1)
            out.append({"workloadKey": wk, **{k: r[f"config.{k}"] for k in CONFIG_KEYS}, "baseline": label,
                        "n": int(r["n"]), "baseline_n": int(b["n"]), "mean": r["mean"], "baseline_mean": b["mean"],
                        "ratio": ratio, "ci95_low": lo, "ci95_high": hi,
                        "verdict": "better" if better else "worse" if worse else "inconclusive" if rel == rel else "n<2"})
    return pd.DataFrame(out)


def numa_note(df: pd.DataFrame) -> str:
    col = "metadata.usableNodes"
    if col not in df:
        return "NUMA nodes: unknown"
    counts = sorted({len(x) for x in df[col].dropna()})
    return f"usable NUMA nodes per run: {counts}" + ("  -- SINGLE NODE: no NUMA effect can appear" if counts and max(counts) < 2 else "")


def footer(df: pd.DataFrame, n_label: str) -> str:
    commit = ",".join(sorted({str(c)[:10] for c in df.get("metadata.gitCommit", pd.Series(["?"])).dropna()}))
    host = ",".join(sorted({str(h) for h in df.get("metadata.host", pd.Series(["?"])).dropna()}))
    return f"{n_label} | commit {commit} | {host} | {numa_note(df)}"


def label_of(row) -> str:
    loc = row["config.locality"]
    s = f"{row['config.variant']}/{loc}"
    if loc not in ("OS", "AUTO", "DENSE", "ROUND_ROBIN"):
        s += f"/ring@{str(row['config.ringHome'])[:4].lower()}"
    if row.get("config.offheap") is True:
        s += "/offheap"
    return s


def make_figures(ok: pd.DataFrame, out: Path) -> list[str]:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    made: list[str] = []

    def save(fig, name: str, note: str):
        fig.text(0.01, 0.005, note, fontsize=6, ha="left", va="bottom", color="#555")
        fig.tight_layout(rect=(0, 0.03, 1, 1))
        fig.savefig(out / name, dpi=150)
        plt.close(fig)
        made.append(name)

    tp = ok[ok["config.metric"] == "THROUGHPUT"]
    if not tp.empty:
        s = summarise(tp, "throughput.eventsPerSec")
        groups = [(wk, g) for wk, g in s.groupby("workloadKey") if len(g) >= 2]
        groups.sort(key=lambda x: -len(x[1]))
        for wk, g in groups[:6]:
            g = g.copy()
            g["label"] = g.apply(lambda r: label_of({k: r[f"config.{k}"] for k in CONFIG_KEYS} | {"config.locality": r["config.locality"],
                                                      "config.variant": r["config.variant"], "config.ringHome": r["config.ringHome"],
                                                      "config.offheap": r["config.offheap"]}), axis=1)
            g = g.sort_values("label")
            fig, ax = plt.subplots(figsize=(8, 0.35 * len(g) + 1.5))
            ax.barh(g["label"], g["mean"] / 1e6, xerr=g["ci95"].fillna(0) / 1e6, color="#4c78a8", ecolor="#222", capsize=2)
            ax.set_xlabel("completed events / s (millions), mean with 95% CI over trials")
            ax.set_title(f"Throughput by placement\n{wk}", fontsize=8)
            save(fig, f"throughput_{_slug(wk)}.png", footer(tp, f"trials per bar: {int(g['n'].min())}-{int(g['n'].max())}"))

    lat = ok[ok["config.metric"] == "LATENCY"]
    if not lat.empty:
        pcts = [("latency.p50Ns", "p50"), ("latency.p95Ns", "p95"), ("latency.p99Ns", "p99"), ("latency.p999Ns", "p99.9")]
        for wk, g in list(lat.groupby("workloadKey"))[:6]:
            rows = []
            for key, gg in g.groupby(["config.variant", "config.locality", "config.ringHome", "config.offheap"], dropna=False):
                rows.append((label_of({"config.variant": key[0], "config.locality": key[1], "config.ringHome": key[2], "config.offheap": key[3]}),
                             [gg[c].astype(float).mean() / 1000.0 for c, _ in pcts], len(gg)))
            if len(rows) < 2:
                continue
            rows.sort()
            fig, ax = plt.subplots(figsize=(max(7, 0.9 * len(rows)), 4))
            w = 0.8 / len(pcts)
            for i, (_, name) in enumerate(pcts):
                ax.bar([j + i * w for j in range(len(rows))], [r[1][i] for r in rows], w, label=name)
            ax.set_xticks([j + 0.4 - w / 2 for j in range(len(rows))])
            ax.set_xticklabels([r[0] for r in rows], rotation=60, ha="right", fontsize=7)
            ax.set_yscale("log")
            ax.set_ylabel("latency, microseconds (log); mean of per-trial percentiles")
            ax.set_title(f"End-to-end latency (intended send -> gateway receipt)\n{wk}", fontsize=8)
            ax.legend()
            save(fig, f"latency_{_slug(wk)}.png", footer(lat, f"trials per bar: {min(r[2] for r in rows)}-{max(r[2] for r in rows)}"))

    # partition-count scaling (shape key = workload without the partition count)
    if tp["config.partitions"].nunique() > 1:
        s = summarise(tp, "throughput.eventsPerSec", ["config.partitions", "config.ring", "config.bytes", "config.consumers", "config.producers"])
        base = s[(s["config.ring"] == 16384) & (s["config.bytes"] == 64) & (s["config.consumers"] == 1) & (s["config.producers"] == 1)]
        if base["config.partitions"].nunique() > 1:
            fig, ax = plt.subplots(figsize=(7, 4))
            for key, g in base.groupby(["config.variant", "config.locality"]):
                g = g.sort_values("config.partitions")
                ax.errorbar(g["config.partitions"], g["mean"] / 1e6, yerr=g["ci95"].fillna(0) / 1e6, marker="o", capsize=2,
                            label=f"{key[0]}/{key[1]}")
            ax.set_xlabel("partitions (independent pipelines)")
            ax.set_ylabel("completed events / s (millions)")
            ax.set_xscale("log", base=2)
            ax.legend(fontsize=7)
            ax.set_title("Throughput vs partition count")
            save(fig, "scaling_partitions.png", footer(tp, "mean with 95% CI"))

    # placement calculation overhead
    ov = ok[ok["placement.optimizerNanos"].notna() & (ok["config.locality"].isin(["AUTO", "DENSE", "ROUND_ROBIN"]))]
    if not ov.empty:
        fig, ax = plt.subplots(figsize=(6, 3.5))
        for loc, g in ov.groupby("config.locality"):
            m = g.groupby("config.partitions")["placement.optimizerNanos"].mean() / 1000.0
            ax.plot(m.index, m.values, marker="o", label=loc)
        ax.set_xlabel("partitions")
        ax.set_ylabel("placement calculation, microseconds")
        ax.set_yscale("log")
        ax.legend()
        ax.set_title("Placement optimizer overhead (one-off, at start)")
        save(fig, "optimizer_overhead.png", footer(ov, "mean over trials, includes graph build + cold JIT"))

    # one-factor sweeps
    sweeps = tp[tp["config.locality"].isin(["OS", "DENSE", "AUTO"])]
    for factor in ["producers", "consumers", "ring", "bytes", "wait", "work", "mode", "stages"]:
        sel = sweeps.copy()
        base = SWEEP_BASE | SWEEP_OVERRIDES.get(factor, {})
        for k, v in base.items():
            if k != factor:
                sel = sel[sel[f"config.{k}"] == v]
        if sel[f"config.{factor}"].nunique() < 2:
            continue
        s = summarise(sel, "throughput.eventsPerSec", [f"config.{factor}"])
        fig, ax = plt.subplots(figsize=(6.5, 3.8))
        vals = sorted(s[f"config.{factor}"].unique(), key=str)
        w = 0.8 / max(1, s.groupby(["config.variant", "config.locality"]).ngroups)
        for i, (key, g) in enumerate(s.groupby(["config.variant", "config.locality"])):
            g = g.set_index(f"config.{factor}").reindex(vals)
            ax.bar([j + i * w for j in range(len(vals))], g["mean"] / 1e6, w, yerr=g["ci95"].fillna(0) / 1e6, label=f"{key[0]}/{key[1]}", capsize=1)
        ax.set_xticks([j + 0.4 - w / 2 for j in range(len(vals))])
        ax.set_xticklabels([str(v) for v in vals])
        ax.set_xlabel(factor)
        ax.set_ylabel("completed events / s (millions)")
        ax.legend(fontsize=7)
        ax.set_title(f"Sweep: {factor} (other factors at base values)", fontsize=9)
        save(fig, f"sweep_{factor}.png", footer(sel, "mean with 95% CI"))

    mig = ok[ok["cpu.migrationsObserved"].notna()]
    if not mig.empty:
        m = mig.groupby(["config.variant"])["cpu.migrationsObserved"].mean()
        fig, ax = plt.subplots(figsize=(4.5, 3.2))
        ax.bar(m.index, m.values, color="#e45756")
        ax.set_ylabel("observed CPU changes per trial (lower bound)")
        ax.set_title("CPU migrations (sched_getcpu proxy)", fontsize=9)
        save(fig, "cpu_migrations.png", footer(mig, "mean over all trials of the variant"))
    return made


def _slug(s: str) -> str:
    return "".join(c if c.isalnum() else "_" for c in s)[:90]


def write_report(df: pd.DataFrame, ok: pd.DataFrame, stats: dict, tables: dict[str, pd.DataFrame], out: Path, figs: list[str]) -> None:
    lines = ["# Benchmark report (generated from recorded measurements)", "",
             f"- sources: {len(stats['files'])} results file(s), {stats['records']} records, status counts {stats['status']}",
             f"- {numa_note(df)}",
             f"- commits: {sorted({str(c) for c in df.get('metadata.gitCommit', pd.Series()).dropna()})}",
             f"- hosts: {sorted({str(c) for c in df.get('metadata.host', pd.Series()).dropna()})}", ""]
    sk = df[df["status"] == "skipped"]
    if not sk.empty:
        lines += ["## Experiments skipped (and why)", ""]
        for reason, g in sk.groupby("reason"):
            lines.append(f"- {len(g)}x: {reason}")
        lines.append("")
    fl = df[df["status"] == "failed"]
    if not fl.empty:
        lines += ["## FAILED trials (correctness violations or crashes) - results are not trustworthy until fixed", ""]
        for _, r in fl.iterrows():
            lines.append(f"- {r['id']} trial {r['trial']}: {r.get('reason')}")
        lines.append("")
    for name, t in tables.items():
        if t.empty:
            continue
        lines += [f"## {name}", "", t.to_markdown(index=False, floatfmt=".4g"), ""]
    if figs:
        lines += ["## Figures", ""] + [f"- {f}" for f in figs]
    lines += ["", "Interpretation rules: a ratio is only meaningful within one workloadKey (identical work and ordering semantics);",
              "`verdict` is `inconclusive` when the approximate 95% interval contains 1.0. Optimizer cost numbers are model estimates,",
              "not measured remote traffic; numastat deltas are page-allocation counters, not access counts."]
    (out / "report.md").write_text("\n".join(lines))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="results.jsonl files or directories containing them")
    ap.add_argument("--out", default="analysis/out")
    ap.add_argument("--no-figures", action="store_true")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    df, stats = load(args.paths)
    ok = df[df["status"] == "ok"].copy()
    if ok.empty:
        write_report(df, ok, stats, {}, out, [])
        sys.exit(f"no successful trials to analyse (status counts: {stats['status']}); see {out / 'report.md'}")

    tables: dict[str, pd.DataFrame] = {}
    tp = ok[ok["config.metric"] == "THROUGHPUT"]
    if not tp.empty:
        s = summarise(tp, "throughput.eventsPerSec")
        tables["Throughput (events/s) per configuration"] = s
        tables["Throughput ratio vs V0 (OS scheduling)"] = ratio_table(s, {"variant": "V0", "locality": "OS"}, "V0/OS")
        tables["Throughput ratio vs V1 (pinned, DENSE)"] = ratio_table(s, {"variant": "V1", "locality": "DENSE"}, "V1/DENSE")
    lat = ok[ok["config.metric"] == "LATENCY"]
    for col, name in [("latency.p50Ns", "p50"), ("latency.p99Ns", "p99"), ("latency.p999Ns", "p99.9")]:
        if not lat.empty:
            s = summarise(lat, col)
            tables[f"Latency {name} (ns) per configuration"] = s
            tables[f"Latency {name} ratio vs V0 (<1 is better)"] = ratio_table(s, {"variant": "V0", "locality": "OS"}, "V0/OS", lower_is_better=True)
    ov = ok[ok["placement.optimizerNanos"].notna() & ok["config.locality"].isin(["AUTO", "DENSE", "ROUND_ROBIN"])]
    if not ov.empty:
        tables["Placement calculation overhead (ns)"] = summarise(ov, "placement.optimizerNanos", ["config.partitions"])
    bp = ok[ok["backpressure.stallsPerMillionEvents"].notna()]
    if not bp.empty:
        tables["Producer stalls per million events"] = summarise(bp, "backpressure.stallsPerMillionEvents")
    for name, t in tables.items():
        t.to_csv(out / (_slug(name).lower() + ".csv"), index=False)

    figs = [] if args.no_figures else make_figures(ok, out)
    write_report(df, ok, stats, tables, out, figs)
    print(f"{len(ok)} ok trials of {stats['records']} records; wrote {len(tables)} tables and {len(figs)} figures to {out}")


if __name__ == "__main__":
    main()
