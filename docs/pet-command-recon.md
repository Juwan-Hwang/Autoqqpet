# 宠物 OIDB 命令语义还原（静态路线，2026-10-13）

回答一个问题：**有没有办法完整搞清楚每个命令号是干什么的？** 有，且不需要 hook native。
工具在 `tools/pet_recon/`，产物在 `_scratch/pet_recon/`（不进仓）。

## 命令语义住在三层，各给不同的东西

| 层 | 位置 | 能给什么 | 局限 |
| --- | --- | --- | --- |
| 主 APK dex | `com.tencent.ergo.*`、`com.tencent.mobileqq.qqpet.*` | 命令名串与 **trpc 服务名 `trpc.qqone.gateway.Gateway.Sso_Xxx_Yyy` 同源出现**，包/类名未混淆 | 只覆盖宿主层用到的命令 |
| 运行时下发的页面包 | 真机 `files/ZRes/online/<hash>/1/res/<页面名>/<版本>/compUnzip/kuikly_dynamic.zip`，里面是**小 APK** | 页面业务代码；命令号以**整数**出现；UI 文案、日志、埋点名可读（`beginStory`、`em_pet_recall`、`queryPetPlayStatus`…） | 类名被 R8 改过；**只有打开过的页面才会下发到机器上** |
| `libfekit.so` | APK 内 native 库 | 一份命令名**白名单**（共 133 个，其中宠物号段 70 个） | 只是网关放行清单，**不是命令全集** |

已验证的量（QQ 9.3.75 + 本机已下发的 23 个页面包，全流水线实跑结果）：
共得到 **204 个** `0x9xxx` 命令号，其中 **174 个不在 libfekit 白名单里**（所以白名单肯定不是全集）；
分级：**A 级 43 条**（命令名与服务名同源）、B 2、**C 级 88 条**（≤ 2 跳走到发包点，已能定位到具体页面模块）、D 71（链上够不到）、N 2（跨页面通用的位模式噪声）。
白名单里 70 个宠物号段命令只有 **9 个**能对上 A/B 级证据。另有 28 个方法是只带拼名前缀 `"OidbSvcTrpcTcp.0x"` 的
**运行时拼串发包点**（命令名不在包里写死，所以靠整数号定位）。

## 对旧文档的更正（`_research/fekit-oidb-commands.md`）

1. ~~命令名字符串只存在于 `libfekit.so`~~ → 主 APK 的 dex 里有 1000+ 处完整命令名，原脚本只读了 `libfekit.so` 一个条目就下了全局结论。
2. ~~召回/领福袋完全看不到、要看得 hook native~~ → 召回逻辑在动态包 `pet_play.apk` 里，静态可读：
   UI 文案「确认要召回宠物吗？提前结束，无法获得奖励哦」、事件名 `hired_recall_btn / em_pet_recall / em_pet_confirm_recall`，
   其 ViewModel `a6.m1`（日志自称 `PlayHomePageModel`）里 `G()` 带 `0x975e` + 串 `beginStory`（=开工/开课/冒险的统一入口）。
   **命令号到语义的链路是静态可追的**，只是召回那一跳还没走完（见下面「还剩什么」）。
3. 新发现命令：`0x975c`（`queryPetPlayStatus`）、`0x9762`/`0x9763`（页面 `fetchFromNetwork`）等，都不在旧表里。

## 召回与到期结算（2026-10-14 真机抓包实锤）

```
pet_play.apk（户外/玩法页）
  a6.m1.m1(...)   → 弹窗 em_pet_recall_secondary_confirmation_popup，“若现在停止打工，将获得金币N。确认要召回宠物吗？”
  a6.m1.l0(Z)     → 日志 doInterruptSettle navigateResultPage=
  y5.d0.a(...)    → 写死 action=6000，拼请求对象 q5/e{1:storyId, 2:6000, 3:petId, 100:2}
  y5.d0.e(cmd,sub,…) → 日志 abort oidb request, no network, cmd[ → 交给页内发包器 w1/r.c(...)
  w1/r.c(...) → 按名字取宿主模块 "QQKuiklyPlatformApi"，调用方法名 "sendPbRequest"
                 → 宿主侧 QQKuiklyPlatformApi → VSNetworkHelper.sendRequest(VSBaseRequest)（richframework VS 网络栈）
```
⇒ **召回 = `OidbSvcTrpcTcp.0x9760_1`（Sso_PetOutdoor_DoAfterStoryInfo），`f2(action)=6000`**，与普通结算（f2=1000）同一条命令。
旧记录“0x9760 所有 action 都回 135004”现在有了合理解释：**当时故事不处于可召回状态**（需 hired/户外且 `STORY_STATUS detail#5 recallable=1`）。
验证做法：开工一个短岗位 → 等到 `recallable=1` → 手发 `0x9760_1` `f1=storyId,f2=6000,f3=petId,f100=2` → 看是否回到 idle 且发奖励。

**到期结算（两个数据点对比，别拿一个当结论）**：
- 19:50:56：45 秒冒险到期，**人就在玩法页** → 页面自己发了 `0x9760_1 {1:storyId, 2:1000, 3:petId, 4:0, 100:2}`；
- 19:33：600 秒打工到期，**人不在页面** → 没发包，但奖励依旧到账（清洁 21→31、综合 51.1→54.1）。
→ 两条路都通，所以自动流程**保留**到期结算（与客户端一致）；但“开新任务前顺手结算残留”已确认多余（带 state=0 残留也能直接开成功）。

页面流量走的是 **Kuikly 宿主模块桥 + VSNetwork 栈**：既不经过 `ProtoUtils`，也不经过 `qqpet.delegate.l`，也不在 NT 分发层——
这才是旧 PacketLogger 那 18 个 hook 点全部落空的真正原因（不是“只有 native 才看不见”）。
（修正：上一轮我写的“`s2/c` + `AISafeRequest` 是一条 OIDB 通道”是错的——`s2/c` 是日志辅助类，`AISafeRequest` 只是它的 log tag。）

## 可行的抓包点（已接进模块，真机已跑通）

钩宿主模块 `com.tencent.mobileqq.ntcompose.export.modules.QQKuiklyPlatformApi` 的
`call(String name, params, Function1 cb)`（实现类 `hook/KuiklyPacketLogger.kt`，开关 `packet op=on`）：
页面每一次发包都会打出 `[sendPbRequest 请求] 命令名,bin(长度)=hex`，响应在 `[回调入参]` 里（响应包 field1 就是命令号，能自动配对）。

已以此定性的例子（2026-10-14 好友宠物页）：
- `0x9d70_0` = 开/领好友金币袋（福袋）：参数 `{1:自己petId, 2:好友petId, 3:“6100-…”会话id, 11:2}`，回 errorCode 0；
  与模块方法名 `fetchCoinBagDetail` 对得上 → 升为 confirmed。同时挖出同族 `0x9955 openByCoinBagId`、`0x9ae7 shareCoinBag`。
- `0x9875_1` = 好友宠物页状态轮询（每 3s，回包带中文状态文案），不但是静态 A 级服务名 `Sso_PetOutdoor_GetOtherPetStatusInfo`，行为也对上了。
- 这两号（`0x9d70/0x9d71`）连 libfekit 白名单都没收录 —— 再次证明白名单不是全集。

## 怎么用（六条命令）

```powershell
cd "<仓库根>/tools/pet_recon"
python scan_apk.py     --apk "<QQ 安装包路径>/qq_9375.apk"   # 扫主 APK，出 main.tsv + whitelist.txt
python pull_bundles.py --extract                                  # 从真机拉宠物页面动态包（需 Root+adb）
python scan_bundles.py                                              # 扫动态包，出 bundles.tsv
python rollup.py       --known ..\..\docs\qqpet-protocol-spec.md    # 汇总成 cmd_map.tsv / cmd_map.md
python drill.py --cmd 0x975e                                        # 定位到某条命令：看哪个类/方法在用，连带它的文案
python capture.py on  /  off  /  peek --all                         # 真机抓包：开/关、把抓到的包按命令号解码（peek 默认只报新号）
```

依赖：Android SDK 的 `dexdump.exe`（自动从 PATH / `ANDROID_HOME\build-tools\*` 找，也可 `--dexdump` 指定）。
纯静态，不改 QQ、不改模块、不发包。

两个默认口径（不要随手改）：
- `scan_apk.py --pkg` 只留 `com.tencent.ergo.*` / `com.tencent.mobileqq.qqpet.*`。不筛类名会拿到 1500+ 假阳性：
  全 QQ 里**任何**一个 32 位常量只要位模式落在 `0x9000-0x9fff` 都会被当成命令号（dexdump 还把它打成 `#float`）。
- `rollup.py --range` 默认 `9000-9fff`（宠物号段）。

## 证据分级（rollup 自动打的标）

- **A**：同一方法里既有完整命令名又有唯一 trpc 服务名 → 可直接当结论。
- **B**：有完整命令名但服务名缺失/不唯一 → 号可信，功能名待补。
- **C**：只有整数命令号，但该方法确实调到发包点（或自己拼命令名）→ 位置可信，功能靠残留文案读。
- **D**：只在字节码里见到 `0x9xxx` 位模式 → **可能是巧合，必须人工核**（号段取整数一定有假阳性）。
- **N**：跳跨多个无关页面、又没有任何发包语汇 → 已自动判废（不要求人工）。

两个提高命中率的技巧（已写进代码）：
1. **跳数定级**：从发包点反向 BFS，≤ 2 跳才算 C；阈值用 `rollup.py --chain-hops` 调，先打印跳数分布再定。
2. **类级 KLog tag**（`class_tags` 列）：很多方法自身没文案，但所属类有 `PetJobTreeVM`、`PetPlayPropVM`、`CurrencyAccountDataSource` 这种名字，直接就能定功能。

## 还剩什么（下一步一起做）

当前总表：256 行（命令号 204 个），已定含义 163（实测 24 / 人工确认 52 / 人工倾向 58 / 静态A 5 / 判废 4），待填 93。

1. **提高覆盖**：`pull_bundles.py` 只能拉到机器上已下发过的页面。要「全量」，先在手机上把宠物每个页面点一遍
   （市集/背包/日记/勋章/等级/领养/装扮/喂食领取/充值/PK/职业小镇/学园…），再重跑第 2、3、4 条。
2. **消 D 级假阳性**：`rollup.py` 已自动把“跨≥ 8 个无关页面、且无任何发包语汇”的号归为 N 噪声；其余用 `drill.py --cmd 0x9xxx` 看方法、读文案。
3. **召回那一跳**：从 `pet_play.apk` 的 `a6.m1.m1(...)`（`em_pet_confirm_recall` 的回调）往下追到整数命令号，再真机验证。
4. **补名字**：A 级 43 条已全进总表；C 级 88 条里已有 46 条靠方法名定了性（剩下的 42 条靠 `drill.py --cmd 0x9xxx` 读文案）。
