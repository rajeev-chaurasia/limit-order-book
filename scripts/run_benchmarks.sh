#!/bin/bash
# Runs the full benchmark suite and collects results for the published graphs.
#
# Run on a quiesced machine: plugged in, high-performance power plan, other
# applications closed. Results land in docs/benchmarks/data/; regenerate the
# SVGs afterwards with: python scripts/plot_benchmarks.py
#
# Pass --quick for a smoke run (1 fork, short iterations, one parameter point).

set -euo pipefail
cd "$(dirname "$0")/.."

DATA_DIR=docs/benchmarks/data
JMH_JAR=core/build/libs/core-2.0.0-jmh.jar
HEAP="-Xms1g -Xmx1g"

QUICK_ARGS=""
if [[ "${1:-}" == "--quick" ]]; then
    QUICK_ARGS="-f 1 -wi 2 -w 1s -i 2 -r 1s -p depth=256 -p mix=ACTIVE"
    echo "Quick smoke mode"
fi

mkdir -p "$DATA_DIR"

echo "=== Building JMH jar ==="
./gradlew :core:jmhJar -q

echo "=== Engine throughput (thrpt, GC profiler) ==="
java -jar "$JMH_JAR" EngineBenchmark -bm thrpt -tu s \
    -jvmArgs "$HEAP" -prof gc \
    -rf json -rff "$DATA_DIR/throughput.json" $QUICK_ARGS

echo "=== Engine latency percentiles (sample) ==="
java -jar "$JMH_JAR" EngineBenchmark -bm sample -tu us \
    -jvmArgs "$HEAP" -prof gc \
    -rf json -rff "$DATA_DIR/latency.json" $QUICK_ARGS

echo "=== Ring handoff (3 producers, 1 consumer) ==="
java -jar "$JMH_JAR" RingBenchmark -bm thrpt -tu s \
    -jvmArgs "$HEAP" \
    -rf json -rff "$DATA_DIR/ring.json" ${QUICK_ARGS:+-f 1 -wi 2 -w 1s -i 2 -r 1s}

echo "=== End-to-end load tests (HdrHistogram) ==="
./gradlew :app:runLoadTest -q --args="100000 30 2 $DATA_DIR/e2e-latency-100k.hgrm"
./gradlew :app:runLoadTest -q --args="300000 30 4 $DATA_DIR/e2e-latency-300k.hgrm"

echo "=== Done. Data in $DATA_DIR ==="
