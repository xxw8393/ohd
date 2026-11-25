package ohd.core.actions

import ohd.core.parsers.LayoutNode

/**
 * 回放时按录制下来的控件身份重新定位。
 * id 唯一才点；没有 id 时用文本加类型，命中多个就失败；两者都空才允许调用方退回原坐标。
 */
object ReplayAim {
    sealed interface Decision
    data class Hit(val x: Int, val y: Int, val by: String) : Decision
    data class Ambiguous(val count: Int) : Decision
    data object Fallback : Decision
    data class NotFound(val by: String) : Decision

    fun poll(
        sample: () -> Decision,
        deadlineMs: Long,
        now: () -> Long = { System.currentTimeMillis() },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        intervalMs: Long = 250,
    ): Decision {
        while (true) {
            val decision = sample()
            if (decision !is NotFound) return decision
            if (now() >= deadlineMs) return decision
            sleep(intervalMs)
        }
    }

    fun decide(nodes: List<LayoutNode>, id: String?, text: String?, type: String?): Decision {
        val idKey = id?.takeIf { it.isNotBlank() }
        val textKey = text?.takeIf { it.isNotBlank() }
        if (idKey == null && textKey == null) return Fallback
        val pool = nodes.filter { it.bounds != null }
        if (idKey != null) {
            val hits = pool.filter { it.id == idKey }
            return when (hits.size) {
                1 -> Hit(hits[0].bounds!!.centerX, hits[0].bounds!!.centerY, "id")
                0 -> NotFound("id")
                else -> Ambiguous(hits.size)
            }
        }
        val hits = pool.filter { node ->
            node.text == textKey && (type.isNullOrBlank() || node.type.equals(type, ignoreCase = true))
        }
        return when (hits.size) {
            1 -> Hit(hits[0].bounds!!.centerX, hits[0].bounds!!.centerY, "text")
            0 -> NotFound("text")
            else -> Ambiguous(hits.size)
        }
    }

    /** 把指针动作的落点换成控件现在的中心，滑动保留原来的位移。 */
    fun retarget(action: UiActions.Action, x: Int, y: Int): UiActions.Action {
        val args = action.args.toMutableList()
        when (action.kind) {
            UiActions.Kind.TAP, UiActions.Kind.DOUBLE_TAP, UiActions.Kind.LONG_PRESS -> {
                args[0] = x.toString()
                args[1] = y.toString()
            }
            UiActions.Kind.SWIPE, UiActions.Kind.DRAG, UiActions.Kind.FLING -> {
                val dx = args[2].toInt() - args[0].toInt()
                val dy = args[3].toInt() - args[1].toInt()
                args[0] = x.toString()
                args[1] = y.toString()
                args[2] = (x + dx).toString()
                args[3] = (y + dy).toString()
            }
            else -> return action
        }
        return action.copy(args = args)
    }

    /** 滑动的落点控件：终点对准控件中心，起点跟着平移，手势形状不变。 */
    fun retargetEnd(action: UiActions.Action, x: Int, y: Int): UiActions.Action {
        val args = action.args.toMutableList()
        if (action.kind != UiActions.Kind.SWIPE && action.kind != UiActions.Kind.DRAG &&
            action.kind != UiActions.Kind.FLING
        ) {
            return retarget(action, x, y)
        }
        val dx = x - args[2].toInt()
        val dy = y - args[3].toInt()
        args[0] = (args[0].toInt() + dx).toString()
        args[1] = (args[1].toInt() + dy).toString()
        args[2] = x.toString()
        args[3] = y.toString()
        return action.copy(args = args)
    }
}
