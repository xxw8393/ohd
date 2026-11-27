package ohd.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.stat

/** macOS：字段名是 `st_mtimespec`。 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun posixStatMtimeSec(st: stat): Long = st.st_mtimespec.tv_sec
