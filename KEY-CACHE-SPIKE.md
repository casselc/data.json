# Payload-local JSON key cache — source-only experiment

This branch starts at the consumer-pinned `56db146`. It does not replace the
default backend, change the public loader, or move any dependency pins.

After `load-writer!`, the experimental Scheme factory
`djn-make-key-cache-writer` returns a fresh four-argument writer closure for
binding to `clojure.data.json/*experimental-native-writer*`. Discard that closure
after one serial payload. The factory is deliberately not a supported Clojure
API yet; concurrent use of a single cache is not qualified.

The cache stores escaped object-key text only, with all three escaping flags.
The same current ABI, option, protocol-Var and live per-value method checks
remain in force. Each map entry still performs key conversion and omission
checks. Values, protocol resolutions, user callbacks and entire rows are never
cached. Keys longer than 128 characters bypass storage; source guards cap each
cache at 128 entries and 8,192 retained key-plus-encoded characters. Changing
flags conservatively consumes additional budget rather than refunding an old
entry. No source telemetry or values are retained globally.

## Initial evidence, 2026-10-05

- Composed Jolt `976dd9d15245b40d447c9ec920e15f119f297c02`, Chez 10.4.1,
  `JOLT_AOT_CACHE=0`.
- New focused namespace: 5 tests, 74 assertions, zero failures/errors.
  Tests cover alternating flags, keyword/string key collisions, saturation,
  long keys, omission, live String writer extension, actual sink prefixes,
  option callbacks, nil-key rejection, dispatch-root changes and one-shot
  failing callbacks. These are serialized fresh-process tests.
- Existing native-writer suite: 23 tests, 500 assertions, zero failures/errors.
- Metric-JSON-only ABBA: 18,432 rows per arm, exact byte parity before timing.
  Uncached arms 377.57/366.40 ms and 263.11/263.13 MB allocation; cached arms
  326.10/328.11 ms and 247.79/247.80 MB. Approximately 12.1% less elapsed time
  and 5.8% less allocation in this component comparison.
- Independent untouched `56db146` source process: four uncached arms measured
  528.21/410.53/372.98/373.88 ms with allocation 262.81–262.83 MB and the same
  checksum. Time varies substantially in that run; no cause was instrumented,
  so do not use its mean or discard its slower arms to inflate a speedup claim.
- Actual local POSIX Durable diagnostic: factory substitution only, real
  encoder limits/FIFO/publication. 20 × 512 items across five physical tables,
  51,200 rows at 8,551.55 rows/s and 2,620,409,648 allocated Scheme bytes.
  Separate fresh reader recovered 10,240 service rows in each table.
  Prior unmodified source screen measured 8,198.47 rows/s and 2,650,720,176
  bytes. This is a sequential screen, not end-to-end causal ABBA or tail proof;
  the approximately 1.1% allocation decrease is small across the full pipeline.
  The target remains unmet. Neither admission nor persistence was weakened.

Local reproducible drivers and bounded receipts are in
`/home/chuck/ai-src/evidence/data-json-payload-key-cache-screen-20261005.{clj,edn}`.
The comparison's uncached arms use this modified source with the cache disabled,
not a separate original-source process; do not present it as end-to-end causal
qualification. No p99, S3, standalone/AOT or concurrent-cache claims.

Additional receipts: `data-json-original-key-baseline-20261005.edn`,
`exporter-durable-key-cache-20261005.edn` and its `.recovery.edn` under the same
local evidence directory. Store:
`/tmp/exporter-durable-key-cache-20261005.x7GX8mAX`.

Before supported integration: qualify original-source/candidate comparisons,
explicit factory ownership and disposal, independent overlapping payloads,
reentrancy, exact typed/native recovery, and review. This is a bounded probe,
not the solution to the whole Durable throughput target.
