# VMConfig isolation and intermittent Rocky Linux test failures

## Summary

Intermittent `:framework:test` failures on Rocky Linux JDK 8 are caused by a split read/write path in `VMConfig` introduced by #6857. The same commit is green on Debian 11 JDK 8, Ubuntu, and macOS. The failing tests are existing VM / net / backup cases, not a product regression in the HTTP API.

## Symptom

On Rocky Linux JDK 8 (`rockylinux:8`, OpenJDK 8), `:framework:test` can fail with a small set of errors that disappear on rerun:

- `AllowTvmLondonTest.testBaseFee` / `testStartWithEF`: London appears off (`hReturn` empty instead of the energy fee; `0xEF` deploy not rejected).
- `ValidateMultiSignContractTest.testTip854RejectsMalformedCalldata`: Osaka appears off (321-byte calldata is not rejected; assertion `non-32-aligned len=321`).
- `BackupServerTest.test`: 60s timeout in `tearDown` → `BackupServer.close` → `shutdownAndAwaitTermination` (often retried).
- `TransactionsMsgHandlerTest.testInvalidSigLength`: Mockito `WrongTypeOfReturnValue` (`ConcurrentHashMap` from `isBadPeer()`).

`framework/build.gradle` runs tests with `maxParallelForks = min(4, ncpu)` and `forkEvery = 100`, so about 100 methods share one JVM and one test thread.

## Root cause

#6857 (`58f6e64`, 2026-06-26) isolates constant-call config from the process-global `VMConfig` so a `triggerConstantContract` / `estimateEnergy` execution against a lagging solidity/PBFT snapshot cannot publish stale proposal flags into the block-processing path.

The implementation is:

- Flags live in `VMConfig.Snapshot`.
- Block processing installs `globalSnapshot` (volatile wholesale replace).
- A constant call installs a `ThreadLocal` `localSnapshot`.
- Getters use `current()`: return the thread-local snapshot when present, otherwise the global.
- Production loading goes through `ConfigLoader.load(store, isolate)` → `setGlobalSnapshot` / `setLocalSnapshot`. `setGlobalSnapshot` also `remove()`s the thread-local view. `Wallet.callConstantContract` clears the local view in `finally`.

`initAllowTvm*()` was kept for tests and legacy callers. Those setters mutate **only** `globalSnapshot`. They do not clear or update `localSnapshot`.

Therefore:

1. If a prior test on the same thread left a local snapshot (constant-call `load(..., isolate=true)`, or any path that called `setLocalSnapshot`), `current()` keeps reading that local view.
2. A later test calls `initAllowTvmLondon(1)` / `initAllowTvmOsaka(1)` and believes the flag is on.
3. `allowTvmLondon()` / `allowTvmOsaka()` still return the leftover local value.
4. `ConfigLoader.disable = true` (test-only) makes `load()` a no-op, so the leftover is never replaced.

#6857's `VMConfigIsolationTest` covers "a local view must not leak to another thread" and "`setGlobalSnapshot` drops the local view". It does not cover "`init*()` is a no-op for `current()` while a local snapshot remains on this thread".

`AllowTvmLondonTest` (present since 2021) and similar suites still use the pre-#6857 `init*` + `ConfigLoader.disable` pattern. They were not updated when the getter path changed.

This is a dual source of truth: production writes snapshots; tests write global in place; reads always prefer thread-local.

## Why Rocky Linux reproduces it more often

The defect is platform-independent. Rocky Linux is slower (OpenJDK 8, tighter heap, slower scheduling), so leftover thread-local state, a 1s `sleep` before `BackupServer.close()`, and a Mockito stub racing `handleTransaction` on the same mock are more likely to hit. Debian/Temurin usually finishes inside the same window. A failed-job rerun going green is consistent with a race, not with a Rocky-specific functional bug.

`BackupServerTest` and `TransactionsMsgHandlerTest` are separate isolation issues (non-volatile channel / close-before-bind / TCP-only port probe; `when(mock.foo())` vs concurrent `isBadPeer()`). They are not the `VMConfig` dual-write bug, but they surface on the same slow host.

## Related

- https://github.com/tronprotocol/java-tron/pull/6857
- https://github.com/tronprotocol/java-tron/commit/58f6e64bd8b7b3fd98af874c400032e6ccc83892
