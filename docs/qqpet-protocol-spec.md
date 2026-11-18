# QQ 宠物 进程内协议规格（Autoqqpet 逆向基线）

> ⚠ 协议字段与命令含义由作者逆向整理，不保证准确；QQ 更新可能失效；写命令勿盲发。模块实际在用的命令见 `docs/commands-in-use.md`。
>
> 📋 **命令全集请看 `docs/pet-commands.md`**（自动生成的总表，带含义与确信度）；
> 本文件只留我们已经跑通的入口、字段表与工程规则。还原方法见 `docs/pet-command-recon.md`。

> ⚠️ **时效性警告（务必先读）**：本文件的协议配方来自参考仓库 `qq-pet-interface-copilot`，
> 其分析基线是 **QQ 9.3.25 / 9.3.35（甚至更早的 9.2.80）**，而我们的目标是 **9.3.65**。
> 参考仓库已偏旧，**不能保证当前正确**：
> - **客户端混淆类名**（`delegate.l`、`si5.m`、`jj5.t` 等）会随版本漂移——已在 9.3.65 dex 初步核对入口仍在，但方法体未逐条验；
> - **命令号 / protobuf 字段 / 业务语义**是**服务端**决定的，会随**赛季、活动、改版**变化，
>   "不随更新变"仅对**客户端混淆**成立，对**服务端迭代不成立**。
>   → 本文件所有命令号与字段表**必须在 9.3.65 真机上逐条重验后才可信**，在此之前一律视为**待验证假设**。
>
> 数据来源：本机 QQ **9.3.65 / versionCode 16240**（本机研究目录 `_research/`）交叉验证；
> 协议配方源自 `qq-pet-interface-copilot`（`qqpet_app/proto.py`、`client.py`、
> `hooks/qqpet_mobile_read_agent.js`、`docs/interface-map.md`、`protocol/qqpet.proto`）。

## 1. 架构：进程内入口（9.3.65 已核对类与方法签名仍在；方法体/返回语义待真机验）

模块被 LSPosed 注入 QQ 进程后，**不 hook、直接反射调用** QQ 现成的发包代理：

```
类：com.tencent.mobileqq.qqpet.delegate.l      // 有公开无参构造 <init>()
方法：
  void c(byte[] body, String oidbName, int command, int subCommand, PetPbDelegate$a cb)  // 发 OIDB
  void a(byte[] body, String cmd,            PetPbDelegate$a cb)                          // 发 SSO
回调接口：com.tencent.ergo.hostdelegate.pb.PetPbDelegate$a
  void onResult(int code, byte[] data, android.os.Bundle extras)
```

- **传参只传"业务 protobuf body"**；OIDB/SSO 的 envelope、签名、加密、会话由 QQ 自身处理。
  （对照 `client.py`：走 `_oidb_read/write_transport` 时直接透传 body，仅 HTTP/NapCat 回退路径才 `oidb_request()` 包一层。）
- **✅ 已定论（`mobile_protocol.py:660-778`）**：进程内通道 `onResult(code, data, bundle)` 的
  **`data` 直接就是业务响应 body**（已剥 envelope，直接按 §3 字段表解析，如 `parse_message(data)` 取 field1）；
  **`code` 就是服务端 errorCode**，非 0 即失败，错误文本取 `bundle.getString("data_error_msg")`（与 JS agent 一致）。
  进程内路径**不需要** §3.3 的 envelope 解包。
- 本人身份：`mqq.app.MobileQQ.sMobileQQ.peekAppRuntime().getCurrentAccountUin()` / `isLogin()`。

**版本腐烂风险**：`delegate.l` 与回调里的 protobuf 类（如 `si5.m`/`jj5.t`）是混淆名，QQ 大版本更新可能改名。
适配策略：① 反射按"方法签名特征"扫描定位，而非写死类名；② protobuf 我们**自己按 wire 格式编解码**，不依赖 QQ 的 pb 类。
好消息：`PetPbDelegate`（`com.tencent.ergo.hostdelegate.pb`）本身可读，且 `delegate.l` 是它的薄封装。

## 2. 通用 protobuf 编解码（移植 `proto.py` → Kotlin）

wire type：0=varint, 2=length-delimited, 5=fixed32。

```
varint(v)                       // 标准 LEB128；负数按 64 位补码
field_varint(n, v)  = varint(n<<3) + varint(v)
field_bytes(n, b)   = varint((n<<3)|2) + varint(len(b)) + b
field_string(n, s)  = field_bytes(n, s.encode(UTF8))
field_fixed32(n, f) = varint((n<<3)|5) + pack("<f", f)   // 部分 float 状态用
parse_message(b) -> map<field#, list<(wireType,value)>>  // 取 field 首个
first_varint / first_bytes / first_string / first_float
```

## 3. OIDB 命令总表 + 逐操作配方

> 🔎 **下表来自旧版参考（≤ 9.3.35）**。列在此处是作为**起点与对照基线**，
> 实现时须先在 9.3.65 真机上用只读命令逐个复核再采信。
>
> ✅ **真机验证记录（2026-09-24，QQ 9.3.65，Autoqqpet 只读探针）**：
> `OWN_PROFILE(0x99f2_1)` 与 `DISPLAY(0x96f2_1)` 两条命令号及字段布局在 9.3.65 **实测有效**
> （成功取到 petId 并读出 心情/体力/清洁/综合/金币）。其余命令仍待逐个复验。
>
> ✅ **只读库存/商城已验**：`FEED_TIMES(0x9949_1)`、`BATH_ITEM_CONFIG(0x9bf1_1)`、`BATH_INVENTORY(0x9bf2_1)` 字段与旧版一致。
> ✅ **写操作 FEED 已验**：`FEED(0x992d_1)` 回包为**空**，但状态复查体力+10/心情+1 确认生效——
>   印证“写命令不靠回包、靠前后状态差判成功”的规则（§5.2）。
> ✅ **写操作 DO_BATH 已验**：`DO_BATH(0x9bf3_1)` 使用香皂片，回包可解析(clean/mood/remaining)，状态复查清洁+10 确认。
>   自动照顾闭环(CareManager: 读→阈值判断→喂/洗→复查→库存耗尽停)已真机跑通。买货类 BUY_FOOD/BUY_BATH_ITEM 待验。
> ✅ **场景只读已验**（2026-09-24）：`OUTDOOR_OVERVIEW(0x9b60_1, f1=6100/6400)` 读到学园阶段+职业列表；
>   `SCENE_CATALOG(0x9ab2_1, f1=6100/6400/6700/6900)` 读到课程/冒险/PK 目录（课程 subEventType 为 7 位如 6114001，非 report_event 路径的 6101）；
>   **冒险目录的 `f100` 要用 3**（真机 2026-10-14 抓包：给 2 只回 1 条选项，“冒险只有 45 秒”这个旧结论就是参数错）；
>   `PK_FRIEND_LIST(0x985d_0)` 读到 10 个好友。字段布局与旧版一致。
> ⚠️ `STORY_STATUS(0x975a_1)`：无进行中任务时易返回传输层超时 code=-1（wait serverResp timeout），非业务错误。
>   已将负码归为可重试(NotReady)，正码才当业务 ServerError。
>
> ✅ **主任务与好友写均已在 9.3.65 真机验证**（2026-09-24）：
>   开课 SCHOOL_START(6100)/开工 WORK_START(6400)/冒险 ADVENTURE_START(6700) 均返回 storyId；
>   STORY_SETTLE(0x9760,action1000) 到期结算成功；STORY_ENCOURAGE(0x9c44) 仅学习/打工支持(冒险报135011不支持)；
>   PK 全链 START(6900)→STATUS(0x975f)→SETTLE(0x9760,action6000) 成功(体力-5/清洁-5/金币+48 验证)；
>   好友访问 REPORT_EVENT(path 1000/100/0)、踩踩 FRIEND_POKE(0x985b) 均发出(踩踩重复报136202属正常)。
>   召回：**2026-10-14 真机抓包实锤：召回走 `0x9760_1`，`f2(action)=6000`**，请求 `{1:storyId, 2:6000, 3:petId, 100:2}`（同一岗位开工→点召回，19:17:51 抓到）。
>   旧记录“所有 action 都 135004”是当时故事不可召回，不是命令不对。**可直接程序化**。
> 🔎 **抓包发现（PacketLogger 宽 hook）**：`0x9753_1`(38739/1) 经 rawsend 实测是**状态/气泡查询**（f1=-1 返回“你好呀/打工中”等文案），**不是**领福袋也不是召回。
>   召回与领福袋**均不走 qqpet.delegate 层**（宽 hook 抓不到）——已查清原因：页面模块内 `w1/r.c` 按名字取宿主模块 **`QQKuiklyPlatformApi`** 调 **`sendPbRequest`**，
>   宿主侧走 `com.tencent.biz.richframework.network.VSNetworkHelper#sendRequest`（不是 native 黑盒）；候选抓包点与具体命令见 `docs/pet-command-recon.md`。
>   新发现命令：`0x9959_1`(39257, 空 body 查询)、`0x95e1_0`(GetUserPet)、`0x95f7/0x95f3`(版本心跳)、`0x994b_1`。

命名元组为 `(oidbName, command, subCommand)`；body 一律为**业务 protobuf**。

### 3.1 读取类（可安全重试）

| 功能 | 命令 | 请求 body | 响应解析 |
| --- | --- | --- | --- |
| 本人宠物资料 OWN_PROFILE | `0x99f2_1` 39410/1 | 空 | field1=profile：f1=宠物名, f5=uin, **f8=petId(base64, 解出为 `"{uin}-..."`)** |
| 数值 DISPLAY | `0x96f2_1` 38642/1 | f1=petId(str) + f2=bytes`0x01` | field1→root：f1..f4 各 `{f3=float}`=心情/体力/清洁/综合 |
| 金币 DISPLAY | `0x96f2_1` 38642/1 | f1=petId(str) + f2=bytes`0x06` | field1→root：**f5** `{f3=float}`=金币 |
| 食物库存 FEED_TIMES | `0x9949_1` 39241/1 | 空 | **f4 repeated item**：f1=balance,f2=resourceId,f3=name,f4=foodId（饼干 foodId"1"/虾仁"3"） |
| 洗护商城 BATH_ITEM_CONFIG | `0x9bf1_1` 39921/1 | f1=1 | f1 repeated item：f1=name,f2=itemId,f5=goldPrice,f6=cleanGain,f7=desc,f12=moodGain,f8/9/10/11=default/step/min/max |
| 洗护库存 BATH_INVENTORY | `0x9bf2_1` 39922/1 | f1=1 | f1 info{ f1 repeated item{f1=itemId,f2=count} } |
| 好友宠物资料 FRIEND_PROFILE | `0x976c_0` 38764/0 | f1=friendUin(str) | **递归 walk 找同时含 f4(profile)&f5(personal) 的节点**：profile f5=uin/f8=petId；personal→(f4 或 f3)→display：f1..f4 `{f3=float}`=心情/体力/清洁/综合 |
| 页面规则 PAGE_RULES | `0x96a4_1` 38564/1 | f2=petId,f3=page,f4=executeExtInfo,[f10=from],[f99=platform] | f1=trace(json, 含 count), f2 repeated event{f1 path{f1 page,f2 type,f3 sub}} |
| 户外状态 STORY_STATUS | `0x975a_1` 38746/1 | f1=petId,**f100=2** | #2=storyId(str); **detail=root.#1** {#1=状态,#2=剩余秒,#3=总时长秒,#4=开始秒,#5=可召回}。进行中 detail 含 #2; 已完成时 #2 缺省=0(仅剩#3/#4)。与旧 9.3.25 一致 |
| PK 战力 PK_POWER | `0x9ad4_1` 39636/1 | f1=petId,f100=2 | f1 info：本人 f4=power/f3=dominantType；好友无 f4→f3=power |
| PK 好友列表 PK_FRIEND_LIST | `0x985d_0` 39005/0 | f2=source(6),[f1=cursor] | f1 repeated friend{f1 profile{f8 petId,f1 petName},f2 user{f1 uin,f2 nick},f14 power{f3,f4},f3 status}, f2=nextCursor, f3=hasMore |
| 学园阶段 SCHOOL_OVERVIEW | `0x9b60_1` 39776/1 | f1=6100,f2=petId,f100=2 | f4=stage ∈ {0,1,2,3,4} |
| 课程目录 SCHOOL_COURSES | `0x9ab2_1` 39602/1 | f1=6100,f2=petId,f3="",f11=stage,f100=2 | f1 repeated item{f1 name,f6 cost,f7 duration,f8 reward,f14 desc,f50 canDo,f52 subEvent} |
| 职业总览 WORK_OVERVIEW | `0x9b60_1` 39776/1 | f1=**6400**,f2=petId,f100=2 | f1 repeated{f1 name,f4 status,f5 msg,f20 careerType}; root f3=当前职业, f5=lastSubEvent |
| 岗位目录 WORK_JOBS | `0x9ab2_1` 39602/1 | f1=**6400**,f2=petId,f3= hiredPetId,f10=careerType,f100=2 | 同课程结构；f2=careerName |
| 冒险目录 ADVENTURE_OPTIONS | `0x9ab2_1` 39602/1 | f1=**6700**,f2=petId,f3= hiredPetId,**f100=3**（真机 2026-10-14：给 2 服务器只回 1 条“附近走走”，给 3 才同时回“诗和远方 2小时”）| f1 repeated item{f1 name,f6 cost,f7 duration,f8 reward,f10/f14 desc,f50 canDo,f51 unavailableReason,f52 subEvent} |
| PK 对手目录 | `0x9ab2_1` 39602/1 | f1=**6900**,f2=petId,f3=好友petId（真机 2026-10-14；雇佣关系变更后页面会重查）| 与上面同一套 item 结构 |

### 3.2 写入类（**一次 one-shot，空响应禁止重发**）

| 功能 | 命令 | 请求 body |
| --- | --- | --- |
| 喂食 FEED | `0x992d_1` 39213/1 | f4=petId(str)；选食物加 f11=foodId(str)；默认饼干 |
| 买饼干 BUY_FOOD | `0x99df_1` 39391/1 | f1=count(int)；resp f1 balance,f2 gold,f3 bought,f4 cost |
| 买洗护 BUY_BATH_ITEM | `0x9bd0_0` 39888/0 | f1=user{f1=1,f2=1001,f3=petId} + f2=1001 + f3=mallItem{f1=355,f2=itemId,f3=count} + f4=21；resp f1 result,f2 orderId |
| 洗澡 DO_BATH | `0x9bf3_1` 39923/1 | f1=petId,f2=itemId("1"/"2"),f3=count(1..99),f4=petUin；resp f1 clean,f2 mood,f3 remaining,f4 completed,f5,f6 |
| 上报行为/洗澡 REPORT_EVENT | `0x96a6_1` 38566/1 | f1=petId,f3=path{f1 page,f2 type,f3 sub},f4=executeExt(bytes),[f5 businessExt]；洗澡=5000/500/501,ext{f5=targetClean} |
| 好友访问(上报) | `0x96a6_1` 38566/1 | f1=friendPetId,f2=friendUin,f3=path{1000,100,sub},f4=ext（访问后须重读好友页确认） |
| 踩踩/戳一戳 FRIEND_POKE | `0x985b_0` 39003/0 | f1=friendUin(**int**) |
| 开课 SCHOOL_START | `0x975e_1` 38750/1 | f1=6100,f2=petId,f3="",f6=courseName,f7=subEvent,f100=2；resp f1=storyId |
| 开工 WORK_START | `0x975e_1` 38750/1 | f1=6400,f2=petId,f3="",[f4=雇佣好友{**f1=好友petId,f2=好友uin**}],f6=jobName,f7=subEvent,f100=2；resp f1=storyId（真机 2026-10-14 抓包纠序：旧文档写的 f1=uin/f2=petId 是反的） |
| 开始冒险 ADVENTURE_START | `0x975e_1` 38750/1 | f1=6700,f2=petId,f3="",[f4=好友{f1=好友petId,f2=好友uin}],f6=optionName,[f7=subEvent],f100=2；resp f1=storyId |
| 鼓励 STORY_ENCOURAGE | `0x9c44_1` 40004/1 | f1=petId,f2=storyId；resp f1 credit,f2 repeated msg,f3 toast |
| 结算 STORY_SETTLE | `0x9760_1` 38752/1 | f1=storyId,f2=1000,f3=petId,**f100=2** |
| 开始 PK START_PK | `0x975e_1` 38750/1 | f1=6900,f2=petId,f4=opponent{f1=oppPetId,f2=oppUin},f6{f10=75},f7=6901,f100=2；resp f1=storyId(须 `6900_` 前缀) |
| 进行中故事进度轮询 STORY_PROGRESS | `0x975f_1` 38751/1 | f1=storyId,f2=petId,f100=2（真机 2026-10-14：打工中每 7s 一次，召回后停；PK 只是复用） |
| PK 结算 PK_SETTLE | `0x9760_1` 38752/1 | f1=storyId,f2=**6000**,f3=petId,f4=0,f100=2 |

### 3.3 响应 envelope 解析（**仅 NapCat/HTTP 回退路径**，进程内路径用不到，见 §1）

`parse(raw)`：`errorCode = first_varint(outer,3)`，`body = first_bytes(outer,4)`，`errText = first_string(outer,5)`。`errorCode != 0` 视为失败。

## 4. 场景路径常量

```
school(6000/6100/*): culture 6101, physical 6201, art 6301
work  (6000/6400/*): culture 6401, physical 6501, art 6601
bath  (5000/500/501, ext field5 = 目标清洁度)
学习选择事件另有 sport/art/culture = 8401/8402/8403
属性关键词映射(课程 reward 匹配): physical→力量, culture→智力, art→魅力
```

## 5. 关键工程规则（照抄 `client.py` 语义）

1. **petId 动态获取**：启动时 `OWN_PROFILE` 拿本人 petId（base64，解出前缀须 `{uin}-`）；好友 petId 由 `FRIEND_PROFILE`/`PK_FRIEND_LIST` 返回，**绝不从 uin 猜**。
2. **写操作幂等 + 空响应禁止重发**：one-shot 写若超时/空回包，用"重读状态前后差"判定是否已生效（见 PK 的 before/after 校验、访问后重读好友页）。
3. **storyId 结算去重**：学习/打工/冒险/PK 启动得 storyId，按 storyId 结算与鼓励并持久化，避免重复。
4. **户外协议版本 f100=2**：`STORY_STATUS/SETTLE/START_*` 若省略 field100=2，服务器会返回**过期已完成**的任务，导致无限结算循环。
5. **互斥主任务**：学习/打工/冒险共用 `0x975e_1` 启动、`0x9760_1` 结算，是互斥主任务；服务器已有倒计时项时不启动另一项。PK/好友访问/照顾是旁路任务。
6. **连接退避**：读命令空响应可重试（默认 3 次、间隔 0.35s）；写命令不重试。

## 6. 待运行时验证清单（TODO）

> 优先级：参考仓库偏旧，**首要任务是把 §3 的命令号/字段在 9.3.65 真机逐个复验**，
> 而非假设其正确。建议先写只读探针（OWN_PROFILE / DISPLAY）验证链路，再逐场景扩展。

- [x] ~~用 9.3.65 真机逐个复核 §3 每条命令号与响应字段是否与旧版一致~~ → **OWN_PROFILE / DISPLAY 已验证有效**；写操作命令待验证。
- [ ] `delegate.l` 无参构造在 9.3.65 是否需 `Api`/`AppRuntime` 预置（Frida 脚本里直接 `.$new()` 成功，倾向不需要）。
- [x] ~~`PetPbDelegate$a.onResult` 的 `data` 是 envelope 还是 body~~ → **已定论：body**（§1）。
- [ ] `delegate.l` 是否要求在主线程/特定线程调用；回调线程。
- [x] ~~好友"雇佣开工"与打工目录"收益字段"部分~~ → **真机定论：岗位/冒险目录根本不回金币数值**（详见 §7.2），与 Cooky 文档"尚未确认职位目录的收益字段"一致；工分（f15）是目录里唯一可用的收益量。
- [ ] 混淆名 `si5.m`(PAGE_RULES)、`jj5.t`(GetOtherUserPet) 在 9.3.65 的实际对应（我们用自研 wire 编解码可规避）。

## 7. 9.3.65 真机校准（2026-10-06，三星 SM-S9380 / QQ 9.3.65 / Vector api102）

### 7.1 解析器必须支持 proto2 group（否则读不到嵌套收益）
岗位项的 `f8/f12/f2` 递归解析时报 `暂不支持 wire type 3/4` → QQ 这些嵌套结构用的是 **proto2 group**。
已在 `net/ProtoBuf.kt` 给 `parseBody` 加上 group（wire type 3/4）支持：把 group 内容原样当作 length-delimited 字节保存，
所以 `firstBytes/firstString/repeatedMessages` 等现有 API 不变。`#14` 类字段则不是 protobuf而是图标/宽字符数据。

### 7.2 岗位目录（SCENE_CATALOG 0x9ab2_1, f1=6400, f10=careerType）真实字段含义
```
#1  str  岗位名            例 帮店主补招牌
#2  bin  图标 URL（markdown/宽字符，不是 protobuf）
#6  str  消耗文本          '体力5(当前78)，清洁2(当前48)'
#7  str  时长              '10分钟' / '45分钟' / '2小时' / '4小时'
#8  bin  图标 markdown     '![#20px #20px](https://qqpet.gtimg.com/pet-outdoor/.../icon/174649024190.png)'
#9  str  '雇佣有额外加成'
#15 str  '+10点' ← **工分**（目录里唯一的收益量！）
#50 varint can_do          1=可做
#51 str  '去打工' ← **按钮文案，不是不可用原因**（f51 仅在 can=false 时才可能是拒绝理由）
#52 varint subEventType    6411001…
→ **没有金币字段**。金币只能在开工后的结算/历史响应里取，或干脆不取。
→ 冒险目录(f1=6700)更窄：只有 #6 消耗 / #7 时长(含**秒**，如'45秒') / #8 描述 / #10 描述 / #50，连 #15 都没有。
```
因此 `DayPlanner` 定价改为：**金币仅在文本真出现"金币N"时才可信**（`incomeKnown`），否则用
工分折算（`pointsValue`=0.2）+ 消耗惩罚（`staminaWeight`=0.1 / `cleanWeight`=0.04）；
冒险无可见报数时用 `adventureBaseline`（每次固定价）→ 短冒险每小时 rate 自然更高（符合玩家刷短冒险的经验）。

### 7.3 逐命令真机结果
| 命令 | 结果 |
| --- | --- |
| `0x96a4_1` PAGE_RULES | ✓ 可用：`trace={"pkey":"1=10|2=55|3=59|4=0","count":0}`；**count 就是服务端当日剩余次数**。本次 paths 为空（宠物正在 2h 冒险中），所以门禁必须"paths 空则放行" |
| `0x9ad4_1` PK_POWER | ✓ `战力=5671 主导属性类型=3`（自身带 #4/#3）——三属性总量的可用替代 |
| `0x976c_0` FRIEND_PROFILE | ✓ body `f1=`**好友 UIN**（不是 petId）；需递归找 profile(#4)/personal(#5)→display(#4,旧版#3) |
| `0x9bd0_0` BUY_BATH_ITEM | ✓ 生效：`result=1 order='po2026…'`，库存 `{1=0}→{1=5}`。**成功码是 1 不是 0**，判成功请复查库存 |
| `0x975a_1` STORY_STATUS | ✓ `detail#5` recallable 在 9.3.65 存在（冒险 `6700_`=false）；#2/#3 能算出进度 22%→42% |
| 读类命令整体回归 | ✓ `read/status/scenes/care` 在 group 支持后无回归（喂食 78→88、四次洗澡 48→88、买饼干 balance=2 cost=10） |
| 互斥门控 | ✓ 忙时 `learn/adventure` 均被 `busyOrSettle` 拒绝，且**不会误触 PAGE_RULES/ReportEvent**（顺序先忙判定后门禁） |

### 7.4 尚未在真机验证（需特定状态，不是代码问题）
- 开课/开工/冒险的**实际执行**与到期结算（当前有 2h 冒险在跑，被互斥门控拦住）——需等冒险结束。
- 召回**正例**（需处于被雇佣 `6500_` 且 `recallable=1` 的状态）。
- `auto`+`tick` 全链路（tick 会按设计概率触发对好友的 PK bypass，需你明确同意后再跑）。
- 雇佣好友 `hirework/hireadv`（会真影响好友，本轮未测）。
```

## 8. 出门记录与单条详情（2026-10-16 新发现，v2.1.0 落地）

> 路径记法：抓包里的 `.4.x` 是 Kuikly 响应信封的 body，模块回调拿到的 body 已剥信封，所以 `.4.1.8` = body `.1.8`。

### 8.1 `0x9876_1`《出门记录》历史流水列表
- 请求：`{1:游标(翻页才带：上一页最后一条的时间戳 ms), 2:每页条数 20, 3:自己 petId, 100:2}`。
- 回包：body `.1` repeated，每条 `{1:storyId, 3:标题, 4:结果枚举, 5:叙事文案, 8:恒 2}`（抓包记作 `.1.1`，模块兼容多包一层）。
- 条目时间戳字段号未记全：模块在条目直接字段里按毫秒区间（或秒×1000）认 varint；认不出就不翻页、不对账（宁缺毋滥）。
- storyId 前缀：`6100_` 学习 / `6400_` 打工 / `6700_` 冒险 / `6900_` 挑战(PK)。
- 标题带“（雇佣）”= 我雇了别人；带“（被雇佣）”= 我被雇走——**这是被雇判据**（解析标题时保留全角括号）。

### 8.2 `0x9874_1` 单条详情
- 请求同结算展示页：`{1:petId, 2:storyId(任意), 3:当前时间戳 ms, 4:2, 100:2}`；页面上与 `0x975b_1` 成对发出（`0x975b` 的 f1=6000 是固定参数，不是召回专属；模块不发 0x975b）。
- body `.1` 字段：`.7` 标题｜`.8` 结果枚举（缺省取 `.15.4`）｜`.10` 开始时间戳(秒)｜`.11` 场景｜`.13` 课程 id｜`.15.12` 课程分钟数｜`.9` 叙事流水 `{2:第几秒, 3:JSON 文案}`｜`.12` 文案（如「今日奖励已达上限，本次未获得额外奖励」= PK 每日奖励上限，算收益要带上）｜`.3.1` 奖励条目 `{2:名称, 3:数值, 7:同值}`。
- 属性奖励块直接给“本次学习加了多少属性”，不用拿 `0x9b62` 前后差；**A+ 记录可能没有该块**，“A+ 额外魅力+3”的字段**没找到，不要假设**。

### 8.3 结果枚举（`.1.8` = `.1.15.4`，列表里 `.4`）——跨类型共用，先看场景再解
| 场景 | 1 | 2 | 3 | 4 | 6 | 7 |
|---|---|---|---|---|---|---|
| 学习 6100 | A+ | A | B+ | | | |
| PK 6900 | | | | 胜 | 负 | |
| 其它 | | | | | | 未解（如实显示“结果 7”） |

### 8.4 其它更正
- `0x985e`（被踩明细）**sub=0**：`_1` 回 150 no privilege；150 是业务码（sub 错/无权限），**不算网络失败**。
- 服务器叙事文本常是 JSON 片段 `[{"text":"…"}]`：显示前抠出 text 值（旧版会把 `text:` 前缀一起显示）。

### 8.5 模块用法（v2.1.0）
- 写命令（0x975e）空回包/无回包：先读 `0x975a` 看 `busy:storyId`，再读 `0x9876` 最新条目（短冒险可能已结束）；都没有才判“未确认”，按退避等待，**绝不重发**。
- 不是本模块开的任务：读一次 `0x9874` 拿真名与开始时间，主页显示“宠物外出中 · 名称”。
- 每 30 分钟读一页 `0x9876`，把今天已结束、还没记过的记录补进“今天”（按 storyId 去重）。
- 倒计时/到期以服务器开始时间戳 + 总时长为准（`0x975e` 回包 `.4.4`、`0x975a` `.1.4`、`0x9874` `.1.10`）。

