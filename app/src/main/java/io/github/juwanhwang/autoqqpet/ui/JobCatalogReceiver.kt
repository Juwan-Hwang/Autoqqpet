package io.github.juwanhwang.autoqqpet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.pet.JobCatalog

/**
 * 收 QQ 进程回传的打工/课程目录，落到模块自己数据目录的 prefs 里，供主页下拉框与排班编辑器读。
 *
 * 【为什么要先存盘】广播到达时界面未必开着（用户可能点了刷新就退出去），
 * 只往内存里塞的话，回来又得重刷一次。存进 prefs 后，进界面就能看到上次的职业清单，
 * 并明确标出“这是什么时候的读数”。
 *
 * 岗位、课程与冒险各自独立入库：某一侧空不影响另一侧（循环里只读钉住的职业，它回传时岗位 extra 就是空的，
 * 不能因此把 joblist 全量读到的完整职业表丢掉）。
 */
class JobCatalogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JobCatalog"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != JobCatalog.ACTION) return
        val ctx = context ?: return
        val at = intent.getLongExtra("at", 0L)
        val text = intent.getStringExtra("catalog").orEmpty()
        val tiers = JobCatalog.decode(text)
        val courseText = intent.getStringExtra("courses").orEmpty()
        val courses = JobCatalog.decodeCourses(courseText)
        val advText = intent.getStringExtra("adventures").orEmpty()
        val adventures = JobCatalog.decodeAdventures(advText)
        if (tiers.isEmpty() && courses.isEmpty() && adventures.isEmpty()) {
            PLog.w(TAG, "📥 目录回包解不出内容（岗位 ${text.length}B / 课程 ${courseText.length}B / 冒险 ${advText.length}B），保持原样不动")
            return
        }
        val ed = ctx.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE).edit()
        if (tiers.isNotEmpty()) {
            ed.putString(JobCatalog.KEY_CATALOG, text).putLong(JobCatalog.KEY_CATALOG_AT, at)
        }
        if (courses.isNotEmpty()) {
            ed.putString(JobCatalog.KEY_COURSES, courseText).putLong(JobCatalog.KEY_COURSES_AT, at)
        }
        if (adventures.isNotEmpty()) {
            ed.putString(JobCatalog.KEY_ADVENTURES, advText).putLong(JobCatalog.KEY_ADVENTURES_AT, at)
        }
        ed.apply()
        PLog.i(TAG, "📥 已存目录：岗位 ${JobCatalog.careers(tiers).size} 个职业 / ${tiers.size} 个档位；课程 ${courses.size} 门；冒险 ${adventures.size} 个")
    }
}
