package io.github.juwanhwang.autoqqpet.log

import android.util.Log

/** 轻量日志封装；后续可替换为写入共享目录的文件日志（对齐 Sesame-M 的 Log 体系）。 */
object PLog {
    private const val PREFIX = "Autoqqpet"

    fun v(tag: String, msg: String) = Log.v("$PREFIX/$tag", msg)
    fun d(tag: String, msg: String) = Log.d("$PREFIX/$tag", msg)
    fun i(tag: String, msg: String) = Log.i("$PREFIX/$tag", msg)
    fun w(tag: String, msg: String) = Log.w("$PREFIX/$tag", msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e("$PREFIX/$tag", msg, t)
}
