# Benchmark environment

The published numbers under [data/](data/) were collected on:

| Component | Value |
|---|---|
| CPU | Intel Core Ultra 7 265H (16 cores) |
| Memory | 64 GB |
| OS | Windows 11 Enterprise 25H2 (build 26200) |
| JDK | Temurin 25.0.4+7 (OpenJDK 64-Bit Server VM) |
| JMH | 1.37 |
| Heap for all runs | -Xms1g -Xmx1g |

Conditions: plugged in, high-performance power plan, no interactive use
during measurement. This is a laptop with corporate endpoint software, not
a tuned bench machine: no core isolation, no fixed CPU frequency, no NUMA
pinning. Three JMH forks per data point absorb some of that noise, and the
run-to-run spread is visible in the committed JSON (scoreError fields).
Treat the optimized-to-naive ratios and the shape of the percentile curves
as the durable results; the absolute numbers travel with the hardware.

## Exact commands

```bash
java -jar core/build/libs/core-2.0.0-jmh.jar EngineBenchmark \
    -bm thrpt -tu s -f 3 -wi 5 -w 1s -i 5 -r 2s \
    -p engine=optimized -prof gc -jvmArgs "-Xms1g -Xmx1g" \
    -rf json -rff docs/benchmarks/data/throughput-optimized.json
# repeated with -p engine=naive, and in -bm sample -tu us for latency

java -jar core/build/libs/core-2.0.0-jmh.jar RingBenchmark \
    -bm thrpt -tu s -f 3 -wi 5 -w 1s -i 5 -r 2s \
    -jvmArgs "-Xms1g -Xmx1g" \
    -rf json -rff docs/benchmarks/data/ring.json

./gradlew :app:runLoadTest --args="100000 30 2 docs/benchmarks/data/e2e-latency-100k.hgrm"
./gradlew :app:runLoadTest --args="300000 30 4 docs/benchmarks/data/e2e-latency-300k.hgrm"
```
