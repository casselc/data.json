# Immutable option slots and integer scratch copies

This source-only experimental byte writer already checks live JSONWriter
dispatch for each value. This slice does not remove that check or change
default writer selection. It removes repeated lookups of two functions in
the admitted row's immutable options map and block-copies decimal integer
scratch ranges into the private 64KiB emission buffer.

The option slots hold function values, including Var objects where provided;
they do not cache Var roots or protocol decisions. Nested calls restore the
outer slots and idle factories clear them. Integer copying retains source and
destination bounds checks, including emission-buffer crossings and signed /
unsigned 64-bit boundaries. Output arrays remain independently owned.

## Checks and measured limits

On the pinned Jolt source stack `2fc2dfa` / Chez 10.4.1:

- Byte-writer and segmented-batch suites: 31 tests, 461 assertions, no failures
  or errors. Tests include nested changed defaults and 80 integer-boundary
  stock-wire comparisons.
- Causal control: a 32-entry string-key map performs two option lookups with
  this implementation versus 32 on parent `a486d52`. Whole-wire comparison is
  also required; a lookup counter alone would not prove compatibility.
- A prepared 10k-row JSON screen compared exact 6,068,398-byte payloads in
  baseline/candidate/candidate/baseline order. Baseline p50 was 79.479/80.236
  ms; candidate 78.896/78.154 ms. Allocation remained about 23.158 MB per
  batch. This is a modest component gain, not a stable tail improvement.
- A cumulative real exporter gate with the separately tested small-output
  declared-attribute collector `9d54565` used normal confirmed Durable calls
  and a fresh unchanged stock reader. All 230,000 physical rows, typed
  Boolean/Int64 values/status, timestamps and duplicate counts matched.
  Its 20-sample screen measured 28.0k confirmed rows/s with p50/p90/p95/p99
  355.387/415.644/418.830/426.536 ms and ~88.5 MB allocated per 10k-row batch.
  This short combined-stack result does not isolate this JSON change or prove
  the sustained 20k inverse-p99 target, S3 behavior or Rust parity.

An ASCII string-to-UTF8 bulk-copy experiment was rejected: it added roughly
0.8 MB per prepared batch and extra GC for little timing benefit. It is not
part of this change. Other cumulative byte/string/runtime optimizations remain
in the parent stack; this is not a replacement encoder or compatibility bypass.
