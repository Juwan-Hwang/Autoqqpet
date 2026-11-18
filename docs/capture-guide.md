# 抓新命令：完整流程

> ⚠ 命令含义靠逆向推断，**不保证准确**；QQ 一更新就可能变。只在**自己的账号、自己的设备**上做。
> **写命令（会改变账号状态、花金币的）绝不盲发**：先抓官方客户端自己发的包，确认字段，再小心复现。
> 抓到的原始日志里有你的 QQ 号、好友 uin、petId（base64 里就是 uin）、storyId、token 等——
> **不要把原始抓包提交进仓库**（`.gitignore` 已忽略 `recon-raw/` 与 `tools/pet_recon/resp_*.txt`），只把结论写进 `meanings.tsv`。

本文是 [pet-command-recon.md](pet-command-recon.md)（方法论与历史结论）的操作版。

## 0. 准备

| 需要 | 说明 |
| --- | --- |
| 已 Root 的安卓机 + LSPosed/Vector | 抓包要在 QQ 进程里 hook；`capture.py` 用 `adb shell su -c` 发广播、读 logcat |
| **debug 版模块** | 抓包开关 `packet`、原样发包 `rawsend`、读字段树 `dbgcmd` 等调试命令**只在 `src/debug`**，release 包里没有。用 `./gradlew assembleDebug` 打包安装 |
| 电脑端 | Python 3、`adb`（不在 PATH 就设环境变量 `ADB=<adb 路径>`）；静态扫描还需要 Android build-tools 的 `dexdump`（或设 `DEXDUMP`） |
| QQ 安装包（可选） | 静态扫描主 APK 用，例如 `qq_9375.apk` |

所有脚本在 `tools/pet_recon/` 下运行。静态扫描的中间产物默认写到 **仓库上一级** 的 `_scratch/pet_recon/`（不进仓库）。

## 1. 动态抓包（最快，推荐先做）

模块的抓包器 `hook/KuiklyPacketLogger.kt` 钩住宿主模块 `QQKuiklyPlatformApi.call(...)`：
页面每发一个包，logcat 里就有一行 `[sendPbRequest 请求] OidbSvcTrpcTcp.0x9xxx_n,bin(长度)=hex`，响应在 `[回调入参 …]` 行里（自动按命令号配对）。

```bash
cd tools/pet_recon
python capture.py on            # 打开抓包（= 广播 action=packet op=on），同时记下手机侧时间基线
#   → 在手机 QQ 里做一个你想研究的动作（只做一个，便于对应）
python capture.py peek          # 汇总这段时间新出现的命令号，并解出请求/响应字段
python capture.py peek --all    # 看整屏，不只看新号
python capture.py raw           # 逐条列请求（不去重），看同一命令多次发送时参数的差别
python capture.py raw --since "11-11 17:00:00"   # 只看某时刻之后（跨 0 点必须带日期）
python capture.py peek --out ../../_scratch/peek.txt   # 结果写 UTF-8 文件（控制台会折行截断）
python capture.py off           # 用完关掉
python capture.py status        # 看开关状态与已装 hook 数
```

等价的手工开关（不用脚本时）：

```bash
adb shell su -c "am broadcast -p com.tencent.mobileqq -a io.github.juwanhwang.autoqqpet.CMD --es action packet --es op on"
adb logcat | grep "Autoqqpet/PKT"
```

要点：
- `peek` 默认只报 `known_cmds.txt` 里**没有的新号**，并把新号追加进去；`peek_mark.txt` / `baseline.txt` 是运行态文件。
- 模块侧单条包体截到约 1400 字节，超大响应会被拆成 `[1/2]` 多行，脚本会自动拼回。
- 如果提示“没有活着的 Kuikly 实例”：先 `packet op=on`，再进一次宠物页触发发包。
- PC 与手机时钟可能差一分多钟，`raw --since` 一律用手机侧时间（`capture.py on` 已自动记录）。

## 2. 只读命令的复现（可选）

确认了某条**只读**命令的包形后，可以用 debug 版的调试命令手发验证：

```bash
# 读任意命令并打印字段树（只读）
adb shell su -c "am broadcast -p com.tencent.mobileqq -a io.github.juwanhwang.autoqqpet.CMD --es action dbgcmd --es cmd OidbSvcTrpcTcp.0x9xxx_n --ei command <十进制命令号> --ei sub <n> --es body <hex>"
# 例：0x9762 → --ei command 38754（am 的 --ei 只认十进制）
```

`rawsend`（原样发包）也在 debug 版里，**写命令不要用它试探**——空回包不代表没生效，重发可能重复扣金币/重复领取。
sub 号不确定时，从 0 开始试；服务器回 `150 no privilege` 说明 sub 错了。

## 3. 静态还原（补全“这个号是干什么的”）

```bash
python scan_apk.py     --apk "<QQ 安装包路径>/qq_9375.apk"   # 扫主 APK → main.tsv + whitelist.txt
python pull_bundles.py --extract                             # 从真机拉宠物页面动态包（需 Root+adb；先把宠物各页面点开一遍）
python scan_bundles.py                                       # 扫动态包 → bundles.tsv
python rollup.py --known ../../docs/qqpet-protocol-spec.md   # 汇总 → cmd_map.tsv / cmd_map.md（带 A/B/C/D/N 证据分级）
python drill.py --cmd 0x975e                                 # 看某个号在哪个类/方法、连带残留文案
python who_uses.py / class_body.py / find_values.py          # 辅助：谁在用某个号、看类体、在回包里找某个值
```

证据分级与常见坑见 [pet-command-recon.md](pet-command-recon.md)。

## 4. 把结论写回仓库

1. **只写结论**：在 `tools/pet_recon/meanings.tsv` 追加一行（`cmd  sub  含义  依据  status  备注`）。
   `status` 用 `confirmed`（必须有实测/静态A/文档依据）、`likely`、`todo`、`rejected`；同 cmd+sub 的**后写行覆盖前面**。
   依据写“抓包实测 日期 + QQ 版本”，**不要贴原始 hex、uin、petId、token**。
2. 有 `_scratch/pet_recon/cmd_map.tsv`（第 3 步产物）时，运行 `python make_command_table.py` 重新生成 [pet-commands.md](pet-commands.md)；
   没有时脚本会提示并跳过，不会覆盖现有总表。
3. 模块要用到这条命令时：在 `app/.../net/Commands.kt` 加一个常量（`op("OidbSvcTrpcTcp.0x9xxx_n")`），
   字段布局写在 `pet/service/*Api.kt`，解析写在 `pet/parse/Parsers.kt` 并补单元测试；同步更新 [commands-in-use.md](commands-in-use.md)。

## 5. QQ 更新后适配的思路

- 先看通知栏/主页是否提示“没有心跳”。入口发现按方法签名找，不写死类名（`hook/LoaderProbes.kt`、`pet/QQPetBridge.kt`）。
- 再用第 1 步抓一遍官方客户端的包，对照 [commands-in-use.md](commands-in-use.md) 看命令号、sub、字段有没有变。
- 激励视频（`RewardAdApi`）与 WebSSO（`WebSsoDirect`）通过反射宿主的混淆类名工作，**最容易随 QQ 版本失效**。
- 这类机械的对照与修改很适合交给 AI 助手：把抓到的结论（不是原始包）和相关源码一起给它。
