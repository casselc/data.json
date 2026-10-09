# Releasing completed lazy rows

The experimental byte collector binds a native writer while consuming rows.
Its binding closure previously captured the original row sequence throughout
the batch. When that sequence was lazy, the head could keep already encoded
rows reachable while subsequent rows were generated.

The collector now owns a one-time source cell. Inside the same writer binding,
it takes the source, clears the cell, and initializes its consuming loop. It
does not keep the original head in the binding closure. No per-row cell write
or cache is added. Lazy production stays inside the native writer binding:
moving the first `seq` outside it would change callback context and is not an
equivalent optimization.

Input validation, source/row order, one-pass effects, newline-inclusive budget
checks, custom writer visibility, nested writes and immutable output are
unchanged. Each invocation owns its source cell. Prefixes and persistence
contracts are unchanged; default/portable encoders are not selected differently.

On the qualified Jolt source runtime, a weak-reference probe tracks the first
of 512 generated rows while later rows are produced. The parent retains it
at rows128/256/384; the candidate releases it at all three observations. A
consume-only control releases it; an explicitly held eager-vector control
retains it. Both encoder arms emit23,332 bytes and visit512 rows.

The source regression test also verifies exact complete wire text and native
writer identity during those producer observations, with a positive strong
reachability control. Parent fails only the three release assertions while
five other assertions pass. Actual candidate byte/native suites pass54tests
922assertions. These are bounded reachability/correctness results, not proof
that every possible row or callback reference becomes collectible, nor a
throughput, GC-pause or cross-platform performance guarantee.

Measure the real lazy-production Durable workload separately. Prepared eager
rows intentionally remain retained by their caller and cannot demonstrate
this improvement. Retain scalar receipts and remove successful generated stores
after fresh recovery and input-integrity checks.
