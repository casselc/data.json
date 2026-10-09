# Reusing the experimental map visitor

The opt-in serial byte writer previously created a visitor closure for every
nested map. The visitor reads only its arguments and the writer factory's
restorable context. It can therefore be created once per factory. Each map
walk still has its own comma/omission accumulator; each nested write still
saves and restores the surrounding writer, scratch and option context.

No input data or converter result is cached. Live protocol dispatch, key/value
option functions, observable flush boundaries, custom writer mutation, error
cleanup and one-pass row/budget checks are unchanged. Serial factories must
not be shared between concurrent workers. Separate factories own separate
visitors. Public/default/AOT selection and Durable behavior are unchanged.

An exact-wire ABBA screen on compiler20f25cf4 compared this source with parent
c2cf28a: 10k prepared wide16 typed rows, 6,068,398 UTF-8 bytes, 20 measured
encodings per arm. Allocation fell from 18.358 MB to 15.799 MB per batch, about
2.56 MB or 14%. P50 was approximately 79–80 ms in both arms. This is an encoding
allocation result, not a confirmed Durable throughput or tail improvement.

Focused source tests cover reuse within one factory and separation between
factories, nested maps, nested failed writes, outer comma/prefix state and
later reuse. The unchanged parent fails only the three expected visitor-count
assertions while preserving the three wire-value assertions. Existing tests
retain custom writer, receiver predicate, options, budget and scratch checks.

Hosted S3 and consumer throughput require separate qualification. No credentials
or persisted telemetry are needed for this source/allocation test. Keep bounded
scalar receipts, not retained fixture heaps or generated object stores.
