package ohd.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.stat

/** Linux：字段名是 `st_mtim`（POSIX 2008 起的标准名）。 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun posixStatMtimeSec(st: stat): Long = st.st_mtim.tv_sec
