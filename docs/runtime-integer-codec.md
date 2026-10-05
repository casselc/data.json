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
