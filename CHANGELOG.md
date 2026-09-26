# Fork changelog

## Unreleased — experimental, fork-only

- The opt-in guarded Jolt JSON writer now skips repeated option-map validation
  when given its captured default options by identity, while checking the actual
  three escaping flags. Each call still receives fresh lazy scratch state;
  other inputs retain the existing validation and public fallback behavior.
  This is an experimental performance checkpoint, not a measured throughput
  improvement or promotion of the guarded writer to the default backend.
