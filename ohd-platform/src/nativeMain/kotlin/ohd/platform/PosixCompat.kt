package ohd.platform

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * 取 `stat` 结构里的 mtime 秒数。
 *
 * **macOS 与 Linux 的字段名不同**：macOS 是 `st_mtimespec`，Linux 是 `st_mtim`。
 * 这是 POSIX 标准化之前的遗留差异，cinterop 无法自动抹平，只能按平台分开实现。
 */
@OptIn(ExperimentalForeignApi::class)
internal expect fun posixStatMtimeSec(st: platform.posix.stat): Long
