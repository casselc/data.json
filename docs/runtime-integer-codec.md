# Reuse Jolt's integer formatter

The opt-in guarded native writer now uses Jolt's existing decimal formatter for
fixnums instead of Chez's general `number->string`. This avoids general formatter
allocation and its shared formatting state; it does not introduce another
library-owned number encoder.

The capability is optional and checked at initialization, including zero,
negative numbers and the runtime's fixnum boundaries. If unavailable or declined,
the old formatter is retained. Big integers always retain `number->string`.
Live JSONWriter method/root checks still happen before scalar formatting. Default
JSON behavior, portable/JVM/Babashka code, object-key cache bounds and ownership,
escaping and the native-writer ABI are unchanged. Standalone/AOT remains
unqualified. No values or dispatch decisions are cached by this change.

## Tests and causal control

With compiler source `97d93c8f`, Chez 10.4.1:

- `jolt -M:jolt-native-integer-codec-test`: four tests, six assertions, zero
  failures. Checks selected helper use, BigInt decline, missing-capability
  fallback, exact boundaries/large integers and a live Long extension.
- Existing `:jolt-native-test`: 23 tests, 500 assertions, zero failures.
- Existing payload key-cache namespace: nine tests, 98 assertions, zero failures,
  including nested and overlapping independently owned payloads.
- A mutant retaining all new definitions but replacing only the helper call
  with `number->string` fails the selection control (zero helper calls instead
  of three). Text still passes; the test distinguishes actual use from merely
  defining a helper.

Run local Jolt commands through the workspace's pinned Chez 10.4.1 wrapper.
The isolated tests intentionally run in separate fresh processes, preserving the
existing loader-before-extension qualification boundary.

## Measured screens

`evidence/json-fixnum-codec-screen-20261005.{clj,edn}` in the workspace compares
the same 5,120 prebuilt ClickStack log rows, 40,960 rows per sample. Both serial
and four-worker profiles include UTF-8 result materialization and check exact
payload parity. 20,003 integer-format controls include both fixnum boundaries.

Allocation: approximately 481–482 MB to 425–426 MB serial and 484 MB to
428–429 MB with four workers, about 11–12% less. Timing was mixed: serial
571/631 ms versus 575/596 ms; four workers 349/345 ms versus 380/341 ms.
Do not infer a qualified latency win from these component samples.

The first actual five-table collector screen on production `f15cc33` completed at
**18,752 physical rows/s**, **13.332 seconds**, **8.692 GB allocated**. The
preceding same-compiler baseline was 17,326 rows/s, 14.429 seconds and 9.365 GB:
roughly 8.2% higher measured throughput and 7.2% less allocation. A fresh
independent reader confirmed 50,000 rows per table, 250,000 total. This remains
a sequential single-run screen, not causal/repeated p99, S3 or Rust qualification.

Collector sources: exporter `1af91f3`, chDB `7dcaec0`; compiler artifact SHA-256
`1f0c78fc2fbbbb1728c12941fbe0bd18417e72abcfcad09312996001aac64ca6`, native
libchdb 26.7.3 SHA-256
`36ad4e999882821ef13cf2d0f52c93f48e6ee1b4498b35a201c23ce4b8d15bf5`.
The collector uses the existing guarded string encoder, not the experimental
byte sink or map-constructor overlay. Receipts:
`exporter-runtime-integer-codec-20261005.edn` and `.edn.recovery.edn`.

Keep this change as a bounded cumulative candidate. Independent review and
matched core Durable/tail measures remain before product repins or merge.

## Matched core Durable screen

Same compiler/native library, clean chDB `228aeb1` (production `cdf3784`),
100 individually confirmed 5,000-row batches, four encoding workers, two warmups.
Both runs explicitly use `JOLT_GC_TRIP_BYTES=16777216`; this is not a change to
runtime defaults. The existing guarded writer is inherited through the
`:configured` encoder; no payload key cache, map-builder or byte-sink overlay.
Only the codec root changes (`993b906` versus `f15cc33` source).

| Measure | Existing codec | Runtime integer codec |
| --- | ---: | ---: |
| Mean confirmed rows/s | 34,786 | 34,230 |
| 5k-batch p50 | 143.317 ms | 141.672 ms |
| 5k-batch p99 | 179.419 ms | 229.323 ms |
| Maximum batch | 180.175 ms | 250.652 ms |
| Allocated | 8.914 GB | 8.232 GB |
| GC count / wall time | 477 / 2.184 s | 401 / 2.286 s |
| Fresh recovery | 22.962 s | 24.364 s |

Both writer/reader pairs terminated successfully, all 100 confirmations were
committed, no pending statements/bytes remained, and independent readback
matched all 510,000 rows and the full aggregate. Parent, writer and reader
resource-source identities matched within each trial.

The observed p50/p99 inverse rates exceed 25k/20k for both trials, but the
selector explicitly does not qualify tail throughput. These are sequential
single local screens, not the five-trial/512-row qualification, adaptive-default
behavior, S3 or matched Rust evidence. Fewer allocations/collections did not
improve overall mean or GC wall time here. In particular, the candidate has a
worse observed tail/recovery than this baseline; do not promote it as a core
latency improvement from these results.

Driver: `chdb-integer-codec-confirmed-driver-20261005.clj`, reusing the existing
owned child trial with the explicit codec root propagated to both children.
Receipts under workspace evidence:
`chdb-integer-codec-{baseline,candidate}-confirmed-20261005.edn`.
An initial invocation rejected a missing owned receipt-root environment variable
before child launch; the subsequent runs supplied explicit persistent roots.
