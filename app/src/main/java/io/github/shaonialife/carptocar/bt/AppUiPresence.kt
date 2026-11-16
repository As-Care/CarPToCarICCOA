package io.github.shaonialife.carptocar.bt

/** 主界面可见时，离车清理保留进程供用户查看状态。 */
object AppUiPresence {
    /**
     * 前台 Activity 计数（用计数而不是布尔：旋转时新旧 Activity 会短暂交叠，
     * 布尔值在那一瞬间会被旧实例的 onPause 误写成「不可见」）。
     */
    private val resumedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 界面此刻是否在前台可见（用户正看着手机）。 */
    val isVisible: Boolean
        get() = resumedCount.get() > 0

    fun markResumed() {
        resumedCount.incrementAndGet()
    }

    fun markPaused() {
        if (resumedCount.decrementAndGet() < 0) resumedCount.set(0)
    }
}
