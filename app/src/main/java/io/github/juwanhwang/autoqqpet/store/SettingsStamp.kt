package io.github.juwanhwang.autoqqpet.store

/**
 * 设置的“最后修改时刻”戳（R10）：三份设置（界面 `autoqqpet_ui` → 主存 `settings_json` → QQ `autoqqpet_settings`）
 * 来回同步时，**旧的一份不能覆盖新的一份**。
 *
 * 真机现象：QQ 侧 `recall_on=true` 刚写下，每 5 分钟的主存补拉回信里带着更早的 `recall_on=false`，
 * 旧逻辑“只要不同就灌回”，于是新值被旧值顶掉（或反过来，界面看到的与生效的不一致）。
 * 现在每份都带 `__at`（毫秒，同一台设备同一时钟）：
 *  - 界面合并进主存时盖 now；QQ 侧 `persist`（命令/迁移）盖 now；
 *  - QQ 收主存回信：回信的戳 < 本地戳 → 整份拒收（并把本地这份上交，主存自愈）；
 *  - 主存收 QQ 上交：上交的戳 < 主存戳 → 拒收（并把主存这份回送 QQ）。
 * 没有戳（旧版本写的）记 0：本地也没戳时照旧灌回（升级第一次），本地有戳时拒收没戳的旧数据。
 */
object SettingsStamp {
    const val KEY = "__at"

    /** incoming 是否可以覆盖 local。 */
    fun accept(incomingAt: Long, localAt: Long): Boolean = when {
        localAt <= 0L -> true
        incomingAt <= 0L -> false
        else -> incomingAt >= localAt
    }
}
