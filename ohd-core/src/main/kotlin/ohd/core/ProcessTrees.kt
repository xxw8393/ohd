package ohd.core

/**
 * 回收一个进程及其子孙。只 destroy 根进程时，shell 拉起的后台子进程会被 init 收养继续跑。
 */
object ProcessTrees {
    fun alive(pid: Long): Boolean =
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    fun kill(pid: Long, graceMs: Long = 800) {
        val root = ProcessHandle.of(pid).orElse(null) ?: return
        val all = (root.descendants().toList() + root).distinctBy { it.pid() }
        all.forEach { runCatching { it.destroy() } }
        waitUntil(graceMs) { all.all { !it.isAlive } }
        all.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
        waitUntil(graceMs) { all.all { !it.isAlive } }
    }

    private fun waitUntil(timeoutMs: Long, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !done()) {
            Thread.sleep(50)
        }
    }
}
