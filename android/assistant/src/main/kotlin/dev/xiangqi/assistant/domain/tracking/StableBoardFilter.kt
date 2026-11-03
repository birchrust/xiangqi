package dev.xiangqi.assistant.domain.tracking

/** Transient frames preserve the confirmed baseline. */
class StableBoardFilter {
    private var accepted: String? = null
    private var pending: String? = null

    fun accept(cells: String): Boolean {
        if (cells == accepted) {
            pending = null
            return true
        }
        if (cells != pending) {
            pending = cells
            return false
        }
        return true
    }

    fun confirm(cells: String) {
        accepted = cells
        pending = null
    }

    fun miss() {
        pending = null
    }
}
