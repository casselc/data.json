# Fork changelog

## Unreleased — experimental, fork-only

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
