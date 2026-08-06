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


def load_jmh_glob(pattern):
    """Merge results split across files (e.g. per-engine runs)."""
    results = []
    for path in sorted(DATA.glob(pattern)):
        results.extend(load_jmh(path))
    return results


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


# JMH sample-mode percentiles are deliberately not plotted: per-command cost
# sits near the Windows timer resolution (about 100 ns), so sample mode
# quantizes to timer ticks and the medians are artifacts. The raw JSON stays
# committed; the published latency story is the end-to-end histogram, whose
# microsecond-scale values are well above timer resolution.


def plot_allocation(results):
    metric = "gc.alloc.rate.norm"
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


LOAD_COLORS = [OPTIMIZED, NAIVE]


def plot_e2e(series):
    """series: list of (label, points) with points as (percentile, value_us)."""
    fig, ax = plt.subplots(figsize=(7.0, 3.6))
    max_x = 10.0
    for (label, points), color in zip(series, LOAD_COLORS):
        xs = [1.0 / (1.0 - p) for p, _ in points]
        ys = [v for _, v in points]
        max_x = max(max_x, max(xs, default=10.0))
        ax.plot(xs, ys, linewidth=2, color=color, label=label, zorder=3)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ticks = [1, 10, 100, 1_000, 10_000, 100_000]
    tick_labels = ["p0", "p90", "p99", "p99.9", "p99.99", "p99.999"]
    shown = [(t, l) for t, l in zip(ticks, tick_labels) if t <= max_x * 1.5]
    ax.set_xticks([t for t, _ in shown], [l for _, l in shown])
    ax.set_ylabel("Enqueue-to-ack latency (us, log scale)")
    ax.set_title("End-to-end latency through ring and engine thread", fontsize=12, color=INK)
    ax.legend(frameon=False, fontsize=9, loc="upper left")
    style_axis(ax)
    ax.grid(axis="x", visible=True)
    fig.tight_layout()
    fig.savefig(ASSETS / "e2e_percentile_curve.svg", bbox_inches="tight")
    plt.close(fig)


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    generated = []

    throughput_results = load_jmh_glob("throughput*.json")
    if throughput_results:
        plot_throughput(throughput_results)
        plot_allocation(throughput_results)
        generated += ["throughput_by_depth.svg", "allocation_per_op.svg"]

    series = []
    for path in sorted(DATA.glob("e2e-latency*.hgrm")):
        label = path.stem.replace("e2e-latency-", "").replace("e2e-latency", "measured load")
        series.append((label + " orders/s" if label[:1].isdigit() else label, load_hgrm(path)))
    if series:
        plot_e2e(series)
        generated.append("e2e_percentile_curve.svg")

    if not generated:
        print("No benchmark data found under", DATA, file=sys.stderr)
        return 1
    for name in generated:
        print("Wrote", ASSETS / name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
