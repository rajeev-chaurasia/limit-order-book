# Architecture

A limit order book with price-time priority matching, built around one idea:
**exactly one thread owns the book**. Everything else in the design follows
from taking that idea seriously: the lock-free command ring that feeds the
engine, the pooled intrusive data structures that let the hot path run
without allocating, and the seqlock that publishes market data without ever
blocking the matching thread.

This document explains the design and the reasoning behind each choice. The
performance methodology and results live in [PERFORMANCE.md](PERFORMANCE.md).

## Contents

1. [What this is](#what-this-is)
2. [The single-writer principle](#the-single-writer-principle)
3. [The command ring](#the-command-ring)
4. [Book structures](#book-structures)
5. [The zero-allocation discipline](#the-zero-allocation-discipline)
6. [Market data without locks](#market-data-without-locks)
7. [Correctness strategy](#correctness-strategy)
8. [Limitations and future work](#limitations-and-future-work)

## What this is

The core is a matching engine for one instrument: limit orders arrive, match
against the opposite side of the book at the resting order's price with FIFO
priority inside a price level, and any remainder rests. Cancels remove
resting orders in O(1). The engine reports results through primitive-argument
callbacks, publishes the top of book through a seqlock, and answers depth,
trade, and statistics snapshots on request.

Around the core sit a REST API (Javalin) and a Streamlit dashboard, kept
deliberately thin. The `core` Gradle module depends on fastutil and nothing
else; every framework lives in `app`.

## The single-writer principle

The obvious way to build a concurrent order book is fine-grained locking: a
concurrent sorted map of price levels, a lock per level, matching threads
locking the levels they touch. Designs in that family invite a specific
catalog of bugs, all living in the gaps between locks:

- **Lost orders**: one thread appends to a price level while another,
  having just drained it, deletes the level from the map. The order is
  linked into a level nothing references: on the books nowhere, yet still
  cancelable through the id index.
- **Double ownership**: a cancel and a match each conclude they own the
  same order, and a pooled object gets returned twice; the pool then hands
  one instance to two borrowers and the book silently corrupts.
- **A crossed book**: two opposite marketable orders scan the far side
  concurrently, each arrives before the other rests, both rest, and the
  book publishes bid >= ask, a state a matching engine must never emit.

None of these is an implementation slip. Each is an interleaving someone
would have had to imagine, across every pair of code paths that can touch a
level, and the set of such pairs grows with every feature. Locks of this
kind are not primarily slow; they are unprovable.

This engine removes the need to imagine interleavings instead: one thread,
the engine thread, owns every mutable structure in the book: the price
trees, the order index, the pools, the trade tape. It is the only thread
that ever reads or writes them. Under that rule:

- Every interleaving inside the engine is sequential. The failure modes
  above are not guarded against; they are *inexpressible*.
- The book needs no locks, no CAS, no volatile fields. Plain loads and
  stores, which is also exactly what makes it fast: no contended cache
  lines, no coherence traffic, no lock queues.
- Correctness arguments become local. `removeResting` is correct if the
  code above it in the same thread is correct; there is no "unless another
  thread is in this method" clause on anything.

Concurrency does not disappear; it moves to the edges, where it is handled
by three small, single-purpose mechanisms, each explainable in a paragraph:

```
producer threads (REST handlers, load generators)
      |                                    reader threads
      v                                          |
  MPSC command ring                              v
      |                                    L1Quote seqlock  <- engine publishes
      v                                          ^             after every command
  engine thread: MatchingEngine + OrderBook -----+
      |
      v
  response futures, trade tape, snapshots (built on the engine thread)
```

This is the shape LMAX made famous with the Disruptor: the business logic
runs on one thread at memory speed, and the engineering effort concentrates
on the handoff structures at the boundary.

## The command ring

Producers hand commands to the engine through a bounded multi-producer,
single-consumer ring (`MpscCommandRing`), a specialization of Vyukov's
bounded queue. The slots are preallocated `OrderCommand` objects; a producer
claims a slot, writes primitives into it, and publishes it. Nothing is
allocated per command, on either side.

**The protocol.** Every slot carries a sequence number. For ring position
`pos` (capacity `N`):

- `sequence == pos`: free, a producer may claim it
- `sequence == pos + 1`: published, the consumer may read it
- `sequence == pos + N`: consumed, free for the next lap

Producers race on a single tail cursor with compare-and-set; winning the CAS
is claiming the position. The consumer keeps a private head position and
walks it forward.

**The memory-ordering argument.** Four ordered operations make the ring
correct, and each exists for a stated reason:

1. The producer CAS on the tail orders competing producers; two producers
   can never claim the same position.
2. The producer's release-store of `sequence = pos + 1` after writing the
   slot fields is the publication point. Release semantics forbid the field
   writes from reordering after it.
3. The consumer's acquire-load of the sequence pairs with that release: if
   the consumer observes `pos + 1`, it observes every field write that
   preceded it.
4. The consumer's release-store of `sequence = pos + N` after processing
   pairs with the producer's acquire-load when reusing the slot: a producer
   cannot start overwriting a slot the consumer is still reading.

That is the entire proof surface, small enough to hold in your head at once,
which is the property fine-grained locking gives up.

**False sharing.** The head and tail cursors each live in a padded holder
(64 bytes on both sides). Producers hammer the tail with CAS; the consumer
advances the head after every batch. Without padding those two hot words can
share a cache line, and every consumer advance would invalidate the line the
producers are spinning on. This is invisible in any correctness test and
only shows up as lost throughput; it is the kind of detail the "mechanical
sympathy" school exists to name.

**Waiting and backpressure.** When the ring is empty the engine thread
either busy-spins (`Thread.onSpinWait`, lowest latency, burns a core) or
progressively backs off through yields to short parks (the default for the
demo server). When the ring is full, producers spin briefly, then park and
retry up to a deadline, then fail with `EngineBusyException`, which the REST
edge maps to HTTP 503. A full ring means the engine is the bottleneck;
queueing more work elsewhere would only hide that.

## Book structures

The book is two trees of price levels (bids and asks) plus a hash index from
order id to live order. Every structure is single-threaded, primitive-keyed,
and pooled.

**Intrusive AVL price trees.** Each `OrderLevel` is its own tree node: the
`left`, `right`, `parent`, and `height` fields live on the level object.
Bids are keyed by negated price and asks by price, so the minimum key is
always the best level and one tree implementation serves both sides. The
intrusive tree exists for a measured reason: library sorted maps (JDK or
fastutil) allocate an entry object per insertion, and price levels churn
constantly as the market moves. During development the GC profiler put that
cost at roughly 18 bytes per command under realistic churn, which is what
motivated making the levels their own nodes; creating or dropping a level is
now pointer surgery over pooled objects.

**Intrusive FIFO order queues.** Inside a level, resting orders form a
doubly-linked list threaded through the orders themselves (`next`, `prev` on
`Order`), preserving arrival order for time priority. An order also carries
a reference to the level it rests in. That back-reference makes cancel O(1)
(no tree search) and makes double-removal structurally impossible: unlinking
demands `order.level == this`, and an unlinked order has `level == null`.

**Pools.** Orders and levels are borrowed from fixed arrays preallocated at
startup and returned when consumed. A pool that runs dry degrades to plain
allocation rather than failing, and a pool that overflows drops the surplus
to the garbage collector: zero steady-state allocation is a sizing property,
verified by measurement, not a promise enforced by crashing.

**The id index.** A fastutil `Long2ObjectOpenHashMap` with primitive long
keys: open addressing, no per-entry allocation, no boxing on any operation.

**Aggregates maintained incrementally.** Each level tracks its total resting
quantity as orders are added, reduced, and removed, so an L2 depth snapshot
costs O(levels), not O(orders), and the L1 publication after each command is
two tree-minimum reads.

## The zero-allocation discipline

The rule is stated in terms of planes:

- **Data plane** (submit, cancel, the matching loop, event callbacks, the L1
  publication): allocates nothing in steady state. Not "little": nothing.
- **Control plane** (depth/trade/stats snapshots, REST responses): allocates
  freely. A snapshot request happens a few times per second; a command
  happens millions of times per second. Spending effort where the
  multiplier is means the split is not a compromise, it is the design.

What makes the data plane allocation-free:

1. Pooled `Order` and `OrderLevel` objects recycled through array stacks.
2. Intrusive links instead of container nodes: orders are their own queue
   nodes, levels are their own tree nodes.
3. Primitive-argument callbacks (`EngineListener`) instead of returned
   lists: reporting a fill costs a method call, not an `ArrayList` and a
   trade object per order.
4. Primitive long keys everywhere: a boxed-key map would allocate or box on
   effectively every book operation.
5. Preallocated ring slots and a trade tape of parallel `long[]` arrays.
6. An optional response future per command: attach one and the gateway
   allocates for you (the REST demo does); attach none and the submission
   path allocates nothing (the benchmark and load-test paths do not).

**The claim is proven, not asserted**, three independent ways (details and
numbers in [PERFORMANCE.md](PERFORMANCE.md)):

- `AllocationCheck` drives every data-plane operation shape and measures the
  engine thread's exact allocated bytes via `ThreadMXBean`: 0 bytes over
  tens of millions of operations.
- The same harness runs under Epsilon GC (a collector that never collects)
  on a 256 MB heap; surviving 80 million operations is itself the proof.
- The JMH suite runs with the GC profiler, where the optimized engine's
  `gc.alloc.rate.norm` reads approximately zero and the naive baseline's
  reads hundreds of bytes per command.

## Market data without locks

Readers (REST threads) need market data; the engine thread must never wait
for them. Three mechanisms, chosen by read rate:

**L1 by seqlock.** The top of book (best bid/ask and their quantities) is
read on every quote request, so it gets the cheapest possible path: a
seqlock. The writer bumps a version counter to odd, writes the four fields,
bumps it back to even; a reader reads the version, the fields, and the
version again, and retries if the version was odd or changed. The writer is
wait-free and allocation-free; readers never block the writer.

The fence placement deserves a sentence, because the obvious release/acquire
version is wrong: a release-store only orders *earlier* writes, so the field
writes could float above the odd-version store and a reader could validate a
torn read. The implementation uses explicit store-store fences around the
field writes and load-load fences around the field reads, which pins the
fields strictly between the two version accesses on both sides. A stress
test hammers the seqlock with four readers asserting an arithmetic relation
between the fields; the relation never breaks.

**L2, trades, and stats by command.** Depth snapshots ride the same ring as
orders: the engine builds the snapshot between matches and completes the
response future. The snapshot is internally consistent by construction (it
can never observe half a match), needs no locking anywhere, and costs the
data plane nothing when nobody asks. The dashboard polls at roughly 1 Hz;
one extra command per second is free.

**The trade tape.** Trades are recorded into fixed parallel `long[]` arrays
(a ring of the most recent 4096) owned by the engine thread. Recording is
five array stores; reading happens only inside a snapshot command, on the
engine thread. No synchronization exists because none is needed.

**Order acknowledgements.** A submitting thread that wants the result parks
on a `CompletableFuture` carried inside the command slot; the engine
completes it after processing. Callers only ever block on `get(timeout)`;
chaining dependents is forbidden by convention because `complete()` runs
dependents on the completing thread, and nothing may borrow the engine
thread.

## Correctness strategy

The design removes classes of bugs; the test suite hunts what remains.

- **A naive oracle.** `NaiveMatchingEngine` implements the same contract
  with `synchronized` methods, `TreeMap`, and `ArrayDeque`: sixty lines of
  obviously-correct logic. It is both the benchmark baseline and the
  reference implementation.
- **Differential testing.** Seeded random command streams (hundreds of
  thousands of commands per seed) run against both engines, which must
  produce byte-identical event sequences and identical final books. Any
  divergence is a bug in one of them. This is also the safety net that
  makes structural changes cheap: the intrusive AVL went in against a
  suite that replays half a million commands against the oracle.
- **Invariant checks.** After every batch: the book is never crossed, level
  aggregates equal the sum of their orders, prices are strictly ordered,
  the index size matches the book contents.
- **Structural tests for the AVL.** Random insert/remove churn against a
  `TreeMap` reference, validating ordering, parent links, and balance
  factors after every batch.
- **Conservation under concurrency.** Multiple producer threads fire orders
  through the ring, then cancel everything; the test asserts that for every
  order, submitted = filled + canceled remainder, that total buy volume
  equals total sell volume, and that the pools reconcile exactly.
- **Ring and seqlock stress.** Four producers push 800k commands through a
  small ring with per-producer FIFO and exact-count assertions; seqlock
  readers assert they never observe a torn quote.
- **Wire contract tests.** The REST layer's JSON field names and status
  strings are pinned by tests against a live server, because the deployed
  dashboard depends on them.

## Limitations and future work

Honest edges of the current design, in rough order of interest:

- **One instrument.** Multi-symbol support is a router in front of N
  engines, one per shard; the single-writer property is per-shard. This is
  the standard scaling story and deliberately out of scope here.
- **No persistence or replay.** The command ring is the natural journal
  point: writing the command stream to disk before the engine consumes it
  would give deterministic replay and crash recovery. The engine is already
  deterministic given a command sequence and a clock.
- **Price ladder.** For instruments with a bounded tick range, an array
  indexed by price outperforms any tree. The tree keeps the demo unrestricted;
  a ladder with tree fallback is the next structural optimization.
- **Binary wire protocol.** The REST gateway is a demo. A length-prefixed
  binary protocol over TCP, parsed straight into command slots, is the
  matching-engine-shaped front end.
- **JCStress.** The ring and seqlock are covered by stress tests and a
  written ordering argument; JCStress would exercise the memory model
  adversarially and is the right next step in verification rigor.
- **Order types.** Market orders, immediate-or-cancel, and self-match
  prevention are matching-policy extensions the engine loop structure
  already accommodates.
