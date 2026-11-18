package io.github.juwanhwang.autoqqpet

/**
 * 模块 App 自己数据目录里那份偏好（`autoqqpet_ui`）的文件名与键名。
 *
 * 【为什么要有这份镜像】真正生效的 `autoqqpet_settings` 是 **QQ 进程**写的，落在 QQ 的数据目录；
 * 模块 App 进程读同名文件只会拿到默认值（2026-10-09 停摆复盘：看门狗与开机自启读到恒 false）。
 * 约定：UI 本地记忆 + 心跳回填都写这份，模块进程内的组件（看门狗/开机自启/界面）只读它。
 * 设置键本身的登记表见 [io.github.juwanhwang.autoqqpet.store.SettingKeys]（同名同型），完整契约见 docs/CONTRACT.md。
 */
object ModulePrefs {
    const val NAME = "autoqqpet_ui"
    const val KEY_AUTO_ON = "auto_on"
    const val KEY_ATTR = "attr"

    /** 最近一帧心跳快照（JSON，结构见 CONTRACT §2）——StatusReceiver 每帧写，界面可挂 prefs 监听实时刷新。 */
    const val KEY_STATUS_SNAPSHOT = "status_snapshot"
    /** 收到该快照的本机时刻（ms）。 */
    const val KEY_STATUS_SNAPSHOT_AT = "status_snapshot_at"
}
