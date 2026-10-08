# Private JSON emitter access bounds

These bounded integer models accompany the private scratch-buffer store and
the two immutable-string reads in `jolt_byte_writer.ss`. They are not a JSON,
compiler, callback, concurrency, or Durable protocol proof.

The source keeps a 65,536-byte scratch buffer and a cursor in `[0, 65536]`.
Before a byte store, a full buffer is flushed and the cursor becomes zero.
Both string loops start at zero, stop at the string length, and advance by one.
The models cover the resulting indices, byte values, and cursor transitions.

Run `bash scripts/check-byte-emitter-bounds.sh` with Z3 installed. The script
rejects a changed emitter source until the source mapping has been reviewed.

| Model | Expected | Scope |
| --- | --- | --- |
| `access` | UNSAT | Out-of-range byte/string access or emitted byte |
| `induction` | UNSAT | Byte, checked bulk-copy, reset, restore, and string-step cursor preservation |
| `no-flush-mutant` | SAT | Removing the full-buffer flush admits index 65536 |
| `small-control` | UNSAT | Ordinary first-byte control |
| `last-byte-boundary` | SAT | Valid final index 65535, violation false |
| `full-buffer-boundary` | SAT | Full-buffer flush produces index zero, violation false |

Trusted premises include the runtime representation of strings and bytes,
immutable string lengths, ASCII output of numeric/literal formatters, correct
saved-frame and backing-buffer restoration, and the checked bulk-copy contract.
Arithmetic checks do not establish those runtime contracts. Supported callback
and reentry behavior remains covered by executable tests; arbitrary unsafe FFI
or Scheme mutation is outside the contract. Global compiler optimization mode
and public byte-array access are unchanged.

The runtime boundary regression compares portable JSON with the candidate for
ASCII, escapes, Unicode, and lengths around one and two scratch-buffer fills.
The no-flush mutant is executed only after replacing every specialized access
with checked access, so its expected failure cannot corrupt memory.

The focused selected-runtime gate passed 21 tests / 253 assertions. Encoder-only
ABBA measurements retain exact byte parity, raw samples, and p50/p90/p95/p99 in
workspace evidence. Neither result establishes end-to-end throughput or tail
qualification; the connected Durable comparison is a separate gate.
