# Fork changelog

## Unreleased — experimental, fork-only

- Add a separate source-only payload string-fragment cache factory. Preserve
  live method/root checks and option-qualified output; cap retention to 128
  stock-string entries/65,536 input-plus-output characters per serial payload.
  Existing key-only/default factories remain unchanged; discard after use.

- Render signed/unsigned 64-bit wide integers as two fixnum decimal chunks in
  the opt-in guarded writer. Reuse the qualified runtime codec; unavailable
  capability and arbitrary larger integers retain number->string. Preserve
  bytes, live JSONWriter dispatch, scalar ownership and default/portable routes.
  Scalar allocation improves; repeated Durable throughput remains unqualified.

- Add an opt-in source-only whole-String reader candidate. Native traversal
  builds Jolt maps/vectors; stock scalar conversions retain number classes.
  Key/value callbacks, incomplete/deep inputs and Reader input keep the portable
  route; extra-data callbacks retain their original unread suffix. No global
  installation, default change, AOT or throughput qualification is implied.

- Reuse the qualified runtime-owned fixnum decimal codec in the opt-in guarded
  Jolt writer, avoiding the general formatter for ordinary integers. Big integers
  and runtimes without the helper retain the previous formatter. Live JSONWriter
  dispatch, value text, default encoding, and JVM/Babashka paths are unchanged.

- Expose an experimental source-only `load-payload-writer!` that returns an
  independently owned serial key-cache writer. The ordinary loader and default
  writer are unchanged; concurrent callers must obtain separate closures.

- Qualify the source-only payload-key-cache spike for nested writes, recovery
  after a callback failure, and overlapping independently owned payloads.
  Sharing one cache concurrently, standalone/AOT, and supported loader
  integration remain unqualified; the default encoder is unchanged.

- Size escaped tokens before allocating their result in the opt-in native
  String reader. Remove growing-port buffer/extraction copies while retaining
  exact values, cursor positions, Unicode handling and malformed-input fallback.
  Results remain independently owned; default/JVM parsing is unchanged.

- Decode valid Unicode escapes and surrogate pairs in the opt-in Jolt reader.
  One escaped Unicode value no longer forces an entire large token back to the
  portable decoder. Malformed/noncanonical escapes and EOF keep original errors
  and cursor positions; default and JVM behavior remain unchanged.

- Add an explicitly bound, source-only Jolt String token reader. Native scalar
  escapes avoid per-escape interop; malformed escapes and EOF retain the
  established reader and cursor/error behavior. Default and Reader-backed
  input are unchanged; standalone/AOT is not qualified.

- Honor the explicitly bound experimental writer in public `write`, as in
  `write-str`. Its existing sink/options/extension guards remain responsible
  for portable fallback; unbound calls and the default backend are unchanged.

- Keep String-backed JSON quoted-string scanning linear for long runs of
  escaped newlines or backslashes. Reuse the next quote position until an
  escape consumes it; values, reader positions and existing errors are unchanged.

- Move the guarded writer's unchanged Jolt-only tests to `src/test/jolt`, with
  an explicit `:jolt-native-test` alias, so ordinary JVM Maven test discovery
  does not load Jolt namespaces. The portable/JVM suites remain separate;
  this does not add or qualify hosted guarded-native CI.
- The opt-in guarded Jolt JSON writer now skips repeated option-map validation
  when given its captured default options by identity, while checking the actual
  three escaping flags. Each call still receives fresh lazy scratch state;
  other inputs retain the existing validation and public fallback behavior.
  This is an experimental performance checkpoint, not a measured throughput
  improvement or promotion of the guarded writer to the default backend.
