# Rocky Linux CI flake：VMConfig 读写不在同一份状态上

本文只记录原因，不含 HTTP / #6523 改动。修复在 `fix/rocky_ci_flakes`（fork PR #16）。

## 现象

- 仓库：`vividctrlalt/java-tron` PR #15，commit `18992c0`，与当时官方 `develop` `4a21592` 一致。
- GitHub Actions run `32169085226`：Rocky Linux JDK 8 的 `:framework:test` 失败（3197 测试，10 失败）。
- 同一 commit 上 Debian 11 JDK 8、Ubuntu 24、macOS、smoke、coverage 都绿。
- 失败的不是 HTTP servlet 测试，重跑 Rocky job 后过了。

独特失败：

1. `AllowTvmLondonTest.testBaseFee` / `testStartWithEF`
2. `ValidateMultiSignContractTest.testTip854RejectsMalformedCalldata`
3. `BackupServerTest.test`（tearDown 60s 超时，重试 6 次）
4. `TransactionsMsgHandlerTest.testInvalidSigLength`（Mockito 竞态）

## 代码根因

`current()` 有 ThreadLocal 就读本地，`initAllowTvm*()` 只改 `globalSnapshot`。本地还在时，写 global 等于没写。

这一套双写路径是 [tronprotocol/java-tron#6857](https://github.com/tronprotocol/java-tron/pull/6857) （2026-06-26，`58f6e64`，作者 yanghang8612）带来的。

#6857 要修的是线上共识：constant call 跑在落后的 solidity/PBFT 快照上，以前会把过期 proposal 写进进程全局 `VMConfig`，跟出块线程抢。做法是：

- 旗标收成 `VMConfig.Snapshot`
- 出块走 `globalSnapshot`
- constant call 走 `ThreadLocal localSnapshot`
- getter 统一 `current()`：有本地用本地，没有才用 global

`initAllowTvm*()` 故意只改 global，注释写明留给测试和老调用。生产加载走 `setGlobalSnapshot`（会 `localSnapshot.remove()`）。老测试仍用 `init*` + `ConfigLoader.disable = true`。`disable` 为 true 时 `load()` 是空操作，清不掉残留的本地快照。

#6857 自己的 `VMConfigIsolationTest` 只测了「本地不污染别的线程」，没测「本线程还有 local 时 init 无效」。

所以：新生产代码加了第二份配置，测试仍走旧写路径。一个进程里两套真相，读新写旧。

## 为什么只有 Rocky 红

洞在所有平台都在。`framework/build.gradle`：`maxParallelForks = min(4, ncpu)`，`forkEvery = 100`，大约 100 个方法共用一个 JVM、同一条测试线程。

Rocky（rockylinux:8 OpenJDK 8）更慢：前一个测试留下的 ThreadLocal、BackupServer 等 1 秒再关、Mockito 和线程池抢同一个 mock，更容易撞上。Debian/Temurin 通常赶在窗口里跑完。重跑后绿，也是竞态，不是 Rocky 上有另一套业务逻辑。

## 与 #6857 新测试的关系

不是 #6857 新写的测试写坏了。`VMConfigIsolationTest` 会清 ThreadLocal。挂的是更早的测试（`AllowTvmLondonTest` 从 2021 年就在）仍用 `init*`。

## 另外两条（不是同一个根）

- **BackupServer**：`channel` 不是 volatile；`close()` 先 `stop()` 再关 UDP；Rocky 上 1 秒 sleep 不够则 channel 仍是 null；`chooseRandomPort()` 只探 TCP。
- **TransactionsMsgHandlerTest**：`when(mock.foo())` 和线程池里的 `isBadPeer()` 打在同一个 mock 上，Mockito 不线程安全。

## Review

#6857 指定 reviewer（排除作者后）只有 CodeNinjaEvan。三个 Approve 没有文字，从第一个 Approve 到 merge 约两分钟。aiden3885、SecretCipher7 不是 `vm` scope 的指定人。
