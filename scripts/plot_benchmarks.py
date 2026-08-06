"""Render the benchmark result SVGs embedded in the README.

Inputs (produced by scripts/run_benchmarks.sh):
  docs/benchmarks/data/throughput.json   JMH thrpt mode, -prof gc
  docs/benchmarks/data/latency.json      JMH sample mode
  docs/benchmarks/data/e2e-latency.hgrm  HdrHistogram percentile distribution

Outputs:
  docs/assets/throughput_by_depth.svg
  docs/assets/latency_percentiles.svg
  docs/assets/allocation_per_op.svg
  docs/assets/e2e_percentile_curve.svg
"""

import json
import math
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "docs" / "benchmarks" / "data"
ASSETS = ROOT / "docs" / "assets"

# Palette: two series only, validated adjacent pair on the light surface.
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SECONDARY = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"
BASELINE = "#c3c2b7"
OPTIMIZED = "#2a78d6"
NAIVE = "#eb6834"

ENGINE_LABELS = {"optimized": "Single-writer engine", "naive": "Naive synchronized engine"}
ENGINE_COLORS = {"optimized": OPTIMIZED, "naive": NAIVE}
MIX_LABELS = {"QUIET": "Quiet (2% crossing)", "ACTIVE": "Active (10% crossing)",
              "CROSS_HEAVY": "Cross-heavy (30% crossing)"}
MIXES = ["QUIET", "ACTIVE", "CROSS_HEAVY"]
DEPTHS = [16, 256, 2048]

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.size": 10,
    "svg.fonttype": "none",
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "axes.edgecolor": BASELINE,
    "axes.labelcolor": INK_SECONDARY,
    "axes.grid": True,
    "grid.color": GRID,
    "grid.linewidth": 0.7,
    "xtick.color": MUTED,
    "ytick.color": MUTED,
    "text.color": INK,
})


def load_jmh(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def result_key(entry):
    params = entry.get("params", {})
    return params.get("engine"), int(params.get("depth", 0)), params.get("mix")


def style_axis(ax):
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.set_axisbelow(True)
    ax.grid(axis="x", visible=False)


def plot_throughput(results):
    scores = {result_key(e): e["primaryMetric"]["score"] / 1e6 for e in results
              if e["benchmark"].endswith("processCommand")}
    fig, axes = plt.subplots(1, 3, figsize=(10.5, 3.4), sharey=True)
    width = 0.38
    for ax, mix in zip(axes, MIXES):
        xs = range(len(DEPTHS))
        for offset, engine in ((-width / 2, "optimized"), (width / 2, "naive")):
            values = [scores.get((engine, d, mix), 0.0) for d in DEPTHS]
            bars = ax.bar([x + offset for x in xs], values, width * 0.94,
                          color=ENGINE_COLORS[engine], label=ENGINE_LABELS[engine], zorder=3)
            for bar, value in zip(bars, values):
                ax.annotate(f"{value:.1f}", (bar.get_x() + bar.get_width() / 2, value),
                            textcoords="offset points", xytext=(0, 3),
                            ha="center", fontsize=8, color=INK_SECONDARY)
        ax.set_title(MIX_LABELS[mix], fontsize=10, color=INK)
        ax.set_xticks(list(xs), [str(d) for d in DEPTHS])
        ax.set_xlabel("Book depth (price levels per side)")
        style_axis(ax)
    axes[0].set_ylabel("Throughput (M commands/s)")
    axes[0].legend(frameon=False, fontsize=9, loc="upper right")
    fig.suptitle("Command throughput, single thread", fontsize=12, color=INK, y=1.02)
    fig.tight_layout()
    fig.savefig(ASSETS / "throughput_by_depth.svg", bbox_inches="tight")
    plt.close(fig)


def plot_latency(results):
    wanted = ["50.0", "90.0", "99.0", "99.9"]
    labels = ["p50", "p90", "p99", "p99.9"]
    entries = {result_key(e): e for e in results if e["benchmark"].endswith("processCommand")}
    fig, ax = plt.subplots(figsize=(7.0, 3.6))
    depth, mix = 256, "ACTIVE"
    for engine in ("optimized", "naive"):
        entry = entries.get((engine, depth, mix))
        if entry is None:
            continue
        pct = entry["primaryMetric"]["scorePercentiles"]
        values = [pct[p] * 1000 for p in wanted]
        ax.plot(labels, values, marker="o", markersize=6, linewidth=2,
                color=ENGINE_COLORS[engine], label=ENGINE_LABELS[engine], zorder=3)
        for x, value in zip(labels, values):
            ax.annotate(f"{value:,.0f}", (x, value), textcoords="offset points",
                        xytext=(8, -3), fontsize=8, color=INK_SECONDARY)
    ax.set_yscale("log")
    ax.set_ylabel("Latency (ns/command, log scale)")
    ax.set_title(f"Per-command latency percentiles (depth {depth}, {MIX_LABELS[mix].lower()})",
                 fontsize=12, color=INK)
    ax.legend(frameon=False, fontsize=9, loc="upper left")
    style_axis(ax)
    fig.tight_layout()
    fig.savefig(ASSETS / "latency_percentiles.svg", bbox_inches="tight")
    plt.close(fig)


def plot_allocation(results):
    metric = "·gc.alloc.rate.norm"
    rates = {}
    for entry in results:
        if not entry["benchmark"].endswith("processCommand"):
            continue
        engine, depth, mix = result_key(entry)
        if (depth, mix) != (256, "ACTIVE"):
            continue
        secondary = entry.get("secondaryMetrics", {})
        if metric in secondary:
            rates[engine] = secondary[metric]["score"]
    fig, ax = plt.subplots(figsize=(6.0, 3.2))
    engines = ["naive", "optimized"]
    values = [rates.get(e, 0.0) for e in engines]
    bars = ax.barh([ENGINE_LABELS[e] for e in engines], values,
                   color=[ENGINE_COLORS[e] for e in engines], height=0.55, zorder=3)
    for bar, value in zip(bars, values):
        label = f"{value:,.0f} B/op" if value >= 1 else f"{value:.2f} B/op"
        ax.annotate(label, (bar.get_width(), bar.get_y() + bar.get_height() / 2),
                    textcoords="offset points", xytext=(6, 0), va="center",
                    fontsize=9, color=INK)
    ax.set_xlabel("Allocation per command (bytes)")
    ax.set_title("Steady-state allocation, JMH GC profiler (depth 256, active mix)",
                 fontsize=12, color=INK)
    ax.grid(axis="y", visible=False)
    ax.grid(axis="x", visible=True)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.set_axisbelow(True)
    fig.tight_layout()
    fig.savefig(ASSETS / "allocation_per_op.svg", bbox_inches="tight")
    plt.close(fig)


def load_hgrm(path):
    points = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) < 3:
                continue
            try:
                value, percentile = float(parts[0]), float(parts[1])
            except ValueError:
                continue
            if 0.0 <= percentile < 1.0:
                points.append((percentile, value))
    return points


def plot_e2e(points):
    fig, ax = plt.subplots(figsize=(7.0, 3.6))
    xs = [1.0 / (1.0 - p) for p, _ in points]
    ys = [v for _, v in points]
    ax.plot(xs, ys, linewidth=2, color=OPTIMIZED, zorder=3)
    ax.set_xscale("log")
    ticks = [1, 10, 100, 1_000, 10_000, 100_000]
    tick_labels = ["p0", "p90", "p99", "p99.9", "p99.99", "p99.999"]
    limit = max(xs) if xs else 10
    shown = [(t, l) for t, l in zip(ticks, tick_labels) if t <= limit * 1.5]
    ax.set_xticks([t for t, _ in shown], [l for _, l in shown])
    ax.set_ylabel("Enqueue-to-ack latency (us)")
    ax.set_title("End-to-end latency through ring and engine thread", fontsize=12, color=INK)
    style_axis(ax)
    ax.grid(axis="x", visible=True)
    fig.tight_layout()
    fig.savefig(ASSETS / "e2e_percentile_curve.svg", bbox_inches="tight")
    plt.close(fig)


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    generated = []

    throughput_file = DATA / "throughput.json"
    if throughput_file.exists():
        results = load_jmh(throughput_file)
        plot_throughput(results)
        plot_allocation(results)
        generated += ["throughput_by_depth.svg", "allocation_per_op.svg"]

    latency_file = DATA / "latency.json"
    if latency_file.exists():
        plot_latency(load_jmh(latency_file))
        generated.append("latency_percentiles.svg")

    hgrm_file = DATA / "e2e-latency.hgrm"
    if hgrm_file.exists():
        plot_e2e(load_hgrm(hgrm_file))
        generated.append("e2e_percentile_curve.svg")

    if not generated:
        print("No benchmark data found under", DATA, file=sys.stderr)
        return 1
    for name in generated:
        print("Wrote", ASSETS / name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
