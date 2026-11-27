package ohd.core

enum class Status { OK, WARN, FAIL }

data class Check(val name: String, val status: Status, val detail: String) {
    companion object {
        fun ok(name: String, detail: String) = Check(name, Status.OK, detail)
        fun warn(name: String, detail: String) = Check(name, Status.WARN, detail)
        fun fail(name: String, detail: String) = Check(name, Status.FAIL, detail)
    }
}

fun List<Check>.hasFailure(): Boolean = any { it.status == Status.FAIL }
