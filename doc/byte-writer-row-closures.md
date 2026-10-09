# Byte-writer row closure reuse

The experimental serial byte writer now creates its execution, flush, restore
and finish functions once per factory instead of creating cleanup closures for
every row. This is allocation work, not a changed JSON or Durable contract.

Each active invocation owns a row value, ending, prior context and scratch loan.
A nested call saves those four fields with the existing eleven writer fields.
Cleanup restores the previous invocation, or clears all invocation fields when
the factory becomes idle. Scratch ownership and the nested dynamic-wind cleanup
boundaries remain the same. This factory is still serial: different concurrent
payloads must obtain separate factories.

Live dispatch and callback-domain guards remain untouched. Custom writers still
receive their real row-local StringWriter; retained writers see the completed
row. Nested failures restore the outer row and original exception identity.
No default encoder, native admission, WAL, lease, confirmation or AOT change.

## Local evidence, 2026-10-09

Parent: `805bb9a26bba73cbb9f13415a2636755ca4aaa69`.
Compiler: `20f25cf4cbef710ab00cd7ea7b903233477557e0`, mandatory Chez 10.4.1.
Exporter fixture: `0d1e41ed2ef3288a250b9533dd55b491ab210d0a`.

Prepared 10,000 wide typed rows produced exactly the same 6,068,398 bytes.
ABBA, 20 samples/arm and three warmups: parent allocated 23.157/23.159 MB
per batch, candidate 18.359/18.359 MB, about 4.8 MB (21%) less. Parent p50
80.414/79.610 ms, candidate 79.456/80.015 ms. No meaningful timing win or
tail claim. This is encoding-only, not confirmed Durable or S3 throughput.

Actual candidate source passed 49 tests and 954 assertions across the native,
segmented, payload-cache and integer-codec namespaces. A new regression checks
the same factory through nested failure, subsequent nested success, outer
newline/retained-writer publication, and reuse after an outer failure.

The allocation diagnostic used a smaller 1,000-row fixture and asserted no GC
during sampling, then restored the original collection trip setting. Empty
sampling allocated zero bytes. Row context allocated 1,454,880 inclusive bytes;
the outer vector traversal included 285,360 bytes and batch appends accounted
for 590,112 bytes. Categories overlap and must not be summed. These findings
led to closure reuse, not a change to scalar or equality semantics. Earlier
fine-grained statistics/live-heap measurements crossing GC produced impossible
negative deltas and were rejected.

Remaining gates: independent Claude review, full real Durable window and fresh
unchanged-reader recovery, then ecosystem pin/hosted-S3 qualification. No
Lemonade server was accessed.
