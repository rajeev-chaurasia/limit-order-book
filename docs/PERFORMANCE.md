# Performance

Every number published in this repository is reproducible: the raw result
files are committed under [benchmarks/data/](benchmarks/data/), the exact
environment is recorded in [benchmarks/environment.md](benchmarks/environment.md),
and everything regenerates with two commands:

```bash
bash scripts/run_benchmarks.sh          # runs the suite, collects data
python scripts/plot_benchmarks.py      # renders the SVGs from the data
```

## What is measured, and what is not

Three distinct kinds of numbers, deliberately kept apart:

1. **Mechanism numbers** (JMH, `EngineBenchmark`): one thread drives one
   engine implementation directly. This isolates matching, book
   maintenance, and allocation behavior. It is the honest way to compare
   the single-writer engine against the naive baseline, because it measures
   the data structures rather than the handoff.
2. **Handoff numbers** (JMH, `RingBenchmark`): the MPSC ring in isolation,
   three producers and one consumer, measuring the claim/publish/drain
   cycle without any matching behind it.
3. **System numbers** (`LoadTestMain`): producer threads submit through the
   gateway at a fixed schedule and record enqueue-to-ack latency into an
   HdrHistogram. This is what a client of the whole system would see.

A mechanism number is not a system number; quoting one as the other is how
benchmark marketing works, and this project does not do it.

## Methodology

Microbenchmark suites fail in well-known ways, and this one is built to
avoid the usual four:

- **Invocation-level fixtures**: rebuilding state around every measured
  invocation makes the measurement mostly setup. JMH's own documentation
  warns about `@Setup(Level.Invocation)`; here, engines are rebuilt per
  iteration, outside the measured window.
- **A single fork**: JIT compilation is path-dependent, and one JVM
  instance is one sample from a distribution. Every published number here
  comes from three forks.
- **Throughput only**: latency systems are judged by their tails, so the
  suite runs SampleTime mode for percentiles alongside throughput.
- **Unverified allocation claims**: a zero-GC claim without a GC profiler
  attached is marketing. The profiler runs on every measurement here.

The suite:

- **Workloads** are precomputed, seeded command streams
  (`bench/workload/CommandStream.java`): no generation or allocation inside
  the measured loop. Three mixes vary the marketable-order share (QUIET 2%,
  ACTIVE 10%, CROSS_HEAVY 30%) with the remainder split between adds and
  cancels generated against a simulated live-order set, so the book stays
  roughly stationary while levels churn realistically. Cancels of orders a
  cross already consumed surface as cheap unknown-order rejects, which real
  order flow also produces.
- **Parameters**: engine (optimized, naive) x book depth (16, 256, 2048
  price levels per side) x mix. The book is rebuilt and prefilled every
  iteration, outside the measured window.
- **Modes**: Throughput for ops/s, SampleTime for per-command latency
  distributions. A caveat the data itself exposed: a single command costs
  from around 50 ns (optimized) to around 120 ns (naive), which sits at or
  below the Windows timer resolution of roughly 100 ns, so sample mode
  quantizes to timer ticks and the medians are artifacts of the clock, not
  the engine. The raw sample JSON is committed for completeness, but no
  chart is drawn from it; the published latency story is the end-to-end
  histogram, whose microsecond-scale values are far above timer resolution.
- **Rigor**: 3 forks, 5 warmup iterations, 5 measurement iterations, fixed
  1 GB heap, GC profiler on every run. The benchmark method returns a
  checksum accumulated by the event listener, so nothing is dead code.
- **Clock**: both engines run on an injected counter clock, so trade
  timestamping costs the same on both sides and neither pays
  `System.nanoTime` in the comparison.

The end-to-end harness corrects for **coordinated omission**: latency is
measured against each order's *intended* send time on a fixed schedule, not
against when the producer actually got around to sending it. A stall in the
engine therefore charges every order queued behind it, which is exactly what
a real client would experience. Measuring send-to-ack instead would let the
system hide its worst moments by slowing the load generator down.

## The zero-allocation proof

Three independent instruments agree:

1. **Exact thread-local counting** (`bench/AllocationCheck.java`): drives
   every data-plane operation shape (add + cancel on one level, add +
   cancel with level churn, crossing pairs producing trades, unknown-order
   rejects) for 10 million operations each and reads
   `ThreadMXBean.getThreadAllocatedBytes` around the measured lap. Result:
   **0 bytes, 0.0000 B/op on every path**.
2. **Epsilon GC endurance**: the same harness under
   `-XX:+UseEpsilonGC -Xmx256m`. Epsilon never collects, so the run only
   survives if the data plane truly does not allocate. It survives all 80
   million operations (warm and measured laps).
3. **JMH GC profiler**: `gc.alloc.rate.norm` for the optimized engine reads
   near zero (single-digit bytes per op on short runs, attributable to JIT
   compiler threads that the whole-process profiler also counts, decaying
   toward zero as compilation settles), against hundreds of bytes per
   command for the naive baseline. The first instrument is the arbiter;
   the profiler is the always-on regression tripwire.

The GC profiler earned its keep during development: it caught a library
sorted map allocating a tree entry on every price-level insertion (roughly
18 B/command under churn), which is what motivated the intrusive AVL tree
described in [ARCHITECTURE.md](ARCHITECTURE.md#book-structures).

## Results

The graphs embedded in the [README](../README.md) are rendered from the
committed data by `scripts/plot_benchmarks.py`:

| Graph | Source data | What it shows |
|---|---|---|
| `throughput_by_depth.svg` | `throughput-*.json` | Optimized vs naive ops/s across depth, per mix |
| `allocation_per_op.svg` | `throughput-*.json` | GC profiler B/op, naive vs optimized |
| `e2e_percentile_curve.svg` | `e2e-latency-*.hgrm` | Full percentile curves through ring and engine, per load level |

(`latency-*.json` from SampleTime mode is committed as raw data but not
charted; see the timer-resolution caveat above.)

Read the numbers with their environment in mind
([benchmarks/environment.md](benchmarks/environment.md)): they come from a
laptop, quiesced as far as a corporate laptop can be, with three forks
guarding against JIT luck. They are honest for what they are; they are not
exchange-colocation numbers, and the absolute values matter less than the
optimized-to-naive ratios and the shape of the tails.

## Reproducing

```bash
# Full suite (about half an hour)
bash scripts/run_benchmarks.sh

# Smoke run (a few minutes, one parameter point, reduced iterations)
bash scripts/run_benchmarks.sh --quick

# Allocation proof alone
./gradlew :core:jmhJar
java -cp core/build/libs/core-2.0.0-jmh.jar \
    io.github.rajeevchaurasia.orderbook.bench.AllocationCheck

# Epsilon endurance variant
java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx256m \
    -cp core/build/libs/core-2.0.0-jmh.jar \
    io.github.rajeevchaurasia.orderbook.bench.AllocationCheck

# Regenerate graphs
pip install -r scripts/requirements.txt
python scripts/plot_benchmarks.py
```

The CI benchmark workflow (`benchmarks.yml`) exists to prove the suite still
runs and to archive raw artifacts; numbers from shared CI runners are noise
and are never published.
