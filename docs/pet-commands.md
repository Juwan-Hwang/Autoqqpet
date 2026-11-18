# QQ 宠物命令总表

> ⚠ **免责声明**：本表的命令含义由作者逆向整理，**不保证准确**；QQ 更新后随时可能失效或改变含义。
> **写命令（会改变账号状态/花金币的）切勿盲发**——先用只读命令和抓包确认，再小心试。
> 模块当前实际在用的命令见 [commands-in-use.md](commands-in-use.md)。

> 📌 **本文件最后一次生成于 2026-10-17**（静态扫描产物 `cmd_map.tsv` 在仓外，公开版不带，脚本缺它时会跳过、不覆盖本文件）。
> 2026-10-17 之后定性的命令（0x9bb1、0x9d9e、0x9bd5、0x9e9c、0x96a6 摸好友、0x9bd4、0x9762/0x9ba3 等）记在
> `tools/pet_recon/meanings.tsv` 末尾「2026-11 补充」段与 [commands-in-use.md](commands-in-use.md)，以那两处为准。

> 自动生成：`tools/pet_recon/make_command_table.py`（来源：静态扫描 `cmd_map.tsv` + libfekit 白名单 +
> `docs/qqpet-protocol-spec.md` + **人工确认层 `tools/pet_recon/meanings.tsv`**）。
> 想改含义/确信，**只改 meanings.tsv 再重跑脚本**；直接编辑本文件会被覆盖。

## 怎么填这张表

1. 在「待填」里按证据强度从上往下挑（C 级比 D 级靠谱）。
2. `python drill.py --cmd 0x9xxx`：看是哪个类、哪个方法在用，以及那行残留文案（UI 文案/日志/埋点名）。
3. 能定性的写进 `meanings.tsv`：`confirmed` 必须有依据（静态A/文档/实测），没把握先写 `likely` 或 `todo`。
4. 要真机确认的：只读命令可以用 `rawsend` 试探；**写命令一律不盲发**。

## 确信程度图例

| 标记 | 依据 |
| --- | --- |
| ★★★ 实测确认 | 真机跑过，状态变化可复核（在 meanings.tsv 记日期/机型/QQ 版本） |
| ★★★ 文档+静态A | 协议文档有配方（§3 表多数带真机验证记录），且命令名与服务名同源 |
| ★★ 人工确认 | 静态证据充分 + 人工读过代码，但没实测 |
| ★★ 文档配方 | 协议文档里有配方，但未标实测 |
| ★★ 静态A | 同一方法里「完整命令名 + Sso_ 服务名」成对出现 |
| ★ 静态B | 有完整命令名，服务名缺失或不唯一 |
| ★ 人工倾向 | 人工读过但还没定死 |
| ◇ 静态C | 确认在发包链上（≤2 跳走到拼名/发包点；阈值＝rollup --chain-hops），功能要靠页面残留文案读 |
| ○ 仅白名单 | 只在 libfekit.so 网关清单里出现，客户端代码里没找到调用 |
| ? 待核 | 不在发包链上，但方法里有网络相关文本，可能是真命令 |
| ✗ 疑似噪声 | 不在链上、也没有网络相关文本，几乎肯定是浮点常量位模式的巧合 |
| ✗ 已否掉 | meanings.tsv 里标了 rejected |

## 已定含义（191 条）

| 命令 | sub | 十进制 | trpc 服务名 | 含义 | 确信 | 依据 | 接入 | 读/写 | 线索 | 出处 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `0x9009` | ? | 36873 |  | 【容器】新手指引推荐加群 | ★ 人工倾向 | 静态D(page_craft_main.FreshmanGroupsService GetRecommendGroup/joinGroupAuth) |  |  | 回包解析为空 | page_craft_main ∥ 非宠物 |
| `0x9030` | ? | 36912 |  | 非命令：埋点参数名表（APP_ID/PAGE_ID/TRACKER） | ✗ 已否掉 | 静态D(page_craft_main) |  |  | — | page_craft_main ∥  |
| `0x9036` | ? | 36918 |  | 非命令：UI 组件里的浮点位模式巧合 | ✗ 已否掉 | 跨 4+ 个页面的 blurStyle/textStyle 方法 |  |  | 判废依据 无发包语汇且跨页面通用 | AILiteGYMainActivity, ai_pet_chat_long_press ∥ 判废依据：无发包语汇且跨页面通用 |
| `0x9040` | ? | 36928 |  | 【容器】上传保存（“正在上传/保存中”） | ★ 人工倾向 | 静态C(AILiteGY) |  |  | 正在上传 | AILiteGYMainActivity ∥ 非宠物 |
| `0x91b6` | ? | 37302 |  | 非命令：Canvas 渲染回调里的位模式巧合 | ✗ 已否掉 | 静态D(Canvas/drawCallbackEmpty，跳跨 3 个无关页) |  |  | — | ai_pet_chat_long_press, pet_home ∥  |
| `0x91c0` | ? | 37312 |  | 领养页 alpha 视频资源相关 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | 不像业务命令 | qq_pet_adopt ∥ 不像业务命令 |
| `0x91e0` | ? | 37344 |  | 【容器】发送表情/表态 | ★ 人工倾向 | 静态D(page_craft_main.SendEmotionService SSO失败/成功) |  |  | 解包失败 | page_craft_main ∥ 非宠物 |
| `0x9200` | ? | 37376 |  | 非命令：UI 组件里的浮点位模式巧合 | ✗ 已否掉 | 跨页面 UI 方法 |  |  | — | AILiteGYMainActivity, ai_pet_chat_long_press ∥  |
| `0x9377` | 0 ? | 37751 |  | **主页运营位/活动配置** | ★★★ 实测确认 | 抓包实测 2026-10-15 00:04:22.308 | 是 |  | 传输层失败 上次那个单号 运营位列表 与领礼包无关 | page_craft_main ∥ 请求 `{1:上次那个单号, 2:1, 5:"{}", 6:[…]}`；回包 `.4` 是一段 JSON `{"op": |
| `0x93ba` | ? | 37818 |  | 通用 OIDB 发送器本身（非具体命令） | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | 号从参数来 不是常量 | page_craft_main ∥ 号从参数来，不是常量 |
| `0x9414` | ? | 37908 |  | 分享信息 | ★ 人工倾向 | 静态C(pet_chat_immerse.fetchShareInfo) |  |  | fetchShareInfo fetchType | pet_chat_immerse ∥  |
| `0x9510` | ? | 38160 |  | 聊天沉浸页 DocArk 卡片请求 | ★ 人工倾向 | 静态C(pet_chat_immerse.requestDocArk success, ark:) |  |  | — | pet_chat_immerse ∥  |
| `0x9515` | ? | 38165 |  | 标签上报 | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | queryRules 传输层失败 传输成功但回包为空 | page_craft_main ∥  |
| `0x95bb` | ? | 38331 |  | 聊天沉浸页另一条 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | — | pet_chat_immerse ∥  |
| `0x95e1` | 0 | 38369 | Sso_PetCache_GetUserPet | 完整宠物对象（**不含三属性总量**） | ★★★ 实测确认 | 实测 2026-09-xx QQ9.3.65 | 是 |  | 魅力读不到 | Lcom/tencent/ergo/view/mainpage/l;.v ∥ GetUserPet；试过 f101=petId，力量/智力/魅力读不到 |
| `0x95e8` | ? | 38376 |  | 【容器】批量查询资格 | ★ 人工倾向 | 静态D(page_craft_main.BatchGetQualification) |  |  | 回包解析为空 调用异常 | page_craft_main ∥ 非宠物 |
| `0x95f3` | 0 | 38387 | Sso_PetResourceRead_GetResource | 读取宠物资源清单（装扮/场景配置） | ★★ 人工确认 | 静态A | 是 |  | 的序号做增量 | Lcom/tencent/ergo/view/mainpage/l;.s ∥ GetResource；配合 0x95f7 的序号做增量 |
| `0x95f7` | 0 | 38391 | Sso_PetResourceRead_GetSequence | 资源序列号/增量拉取 | ★★ 人工确认 | 静态A | 是 |  | — | Lcom/tencent/ergo/view/mainpage/l;.p ∥ GetSequence；参数 lastResourceHash |
| `0x9638` | ? | 38456 |  | 【容器】使用推广/扩散资格 | ★ 人工倾向 | 静态D(page_craft_main.UseActiveDiffusion “未开通 diffusionRes=”) |  |  | 回包解析为空 | page_craft_main ∥ 非宠物 |
| `0x96a4` | 1 | 38564 | Sso_PetBehavior_GetPageRules | 页面规则（服务端当日剩余次数 + 可执行路径） | ★★★ 实测确认 | 实测 2026-10-06 QQ9.3.65 | 是 | 读 | setUp 当日剩余次数 空则放行 就是服务端当日剩余次数 | Lcom/tencent/ergo/behavior/net/a;.b ∥ PAGE_RULES；trace.count=当日剩余次数；paths 空则放行 ｜真机: ✓ 可用：`trace={" |
| `0x96a5` | 1 | 38565 | Sso_PetBehavior_GetEventRule | 单个事件规则查询（能不能做、剩余次数） | ★★ 人工确认 | 静态A | 是 |  | fetchA PetBehaviorManager | Lcom/tencent/ergo/behavior/net/a;.a ∥ GetEventRule；与 0x96a4 同一套 behavior 模块 |
| `0x96a6` | 1 | 38566 | Sso_PetBehavior_ReportEvent | 上报行为（页面路径码已扩充） | ★★★ 实测确认 | 实测 2026-09-24 + 抓包实测 2026-10-14 19:31:12 | 是 | 写 | 勋章页进入上报 装扮页为 这些就是页面无操作时的门禁计数上报 | Lcom/tencent/ergo/behavior/net/a;.c ∥ 勋章页进入上报 path={11000, 80, 0}；装扮页为 {1000,100,101}——这些就是页面无操作时的 |
| `0x96ef` | ? | 38639 |  | 主页/好友页资源读取 | ★ 人工倾向 | 静态D(pet_home) + 抓包实测 2026-10-14 |  |  | 在好友页反复出现 | pet_home ∥ 在好友页反复出现 |
| `0x96f2` | 1 | 38642 | Sso_PetGrowth_GetDisPlayValue | 宠物三属性读数（**好友也能读**） | ★★★ 实测确认 | 抓包实测 2026-10-14 20:49:32 | 是 | 读 | 请求直接把 好友页想拿真实清洁 心情不需要另外找命令 这条就够 | Lcom/tencent/ergo/view/mainpage/l;.k ∥ 请求直接把 f1 传成**好友 petId**；回包 fixed 0000c842=100.0、00007842=60. |
| `0x9753` | 1 | 38739 | Sso_PetHeart_HeartBeat | 心跳/气泡文案查询（f1=-1 返回“你好呀/打工中”） | ★★★ 实测确认 | 实测 2026-10-13 QQ9.3.75 | 是 |  | 不是领福袋也不是召回 | Lcom/tencent/ergo/view/mainpage/l;.w ∥ HeartBeat；不是领福袋也不是召回 |
| `0x975a` | 1 | 38746 | Sso_PetOutdoor_GetPetStoryStatus | 户外任务状态（剩余/总时长/可召回） | ★★★ 实测确认 | 实测 2026-10-06 QQ9.3.65 + **抓包实测 2026-10-15 01:45 被雇佣场景** | 是 | 读 | 被雇时的实测形状 | Lcom/tencent/ergo/view/mainpage/l;.A ∥ STORY_STATUS；detail#2剩余 #3总秒 #5recallable；必须 f100=2。**被雇时的实测 |
| `0x975b` | 1 ? | 38747 |  | **到期收尾上报** | ★ 人工倾向 | 抓包实测 2026-10-14 19:33:12（与 0x9874 同秒） |  |  | fetchSe 同一时间戳 回包很短 | pet_play_end ∥ 请求{1:6000, 2:storyId, 3:petId, 4:同一时间戳, 100:2}；回包很短；f1=6000 是固定参数（不是“召回”专属），与 0x9874_1 成对 |
| `0x975c` | ? ? 或 1 | 38748 |  | 查询宠物“在玩”状态 | ★ 人工倾向 | 静态C(pet_play: queryPetPlayStatus send petId=) |  |  | 整数号出现 | pet_pk, pet_play ∥ 整数号出现，sub 未定 |
| `0x975e` | 1 | 38750 |  | 雇佣打工开工（雇主侧全链） | ★★★ 实测确认 | 抓包实测 2026-10-15 00:04:17 | 是 | 写 | startPlay PetAcademyVM AICommonModule 职业总览 | pet_academy, pet_career_town ∥ 完整链：`0x9b60`(职业总览) → `0x9ab2 {1:6400, f10=careerType=7, f100 |
| `0x975f` | 1 | 38751 |  | **进行中故事的通用进度轮询**（不只 PK） | ★★★ 实测确认 | 抓包实测 2026-10-14 19:16-19:17（打工中每 7s 一次，召回后停止） | 是 | 写 | 只是复用同一条 两个新用法 结算页的奖励条目会出现在本条响应里 的富文本 | pet_pk, pet_play ∥ 请求{1:storyId, 2:petId, 100:2}；旧定性“PK_STATUS”不准，PK 只是复用同一条。** |
| `0x9760` | 1 | 38752 | Sso_PetOutdoor_DoAfterStoryInfo | 结算（action 1000）/ **召回与提前结束、PK 结算（action 6000）** | ★★★ 实测确认 | 实测 2026-09-24 + 抓包 2026-10-14 19:17:51（召回 6000）、19:50:56（到期 1000）、20:43:28（PK 结束 6000）、**2026-10-15 00:14:14–17（雇主到期三发序列）** | 是 | 写 | 到期领奖 到期真实动作是三发 我们现在只发一发 可考虑照抄 | Lcom/tencent/ergo/view/mainpage/l;.g ∥ 召回/PK 结算={1:storyId, 2:6000, 3:petId, 4:0, 100:2}；到期领奖={1:st |
| `0x9762` | 0 | 38754 |  | 日记按月分页拉取 | ★★★ 实测确认 | 抓包实测 2026-10-14 20:26:23 | 是 |  | fetchFromNetwork 响应为空 正在写日记呢 稍等一下哦 | pet_calendar, pet_diary ∥ 请求{1:petId, 3:“2026-09”}；回包按日给 {1:“2026-09-23”, 2:[日记uuid…]} |
| `0x9763` | 0 | 38755 |  | 日记按日取详情 | ★★★ 实测确认 | 抓包实测 2026-10-14 20:26:23 | 是 |  | fetchFromNetwork 响应为空 | pet_diary ∥ 请求{1:petId, 3:“2026-10-01”}——与 0x9762 同族，f3 传“年-月-日” |
| `0x9764` | 0 | 38756 | Sso_calendar_GetCalendarRed | 日历红点查询（**两个返回字段含义未知；已不是火花候选**） | ★ 人工倾向 | 抓包实测 2026-10-15 01:15 / 01:22（rawsend） |  |  | 火花已确认另有专属命令 里面直接给天数 而本条请求只带自己 界面也没任何地方显示 | Lcom/tencent/ergo/view/mainpage/l;.i ∥ 回 `{.2: 1791820800（按+8 为 2026-10-13 00:00）, .4: 6}`。**火花已确认另 |
| `0x9765` | 0 | 38757 | Sso_calendar_ReadCalendarRed | 日历红点读取 | ★ 人工倾向 | 静态A |  |  | MainPageViewModel | Lcom/tencent/ergo/view/mainpage/MainPageView ∥ ReadCalendarRed |
| `0x976c` | 0 | 38764 | Sso_QQpet_GetOtherUserPet | 好友宠物数值 | ★★★ 实测确认 | 实测 2026-10-06 QQ9.3.65 | 是 | 读 | getFriendPet UserPetManager 需递归找 | Lcom/tencent/ergo/user/UserPetManager;.p ∥ FRIEND_PROFILE；body f1=好友UIN 不是 petId ｜真机: ✓ body `f1=`**好友  |
| `0x9777` | 1 | 38775 | Sso_QQpet_AdoptPet | 领养宠物 | ★★ 人工确认 | 静态A | 是 |  | 候选名单 | Lcom/tencent/ergo/view/mainpage/l;.e ∥ AdoptPet；配合 0x9779 候选名单 |
| `0x9778` | 1 | 38776 | Sso_PetAccess_CheckUserInGray | 灰度准入检查 | ★★ 人工确认 | 静态A | 是 |  | — | Lcom/tencent/ergo/view/mainpage/l;.f ∥ CheckUserInGray |
| `0x9779` | 1 | 38777 | Sso_QQpet_GetCandidatePets | 候选宠物名单 | ★★ 人工确认 | 静态A | 是 |  | 领养界面用 | Lcom/tencent/ergo/view/mainpage/l;.j ∥ GetCandidatePets；领养界面用 |
| `0x97ac` | 1 | 38828 | Sso_PetGame_SetC2CPetSwitch | 设置桌宠 C2C 开关 | ★★ 人工确认 | 静态A | 是 |  | setC2CPetSwitchToServer | Lcom/tencent/ergo/api/a;.c ∥ SetC2CPetSwitch；日志 setC2CPetSwitchToServer friendUin/isOpen |
| `0x97ba` | 0 | 38842 | Sso_PetAccess_GetUpgradeConfig | 升级/等级配置 GetUpgradeConfig | ★ 人工倾向 | 主动发时空响应（参数不对） |  |  | 不能当作否定 需要正确的 | Lcom/tencent/ergo/view/mainpage/l;.u ∥ 不能当作否定；需要正确的 body 再试 |
| `0x985b` | 0 | 39003 | Sso_PetRelation_Like | 踩踩/戳一戳 | ★★★ 实测确认 | 实测 2026-09-24 QQ9.3.65 | 是 | 写 | — | Lcom/tencent/ergo/view/mainpage/l;.E ∥ FRIEND_POKE；重复报 136202 属正常 |
| `0x985c` | 0 | 39004 | Sso_PetRelation_QueryLikeCount | **踩踩次数查询 QueryLikeCount** | ★★★ 实测确认 | 抓包实测 2026-10-15 01:15 / 01:22（rawsend） | 是 |  | queryLikeCou 是字符串不是整数 用户确认 累计被踩 | Lcom/tencent/ergo/view/mainpage/l;.z ∥ 回包 `.1 = “312”`——**是字符串不是整数**；**用户确认：312 = 累计被踩（获得踩踩）的总次数**， |
| `0x985d` | 0 1 | 39005 |  | **底部好友栏数据源（福袋标记就在这里）** | ★★★ 实测确认 | 实测 2026-09-24（静态名 PK_FRIEND_LIST）+ **抓包实测 2026-10-15 02:06:39 / 02:08:35 / 02:11:37 / 02:11:54** | 是 | 读 | queryLevelLBSList 请求带分页 每条记录与 宠物昵名 | pet_career_town, pet_friend ∥ 请求带分页 `{sid, offset}`；每条记录与 `0x9b87` 同族（`.1.1` 宠物昵名、`.1.5` u |
| `0x985e` | 0 | 39006 |  | **踩踩页面：被踩/获赞明细列表（内含“火花是否点亮”与每好友宠物的可雇佣状态）** | ★★★ 实测确认 | 静态C(pet_like_list.fetchLikeList) + **抓包实测 2026-10-15 01:28:01（完整 16672 字节，13 条记录，与界面逐项对账）** | 是 |  | fetchLikeList 总获得次数 总共获得 今日新增 | pet_like_list ∥ 顶层：`.4.3`=总获得次数（实测“312”=界面“总共获得 312”）、`.4.4`=今日新增（“1”）、`.4.5；**sub=0**（_1 回 150，2026-10-16） |
| `0x9874` | 1 ? | 39028 |  | **到期结算展示页（含奖励明细）** | ★★★ 实测确认 | 抓包实测 2026-10-14 19:33:12（打工 600s 到期那一秒）SM-S9380 QQ9.3.75；2026-10-15 00:14:17 再过一次 | 是 |  | fetchSettlement PetSettlementVM 结算时间戳 带叙事文案 | pet_play_end ∥ 请求{1:petId, 2:storyId, 3:结算时间戳ms, 4:2, 100:2}；回包 .4.1 带叙事文案；**2026-10-16：传任意 storyId 读单条详情**：.1.8 结果枚举(=.15.4；学习 1=A+ 2=A 3=B+，PK 4=胜 6=负，7 未解)、.1.3.1 属性奖励 {2:名,3:值,7:同值}、.1.10 开始秒、.1.11 场景、.1.13 课程 id、.1.15.12 课程分钟、.1.9 叙事流水、.1.12 PK 每日上限文案 |
| `0x9875` | 1 | 39029 | Sso_PetOutdoor_GetOtherPetStatusInfo | **好友宠物状态 + 能不能雇佣的真值** | ★★★ 实测确认 | 抓包实测 2026-10-14 20:49:24（好友页轮询，每 3s） | 是 |  | AICommonModule PetGameplayVM 每日被雇佣上限 对方宠物今天被雇佣次数已达上限 | Lcom/tencent/ergo/view/mainpage/l;.y ∥ 请求{1:好友petId, 2:好友uin, 3:自己petId, 4:1, 100:2}；回包 f1.1=20（每日被 |
| `0x9876` | 1 | 39030 |  | **《出门记录》历史流水（学习/打工/冒险/PK）** | ★★★ 实测确认 | 抓包实测 2026-10-14 20:26 + 2026-10-16 分页/字段核对（meanings.tsv 三条旧行已合并） | 是 |  | 请求{[1:游标 ms], 2:20, 3:petId, 100:2} | 每条 {1:storyId, 3:标题, 4:结果枚举, 5:叙事, 8:2}；翻页 f1 传上一页最后一条时间戳 ms；v2.1.0 起模块用于外部任务识别/写后核对/今日对账 |
| `0x989b` | ? | 39067 |  | 曝光/详情页进入埋点 | ★ 人工倾向 | 静态C(AILiteGY.reportExposure,reportEnterDetailPage) |  |  | reportExposure reportEnterDetailPage AILiteBrowserListItemViewModel | AILiteGYMainActivity ∥ 埋点，非业务 |
| `0x992d` | 1 | 39213 | Sso_PetFeed_Feeding | **喂食（自身/好友同一条，好友向多带 uin+昵称+petId）** | ★★★ 实测确认 | 实测 2026-09-24（自身）+ 抓包实测 2026-10-14 20:49:30（好友向） | 是 | 写 | MainPageViewModel 好友向请求 客户端自己传的是零宽占位符 不必真实 | Lcom/tencent/ergo/view/mainpage/MainPageView ∥ 好友向请求{1:好友uin, 2:昵称（客户端自己传的是零宽占位符，**不必真实**）, 4:好友petId, 5:0, |
| `0x9945` | ? | 39237 |  | 【容器】应用/内容信息设置 | ★ 人工倾向 | 静态C(AILiteBrowserTopBarViewModel requestSetInfo) |  |  | AILiteBrowserTopBarViewModel 应用名称不能为空 应用名称不能超过 应用描述不能超过 | AILiteGYMainActivity ∥ 非宠物 |
| `0x9949` | 1 | 39241 | Sso_PetFeed_GetFeedTimesInfo | 自己的食物库存/目录（好友页也会顺带读） | ★★★ 实测确认 | 抓包实测 2026-10-14 23:49:31；2026-10-15 01:07 读出《饼干(id=1)=40, 虾仁(id=3)=9》，01:10 变为 39（用户吃掉 1 个） | 是 | 读 | fetchFeedTimes 回包形如 当前目录里只有两项 但用户提醒 | Lcom/tencent/ergo/view/mainpage/l;.l ∥ 回包形如 `{9, 99, “饼干”}`；**当前目录里只有两项：id=1 饼干、id=3 虾仁**，但用户提醒：**过 |
| `0x994a` | 1 | 39242 |  | → 已改判为消息/通知中心，详见下方 01:46 那条（旧记“updateSeq”作废） | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | updateSeq | Lcom/tencent/mobileqq/qqpet/delegate/QQPetRo ∥  |
| `0x994b` | 1 | 39243 | Sso_PetFeed_GetRedDot | 喂食红点查询 | ★★ 人工确认 | 静态A | 是 |  | — | Lcom/tencent/ergo/view/mainpage/l;.h ∥ GetRedDot(Feed) |
| `0x994c` | 1 | 39244 | Sso_PetFeed_ReportRedDot | **消息已读上报（ReportRedDot）** | ★★★ 实测确认 | 静态A + 抓包实测 2026-10-15 01:46:36（紧跟 0x994a 之后） | 是 |  | reportConsumeReminderBubble 通知时间戳 把刚才那条通知标已读 第二个字段用的就是 | Lcom/tencent/ergo/view/mainpage/l;.C, Lcom/t ∥ 请求 `{4.1.1: 3, 4.1.2: 通知时间戳ms}` → 就是“把刚才那条通知标已读”，第二个字段用的就是 0 |
| `0x994d` | ? | 39245 |  | 喂食历史分页 | ★ 人工倾向 | 静态C(pet_feed_history.loadHistoryPage) |  |  | loadHistoryPage PetChatHistoryRepository | pet_feed_history ∥  |
| `0x9955` | 1 | 39253 |  | **进主页自动发的一条上报（与福袋无关）** | ★★★ 实测确认 | 抓包实测 2026-10-17 01:08（没袋）与 02:10（有袋） | 是 |  | 请求 `{1:自己petId}`；**两种情况下响应 body 都是空**（656 字节全在 envelope 扩展里）→ “它是自己福袋查询”的定名已于 2026-10-17 02:24 否证（recon 19970ca）。自己的袋 id 来自 `0x9acb_0` 响应 body `.1.2.1`；拆自己的袋走 0x9d70_0、f2 传自己 petId，与领好友袋同一条命令 | pet_home ∥  |
| `0x9acb` | 0 | 39627 |  | **事件消息聚合（自己的福袋 id 从这条来）** | ★★★ 实测确认 | 抓包定案 2026-10-17 02:10–02:23（recon 19970ca） | 是 |  | 请求 `{1:自己petId, 2:11 个 msgTypes 打包 [01,03,04,05,08,09,0b,0f,0a,0e,0d]}`（另有只带单个 type 的形态）；**自己的袋 id 在响应 body `.1.2.1`**（完整三段，如 `6400-6ad25f79-…`）——袋未领时每次进主页都报、领完即无，可当“当前有没有自己的袋”的权威查询，不用记账；一次最多一个袋。好友的袋也会在此流出现（另有 0x985d `.21` 这个更稳的源）。自动化链：读本条取 id → 0x9d71_0 查详情 → 0x9d70_0 领取（f2 传自己 petId） | pet_home ∥ 参数 msgTypes |
| `0x9956` | ? | 39254 |  | **抽奖/每日福袋入口** | ★ 人工倾向 | 静态D(pet_home：em_pet_lottery_activity、consumeNativeDailyB…、entry_id) |  |  | AICommonModule 旧文档说 领福袋看不到 这里就是候选 | pet_home ∥ 旧文档说“领福袋看不到”，这里就是候选 |
| `0x9959` | 1 | 39257 | Sso_PetHome_GetResource | 主页资源/配置读取 | ★★ 人工确认 | 静态A | 是 |  | — | Lcom/tencent/ergo/view/mainpage/l;.q ∥ PetHome_GetResource |
| `0x99c1` | ? | 39361 |  | 拍一拍好友宠物 | ★ 人工倾向 | 静态D(pet_home.sendPatFriendPet) |  |  | sendPatFriendPet AICommonModule PatBubbleViewModel 踩踩不同 | pet_home ∥ 与 0x985b 踩踩不同 |
| `0x99c2` | 0 ? | 39362 |  | 一键照顾配置首次拉取 | ★★★ 实测确认 | 抓包实测 2026-10-14 20:08:24 + 模块源码 PetFeedViewModel/PetOutDoorVM.fetchOneClickCareConfigFirst | 是 |  | fetchOneClickCareConfigFirst PetOutDoorVM 带首次去重 同族两条 | pet_home ∥ 日志“fetchOneClickCareConfigFirst: isConfigCall=first / , skip |
| `0x99c3` | ? | 39363 |  | 技能列表/技能详情数据加载 | ★★ 人工确认 | 静态C(pet_skills_list.loadPageData、PetSkillDetailPage) | 是 |  | KRNotifyModule | pet_skill_detail, pet_skills_list ∥  |
| `0x99c7` | ? | 39367 |  | 【容器】内容发布（带 BGM） | ★ 人工倾向 | 静态C([PublishReq] contentId=, bgmId=, ComicBgm) |  |  | — | AILiteGYMainActivity ∥ 非宠物 |
| `0x99c8` | ? | 39368 |  | 【容器】分享配置缓存 | ★ 人工倾向 | 静态C(AILiteShareConfigMgr restoreFromMMKV) |  |  | KRRouterModule AILiteBrowserViewModel AILiteDataManager | AILiteGYMainActivity ∥ 非宠物 |
| `0x99c9` | ? | 39369 |  | 【容器】推荐内容流 | ★ 人工倾向 | 静态C(fetchRecommendFeed) |  |  | fetchRecommendFeed AILiteDataManager | AILiteGYMainActivity ∥ 非宠物 |
| `0x99ca` | ? | 39370 |  | 【容器】详情分页/主页档案 | ★ 人工倾向 | 静态C(loadMoreContentDetails,fetchHomeProfile) |  |  | loadMoreContentDetails fetchHomeProfile AILiteBrowserViewModel | AILiteGYMainActivity ∥ 非宠物 |
| `0x99ce` | ? | 39374 |  | 【容器】白名单检查 | ★ 人工倾向 | 静态D(page_craft_main.WhitelistService.Check) |  |  | 读取客户端版本号失败 | page_craft_main ∥ 非宠物 |
| `0x99de` | ? | 39390 |  | 免费领取食物（领取确认） | ★ 人工倾向 | 静态C(qq_pet_feed_claim：em_pet_accept、biscuit_num、pg_pet_feed) |  |  | 网络异常 请检查网络设置后重试 买饼干不同 | qq_pet_feed_claim ∥ 与 0x99df 买饼干不同 |
| `0x99df` | 1 | 39391 |  | 买食物（**永远买给自己的库存**） | ★★★ 实测确认 | 抓包实测 2026-10-14 23:52:59（id=1）与 23:53:03（id=3），23:53:30/37 在好友页重复 | 是 | 写 | 界面上有 两种食物 默认批量是 用户没改数量 | qq_pet_feed_purchase ∥ 请求{1:数量, 2:**自己petId**, 3:食物id}；界面上有**两种食物（id=1 与 id=3）**；默认 |
| `0x99e8` | 1 | 39400 | Sso_PetResourceRead_RemoveOneSuit | RemoveOneSuit（服务名直读） | ★★ 静态A | 同一方法里命令名 + trpc 服务名成对出现 |  |  | PetStoreViewModel | Lcom/tencent/ergo/view/mainpage/store/viewmo ∥  |
| `0x99e9` | 1 | 39401 | Sso_PetResourceRead_SaveOneSuit | 保存一套穿搭（旧猜测） | ✗ 已否掉 | 静态A(SaveOneSuit) |  |  | saveOneSuit PetStoreViewModel 换装实测 全程未出现此号 | Lcom/tencent/ergo/view/mainpage/store/viewmo ∥ **换装实测（19:29）全程未出现此号**；模块源码里真正的保存套装是 `0x9b6a saveOneSuit`（日志 |
| `0x99ea` | 1 | 39402 | Sso_PetResourceRead_GetMySuits | 我的套装列表 | ★ 人工倾向 | 静态A |  |  | PetStoreViewModel | Lcom/tencent/ergo/view/mainpage/store/viewmo ∥ GetMySuits |
| `0x99eb` | 1 | 39403 | Sso_PetResourceRead_GetRedDot | **资源红点查询 GetRedDot** | ★★★ 实测确认 | 抓包实测 2026-10-15 01:15（rawsend） | 是 |  | PetStoreViewModel 哪些装扮 资源有新内容 | Lcom/tencent/ergo/view/mainpage/store/viewmo ∥ 回包 85 字节，`f1` = repeated 资源 id（如 08950a/08e907）→ 就是“哪些装扮/资源有 |
| `0x99ec` | 1 | 39404 | Sso_PetResourceRead_ReportRedDot | 资源红点上报 | ★ 人工倾向 | 静态A |  |  | reportRedDot PetStoreViewModel | Lcom/tencent/ergo/view/mainpage/store/viewmo ∥ ReportRedDot |
| `0x99ed` | 1 | 39405 | Sso_PetResourceRead_GetClothing | 服装列表 | ★ 人工倾向 | 静态A |  |  | getClothing PetStoreViewModel | Lcom/tencent/ergo/view/mainpage/store/viewmo ∥ GetClothing |
| `0x99ee` | ? | 39406 |  | 删除喂食/聊天记录 | ★ 人工倾向 | 静态D(pet_feed_history：PetChatHistoryRepository deleteRecord) |  |  | PetChatHistoryRepository | pet_feed_history ∥ 写操作 |
| `0x99ef` | 1 | 39407 | Sso_PetFeed_PushFeedMsg | **代喂后“我喂了你宠物”推送（Sso_PetFeed_PushFeedMsg）** | ★★★ 实测确认 | 静态坐实 + 三个真机样本（10-14 20:49:32、23:44:47，10-15 00:21:04） |  |  | MainPageViewModel 客户端传的就是零宽占位符 同批出现 本条更像 | Lcom/tencent/ergo/view/mainpage/MainPageView ∥ 请求{1:好友uin串, 2:零宽昵称, 4:1, 5:0, 6:"1"}；喂好友后约 2 秒页面自动发；v2.0.0 由 feed_push_on 控制补发。与 0 |
| `0x99f1` | 1 | 39409 | Sso_QQpet_UpdateProfile | 写回宠物资料（含新头像 URL 与签名） | ★★★ 实测确认 | 抓包实测 2026-10-14 19:29:34 | 是 |  | 一段签名串 | Lcom/tencent/ergo/view/mainpage/l;.F ∥ 请求 .4.2.3/.4.2.18=qqpet/head/… 图片 URL，.4.4=一段签名串 |
| `0x99f2` | 1 | 39410 | Sso_QQpet_GetProfile | 本人宠物资料（取 petId） | ★★★ 实测确认 | 实测 2026-09-24 三星SM-S9380 QQ9.3.65 | 是 | 读 | 解出前缀 | Lcom/tencent/ergo/view/mainpage/l;.o ∥ OWN_PROFILE；resp f8=petId(base64，解出前缀 {uin}-) |
| `0x99f3` | 1 | 39411 | Sso_QQpet_SaveAvatar | 合成并保存宠物形象（换装后） | ★★★ 实测确认 | 抓包实测 2026-10-14 19:29:31 | 是 |  | UserPetManager 只存头像 请求带各部件 旧服务名 | Lcom/tencent/ergo/user/UserPetManager;.E ∥ 不是“只存头像”：请求带各部件 id（5030180/9000603/2010452）与昵称，旧服务名 SaveAvat |
| `0x99f4` | 1 | 39412 |  | 宠物开关设置读取 | ★★ 人工确认 | 静态A + 设置页模块(pet_settings_main: switchInfo/patPush) | 是 |  | PetSettingManager 请先领养 宠物才能设置开关 两处独立证据对上 | Lcom/tencent/mobileqq/qqpet/setting/PetSetti ∥ PetSettingManager；两处独立证据对上 |
| `0x99f5` | 1 | 39413 |  | 设置写回 | ★★★ 实测确认 | 抓包实测 2026-10-14 20:27:22（切开关那一刻） | 是 |  | PetSettingManager 请求体极短 只有一个开关位 | Lcom/tencent/mobileqq/qqpet/setting/PetSetti ∥ 请求体极短（只有一个开关位），回包 .4.1.1=1；与 0x99f4（读）成对 |
| `0x9a02` | 1 | 39426 | Sso_PetGame_GetC2CPetInfo | 读桌宠 C2C 信息 | ★★ 人工确认 | 静态A | 是 |  | FullScreenPetInfoModel C2CPetInfoModel | Lcom/tencent/ergo/c2c/viewmodel/C2CPetInfoMo ∥ GetC2CPetInfo |
| `0x9a03` | 1 | 39427 | Sso_PetGame_SetC2CPetInfo | 写桌宠 C2C 信息 | ★★ 人工确认 | 静态A | 是 |  | setRemote FullScreenPetInfoModel C2CPetInfoModel | Lcom/tencent/ergo/c2c/viewmodel/C2CPetInfoMo ∥ SetC2CPetInfo |
| `0x9a04` | 1 | 39428 | Sso_MediaUpload_Upload | 媒体上传（生成好的头像图） | ★ 人工倾向 | 静态A |  |  | 非宠物专有网关也有同名 | Lcom/tencent/ergo/view/mainpage/l;.G ∥ MediaUpload_Upload；非宠物专有网关也有同名 |
| `0x9a12` | ? | 39442 |  | 发送点赞 | ★ 人工倾向 | 静态C(AILiteGY.sendLike) |  |  | sendLike AILiteActionManager | AILiteGYMainActivity ∥  |
| `0x9a13` | ? | 39443 |  | 【容器】底栏弹窗确定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | AILiteBrowserTopBarViewModel GYComposePlatformModule KRPhotoModule | AILiteGYMainActivity ∥ 非宠物 |
| `0x9a17` | ? | 39447 |  | 【容器】兴趣标签/反馈面板 | ★ 人工倾向 | 静态C(AILiteInterestTagApi resetShownFlag type=299) |  |  | — | AILiteGYMainActivity ∥ 非宠物 |
| `0x9a3f` | ? | 39487 |  | 音色列表 | ★★ 人工确认 | 静态C(qq_pet_voice_select.fetchVoiceList) | 是 |  | fetchVoiceList AIPttModule 网络异常 请检查网络设置后重试 | qq_pet_voice_select ∥ 语音选择页 |
| `0x9a50` | ? | 39504 |  | 【容器】待定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | — | AILiteGYMainActivity ∥ 无文案 |
| `0x9a51` | ? | 39505 |  | 【容器】待定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | — | AILiteGYMainActivity ∥ 无文案 |
| `0x9a52` | ? | 39506 |  | 【容器】待定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | — | AILiteGYMainActivity ∥ 无文案 |
| `0x9a53` | ? | 39507 |  | 【容器】待定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | — | AILiteGYMainActivity ∥ 无文案 |
| `0x9a54` | ? | 39508 |  | 【容器】待定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | — | AILiteGYMainActivity ∥ 无文案 |
| `0x9a55` | ? | 39509 |  | 【容器】待定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | — | AILiteGYMainActivity ∥ 无文案 |
| `0x9a56` | ? | 39510 |  | 【容器】待定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | AILiteCreditManager | AILiteGYMainActivity ∥ 无文案 |
| `0x9a94` | 1 | 39572 | Sso_PetHome_SetDeviceInfo | SetDeviceInfo（服务名直读） | ★★ 静态A | 同一方法里命令名 + trpc 服务名成对出现 |  |  | setDeviceInfo | Lcom/tencent/ergo/device/PetDeviceRemoteData ∥  |
| `0x9ab2` | 1 | 39602 |  | 场景目录（6100 课程 / 6400 岗位 / 6700 冒险 / **6900 PK**） | ★★★ 实测确认 | 实测 2026-09-24 + 抓包实测 2026-10-14 19:48:49（冒险）与 20:42:47.98/20:43:01.51（6900） | 是 | 读 | PetAcademyVM 冒险目录要 才回全量 附近走走 | pet_academy, pet_career_town ∥ **冒险目录要 f100=3 才回全量**（同时给“附近走走 45秒”与“诗和远方 2小时”）；我们原用 2 只拿 1  |
| `0x9abe` | 0 ? | 39614 |  | **每日经验/任务总览（经验值的唯一读数入口）** | ★★★ 实测确认 | 抓包实测 2026-10-15 00:27:27（界面 50/120 逐字对上） | 是 |  | loadDailyTasks 今日已获得经验 今日上限 含义待核 | pet_level_task ∥ 回包：`f2=今日已获得经验(50)`、`f3=今日上限(120)`、`f1=1.2 float(含义待核)`、`f4` |
| `0x9ac0` | ? | 39616 |  | 主页弹窗列表 | ★★ 人工确认 | 静态C(pet_home.fetchPopups) | 是 |  | fetchPopups AICommonModule KRNotifyModule | pet_home ∥  |
| `0x9ac1` | ? | 39617 |  | 弹窗已读回执 | ★★ 人工确认 | 静态D(pet_home：[EGPopup] ackPopup、事件 ai_pet_popup_consumed) | 是 |  | KRNotifyModule | pet_home ∥ 参数 petId/ids |
| `0x9ac3` | ? 或 0 | 39619 |  | 勋章墙列表 | ★★★ 实测确认 | 抓包实测 2026-10-14 19:31:14 SM-S9380 QQ9.3.75 | 是 |  | HRCacheModule 回包逐项带勋章 达成描述 宠友星探员 | pet_medal_gallery ∥ 实抓 sub=0；回包逐项带勋章 id/名称/达成描述（如 21286 宠友星探员） |
| `0x9ac5` | ? ? 或 0 | 39621 |  | 佩戴勋章 | ★★ 人工确认 | 静态C(pet_medal_detail：pet_medal_equip_changed、文案“勋章已佩戴到头像”) | 是 |  | KRNotifyModule | pet_medal_detail ∥ 参数 medal_id/pet_id/is_equipped/icon_url |
| `0x9ac6` | ? 或 0 | 39622 |  | 勋章详情 | ★★★ 实测确认 | 抓包实测 2026-10-14 19:31:17；静态名 pet_medal_detail.loadDetail | 是 |  | loadDetail 回包给名称 达成条件 描述文案 | pet_medal_detail ∥ 请求{1:petId, 2:medalId(21286)}；回包给名称/达成条件/分类/图标URL/描述文案/获得时间 |
| `0x9ac8` | ? ? 或 0 | 39624 |  | 取消佩戴勋章 | ★★ 人工确认 | 静态C(pet_medal_detail：文案“已取消佩戴”) | 是 |  | KRNotifyModule | pet_medal_detail ∥ 与 0x9ac5 成对 |
| `0x9acb` | ? | 39627 |  | 宠物状态/生病消息拉取（**实测它也带福袋id**） | ★★★ 实测确认 | 静态D(pet_home：日志写了 “[PetSick][Fetch] sending 0x9acb, petId=, msgTypes=”) + 抓包实测 2026-10-15 02:06:40 | 是 |  | 字节响应里含好友B的袋 本条不只是生病消息 事件消息 | pet_home ∥ 参数 msgTypes；⚠ 02:06:40 那条 1991 字节响应里含好友B的袋id → 本条不只是生病消息，可能是“ |
| `0x9ad4` | 1 | 39636 |  | 战力（仅自身 f4 可信） | ★★★ 实测确认 | 实测 2026-10-06 QQ9.3.65 | 是 | 读 | 好友响应字段含义已变 主导属性类型 三属性总量的可用替代 | pet_pk_power ∥ PK_POWER；好友响应字段含义已变 ｜真机: ✓ `战力=5671 主导属性类型=3`（自身带 #4/#3）——三属 |
| `0x9ad7` | ? | 39639 |  | 【容器】好友推荐列表 | ★ 人工倾向 | 静态D(page_craft_main.FriendRecTabSvr.Get) |  |  | 回包解析为空 | page_craft_main ∥ 非宠物 |
| `0x9ae6` | ? 或 0 | 39654 |  | **好友列表**（昵称+头像） | ★★★ 实测确认 | 抓包实测 2026-10-14 19:50:41 SM-S9380 QQ9.3.75 | 是 |  | 请求体几乎空 好友页请求 到此定名 | pet_friend ∥ 请求体几乎空；回包 repeated {1:uin, 2:昵称, 3:qlogo头像URL, 5:1}——旧行“好友页请 |
| `0x9ae7` | ? | 39655 |  | 邀请好友养宠 | ★★ 人工确认 | 静态D(pet_friend：InviteTabViewModel inviteFriend start / already invited today) | 是 |  | InviteTabViewModel 每天一次 重复会被客户端自己拦 | pet_friend, pet_home ∥ 每天一次，重复会被客户端自己拦 |
| `0x9ae8` | 1 ? | 39656 |  | 好友页请求（同一封装类的第三个方法） | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | 同一封装类 需抓包区分 | pet_friend ∥ 与 0x9ae6/0x9ae7 同一封装类，需抓包区分 |
| `0x9b04` | ? | 39684 |  | 收藏 | ★ 人工倾向 | 静态C(AILiteGY.sendFavorite) |  |  | sendFavorite AILiteActionManager | AILiteGYMainActivity ∥  |
| `0x9b1a` | ? | 39706 |  | 批量上报/批量发送 | ★ 人工倾向 | 静态C(AILiteGY.sendBatch) |  |  | sendBatch | AILiteGYMainActivity ∥  |
| `0x9b33` | ? | 39731 |  | 【容器】学校列表 | ★ 人工倾向 | 静态D(page_craft_main.School.List) |  |  | 回包解析为空 业务失败 天选页选大学 | page_craft_main ∥ 非宠物（天选页选大学） |
| `0x9b34` | ? | 39732 |  | 【容器】清空学校选择 | ★ 人工倾向 | 静态D(page_craft_main.School.Clear) |  |  | 回包解析为空 业务失败 | page_craft_main ∥ 非宠物 |
| `0x9b47` | 0 1 ? | 39751 |  | **我的 QQ 群列表** | ★★★ 实测确认 | 抓包实测 2026-10-14 21:01:04 | 是 |  | 请求体空 把宠物授权到哪个群 的群选择器 不是宠物玩法 | pet_friend ∥ 请求体空；回包 {群号, 群名, 人数}——就是“把宠物授权到哪个群”的群选择器；属QQ 群体系，不是宠物玩法 |
| `0x9b48` | 0 1 ? | 39752 |  | **按群号查群名与成员** | ★★★ 实测确认 | 抓包实测 2026-10-14 21:04:45 | 是 |  | 回包带群名与成员列表 | pet_friend ∥ 请求{1:群号(varint)}；回包带群名与成员列表 |
| `0x9b49` | ? | 39753 |  | 设置群宠物授权 | ★★ 人工确认 | 静态D(pet_friend：GroupTabViewModel setPetGroupAuth groupCode=, target=) | 是 |  | setPetGroupAuth GroupTabViewModel 网络异常 请检查网络设置后重试 | pet_friend ∥ 写操作 |
| `0x9b5a` | 1 | 39770 |  | 资源/配置拉取 | ★ 人工倾向 | 抓包实测 2026-10-14 19:29:22 |  |  | getClothing getCl 不是玩法命令 | pet_home ∥ 请求{1:QQ版本号, 2:1000006(appid), 4:petId, 6:1}，不是玩法命令 |
| `0x9b5e` | 0 ? | 39774 |  | 买食物前的前置查询 | ★★★ 实测确认 | 抓包实测 2026-10-14 23:52:58、23:53:01、23:53:28、23:53:36（四次买食物前各发一次） | 是 |  | 只在买食物前出现 买洗护道具前不出现 价格校验 | qq_pet_feed_purchase ∥ 请求{1:petId, 2:固定串 "0b"}；只在买食物前出现、买洗护道具前不出现 → 相当于“可买性/价格校验” |
| `0x9b60` | 1 | 39776 |  | 户外总览（6100 学园阶段 / 6400 职业列表） | ★★★ 实测确认 | 实测 2026-09-24 + 抓包实测 2026-10-14 19:54:15 | 是 | 读 | getSecondMapInfo | pet_academy, pet_career_town ∥ 请求{1:6400, 2:petId, **100=2**} → 这条用 2 是对的（与只有 0x9ab2 用 3 不矛 |
| `0x9b61` | ? | 39777 |  | NPC 引导上报 | ★★ 人工确认 | 静态C(pet_academy/career_town/play.reportNPCGuide) | 是 |  | reportNPCGuide 新手引导相关 | pet_academy, pet_career_town ∥ 新手引导相关 |
| `0x9b62` | 1 ? | 39778 |  | **职业树/属性总览（三属性总量在里面；逐职业“当前/要求”仅供参考）** | ★★★ 实测确认 | 抓包实测 2026-10-14 23:15:45 + rawsend 23:23:42 + **2026-10-15 00:59 拆行后拿到完整 36102 字节** | 是 |  | KRNotifyModule PetJobTreeVM QQPetModule 回包全在 | pet_job_tree ∥ 请求 `{1:petId, 100:2}`；回包全在**文案**里：`力量839/0 智力1607/0 魅力6468/0 |
| `0x9b63` | ? | 39779 |  | 职业树装备/转职点击 | ★ 人工倾向 | 静态C(pet_job_tree：onEquipClick start careerType=) |  |  | PetJobTreeVM | pet_job_tree ∥ 日志带 [Pet][CareerTree][VM] |
| `0x9b68` | ? | 39784 |  | 【容器】子页签配置与红点 | ★ 人工倾向 | 静态C(AILiteSubTabConfigManager、AILiteRedDotManager fetchRedDot) |  |  | KRSharedPreferencesModule AILiteSubTabConfigManager | AILiteGYMainActivity ∥ 非宠物 |
| `0x9b6a` | ? | 39786 |  | **保存一套穿搭 saveOneSuit** | ★★ 人工确认 | 模块 pet_home.PetStoreDataSource.saveOneSuit req: suitId= / goodsSize= / baseResId= / success, snapshotUrl= | 是 |  | saveOneSuit 换装链里真正的 保存套装 旧行按服务名猜错了 | pet_home ∥ **换装链里真正的“保存套装”是这条**，不是 0x99e9（旧行按服务名猜错了，换装实测 0x99e9 全程未出现） |
| `0x9b6b` | ? | 39787 |  | 删除一套穿搭 removeOneSuit | ★★ 人工确认 | 模块 PetStoreDataSource.removeOneSuit: suitId= | 是 |  | — | pet_home ∥ 写操作 |
| `0x9b6c` | 1 | 39788 |  | 资源/配置拉取（另一路） | ★ 人工倾向 | 抓包实测 2026-10-14 19:29:22 |  |  | getMySuits | pet_home ∥ 回包带 https://qqai.gtimg… URL |
| `0x9b79` | 1 | 39801 |  | 读取用户正在使用的背景/装扮 | ★★★ 实测确认 | 抓包实测 + 模块源码 pet_home.BackgroundDataSource.getUserUsingGoods | 是 |  | getUserUsingGoods getUserUsingGo 旧行猜的 装扮页初始化 | pet_home ∥ 日志“getUserUsingGoods: petId= / good is null”——旧行猜的“装扮页初始化”方向 |
| `0x9b7f` | 1 | 39807 |  | 一键照顾配置（带 version 3） | ★★★ 实测确认 | 抓包实测 + 模块源码 pet_home.PetCareViewModel.fetchOneClickCareConfig | 是 |  | fetchOneClickCareConfig AICommonModule EGPetDataModule 同族但不是同一条 | pet_home ∥ 日志“fetchOneClickCareConfig: from= / version 3 / petId is emp |
| `0x9b80` | 1 ? | 39808 |  | **一键照顾执行** | ★★★ 实测确认 | 抓包实测 2026-10-14 20:50:00 与 **2026-10-15 02:44:03**（两次都对好友宠物执行） | 是 |  | doOneClickCa 旧行从模块日志推的 预期字段就对应这几个小整数 两次参数不同 | pet_home ∥ 请求 `{1:好友petId, 2:2, [3:12,] 5:4, 6:1\|3}`（旧行从模块日志推的 biscuit/ |
| `0x9b87` | ? | 39815 |  | **火花列表 fetchSparkList（每好友连续互踩天数就在此）** | ★★★ 实测确认 | 静态C(pet_like_list.fetchSparkList) + **抓包实测 2026-10-15 01:28:01（用户点「火花 7 人」，8368 字节，12 条记录）** | 是 |  | fetchSparkList 就是界面上的 对方昵名 对方的宠物名 | pet_like_list ∥ 顶层 **`.4.2 = {"remain":[],"total":7,"version":1}`** 与 `.4.4  |
| `0x9b8f` | ? | 39823 |  | 【容器】关注 | ★★ 人工确认 | 静态C(AILiteFollowRequest send: followeeUin) | 是 |  | 网络不可用 请稍后重试 | AILiteGYMainActivity ∥ 非宠物 |
| `0x9b90` | ? | 39824 |  | 【容器】取关 | ★★ 人工确认 | 静态C(AILiteUnfollowRequest) | 是 |  | 网络不可用 请稍后重试 | AILiteGYMainActivity ∥ 非宠物 |
| `0x9b94` | ? | 39828 |  | 【容器】批量关注关系查询 | ★★ 人工确认 | 静态C(AILiteBatchFollowRelationReq batchQuery) | 是 |  | — | AILiteGYMainActivity ∥ 非宠物 |
| `0x9b95` | ? | 39829 |  | 【容器】推荐用户列表 | ★★ 人工确认 | 静态C(AILiteGetRecommendUsersRequest send: limit=) | 是 |  | — | AILiteGYMainActivity ∥ 非宠物 |
| `0x9ba3` | 0 | 39843 |  | **日记点赞** | ★★★ 实测确认 | 抓包实测 2026-10-14 20:26:29 | 是 |  | 响应为空 点赞成功 无其他控制字段 那条是取消 | pet_diary ∥ 请求{1:petId, 2:日记uuid “de2aca3a-9380-…”，无其他控制字段；与 0x9ba4 成对（那 |
| `0x9ba4` | 0 ? | 39844 |  | **日记取消点赞** | ★★★ 实测确认 | 抓包实测 2026-10-14 20:32:28（紧接着上一轮点赞） | 是 |  | 响应为空 取消点赞成功 完全同形状 取消就是两条不同命令 | pet_diary ∥ 请求{1:petId, 2:日记uuid “0e411b7d-ed9b-…”}，与 0x9ba3 完全同形状——点赞/取 |
| `0x9bb1` | 0 | 39857 | Sso_PetReport_ReportAction | 上报行为动作（气泡/动作） | ★ 人工倾向 | 静态A |  |  | — | Lcom/tencent/ergo/view/mainpage/l;.B ∥ ReportAction；参数 actionId/actionScene/bubbleSource |
| `0x9bb3` | 0 ? | 39859 |  | **取消雇佣好友代打** | ★★★ 实测确认 | 抓包实测 2026-10-14 20:42:47 | 是 |  | 全程只出现这一条独立命令 雇佣本身不发包 的开始包里带 时由服务端完成并扣费 | pet_pk ∥ 请求{1:自己petId, 2:好友petId}；全程只出现这一条独立命令——**雇佣本身不发包**，是在 0x975e |
| `0x9bcc` | 0 ? | 39884 |  | 资产/金币流水分页 | ★★★ 实测确认 | 抓包实测 2026-10-14 20:24:01（静态名 fetchFlows） | 是 |  | PetProductFlowVM KRCalendarModule 看形状是 | pet_product_flow ∥ 请求{1:3, 2:1, 5:20, 7:{1:1}} → 看形状是 类型=3/页码=1/每页=20/过滤 |
| `0x9bcd` | 0 ? | 39885 |  | 账户余额 | ★★★ 实测确认 | 抓包实测 2026-10-14 20:24:01（静态名 fetchBalance） | 是 |  | fetchBalance PetProductFlowVM 的货币类型同一取值 | pet_product_flow ∥ 请求{1:3, 2:{1:1, **2:1001**, 3:petId}}——1001 与商城 0x9bd0 的货币类型 |
| `0x9bce` | 0 ? | 39886 |  | 钻石/代币商品列表 | ★★★ 实测确认 | 抓包实测 2026-10-14 20:24:08（静态名 fetchDiamondList） | 是 |  | fetchDiamondList PetProductListVM 回包带一批商品 | pet_product_list ∥ 请求{1:1, 2:{1:1, 2:1001, 3:petId}}；回包带一批商品 id 与子集（1000001/100 |
| `0x9bcf` | ? | 39887 |  | 商店购买前余额校验 | ★★ 人工确认 | 静态C(pet_home+pet_play.StorePaymentManager checkBalance req: cmd=0x) | 是 |  | StorePaymentManager 网络异常 请稍后重试 | pet_home, pet_play ∥ 日志带 items/result/guides/petId empty |
| `0x9bd0` | 0 | 39888 |  | 下单买道具（一键照顾不够用时自动补货） | ★★★ 实测确认 | 抓包实测 2026-10-14 20:50:01 | 是 | 写 | StorePaymentManager 网络异常 请稍后重试 回包直接给到手物品 | pet_home, pet_play ∥ 请求{1:{1:1,2:1001,3:petId}, 2:1001, 3:{1:355,2:1,3:1}, **4:12 |
| `0x9bd1` | ? | 39889 |  | 购买凭证/月卡 | ★★ 人工确认 | 静态C(pet_product_list.doBuyVoucher) | 是 |  | doBuyVoucher PetProductListVM 购买失败 请稍后重试 | pet_product_list ∥ 写操作，不要盲发 |
| `0x9bd2` | ? | 39890 |  | 购买参数拉取 | ★ 人工倾向 | 静态C(pet_product_list.buyParams) |  |  | buyParams PetProductListVM 充值失败 请稍后重试 | pet_product_list ∥  |
| `0x9bd4` | 0 | 39892 |  | **VIP/SVIP 可领取礼包列表** | ★★★ 实测确认 | 模块源码 `EntryNavVM.fetchVipReceiveInfo` + 抓包实测 2026-10-15 00:04:22（用户在主页点领 SVIP 礼包那两秒） | 是 |  | fetchVipReceiveInfo fetchVipReceiveIn EntryNavVM 请求体为空 | pet_home ∥ 请求体为空（读）；**回包直接给礼包内容**：`{1:礼包序号(1与2), 2:[{图标URL,“点券*5”},{图标U |
| `0x9bd5` | 1 ? | 39893 |  | 商城全部商品 | ★★★ 实测确认 | 静态C(fetchAllMarketGoods) + 抓包实测 2026-10-14 19:29:22 | 是 |  | fetchAllMarketGoods 服务名与实际行为对上 | pet_home, pet_play ∥ 请求{2:petId,100:1,101:版本号}；服务名与实际行为对上 |
| `0x9bd6` | 1 | 39894 |  | **不是“穿戴动作”** | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | getBagClothingItems 上一轮因它在换装流程里出现就定为 进相关页面就会自动发 归为读取类 | page_craft_main, pet_home ∥ 上一轮因它在换装流程里出现就定为“穿戴”，错了——进相关页面就会自动发（请求{1:petId,2:9000}，回包带 3 |
| `0x9bd9` | ? | 39897 |  | 货币账户刷新（金币余额） | ★★ 人工确认 | 静态C(CurrencyAccountDataSource fetchCurrencyAccount: gold=, cachedGold, currencyType) | 是 |  | — | pet_home, pet_play ∥  |
| `0x9be3` | ? | 39907 |  | 校验购买凭证 | ★★ 人工确认 | 静态C(pet_product_list.doCheckBuyVoucher) | 是 |  | doCheckBuyVoucher | pet_product_list ∥  |
| `0x9bf1` | 1 | 39921 |  | 洗护商城配置 | ★★★ 实测确认 | 实测 2026-09-24 QQ9.3.65 | 是 | 读 | HRBridgeModule PetShowerViewModel | pet_home ∥ BATH_ITEM_CONFIG |
| `0x9bf2` | 1 | 39922 |  | 洗护库存 | ★★★ 实测确认 | 实测 2026-09-24 QQ9.3.65 | 是 | 读 | fetchBathItemConfig PetShowerViewModel | pet_home ∥ BATH_INVENTORY |
| `0x9bf3` | 1 | 39923 |  | **洗澡（可对待好友）** | ★★★ 实测确认 | 抓包实测 2026-10-14 20:49:35 | 是 | 写 | PetShowerViewModel 好友向形态比自身多一个字段 就是洗后的清洁 心情变化 | pet_home ∥ 好友向形态比自身多一个字段：{1:好友petId, 2:道具id“1”, 3:数量1, **4:好友uin**}；回包  |
| `0x9bf5` | 1 ? | 39925 |  | 商城/主页状态查询 | ★ 人工倾向 | 静态C(getStatus) + 抓包实测 2026-10-14 |  |  | getStatus PurchaseAgreementManager AICommonModule 同批发出 | pet_home, pet_product_list ∥ 与 0x9bd5 同批发出，回包极短 |
| `0x9bf6` | ? | 39926 |  | 商店购买协议确认 | ★★ 人工确认 | 静态C(pet_home+pet_product_list.PurchaseAgreementManager “[Pet][Store][Agreement] confirm rsp confirmed=”) | 是 |  | PurchaseAgreementManager | pet_home, pet_product_list ∥ 写操作 |
| `0x9c0a` | 1 | 39946 |  | 道具目录（含 id 354/355 等） | ★ 人工倾向 | 抓包实测 2026-10-14 20:50:00（一键照顾前自动发） |  |  | 它状态目前很好哦 它状态很好呢 谢谢你哦 看形状是按类型拉可用道具 | pet_home ∥ 请求{1:2}；回包“354/饼干/5”、“355…”——看形状是按类型拉可用道具 |
| `0x9c11` | 0 | 39953 |  | **信箱：可领取数量 + 邮件列表** | ★★★ 实测确认 | **sub=0 才有数据**（抓包实测 2026-10-15 00:56：`0x9c11_0` 回 7409 字节；`0x9c11_1` 回 150 no privilege） | 是 |  | getClaimableCount PetMailBoxRepository 邀请好友领养 的奖励就躺在这 | pet_feed_history ∥ 请求{1:petId}；body.f1 = repeated 邮件 {1:邮件id, 3:标题, 4:正文, 5:{1, |
| `0x9c12` | 0 | 39954 |  | 信箱：未读列表 | ★★★ 实测确认 | 抓包实测 2026-10-15 00:47（`0x9c12_0` 回 4 字节，当前无未读） | 是 |  | loadUnread PetMailBoxRepository 同样必须是 | pet_feed_history ∥ 请求{1:petId}；sub 同样必须是 0 |
| `0x9c13` | ? | 39955 |  | 信箱：批量取邮件正文 | ★★ 人工确认 | 模块 PetMailBoxRepository.batchGetMails | 是 |  | PetMailBoxRepository | pet_feed_history ∥ 请求带 petId + 邮件 id 列表 |
| `0x9c14` | ? | 39956 |  | 信箱：上报已读 | ★★ 人工确认 | 模块 PetMailBoxRepository.reportRead | 是 |  | reportRead PetMailBoxRepository | pet_feed_history ∥ 请求带 petId + long（邮件 id/时间） |
| `0x9c17` | 0 | 39959 |  | 信箱：领取单封邮件 claimMail | ★ 人工倾向 | 模块源码 `q4/h.c(String petId, String id, cb)` → 0x9c17 |  |  | claimMail PetMailBoxRepository 参数形状由方法签名得出 本窗口未实测试 | pet_feed_history ∥ 参数形状由方法签名得出（petId + 邮件 id）；**本窗口未实测试**（无待领邮件），拿 token 去领会弄出未 |
| `0x9c19` | 0 | 39961 |  | **信箱：一键全部领取 batchClaim** | ★★★ 实测确认 | 抓包实测 2026-10-15 01:05:32（rawsend） | 是 |  | PetMailBoxRepository 无待领时回 暂时还没有可领取的奖励呢 没东西可领 | pet_feed_history ∥ 请求 `{1:petId, 100:2}`，**sub 必须是 0**（`_1` 回 150 no privilege） |
| `0x9c1a` | ? 或 0 | 39962 |  | 信箱：删除邮件 | ★★★ 实测确认 | 模块源码 PetMailBoxRepository.deleteMail；**本家族 sub 实测统一为 0**（`0x9c11_0`/`0x9c19_0` 可用，`_1` 一律 150 no privilege） | 是 |  | PetMailBoxRepository | pet_feed_history ∥ 写操作；请求带 petId + 邮件 id |
| `0x9c1b` | 1 ? | 39963 |  | 宠物名片页取数 | ★ 人工倾向 | 抓包实测 2026-10-14 20:28:28 |  |  | fetchResourcePath fetchResour fetchUserPetInfo QQPetModule | pet_profile_card ∥ 请求 .4=petId（其余空）；静态名 PetCardDataSource.fetchUserPetInfo 对得上 |
| `0x9c1d` | ? | 39965 |  | 解锁职业 | ★★ 人工确认 | 静态C(pet_job_tree.PetJobTreeVM onUnlockCareerClick) | 是 |  | PetJobTreeVM | pet_job_tree ∥ 写操作；日志带 empty petId 校验 |
| `0x9c3c` | 1 | 39996 |  | 商城红点/入口标记 | ★★★ 实测确认 | 抓包实测 + 模块源码 pet_home.PetStoreDataSource.getMallRedDot | 是 |  | getMallRedDot | pet_home ∥ 日志“getMallRedDot req: cmd=0x … success: entry= / , baseResou |
| `0x9c3d` | ? | 39997 |  | 商城红点已读上报 | ★★ 人工确认 | 模块 PetStoreDataSource.writeMallRedDotRead req: cmd=0x / node= / tabId= / appId= | 是 |  | getMallRedDot 一读一写 | pet_home ∥ 与 0x9c3c getMallRedDot 成对（一读一写） |
| `0x9c44` | 1 | 40004 |  | 鼓励（学习/打工支持，冒险报 135011） | ★★★ 实测确认 | 实测 2026-09-24 QQ9.3.65 | 是 | 写 | PlayHomePageModel | pet_play ∥ STORY_ENCOURAGE |
| `0x9c6d` | 0 ? | 40045 |  | **每日签到领食物 claimCheckinFood** | ★★★ 实测确认 | 抓包实测 2026-10-15 01:05:32（一发就成功）+ 01:07:41/01:10:26 库存比对 | 是 |  | claimCheckinFood 不需其他参数 是客户端自己带的标识 已证明是 | pet_home ∥ 请求 `{1:petId, 100:2}`（不需其他参数！`source=` 是客户端自己带的标识）；**回包 `f1` |
| `0x9c71` | 1 | 40049 |  | **好友列表（每好友：宠物信息 + 本人信息 + 三状态 + 火花类状态位）** | ★★★ 实测确认 | 抓包实测 2026-10-14 19:50:41 + **2026-10-15 01:23:42（拆行后拿到完整 19300 字节，共 43 条记录）** | 是 |  | fetchSparkList 每条记录 宠物昵名 宠物头像 | pet_friend ∥ 请求{1:1}；每条记录 = `{1.1:宠物昵名, 1.3:宠物头像URL, 1.5:好友uin（串）, 1.6:va |
| `0x9c79` | ? | 40057 |  | 【容器】推荐卡片轮询/引导气泡 | ★ 人工倾向 | 静态C(AILiteRecommendViewModel sendGuideBubble/cardType=FOLLOW_USER) |  |  | AILiteRecommendViewModel | AILiteGYMainActivity ∥ 非宠物 |
| `0x9c7a` | ? | 40058 |  | 【容器】卡片曝光上报 | ★ 人工倾向 | 静态C(AILiteReportCardExposure send: cardType=, usersCount=) |  |  | — | AILiteGYMainActivity ∥ 非宠物 |
| `0x9c7f` | ? | 40063 |  | 强制设置 QQ 基础开关 | ★ 人工倾向 | 静态C(qq_pet_adopt.sendForceSetQQBaseFlag) |  |  | sendForceSetQQBaseFlag getUin 领养流程内 | qq_pet_adopt ∥ 领养流程内 |
| `0x9c9c` | ? | 40092 |  | 更换领养（重生宠物） | ★★ 人工确认 | 静态C(qq_pet_adopt.rebirthPet: oldPetId=, eyeTextureResId=；文案“更换领养失败”) | 是 |  | AICommonModule 更换领养失败 请稍后重试 重生条件 | qq_pet_adopt ∥ 对应 0x9ca2 重生条件 |
| `0x9ca2` | ? | 40098 |  | 重生条件/配置 | ★ 人工倾向 | 静态C(qq_pet_adopt.fetchRebirthCon) |  |  | HRBridgeModule 昵称不能为空 | qq_pet_adopt ∥ rebirth config |
| `0x9ce5` | ? | 40165 |  | **宠物头像照片刷新与回写** | ★★ 人工确认 | 模块 pet_home.PetAvatarRefresher：photo_refresh / store_save / captureAndUpdate trigger= / specs= / reportPhotoRefresh | 是 |  | 换装后生成头像图并上报的那一跳 写回资料含头像 合成形象 同属一条链 | pet_home ∥ 换装后生成头像图并上报的那一跳——与 0x99f1(写回资料含头像URL)、0x99f3(合成形象) 同属一条链；旧行归 |
| `0x9ce9` | 1 | 40169 | Sso_PetResourceRead_GetIncrResource | 主页某个读写（待定） | ★★ 静态A | 同一方法里命令名 + trpc 服务名成对出现 |  |  | 只抓到命令名没抓到服务名 | Lcom/tencent/ergo/view/mainpage/l;.n ∥ 只抓到命令名没抓到服务名 |
| `0x9cea` | 1 | 40170 | Sso_PetResourceRead_GetResourceDetail | GetResourceDetail（服务名直读） | ★★ 静态A | 同一方法里命令名 + trpc 服务名成对出现 |  |  | getResourceDetail | Lcom/tencent/ergo/view/mainpage/l;.t ∥  |
| `0x9cee` | ? | 40174 |  | 【容器】顶栏相关待定 | ◇ 静态C | 确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读 |  |  | AILiteBrowserTopBarViewModel GYComposePlatformModule KRPhotoModule | AILiteGYMainActivity ∥ 非宠物 |
| `0x9d50` | ? | 40272 |  | 标签列表 | ★ 人工倾向 | 静态C(AILiteGY.fetchTags) |  |  | AILiteInterestTagVM | AILiteGYMainActivity ∥  |
| `0x9d51` | ? | 40273 |  | 【容器】提交兴趣标签 | ★ 人工倾向 | 静态C(AILiteInterestTagApi submitTags: tagIds=) |  |  | — | AILiteGYMainActivity ∥ 非宠物 |
| `0x9d68` | ? | 40296 |  | 拍一拍曝光上报 | ★★ 人工确认 | 模块 pet_home.x5.k.reportPatExposure: friendUin= / patType= | 是 |  | reportPatExposure 埋点性质 不是业务写操作 | pet_home ∥ 埋点性质，不是业务写操作 |
| `0x9d70` | 0 | 40304 |  | **开/领好友金币袋（福袋）** | ★★★ 实测确认 | 抓包实测 2026-10-14 20:08:34（完整回包）+ 用户确认“本次领到 5 金币”；模块方法名 fetchCoinBagDetail | 是 |  | fetchCoinBagDetail AICommonModule 来源场景 字段语义已对齐 | pet_home ∥ 请求{1:自己petId, 2:好友petId, 3:袋id(前缀=来源场景，本次 6400-…), 11:2}；回包  |
| `0x9d71` | 0 | 40305 |  | 金币袋列表/前置查询 | ★★★ 实测确认 | 抓包实测 2026-10-14 20:08:34（比 0x9d70 早 0.4s）；回包与领取同构 | 是 |  | 同一个袋 不带好友 旧行写的 | pet_home ∥ 请求{1:自己petId, 3:同一个袋id}——**不带好友petId**；旧行写的 sub=1 错，实抓 sub=0 |
| `0x9d9e` | ? | 40350 |  | 使用道具（远程生效） | ★★ 人工确认 | 静态C(pet_play.PetPlayPropVM usePropRemote fallback: no storyId) | 是 |  | PetPlayPropVM 走页面自带 | pet_play ∥ 走页面自带 OIDB 通道（AISafeRequest） |
| `0x9db9` | 1 ? | 40377 |  | 等级奖励相关列表 | ★ 人工倾向 | 抓包实测 2026-10-14 20:32:10（进等级页时） |  |  | loadLevelRewardInfo AICommonModule PetLevelRewardVM 回包带一批好友条目 | pet_level_reward ∥ 请求 .4=petId；回包带一批好友条目（昵称/头像/uin/petId）与“比心”字样，具体是可领奖励还是助力名单待 |
| `0x9dd2` | 0 | 40402 |  | **可授权到群的宠物列表**（自己+好友，带三属性） | ★★★ 实测确认 | 抓包实测 2026-10-14 21:04:33（进群宠物授权页） | 是 |  | setPetGroupAuth 请求体空 的前置读 不是宠物玩法命令 | pet_friend ∥ 请求体空；回包 {昵称,头像URL,uin,petId,f10=5, 属性 fixed 100.0/100.0}——是  |
| `0x9e0a` | ? | 40458 |  | 解锁等级奖励 | ★★ 人工确认 | 静态C(pet_level_reward.requestUnlockLevelReward / onUnlockConfirmOk level=, coins=) | 是 |  | — | pet_level_reward ∥ 写操作 |
| `0x9e0e` | ? | 40462 |  | 设置一键照顾偏好 | ★ 人工倾向 | 模块源码 pet_home.q6.r.i：setOneClickCareConfig: useGold= |  |  | setOneClickCareConfig 是否允许花金币买道具 | pet_home ∥ 写操作：是否允许花金币买道具；与 0x9b7f（读）成对 |

## 待填（有号、含义未知；按证据强度排序）（65 条）

| 命令 | sub | 十进制 | trpc 服务名 | 含义 | 确信 | 依据 | 接入 | 读/写 | 线索 | 出处 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `0x9000` | ? | 36864 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | AILiteGYMainActivity ∥  |
| `0x901f` | 1 | 36895 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x902e` | 1 | 36910 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9048` | ? | 36936 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | AILiteGYMainActivity ∥  |
| `0x9076` | 1 | 36982 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9078` | 1 | 36984 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9079` | 1 | 36985 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9082` | 1 | 36994 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9082` | 2 | 36994 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x90a5` | 1 | 37029 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x90a5` | 3 | 37029 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x90a5` | 8 | 37029 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x911a` | 19 | 37146 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x911a` | 22 | 37146 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9124` | 0 | 37156 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9127` | 1 | 37159 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x913b` | 1 | 37179 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9144` | 1 | 37188 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9176` | 1 | 37238 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x92d5` | 0 | 37589 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x92e3` | 0 | 37603 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x92e4` | 0 | 37604 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x92eb` | 0 | 37611 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x930d` | 0 | 37645 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x930e` | 0 | 37646 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x93d7` | 1 | 37847 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 10 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 11 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 12 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 13 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 14 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 15 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 16 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 18 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9409` | 7 | 37897 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9559` | 0 | 38233 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x955f` | 1 | 38239 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9560` | 1 | 38240 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x962a` | 1 | 38442 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9680` | ? | 38528 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | Lcom/tencent/mobileqq/qqpet/api/IQQPetProfil ∥  |
| `0x9689` | 1 | 38537 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x969c` | ? | 38556 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | Lcom/tencent/mobileqq/qqpet/api/IQQPetProfil ∥  |
| `0x97a4` | ? | 38820 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | Lcom/tencent/mobileqq/qqpet/api/b$c;.<clinit ∥  |
| `0x97ab` | ? | 38827 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | AILiteGYMainActivity ∥  |
| `0x97f5` | ? | 38901 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | AILiteGYMainActivity ∥  |
| `0x9960` | 1 | 39264 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9962` | 1 | 39266 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9a4a` | 0 | 39498 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9a5b` | 1 | 39515 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9a82` | 0 | 39554 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9aa0` | ? | 39584 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | AILiteGYMainActivity ∥  |
| `0x9ae9` | 0 | 39657 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9b42` | 0 | 39746 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9b77` | ? | 39799 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | 回包解析为空 | page_craft_main ∥  |
| `0x9b78` | ? | 39800 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | setGoods | pet_home ∥  |
| `0x9b7d` | 1 | 39805 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9b7e` | 1 | 39806 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9bee` | ? | 39918 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | — | AILiteGYMainActivity ∥  |
| `0x9c00` | 0 | 39936 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9c01` | 0 | 39937 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9c02` | 0 | 39938 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9c0c` | 0 | 39948 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9cdf` | 1 | 40159 |  | — | ○ 仅白名单 | 只在 libfekit.so 网关清单里出现 |  |  | — | - ∥  |
| `0x9d5d` | ? | 40285 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | 直接按失败处理 | page_craft_main ∥  |
| `0x9d5e` | ? | 40286 |  | — | ? 待核 | 不在链上，但方法里有网络相关文本，可能是真命令 |  |  | 直接按失败处理 | page_craft_main ∥  |

合计 256 条命令号；已定含义 191，待填 65。
