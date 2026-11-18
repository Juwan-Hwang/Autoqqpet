# 前后端数据契约（v2.0.2，契约版本 `contract_v = 1`）

前端 = 模块 App 进程（`com.juwanh.autoqqpet`，`ui/*`）；后端 = 注入 QQ 进程的循环（`hook/` `pet/` `optimizer/`）。
两进程**不共享数据目录**，只靠三条广播通道 + 一份模块自有主存。代码里的单一真源：

| 内容 | 真源 |
|---|---|
| 设置键（名/型/默认/范围） | `store/SettingKeys.kt` |
| 心跳快照字段 | `hook/StatusSnapshot.kt`（`VERSION`） |
| 命令广播 | `hook/cmd/*Commands.kt`（QQ 里发 `action=help` 打印全表） |
| 协议命令号 | `net/Commands.kt` |

改任何一项都要同步改本文件；快照字段改名/删字段要把 `StatusSnapshot.VERSION` +1。

---

## 1. 设置键

### 1.1 存储与流向

```
界面写 autoqqpet_ui (ModulePrefs.NAME)            ← 前端只需要写这里 + 发命令
   │ AutoqqpetApplication 的 prefs 监听：凡是 SettingKeys 里登记的键 → 合并进主存
   ▼
主存 autoqqpet_store / settings_json（模块自有目录，QQ 重装不丢）
   │ QQ 启动时 ask 一次（≤2.5s）；循环每 5 分钟非阻塞补拉；回灌时按登记类型强制写（JSON 不分 Int/Long）
   ▼
QQ 进程 autoqqpet_settings —— 唯一真正生效的那份
   ▲ 也可由命令直接写：setopt / setattr / adv / setwork / …（命令会顺带上交主存并重建循环；连续改多项防抖合并为一次复查）
   │ QQ 侧每次写（命令/迁移）都上交主存 → 模块 App 的 StoreReceiver 把与界面不同的键写回 autoqqpet_ui（R8）
   ▼ 界面 prefs 监听 → dataVersion+1（150ms 防抖）→ 设置页/主页重读；写回时 Application 的镜像监听跳过，不回环
```

**时间戳守卫 `__at`（R10）**：三份设置都带“最后修改时刻”（毫秒）。界面合并进主存时盖 now（只在值真变了时）；QQ 侧 `persist` 盖 now；
QQ 收主存回信时**回信戳 < 本地戳就整份拒收**（并上交本地这份让主存自愈）；主存收 QQ 上交时同理（拒收并回送主存这份）。
没有戳（旧版写的）记 0：本地也没戳时照旧接收（升级第一次），本地有戳时拒收无戳数据。`__at` 不是设置键，不进 §1.2。
模块 App 进程启动时只做“主存 → 界面”（不再把界面 prefs 全量合并回主存：界面那份可能是旧的）。

**日账本防清空（`daily_json`）**：当日进度载荷没有时间戳守卫，改用一条不变量兜底——**同一天的账本，有内容的一侧永远不能被空的一侧盖掉**
（「空」= 没有 `counts` 且没有 `ledger`，保守口径）。判定点两处共用 `Store.dailyEmptyOverwriteBlocked`：主存收 QQ 上交落盘前（`saveBackup`）、QQ 启动把备份灌回本地前（`DailyStore.adoptFromStore`，拦下后把本地这份强制上交让主存自愈）。
只拦「带有效日号且同日 + 来件空 + 本地有内容」；跨天的新账本为空是正常的，不拦。起因是 2026-10-19 卸载重装模块 App 后，空账本经同步盖掉了全天记录、今日统计从零重记。

**单实例（R10）**：只有 QQ 主进程 `com.tencent.mobileqq` 跑循环。进程名依次取 `ActivityThread.currentProcessName()` → `Application.getProcessName()` → `/proc/self/cmdline`；
再加跨进程文件锁 `files/autoqqpet-runner.lock`（`FileChannel.tryLock`，进程退出即释放），拿不到锁的进程不建 runner、不发心跳。

**前端改一个设置的推荐写法**（两步都做：QQ 不在线时第 1 步保证不丢，在线时第 2 步立即生效）：
1. `getSharedPreferences("autoqqpet_ui").edit().put…(key, value)`（类型必须与下表一致）；
2. 发命令 `setopt --es key <key> --es value <字符串>`（`auto_on` 除外，用 `auto`/`autooff`）。

### 1.2 键表

“谁写”：U=界面，Q=QQ 进程命令处理；“谁读”：R=RunnerConfig（QQ 循环），W=看门狗/开机自启，U=界面。
标 🧪 的是**实验**功能（命令含义已定，但请求字段号/sub 没抓到或数据来源是推断），界面应标注“实验”。

| 键 | 类型 | 默认 | 含义 | 写/读 |
|---|---|---|---|---|
| `auto_on` | bool | false | 自动托管开关。改它请发 `auto`/`autooff` | U,Q / R,W,U |
| `attr` | string | `力量` | 目标属性：`力量`/`智力`/`魅力`/`均衡`（均衡 = 学习在力量、智力、魅力之间轮着来，每派出一趟学习轮一个，轮到的没课就顺延到下一个有课的） | U,Q / R |
| `adv_mode` | string | `auto` | 怎么干活：`auto` 最优调度（按 `auto_goal` 算今天的最优安排，每趟重算）/`mix` 学习优先（旧逻辑，疲惫后转冒险）/`work` 打工优先（旧逻辑，疲惫后转冒险）/`only` 只重复冒险/`custom` 自定义排班。新装默认 `auto` | U,Q / R |
| `auto_goal` | string | `attr` | 最优调度（`adv_mode=auto`）的目标：`gold` 金币优先（冒险先行：当天金币池没见底——只认连续 30 趟无金币的到顶判定——之前先派冒险，见底后转打工）/`points` 工分优先（岗位工分按目录标称计，不取实收学习值——学习值是折后均值、会折两道；另有净亏刹车：按所处疲劳档折算后收入盖不住补给耗费的工不排，收入未知的工不拦）/`attr` 属性优先；工分、属性两挡默认要求最终金币不能比早上少（不够就先回血），属性优先填了 `attr_loss_budget` 时底线按其放宽；这条基线按今日起始金币算，会扣除用户在官方界面手动花掉的金币（模块自家支出如买补给、学费先行解释，剩下的余额跌幅视为非模块支出、等额下调基线），不把手动消费算成模块亏损 | U,Q / R |
| `study_brake` | string | `no10` | 学习停课线（仅最优调度·属性优先）：学费不打折、收益按疲劳打折。`none` 不设线（打折也上）/`full` 只在全额时段上课/`no10` 进一折时段（学工累计 12 小时后）就停课 | U,Q / R |
| `adv_max` | int 0..1000000 | 0 | 每日冒险上限，0=不限（无上界） | U,Q / R |
| `adv_only_name` | string | `""` | 只冒险（`only`）固定刷哪一个冒险，按名字存；空=自动（每轮选当时最划算的一条）。所选的一时不可做时回退自动。主页选了「只冒险」时可直接切换 | U,Q / R |
| `adv_weather_recall_on` | bool | true | 天色不对自动召回：模块派出的冒险（自动/只冒险/最优调度/排班）出发满 6.5 秒起查 0x975f 进度，没读到结果（没回包/条目为空）隔 8 秒再查、最多 4 次，条目含「天色不对」就立刻召回重开（召回写失败最多再试 2 次）；派出时没挂上记号的自家冒险由进行中的 tick 接手补查；天气召回的趟与普通结算一样计入连续无金币连段（连续 30 趟无金币判冒险金币到顶、当天停派）；手动/外部故事不查 | U,Q / R |
| `work_career` | **long** 0..99 | 0 | 打工方向（职业 type 1..8），0=自动；档位每轮按实时读数选。界面入口在**主页**（“工作优先”时显示“去哪打工”），设置页已不再列出 | U,Q / R |
| `hire_alert_on` | bool | false | 被雇佣提醒：确认被雇（state=51 + 0x994a“雇佣了”通知）且进度≥71% 时提醒；`recall_on` 开着时不提醒；`recall_external_on`（增强识别）开着时亲手开的打工也会提醒 | U,Q / R |
| `recall_on` | bool | **true** | 被雇走时自动召回：不是本模块开的 + 确认被雇 + 进度≥71%（**不看 recallable**，实测缺省也能召回） | U,Q / R |
| `recall_mode` | string | `best_split` | `best_split` 进度≥71% / `immediate` 立即 | U,Q / R |
| `recall_external_on` | bool | false | 增强识别：不是本模块开的打工（被雇、用户亲手开的）不等通知确认、按 `recall_mode` 处理；亲手开的会被中途叫回、按当时进度结算。开启时有确认弹窗明示 | U,Q / R |
| `hire_on` | bool | false | 开工时雇佣一个好友：先按名单与资格过滤，再按这趟岗位的加成上限从高到低排队，依次问可雇；雇谁开工被服务器明确拒绝就按排名换下一位，都雇不成自己开工（雇佣只是加成，不堵开工）；雇人不收费，每日次数上限按雇佣开工班次计，不看金币留底、不按工时算回本 | U,Q / R |
| `schedule_json` | string | `""` | 自定义排班整份 JSON（`pet/Schedule.kt` 编码；`adv_mode=custom` 生效；也可发 `schedule --es json`） | U,Q / R |
| `gold_reserve` | int 0..100000 | 300 | 金币预留：交学费后仍要至少剩这个数（雇人不再看它） | U,Q / R |
| `attr_loss_budget` | int 0..100000 | 0 | 今日可亏金币（仅最优调度·属性优先）：期末金币底线改成今日起始金币减这个数，拿金币换上课，不再为了守底线先安排赚钱；放宽不穿 `gold_reserve`。0=一分不亏 | U,Q / R |
| `biscuit_price` | int 0..1000000 | 5 | 最优调度算账用的饼干单价；游戏改价了就改这里 | U,Q / R |
| `soap_price` | int 0..1000000 | 2 | 最优调度算账用的香皂片单价；游戏改价了就改这里 | U,Q / R |
| `stamina_floor` | int 0..100 | 30 | 体力低于此先喂 | U,Q / R |
| `clean_floor` | int 0..100 | 30 | 清洁低于此先洗 | U,Q / R |
| `feed_full_on` | bool | false | 饱食不足马上喂：照顾检查时只要饱食没满就喂到满，不等低于 `stamina_floor`；食物不够或喂食被临时禁止（冷却中）时照旧跳过；只管喂食，不管洗澡 | U,Q / R |
| `auto_buy_supplies` | bool | true | 饼干/香皂用完自动买 10 个 | U,Q / R |
| `hire_max` | int -1.. | -1 | 每日雇佣开工次数上限，新建立与复用每次开工都计一次，达到上限后复用也不再雇；-1=不限（默认） | U,Q / R |
| `pk_on` | bool | true | 主任务进行中随机顺带 PK（每轮复查约三分之一概率；只挑战力 ≤ 我方 `pk_margin`% 的；胜负按结算称号；输了是否拉黑看 `pk_blacklist_on_loss`） | U,Q / R |
| `pk_blacklist_on_loss` | bool | true | PK 输了拉黑对方：输局后把对方排除出互动（互动名单黑名单模式加进名单、白名单模式从名单移除；设置页可改回）；关掉后输了不处理 | U,Q / R |
| `pk_max` | int -1.. | 10 | 每日 PK 上限；-1=不限 | U,Q / R |
| `settle_view_on` | bool | true | 到期后读 0x9874 结算明细（今日实收、判疲劳） | U,Q / R |
| `learn_rewards_on` | bool | true | 目录不报金币时用同档位历史实收金币（满档还原、雇佣单独记账）参与择优（按职业与时长记账，定价读数 ≥1 样本） | U,Q / R |
| `progress_on` | bool | true | tick/复查时读一次 0x975f 叙事（快照 `task.narrative`） | U,Q / R |
| `encourage_times` | int 0..10 | 1 | 每次学习/打工自动鼓励次数（0x9c44，仅学工）；第 1 次开工后立刻，其余进行中每约 20s 补发，服务器拒绝即停；0=不鼓励 | U,Q / R |
| `mail_check_on` | bool | false | **已不再产生提醒**（R7）：0x9c11 列表没有“已领”标志，附件数≠可领数。只开它不开领取时只记一行说明。界面已移除，键保留兼容 | — / R |
| `checkin_food_on` | bool | **true** | 每日签到领食物 0x9c6d_0（回包 f1=领后库存；空回包=今天已领） | U,Q / R |
| `mail_claim_on` 🧪 | bool | true | 每 6h 一键领取 0x9c19_0。**信箱提醒只来自它**：成功=已领（撤提醒）；136610=没东西可领（撤提醒）；其它业务码（≥100000）=有东西但自动领不了 → INFO 提醒“去 QQ 领”（src=`mailclaim`，非命令号哨兵串，加固后不再用原始命令号作来源标记）。无成功样本 | U,Q / R |
| `coinbag_on` | bool | true | 自动领福袋：自己的袋走 0x9acb_0 查、好友的袋看好友栏 0x985d 记录 `.21`，两边都 0x9d71→0x9d70（f2 传袋主），余额差确认 | U,Q / R |
| `coinbag_max` | int -1.. | **-1** | 每日领福袋上限；-1=不限（默认）。风控靠每轮上限 `spark_per_round` 与间隔抖动 | U,Q / R |
| `coinbag_every_min` | int 1..60 | **5** | 福袋扫描间隔（分钟）：下一次扫描时刻加进主循环唤醒排程，到点准时扫，不等手头任务结束/复查/结算 | U,Q / R |
| `friend_care_on` | bool | false | 替好友喂食洗澡，用自己的饼干、香皂 | U,Q / R |
| `friend_care_max` | int -1.. | 10 | 每天最多帮几次，喂食和洗澡合计；-1=不限 | U,Q / R |
| `friend_care_floor` | int 0..100 | 60 | 好友体力/清洁低于此才动手 | U,Q / R |
| `friend_oneclick_on` | bool | false | 好友一键照顾 0x9b80，道具不够时服务器自动花金币补；与替好友喂食洗澡同开时以那个为准 | U,Q / R |
| `friend_oneclick_max` | int -1.. | 3 | 每日一键照顾上限；-1=不限 | U,Q / R |
| `daily_report_on` | bool | true | 跨天归档昨日报告（快照 `report_last`） | U,Q / R |
| `settle_claim_on` | bool | true | 到期发 0x9760 action=1000（实测不发也到账；回包有奖励就不再读 0x9874） | U,Q / R |
| `attr_read_on` | bool | true | 每小时读 0x9b62 三属性（快照 `pet.attrs`），PK 选敌也用 | U,Q / R |
| `exp_read_on` | bool | true | 每小时读 0x9abe 今日经验（快照 `pet.exp`） | U,Q / R |
| `pk_margin` | int 50..100 | 83 | PK 只挑战力 ≤ 我方 N% 的对手（好友战力 = 0x9b62 三属性和，缓存 6h） | U,Q / R |
| `spark_on` | bool | false | 每天踩**所有 QQ 宠物好友**（0x985d 名册，不含陌生人）各一次（0x985b），火花名单按模式排除（黑名单模式：名单内不踩；白名单模式：只踩名单内，名单为空 = 踩所有）；待重燃优先 → 在燃按天数 → 其余按最近互动；5 分钟一轮，每轮最多 `spark_per_round` 人（请求间隔 1.5~4s 随机）；136202=今天踩过也算完成 | U,Q / R |
| `spark_max` | int -1.. | **-1** | 每日踩踩总上限（火花 + 回踩）；-1=不限（默认）。schema 4 迁移：旧默认 20 → -1，旧 0 → 关 `spark_on` | U,Q / R |
| `spark_per_round` | int 1..30 | 6 | 每轮最多踩几人（≈ 每小时 72 人） | U,Q / R |
| `spark_remind_on` 🧪 | bool | false | 每 3h 读火花列表 0x9b87（请求体推断）；火花名单排除范围外好友待重燃且断 ≥5 天（7 天清零）时 INFO 提醒 `spark` | U,Q / R |
| `spark_tonight_on` 🧪 | bool | false | 到 `spark_tonight_at` 后每 30 分钟查一次：在燃（阶段 1）但今天没双向互踩的好友（火花名单排除范围外）→ INFO 提醒 `spark_tonight`（前 3 人 + 其余人数）；一天只响一次、互踩完自动撤、读不到数据不提醒 | U,Q / R |
| `spark_tonight_at` | int 0..1439 | 1260 | 今晚火花提醒时刻（当天第几分钟，本机时区；默认 21:00） | U,Q / R |
| `spark_return_on` 🧪 | bool | false | 回踩：0x985e 今天踩了你的人当天回踩一次（每人每天一次，排除火花名单排除范围）。`spark_on` 开时只回踩**非好友**（好友已在每日计划里）；关时好友和非好友都算 | U,Q / R |
| `hired_poll_on` | bool | true | 每 10 分钟拉 0x994a 找“雇佣了”（服务器不推送），用于被雇提醒；关掉后不再定时拉，但自动召回需要确认被雇时仍会现拉一次（确认用途与本开关解耦，2.1.10） | U,Q / R |
| `feed_push_on` | bool | false | 替好友喂食成功后补发 0x99ef“我喂了你宠物”通知 | U,Q / R |
| `alarm_clock_on` | bool | false | v2.1：长短唤醒的系统闹钟改用 `setAlarmClock`（Doze 不节流；状态栏有闹钟图标）。关时用 `setExactAndAllowWhileIdle`（QQ 无精确闹钟权限时退 `setAndAllowWhileIdle`） | U,Q / HostRuntime→AlarmScheduler |
| `screen_pause_on` | bool | false | 息屏时暂停（#11）：屏幕熄灭期间 runner 不发任何包（派活/照顾/轮询/到期结算全延后），屏幕状态读 QQ 进程侧 `PowerManager.isInteractive`；亮屏后按迟到结算/断点恢复续上 | U,Q / R |
| `notify_open` | string | `qq` | 点通知打开：模块发的全部通知（常驻状态、中断提醒、动手提醒、火花提醒）点击后打开哪个应用；`qq`=QQ（默认，与旧行为相同），`app`=模块主界面；通知正文里的目标名跟着这个值走 | U / KeepAliveService |
| `night_pause_on` | bool | false | 夜间暂停（#11）：在 `night_pause_start`–`night_pause_end` 时段内同息屏口径全停，时段结束后继续；与 `screen_pause_on` 任一生效即暂停，同时生效取并集 | U,Q / R |
| `night_pause_start` | int 0..1439 | 90 | 夜间暂停开始时刻（当天第几分钟，本机时区；默认 01:30；开始晚于结束表示跨零点） | U,Q / R |
| `night_pause_end` | int 0..1439 | 390 | 夜间暂停结束时刻（当天第几分钟，本机时区；默认 06:30；开始与结束相同视为空时段、不暂停） | U,Q / R |
| `short_adv_gap_s` | int 0..3600 | 10 | 任务间隔（键名沿用旧名，2026-10-16 起语义推广）：每件主任务（学习/打工/冒险）结束后隔多少秒再开下一件；0=连着跑。短冒险连续失败/未确认的指数退避另算 | U,Q / R |
| `settle_buffer_s` | int 0..300 | 5 | 结算等待：主任务（学习/打工）到期后等多少秒再发结算（RunnerConfig.settleBufferMs = 本值 ×1000）；冒险另用固定 3 秒缓冲，不随本项 | U,Q / R |
| `log_keep_days` | int | 3 | v2.1：QQ 侧按天日志保留天数（含今天，1..14），跨天时清理 | U,Q / DailyStore |

迁移（`SettingKeys.migrate`，每次 QQ 启动幂等执行；有改动就盖 `__at` 并上交）：旧 `adv_on` → `adv_mode`；`adv_mode=off` → `mix`；类型不对的键按登记类型重写，写不回就删回默认；
schema 4：每日上限统一 -1=不限，旧值 0（=一次都不做）→ 关掉对应开关；`spark_max` 旧默认 20、`coinbag_max` 旧默认 10 → -1。`settings_schema`=4。
**每日上限的界面口径**：显示“不限 / 限制 N 次”（N≥1），不再出现“0=不限”。
**v1 → v2 无改名**。默认值变化：`recall_on` false→true、`checkin_food_on` false→true（只影响没存过该键的用户；显式存过的值保留）。

### 1.3 界面自用键（`autoqqpet_ui`，不进主存、QQ 不读）

| 键 | 类型 | 写入方 | 含义 |
|---|---|---|---|
| `status_snapshot` | string(JSON) | StatusReceiver 每帧 | 最近一帧快照（§2），界面可挂 prefs 监听实时刷新 |
| `status_snapshot_at` | long | StatusReceiver | 收到该快照的本机时刻 |
| `work_catalog` / `_at` | string / long | JobCatalogReceiver | 岗位目录（`JobCatalog.decode`） |
| `course_catalog` / `_at` | string / long | JobCatalogReceiver | 课程目录（`JobCatalog.decodeCourses`） |
| `adventure_catalog` / `_at` | string / long | JobCatalogReceiver | 冒险目录（`JobCatalog.decodeAdventures`） |
| `ui_theme_accent` | string | 界面 | 主题色：`ocean`(v2.1 起默认 海蓝；一次性把仍停在旧默认 `teal` 的切到 `ocean`，标记 `ui_ocean_default_v21`)/`teal`(青碧)/`pine`/`amber`/`coral`/`graphite`；旧 `ui_dynamic_color`（跟随壁纸取色）已删除，启动时移除 |
| `alert_dismissed` | string set | 主页提醒条 | 本地已划掉的提醒 `id|incident`（旧文本提醒 `legacy|hash`），QQ 确认前先藏起来；最多 60 条 |
| 主题模式、减少动效、协议指纹等 | — | 界面 | 界面自管 |

主存 `autoqqpet_store` 另有：`daily_json`（当日进度镜像，含 `report_last`）、`friends_json`、`diag_json`/`diag_at`（诊断快照，界面只读）。

`friends_json` 键（2.1.10 起名单统一为「一份名单 + 模式」：`wl`=白名单只认名单内、`bl`=黑名单排除名单内，名单为空两种模式都不限）：`friend_list`+`friend_list_mode`（互动名单，csv；旧 `whitelist`/`blacklist` 双名单在 QQ 进程启动时迁移合并：白名单非空 → 白名单模式、名单 = 白名单 − 黑名单；为空 → 黑名单模式、名单 = 原黑名单；旧键删除）、`hire_list`+`hire_list_mode`（雇佣名单，csv，默认 `wl`：非空时只在名单内挑，全不可雇则本轮不雇；`bl` 时排除名单内）、`roster`=`uin⋄昵称~…`、`auto:<uin>`、`last.pk.*`、
**火花**：`spark_bl`（csv，火花名单）+`spark_list_mode`（默认 `bl`；**`spark_wl` 已废弃**，`spark_schema=2` 迁移时删除）、`spark_poked`=`<本地日号>;uin;uin…`（今日已踩）、
`spark_skip`（今天被拒的）、`spark_last`（每人最近一次踩的日号，排序用）、`spark_backoff`（风控退避）、`roster_at`、
`sparks`=`uin⋄昵名⋄阶段(1点亮/2待重燃/3未点亮)⋄天数⋄已断天数(-1未知)⋄今日已互踩(0/1)~…`、`sparks_at`（**字符串**毫秒）。
界面写名单：`Store.mergeFriendsLocal` 先写副本，再发 `friend`/`hire`/`spark` 命令给 QQ（带 `mode` 时连模式一起写）。

---

## 2. 状态心跳广播（QQ → 模块 App）

`action = io.github.juwanhwang.autoqqpet.STATUS`，`setPackage(com.juwanh.autoqqpet)`。
时机：每轮 tick 结束一帧；auto 开着时每 60s 一帧心跳；命令 `snapshot`/`refresh`/`setopt` 后各一帧。
清单里的 `StatusReceiver` 收；界面进程也可以动态注册同 action 的 receiver（显式包广播会同时送达），或监听 `status_snapshot` prefs。

### 2.1 Extras（v1 兼容字段保留）

| extra | 类型 | 含义 |
|---|---|---|
| `state` | string | 最近一次业务状态一行（如 `started:work:街角占卜摊`、`busy:6400_… 剩1200s`） |
| `auto_on` | bool | 托管是否开着（StatusReceiver 镜像进 `autoqqpet_ui/auto_on`） |
| `next_wake_at` | long ms | 下一次唤醒时刻；0=没排（看门狗据此判停摆） |
| `net_bad_at` | long ms | 连续请求回不来的起点；0=正常 |
| `heartbeat` | bool | true=纯心跳帧 |
| `alert` | string? | 最新一条 ACTION 级提醒（→ 高优通知 1003）；null=撤通知 |
| `snapshot` | string(JSON) | **v2 新增**：完整快照，见 §2.2 |
| `contract_v` | int | 快照结构版本（当前 2） |

### 2.2 `snapshot` JSON

```jsonc
{
  "v": 2, "at": 1760450000000, "auto_on": true,
  "state": "busy:6400_ab… 剩1200s",
  "activity": { "kind": "work", "text": "进行中「街角占卜摊」剩 1200s", "decision": "WORK 街角占卜摊" },
  "pet": { "name": "旺旺", "feel": 88.0, "hunger": 72.5, "clean": 64.0, "total": 75.1, "gold": 1350, "at": 1760449990000, "stage": 2,
           "attrs": { "strength": 839, "intel": 1607, "charm": 6468, "power": 8914, "at": 1760449000000 },   // 0x9b62；没读过则无此键
           "exp": { "today": 86, "cap": 120, "mult": 1.2, "self_care_full": true, "at": 1760449000000,     // 0x9abe；today 已乘倍率
                    "tasks": [ { "name": "去好友家踩一踩", "done": 3, "cap": 3, "exp": 30, "full": true } ] } },
  "task": {                                   // 没任务时为 null
    "story_id": "6400_ab…", "kind": "work",   // study|work|adventure|pk|unknown（按 storyId 前缀）
    "name": "街角占卜摊",                        // 本模块开的才有；外来任务为空
    "state": 51, "total_sec": 2700, "remaining_sec": 1200,   // remaining 已按快照时刻折算
    "progress": 0.556, "started_at": 1760448500000, "ends_at": 1760451200000,
    "mine": true,                             // 是否本模块开的；null=不知道。开工时先记认领，开工登记丢失后在 10 分钟内按认领领回的也算本模块开的
    "recallable": false,                      // 服务器 detail#5（仅展示；召回不看它）
    "hired": false,                           // 已确认被好友雇走（state=51 + 0x994a 通知）
    "narrative": "宠物正在为客人占卜…"          // 0x975f 最新一行（progress_on）
  },
  "today": {
    "day": "2026-10-14",
    "counts": { "study": 3, "work": 4, "adventure": 12, "pk": 2, "pk_win": 1, "hire": 1, "hire_spent": 0,
                "recall": 0, "friend_care": 2, "friend_oneclick": 0, "coinbag": 1, "coinbag_gold": 5, "checkin": 1,
                "recall_gold": 47, "mail_claim": 1, "spark_poke": 3, "spark_return": 0, "hired_notice": 1, "hired_settled": 0 },
    "study_work_min": 270, "adventure_min": 9,     // 只算已完成（与 counts 同源，完成时记）
    "study_work_sec": 16200, "adventure_sec": 540, // 同上，精确到秒（区分 0 与“不到 1 分钟”）
    "started": { "study": 4, "work": 4, "adventure": 12 },   // 开工次数（含进行中），仅诊断
    "planning_study_work_min": 315,                // 规划用学工分钟（含进行中任务），8h 疲劳窗口按它算
    "gold_start": 1000, "gold_now": 1350, "gold_delta": 350,    // 今日收益（金币净变化，已含雇佣支出等所有花销）
    "hire": 1, "hire_spent": 0,
    "rewards": { "工分": 42, "力量": 90, "金币": 120 },          // 0x9874 结算明细实收累计
    "gold_curve": [[1760400000000, 1000], [1760400600000, 1030]],  // 今日收益曲线：≥10 分钟或变动≥50 一个点，≤160 点
    "decisions": { "WORK/rate 最高": 4, "STUDY/rate 最高": 3 }
  },
  "report_last": { /* 昨日报告，结构见 §2.3；没有则 null */ },
  "next_wake_at": 1760451220000,
  "health": {
    "last_tick_at": 1760449990000, "net_bad_at": 0,
    "alarm_kind": "exactIdle", "exact_alarm_ok": true,   // v2.1：最近一次闹钟种类（alarmClock/exactIdle/idle/set/none）、QQ 能否排精确闹钟
    "last_error": "", "last_error_at": 0,
    "feed_blocked": false, "bath_blocked": false,   // 照顾被服务端连拒 3 次 → 当天绕过
    "pay_tired": false,                              // 学工全部档位降效
    "penalized": 0,                                  // 今日被削的档位数
    "mail_claimable": -1,                            // 只由 0x9c19 结果决定：-1=不知道，0=没东西可领/已领，1=有东西要去 QQ 手动领
    "decision_packets": 3, "catalog_packets": 0,     // 上一轮决策/读目录发了几个包（目录按游戏日缓存）
    "spark_poked": 12, "spark_target": 40,           // 今日火花进度（已踩/应踩）
    "process": "com.tencent.mobileqq", "pid": 12345, // 发这帧的 QQ 进程（R10：应只有主进程）
    "cfg_check": { "ok": true, "at": 1760449000000, "text": "一致",          // 配置自检（R10），5 分钟一次；null=还没查过
                   "disk_vs_memory": [], "runner_vs_disk": [] },           // 不一致时：["recall_on: 磁盘=true 内存=false"] / ["召回=best_split → 召回=关"]
    "entry": "com.tencent.mobileqq.qqpet.delegate.QQPetPbDelegate", "pet_ready": true
  },
  "alerts": [ { "id": "recall", "level": "action", "text": "…", "at": 1760449000000,
                "incident": "6400_ab…",   // 事件键：划掉时回传（默认 "d<日号>"=当天；recall=storyId；spark_tonight=当天事件键）
                "src": "" } ],             // 来源；mail 只认 "mailclaim"
  "config": { "mode": "mix", "attr": "STRENGTH", "work_career": 0, "recall_on": true, "recall_mode": "best_split", "hire_alert_on": false, "summary": "目标=… 模式=…", "settings_at": 1760448000000 }   // recall_on/recall_mode/hire_alert_on 是召回设置原值，界面据此选文案、不许解析 summary 猜；settings_at=QQ 侧设置的 __at
}
```

`alerts[].id` 取值与主页提醒条的按钮（`ui/AlertActions.kt`）：

| id | 级别 | 含义 | 按钮 |
|---|---|---|---|
| `recall` | action | 确认被雇、到 71%（自动召回关、提醒开时） | 立即召回（`recall --es mode immediate`） |
| `care_blocked` | action | 照顾被服务器连拒 | 去设置 · 照顾与补给 |
| `idle` | action | 无事可做且照顾被拒 | 去设置 · 照顾与补给 |
| `fee` | action | 学费不够（排班） | 去设置 · 金币与雇佣 |
| `pay_tired` | info | 今日学工降效 | 知道了 |
| `mail` | info | 一键领取说有东西但自动领不了（src=`mailclaim`；无 src 的旧提醒界面丢弃） | 去 QQ 领 + 知道了 |
| `spark` / `spark_tonight` | info | 火花快清零 / 今晚会熄 | 去火花页 |
| 其它（`manual`、`hired`…） | — | — | 去诊断 |

每条都可划掉（没有“知道了”按钮的在右上角有 ×）：界面先本地藏（`alert_dismissed`），再发 `alert op=dismiss id incident`；
QQ 侧把 `id|incident` 记进 `autoqqpet_alerts/dismissed`（3 天、≤80 条），同一事件之后不再挂，换事件（新的一天、新的被雇任务）才重新提醒。
旧后端（没有完整快照）只给一行 `alert` 文本时：含“雇佣”→ 立即召回；含“疲劳”→ 知道了；其它 → 去诊断（只在本地划掉）。
`level=action` 的会同时走 `alert` extra 变成高优通知；`info` 只在快照里。
R7：新版本第一轮（`autoqqpet_alerts/mail_reset_v2` 一次性标记）清掉旧版按附件数挂的信箱提醒与它的划掉记录。

仪表盘“健康”建议判据（与看门狗同口径）：心跳 ≤180s 且 `next_wake_at` 未过期超过 180s 且 `net_bad_at==0`。

### 2.3 每日报告（`report_last`、`report` 命令输出）

`{day, study, work, adventure, pk, pk_win, recall, hire, hire_spent, friend_care, coinbag, coinbag_gold, checkin, study_work_min, adventure_min, gold_start, gold_end, gold_delta, rewards{名称:数量}, decisions{动作/原因:次数}}`

---

## 3. 命令广播（模块 App → QQ）

`action = io.github.juwanhwang.autoqqpet.CMD`，`setPackage("com.tencent.mobileqq")`，`--es action <名字>`。
执行线：TICK=单线程串行（会动状态机），CMD=查询池。QQ 不在线时广播丢失 → 设置类务必先写 `autoqqpet_ui`（§1.1）。

鉴权（v2.0.2 起）：CMD 接收端挂签名级权限 `com.juwanh.autoqqpet.permission.BROADCAST`，只有同签名的模块 App 能发进来（debug 构建不挂，保留 adb 调试）。闹钟 tick 不走这条：发送方是 QQ 进程自己（腾讯签名，拿不到该权限），改走进程内专用 action `io.github.juwanhwang.autoqqpet.TICK`（不导出），同样进 `CommandRouter.dispatch`。

### 3.1 界面常用

| action | 参数 | 作用 |
|---|---|---|
| `auto` | `attr`? | 开托管并立刻跑一轮 |
| `autooff` | — | 关托管 |
| `refresh` | — | 立即刷新：开着=立刻跑一轮；关着=只读数值/任务并推一帧快照 |
| `snapshot` | — | 立刻推一帧快照（不发网络请求） |
| `setopt` | `key`,`value` | 通用设置（§1.2 任一键，`auto_on` 除外）；非法键/值拒绝并打日志 |
| `setattr` | `attr` | 改目标属性 |
| `adv` | `op`=mix\|work\|only\|custom；`op=limit --ei n` | 任务模式 / 冒险上限 |
| `setwork` | `--el career` | 打工方向 |
| `schedule` | `json` 或 `op`=show\|reset\|clear | 排班 |
| `joblist` | — | 读职业×档位+课程+冒险，回 `JOBS` 广播（下拉框/排班数据源） |
| `roster` | — | 刷新好友名册进主存 |
| `friend` | `op`=set\|add\|rm，`uins`=a,b，`mode`=wl\|bl | 互动名单（`set` 整份写回；带 `mode` 时连模式一起改） |
| `wl` / `bl` | `op`=set\|add\|rm，`uins`=a,b | 旧别名：互动名单 + 强制白/黑名单模式 |
| `hire` | `op`=set\|add\|rm，`uins`=a,b，`mode`=wl\|bl | 雇佣名单（`set` 整份写回；空 = 不限） |
| `spark` | `bl`=c,d；`op`=mode，`mode`=wl\|bl | 火花名单整份写回 / 切名单模式 |
| `sparks` | — | 现读火花列表 0x9b87 并交主存（`friends_json.sparks`） |
| `diag` | — | 诊断快照 → 主存 `diag_json` |
| `alert` | `op=dismiss --es id --es incident`；`op=list` | 划掉一条提醒（持久，同一事件不再出现）/ 打印当前提醒与划掉记录 |
| `cfgcheck` | `op=heal`? | 配置自检（R10）：磁盘 prefs vs 进程缓存 vs runner 配置，结果进快照 `health.cfg_check`；`heal` 时 runner 落后就重建。诊断页“设置是否生效”一项的按钮 |
| `report` | `op=last`? | 日志里打印今日/昨日报告 |
| `hirealert` / `setrecall` / `sethire` | `op=on\|off`（`setrecall` 另有 `mode`） | v1 兼容开关（等价 setopt） |

### 3.2 手动动作（按钮可用）

`learn` `work` `adventure[name]` `settle[op=force]` `recall[mode]`（不看 recallable；成功则撤 `recall` 提醒、记一次召回并推一帧） `hirework[uin,pet]` `hireadv` `hirecheck(uin,pet)` `care[op=reset|status, hunger, clean]` `feed` `bath` `buyfood[n]` `buybath[item,n]`
`pk` `visit` `poke` `fcare[uin,pet,n]` `foneclick[uin,pet]` `coinbag[bag,pet]` `cancelhire(pet)` `friendpet(pet)` `mail` `mailclaim` `checkin` `sparkrun` `attrs[pet]` `exp` `notices` `progress` `settleview[sid]` `plan` `status` `read` `scenes` `rules[page]`

### 3.3 调试（不建议放进界面）

`envprobe` `loaderprobe` `rawsend` `dbgcmd` `dbgstory` `dbgprofile` `settimeout(ms)` `failread(what)` `paypeak(op,key,n)` `fatigue` `packet(op)` `remind(text|op=off)` `getopts` `settings` `recent(n)` `ledger(n)` `pkpool` `pkpower` `help`

### 3.4 其它广播

| action | 方向 | 内容 |
|---|---|---|
| `io.github.juwanhwang.autoqqpet.JOBS` | QQ → App | `catalog`/`courses`/`adventures`（`JobCatalog.encode*`）、`at` |
| `io.github.juwanhwang.autoqqpet.STORE` | 双向 | 主存备份/恢复（`settings_json`/`daily_json`/`friends_json`/`ask`/`diag_json`/`work_catalog`）。`settings_json` 带 `__at`；模块 App 收到 QQ 的设置后同步进 `autoqqpet_ui`（R8） |
| `io.github.juwanhwang.autoqqpet.TICK` | QQ 进程内 | 闹钟 tick（`action=tick`、`src`、`gen`），不导出，见 §3 鉴权段 |

广播鉴权口径（v2.0.2）：合法发送方是模块 App 的接收端一律挂签名级权限（CMD、QQ 侧的 STORE 接收端）；发送方是 QQ 进程的接收端（STATUS/JOBS/主存 StoreReceiver）挂不了——QQ 是腾讯签名——保持导出，`from` extra 只作日志标记不作鉴权，载荷靠时间戳守卫判新旧。BootReceiver 挂签名级权限挡显式伪造触发，系统广播不受影响。
