package ohd.core

import ohd.platform.Env
import ohd.platform.isProcessAlive
import ohd.platform.killProcess
import ohd.platform.listDescendantPids

/**
 * 回收一个进程及其子孙。
 *
 * 只 destroy 根进程时，shell 拉起的后台子进程会被 init 收养继续跑——
 * 变成孤儿进程后仍占着设备上的采集器，下次 `ohd` 起来会发现「端口被占」却查不到谁占。
 * 所以必须**先收子孙再收根**。
 */
object ProcessTrees {

    fun alive(pid: Long): Boolean = isProcessAlive(pid)

    fun kill(pid: Long, graceMs: Long = 800) {
        if (!isProcessAlive(pid)) return
        // 先取树（此时根还活着，ps 才能看到它的子进程），再一起收
        val descendants = listDescendantPids(pid)
        val all = descendants + pid

        all.forEach { runCatching { killProcess(it) } }
        waitUntil(graceMs) { all.none { isProcessAlive(it) } }
        // 还活着的（TERM 被忽略的）补一次强制 kill
        all.filter { isProcessAlive(it) }.forEach { runCatching { killProcess(it) } }
        waitUntil(graceMs) { all.none { isProcessAlive(it) } }
    }

    private fun waitUntil(timeoutMs: Long, done: () -> Boolean) {
        val deadline = Env.nowMillis() + timeoutMs
        while (Env.nowMillis() < deadline && !done()) {
            Env.sleepMillis(50)
        }
    }
}
