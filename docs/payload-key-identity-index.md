# Payload key identity index: qualification candidate

The source-only native payload writer already caches escaped object keys by
string content. This candidate checks an identity index first for immutable key
objects that the cache already retains. Repeated schema literals therefore do
not need another content hash. Equal but newly allocated strings still use the
content lookup and never create additional alias entries.

Both indices reference the same representative keys and encoded entries. Escape
flag changes update both. The existing 128 logical key / 8,192 retained character
bounds remain; the additional table has at most 128 references to those same
keys. The conservative existing accounting on flag replacement is unchanged.
Values, omission decisions, protocol methods and callbacks are never cached.
Every payload/worker owns an independent writer; the default loader and portable
writer remain unchanged. Shared concurrent use, standalone/AOT and automatic
enablement remain outside qualification.

## Evidence, 2026-10-05

Candidate source: `94fe1cd`, parent `993b906`. Jolt `2223c24a`, Chez 10.4.1,
AOT disabled. The controlled component screen swaps only the actual old/new
key helpers; both arms use identical new factories, contexts and prebuilt metric
rows. Each arm encodes 12,288 rows. Exact payload bytes match before timing.

| Helper | Two arm times | Scheme allocation |
| --- | ---: | ---: |
| Content-key lookup | 391.90 / 319.31 ms | ~202.13 MB |
| Identity index first | 314.08 / 282.31 ms | ~202.17 MB |

The ~16% difference in average component time is promising but does not yet
prove a collector gain. Actual local POSIX Durable five-table collector, 10 x
5,000 items, unchanged per-physical confirmation, exporter `1af91f3`, chDB
`7dcaec0`, OTel `19fc49d`, libchdb 26.7.3:

- Previous same-shape source baseline: 17,676.32 rows/s, 9,365,567,952 B.
- Candidate: 17,723.78 rows/s, 9,365,587,408 B.

Writer and fresh-process snapshot reader both exited zero; the independent
reader confirmed 50,000 service rows in each of five tables. This is count-only
recovery evidence, not complete value equivalence.

These sequential pipeline results are essentially flat, not an application
speedup claim. Keep prior natural native encoder/interop gains intact. Do not
repin Oscope or claim throughput/tail targets solely from the component result.

Focused cache suite: 10 tests / 871 assertions, including 256 distinct equal
key aliases with alternating escape flags, both index bounds, nested writes,
failure/reuse, live protocol/Var replacement and overlapping independent
payloads. Combined native/cache suite passed 33 tests / 1,371 assertions.
Independent review remains a gate; Claude authentication still reports logged
out, and no review, product PR or merge is claimed.

Workspace receipts/drivers:
`evidence/json-key-identity-screen-20261005.{clj,edn}` and
`evidence/exporter-json-key-identity-durable-20261005.edn` with its independent
count-reader `.recovery.edn` companion. A separate diagnostic bypassing protocol
resolution (`json-protocol-cost-screen-20261005`) suggests ~18% potential in that
component, but is explicitly unsafe for production: live extensions cannot be
ignored to obtain it.
