package cf.wayzer.scriptagent.ideaplugin.index

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import java.util.concurrent.Callable

private class BlockCallable<T>(private val block: () -> T) : Callable<T> {
    override fun call(): T = block()
}

/**
 * 在 smart 模式下执行索引查询 —— 用平台官方原语：
 * ReadAction.nonBlocking(...).inSmartMode(project).executeSynchronously()。
 *
 * 语义：排队直到工程处于 smart 模式再执行读操作；期间（包括排队时又进入 dumb 模式）平台会等待/重排，
 * 不会抛 IndexNotReadyException。之前自己用 DUMB_MODE 消息总线等 + 失败重试，属于重复实现平台能力。
 *
 * 约束（平台规定）：不能在 EDT（UI 线程）执行，也不能在已有读操作里执行；
 * 本插件所有调用点都在后台协程（project scope / Dispatchers.Default）里。
 */
fun <T> Project.withIndexInSmartMode(block: () -> T): T =
    ReadAction.nonBlocking(BlockCallable(block)).inSmartMode(this).executeSynchronously()
