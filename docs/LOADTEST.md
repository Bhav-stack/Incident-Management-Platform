# Ingest performance: measured, not asserted

Raw output of the run below: `docs/loadtest-ingest.txt`.

```
./scripts/loadtest.sh --rates 200,1000,2000,5000,7000,10000 --phase-seconds 30 \
    --out docs/loadtest-ingest.txt
```

Host: Docker Desktop on Windows 11 (16 logical cores, i5-1240P, ~7.6 GB for the
Linux VM), Kafka/Postgres/Redis in containers, ingest-svc as a local JVM
(JDK 24, default heap). Single broker; `raw.events` is created with 3 partitions
and the listener runs 3 threads, one per partition.

Numbers are from one machine and one topology; treat them as a lower bound on
real hardware and read the ratios, not the absolute figures.

## Why the harness paces events instead of bursting them

The first version of this script pushed 100k events through the Kafka CLI as fast
as it could and reported "pipeline throughput". That number was meaningless, and
the reason is a property of the pipeline rather than of the script:

* the buffer holds every event for `ingest.buffer.window` (5s) and drops anything
  older than `window + grace` (20s) as a straggler, by design;
* a burst finishes producing in ~9s, but the consumer needs minutes to work
  through it, so by the time it reaches the tail the tail is older than the grace
  window and is correctly dropped.

A burst therefore measures the first 20 seconds of a stream, not the pipeline. The
harness paces events at a fixed offered rate with `eventTime = send time`, which
is how a real producer behaves, and steps the rate up. Each event time is live, so
`event_time -> rule` staleness is a real staleness figure rather than an artifact
of the generator.

## What "kept up" means

The pipeline is *allowed* to hold events back: the out-of-order buffer waits 5s
for late siblings and drains on a 1s tick. At the instant a phase ends, six
seconds' worth of its tail is still legitimately in flight, so the raw figure
(`ordered_by_end_of_phase`) tops out near 75% at *every* rate — including rates
the pipeline handles with room to spare.

`kept up` therefore compares what completed against what was due by then: the
offered stream minus the 5s window and the 1s flush tick. Above 90% is ripple;
below it is a backlog. The script prints both, plus `drain_rate`, which is the
backlog divided by the catch-up time — the pipeline's own rate once the producer
has stopped competing for the machine.

Getting this wrong is easy and it matters: the first version of this file read
"77% at 200/s" as headroom and "77% at 5,000/s" as the same number, when the
second was a saturated rig and the first was arithmetic.

## Results

`accepted = received - deduplicated`, and accepted events are fully accounted for
as `ordered + dropped`.

| offered | accepted | ordered | due by end | kept up | drain rate | dropped |
|---|---|---|---|---|---|---|
| 200/s | 5,453 | 5,453 | 4,800 | 93% | 240/s | 0 |
| 1,000/s | 27,059 | 27,059 | 24,000 | 94% | 1,188/s | 0 |
| 2,000/s | 54,069 | 54,069 | 48,000 | 93% | 2,331/s | 0 |
| 5,000/s | 135,237 | 135,237 | 120,000 | 92% | 6,274/s | 0 |
| 7,000/s | 189,060 | 189,060 | 168,000 | 96% | 8,254/s | 0 |
| 10,000/s | 270,077 | 270,077 | 240,000 | 74% | 9,729/s | 0 |

**Nothing was dropped at any step, including 10,000/s.** `ordered` equals
`accepted` everywhere; the pipeline never had to discard a straggler or overflow a
queue. What changes at 10,000/s is that it stops keeping up: it completes 74% of
the stream while it is being produced, has to drain the rest afterwards, and that
backlog is what pushes `event_time -> rule` from 5.5s to 10.4s.

Per-stage mean latency for each step, exact from timer `count`/`sum` deltas:

| offered | dedup (ms/event) | buffer offer (ms) | rule (ms/sample) | `event_time -> rule` (ms) |
|---|---|---|---|---|
| 200/s | 0.511 | 0.003 | 0.073 | 5,520 |
| 1,000/s | 0.141 | 0.002 | 0.015 | 5,514 |
| 2,000/s | 0.094 | 0.002 | 0.008 | 5,517 |
| 5,000/s | 0.083 | 0.002 | 0.006 | 5,541 |
| 7,000/s | 0.083 | 0.002 | 0.006 | 5,563 |
| 10,000/s | 0.079 | 0.002 | 0.006 | 10,448 |

Whole-run percentiles (Micrometer publishes them over a rolling window, so the
final table describes the end of the run — which is the 10,000/s step, backlog
included):

| stage | p50 | p95 | p99 | max |
|---|---|---|---|---|
| dedup | 0.072ms | 0.145ms | 0.178ms | 2.31ms |
| buffer offer | 0.001ms | 0.004ms | 0.005ms | 12.2ms |
| rule | 0.006ms | 0.010ms | 0.012ms | 0.06ms |
| `event_time -> rule` | 11.0s | 13.2s | 13.2s | 13.5s |

Up to 7,000/s, `event_time -> rule` is flat at 5.5s — the deliberate 5s ordering
window plus ~0.5s of production-to-rule delay — and it does not grow with load.
It used to grow with load (5.7s at 200/s to 14.2s at 1,000/s) because events
queued behind the rule work; that queueing is gone.

## Where the ceiling actually is

The first pass at 5,000/s and 10,000/s produced much worse numbers than the table
above (40-44% kept up at 10,000/s). The limit was not the ingest service, and
finding that out took three measurements rather than an opinion:

* **`process_cpu_usage` on ingest-svc** peaked at 0.076 — the whole service used
  *under one core* while it was falling behind. The pipeline was idle, waiting.
* **`docker stats` on the broker** peaked at 652% CPU (6.5 cores) and the machine
  as a whole hit 84% of its 16 logical cores. The broker was the busy component,
  not the consumer.
* **`redis-cli INFO commandstats`** reported `cmdstat_set` at 450,014 calls and
  8.77µs per call, 8.8s of CPU over ~10 minutes: Redis was under 1.5% of a core.
  Its dedup stage was never the ceiling.

The broker was burning six cores because the load generator fed it one request
per record: `kafka-console-producer.sh` reads a line at a time, and with the
default `linger.ms=0` every line became its own produce request. That is a
generator defect, not a pipeline one, but it was the ceiling. The generator now
accumulates and compresses like a real producer
(`linger.ms=50`, `batch.size=262144`, `compression.type=lz4`,
`max.in.flight.requests.per.connection=5`), which took the 10,000/s step from 40%
to 54% kept up and roughly halved broker CPU. The remaining gap is the rig:
single broker, single node, producer-and-consumer on one box.

## What this run changed

**1. The load generator batches and compresses.** As above: 10,000/s went from
3,990/s sustained to 5,362/s immediately after the change, with broker CPU down
from a 652% peak to a 367% peak. Without it the ladder measures the harness.

**2. The keep-up metric accounts for the ordering window.** `kept up` is now
against the events due by the end of the phase, which is what makes 92% at
5,000/s read differently from 74% at 10,000/s instead of both reading as "77%".

**3. The report separates the rig from the pipeline.** `drain_rate` plus
`generator_wall_ms` say whether a step was limited by the generator, the broker,
or the pipeline; `generator_wall_ms` also catches the case where the generator is
throttled by its own pipe (it would exceed the phase length).

## What changed before this run

The previous ladder measured a ceiling of ~490 events/s and identified one Redis
round trip per event in dedup. Four changes removed it, and the order matters
because the first one alone made things *worse*.

**1. Dedup claims a whole poll batch in one pipelined round trip.**
`spring.kafka.listener.type: batch` and `RedisDedupStore.filterNew` issue a
pipelined `SETNX` for every id in the poll, instead of one `SETNX` per record.
Dedup mean fell from 1.43ms to ~0.1ms per event.

**2. That alone regressed 1,000/s, which is the finding.** With dedup pipelined,
the consumer outran the rule stage, the per-source buffer filled, and the
oldest-first trim dropped events that were about to become due: at 1,000/s the run
lost 22,979 events to overflow, against 1,353 before. The old number was better
only because a slow consumer was acting as a throttle. This is why the two halves
of "pipeline the Redis calls" cannot be split.

**3. Rule state is read and written once per flush batch.**
`RuleStateStore.snapshot` reads streak and fired state for every key in the batch
in one pipeline (two `GET`s per key), the rule simulates the batch locally, and
`applyStreaks` writes the net result in one more pipeline. Rule mean fell from
1.66ms per sample to 0.006ms. Two correctness details came out of this:
* a reset-then-breach cycle inside one batch must clear the fired flag *between*
  the two episodes, or the second episode is silently lost — hence `clearFired`,
  called at most once per firing episode;
* deletes are applied before streak writes in `applyStreaks`, because a key that
  reset and then breached again appears in both arguments.

**4. The per-source queue cap was smaller than one window of traffic.**
At 1,000/s with 3 sources, a 5s window is ~1,700 events per source against a
`max-queue-size` of 1,000, so the trim dropped events that were not late at all.
The cap is now 20,000 — a bound on memory under a runaway producer, not the
working size. This is the change that turned the 1,000/s step from 22,979 drops
into zero.

Two earlier fixes are still in the tree and still matter: `OutOfOrderBuffer.flush()`
collects due events under the per-source lock but runs the sink outside it (offer
mean 0.45ms → 0.002ms), and `spring.task.scheduling.pool.size` is 4 so the flush
never queues behind the Kafka lag sampler.

## Where the ceiling is now

**The pipeline holds up to about 7,000 events/s and starts falling behind at
10,000.** Sustained, on this machine: 7,000/s at 96% kept up with zero loss and
5.5s staleness. At 10,000/s it keeps 74%, drains the remainder at 9,729/s (which
is roughly its own capacity — during the drain the producer is idle), and its
signals are 10.4s stale instead of 5.5s.

Nothing in the service is saturated at that point: ingest-svc uses under one core,
Redis under 2%, and the stage means stay flat (dedup 0.079ms/event, rule
0.006ms/sample). The step is limited by the single-node rig — one broker doing
both the ingest and the fan-out, on the same box as the producer and the consumer.
A real deployment spreads those across brokers and hosts; the honest reading of
this ladder is that ~7,000/s is a floor set by the test rig, and that the drop
counters stay at zero either way.

`event_time -> rule` of 5.5s at every rate up to 7,000/s is a product decision,
not a limit: `ingest.buffer.window` is 5s, so the platform is deliberately blind
for five seconds to buy ordering. If that is too slow for a signal, shorten the
window and accept more out-of-order arrivals.

## Next step if more throughput is needed

Ranked by effect per unit of risk:

1. **Measure on a rig that is not the ceiling.** A broker with its own host (or
   three brokers), and a generator outside the container. Until then, the
   `drain_rate` column is the better capacity figure than the phase numbers.
2. **Reduce the dedup round trip — the only Redis call left on the hot path.**
   Options: raise `max.poll.records` so one pipeline covers more events, or move
   from per-key `SETNX` to a time-bucketed set claimed by one script per batch.
   The first is a config change worth measuring; both trade memory or
   cross-instance guarantees, so neither is free.
3. **Give the flush thread a peer.** It is the only single-threaded stage that
   every ordered event passes through. It is nowhere near saturated today
   (rule mean 0.006ms), but it is the next structural assumption to test rather
   than a measured limit.
4. **Make the ordering window configurable per signal.** 5s is right for
   error-rate confirmation windows of 10s; a latency signal may want 1s. The
   window is already config (`ingest.buffer.window`), but it is global.
