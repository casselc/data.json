# Byte-buffer writer integration spike

Not enabled by default, not ready for adoption as a performance improvement.
This extends the earlier closed-shape byte-buffer experiment to the real
StringWriter/JSONWriter contract. Custom writers, live receiver predicates,
partial output on failure, nested writes, and nondefault options are retained.

The core method-resolution site needs an optional callback boundary so the
buffer can publish the current prefix before potentially observable receiver
classification. The adapter uses this hook; it never relies on a separate,
racy library-side check that classification will remain callback-free.
Unchanged two-argument sites retain their existing behavior.

One writer belongs to one serial payload. It loans bounded 64KiB byte scratch
and 21-byte decimal scratch per operation; nested writes get separate loans.
It retains a bounded stock escaped-string cache (128 entries, input character
upper-bound plus encoded bytes <=65536), not arbitrary callback results.
Discard the writer after the payload; sharing it across workers is unsupported.

`write-terminated-row!` is an explicit adapter: its outer call places a newline
inside the final materialization, while nested JSON calls remain unterminated.
Errors do not append a success newline. It requires the bound byte writer;
it is not a new ordinary JSON option or a durable operation.

The byte-batch collector's demand loop now calls the runtime's existing
`jolt-seq`, `jolt-first`, and `jolt-next` directly in Chez. It still checks the
budget after a completed row and before demanding the next row. Every row gets
a fresh real StringWriter, including rows with custom writers that retain it.
Default options are read live for each row; nested calls and failures retain
the existing writer context cleanup. This change neither buffers all lazy rows
nor changes the persistence boundary. The `:jolt-native-batch-loop-test` alias
checks the actual compiled runtime without injecting runtime source.

## What was measured

Runtime bcb376a0, Chez10.4.1, source-loaded edited method site; no new compiled
runtime artifact. Existing exporter5d273302/chDBca7/data.json3adc8c5 stack is
preserved. Prototype driver selects this writer through existing native encoder
factory and explicitly substitutes the row terminator adapter; other exporter
row construction, byte limits and context lifecycle are delegated unchanged.

Five5000-row signal shapes, each2samples x3encodings. Prepared DTO generation
is outside the section, but real exporter row construction and bounded encoding
are inside. All five payload SHA-256 values match the reference. No insertion,
WAL, receiver ingestion, S3 or recovery qualification is claimed.

The first adapter regressed badly: constructing a method site for every row
added both time and allocation. Hoisting its cache to the payload reduced that
cost. Including the newline before final materialization removed a redundant
row-string copy. A bounded escaped-byte cache retained the existing stack's
repeated-string optimization, rather than losing it during the new baseline.

Even with those corrections, this adapter is NOT a clear throughput win:

| Signal | Reference ms | Prototype ms | Reference allocation MB | Prototype MB |
| --- | --- | --- | --- | --- |
| spans | 175.9–205.6 | 183.7–200.3 | 125.83 | 124.93 |
| logs | 122.7–124.2 | 129.2–130.3 | 100.42 | 94.03 |
| gauge | 114.5–118.5 | 121.4–125.9 | 101.96 | 95.14 |
| sum | 119.1–119.9 | 128.4–129.7 | 105.63 | 97.82 |
| histogram | 136.6–154.9 | 143.3–148.5 | 119.26 | 106.92 |

Allocation is estimated cumulative Scheme allocation, not live heap. Timing
samples are too few for meaningful percentiles. Final decimal zero checks also
accept a still-wide first quotient (uint64 maximum); the table's fixture values
do not reach that case, which is separately covered by focused tests.

The earlier single-buffer, whole-prepared-batch kernel had much larger gains.
This adapter still creates a writer operation and row string for every row,
and then assembles the batch. Moving byte encoding inside each row cannot be
advertised as reproducing the whole-batch result. Further work must eliminate
that staging or hoist more row-invariant setup, not just adopt this slower path.

Receipts live under /home/chuck/ai-src/evidence/data-json-byte-writer-*20261007.*.
The component driver records source-loaded runtime mode and verifies candidate
factory interception (90 calls in the measured run). Native boundary tests
cover a deliberately omitted-hook red control. Focused writer tests and the
existing scalar-indexed Jolt compatibility runner pass. The generic JVM test
namespace cannot be parsed by Jolt because it contains literal lone surrogates;
that failure is not promoted to a candidate test pass.

## Hoisted-context continuation

The next experimental branch moves emitter helper closures to the payload
factory, clears row-owned references after use, and snapshots/restores the
active context for nested writes (including caught nested failures). It restores
the earlier guarded empty-vector shortcut before allocating row context. A
probe verifies that shortcut is actually reached, not merely wire-equivalent.
Default-flag qualification is cached only by identity of the immutable captured
options map; live protocol and method-root checks are not cached away.

Against the reference above, the measured continuation allocated 104.30 MB for
three encodes of 5,000 spans instead of 125.83 MB (about 17% less). Span samples
were 170.4–183.7 ms; other signals' CPU results were mixed. This remains a
component result, not a demonstrated full-pipeline speedup. A further clean
ASCII scan/UTF-8 conversion allocated extra temporary byte arrays and did not
show a clear speedup, so it was removed. Escaping and 64KiB buffer-boundary
tests remain. The retained continuation passes nine tests / 76 assertions.

Read-only review found no implementation blocker but identified a weak cache
test: its changed options and captured stock defaults were equal, not identical.
The test now asserts identity before each call. An in-process accept-all-flags
mutant is rejected with exactly one output mismatch and no errors; the corrected
candidate passes nine tests / 79 assertions. This distinguishes rejection by
the escape-flag guard from the earlier identity decline.

The Go/Rust collector source audit and exact measurements are recorded in
`/home/chuck/ai-src/evidence/observability-allocation-transfer-20261007.md`.
The next larger opportunity is eliminating per-row materialized strings with
an explicitly owned batch sink; no default writer or persistence policy has
changed here.

## Batch-owned sink work in progress

An isolated continuation adds internal Chez collector storage in
`jolt_byte_batch.ss`: append owned bytes into one growable buffer, expose its
UTF-8 byte count, materialize immutable text at completion, and replace only
the current row after an observable writer mutation. Seven focused storage
checks cover row slices, non-ASCII byte counts, replacement and capacity growth.
The collector is now wired into the source-only JSON emitter through
`write-batch-text!`. Sixteen focused tests / 101 assertions pass, including
real row-local prefixes, arbitrary writer mutation, retained completed writers,
original error identity, nested writes, Unicode, growth and incremental budgets.
It has no measured performance result or default backend selection yet.

The intended emitter must preserve a separate real StringWriter view for each
row. It should publish that row's prefix before observable classification or
custom JSONWriter code, then reconcile any callback mutation back into the
collector. Warm callback-free stock rows avoid intermediate row strings. Cold
protocol classification still publishes a row-local view; the runtime hook is
not skipped based on a racy library-side purity guess. A causal test distinguishes
cold publications and final batch materialization from one string per row.
Nested writes still need independent scratch and context restoration. Keep
row-completion byte-limit checks and lazy input realization order unchanged;
The collector has no cross-call pool or retained payload. Its allocation limit
remains a completed-row check, not a promise that trusted custom writers cannot
allocate a large row. A separate chDB integration retains existing admission and
close/release semantics; persistence is unchanged and is not qualified by these
focused JSON tests.

The internal collector invocation requires a fresh, empty StringWriter for each
row, including fallback and empty-vector branches. `write-batch-text!` owns and
enforces this construction; do not reuse a previous row's writer to save an
allocation. The caller cannot access its opaque batch storage through this API.
