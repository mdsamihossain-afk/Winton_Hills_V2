# Phase-2 TODOs and tracked technical debt

This file collects currently open TODOs related to the Phase-2 native/JNI/Oboe hardening effort. It is a small, human-maintainable subset of inline TODO comments found in source so reviewers and release managers can triage work.

## High-priority TODOs

- `engine/src/main/java/com/winton/validationshell/engine/Engine.kt`
  - keep callback path allocation-free and free of logging
  - restart streams safely on route/Bluetooth changes outside callback
  - add a guarded native DSP insertion point without breaking Kotlin path

- `engine/src/main/cpp/CMakeLists.txt`
  - keep native stream migration guarded and opt-in
  - enforce no-allocation hot-path rules when native callback is active

## Medium-priority TODOs (third-party/external)

- `engine/src/main/cpp/external/*` (Eigen/other libs) contain TODOs. Evaluate whether upstream patches are required or local wrappers suffice.

## Recommended next steps

1. Create GitHub issues for each high-priority item with owner and target milestone.
2. Add CI checks that detect per-frame allocations in the native callback (sanity smoke test).
3. Add a short integration test that simulates route changes and verifies safe restart debounce behavior.

## Notes
Keep this file in sync with inline TODO comments. When a TODO is resolved, update this file and close the corresponding issue.

