package cf.wayzer.scriptagent.ideaplugin.index

import com.intellij.openapi.project.DumbService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 挂起直到工程退出 dumb 模式（索引完成）。
 *
 * [SAIndexerService] 的查询都走 [com.intellij.util.indexing.FileBasedIndex]，只能在 smart 模式执行；
 * dumb 模式下调用会抛 [com.intellij.openapi.project.IndexNotReadyException]。用户操作（Sync、工具窗按钮）
 * 可能正好落在索引期间 —— 这些入口先 await，而不是把"还在索引"当成失败。
 *
 * **不要在读操作里调用**：smart 模式需要写锁才能结束。
 */
suspend fun DumbService.awaitSmartMode() {
    while (isDumb && !project.isDisposed) {
        suspendCancellableCoroutine { cont ->
            val connection = project.messageBus.connect()
            connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                override fun exitDumbMode() {
                    connection.disconnect()
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            cont.invokeOnCancellation { connection.disconnect() }
            // 订阅后再确认一次：否则"检查完 isDumb、订阅前刚好退出"会漏掉事件而永久挂起。
            if (!isDumb && cont.isActive) {
                connection.disconnect()
                cont.resume(Unit)
            }
        }
    }
}
