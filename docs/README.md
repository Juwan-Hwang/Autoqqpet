# 文档索引

> ⚠ 文档里的命令含义与协议字段均由作者**逆向整理**，**不保证准确**；QQ 更新后可能失效。写命令勿盲发。

## 命令与协议

| 文档 | 内容 |
| --- | --- |
| [commands-in-use.md](commands-in-use.md) | **模块当前实际在用的命令**（以 `net/Commands.kt` 为准）：命令名、用途、读/写、请求关键字段、确信程度、来源。先看这个 |
| [pet-commands.md](pet-commands.md) | 全量逆向总表（含静态扫描到、尚未接入的命令），由 `tools/pet_recon/make_command_table.py` 生成；最后生成于 2026-10-17 |
| [qqpet-protocol-spec.md](qqpet-protocol-spec.md) | 进程内发包入口、字段表与工程规则（写命令空回包不重发等） |
| `../tools/pet_recon/meanings.tsv` | 命令含义的人工确认层（总表的“真源”），新结论写这里 |
| `../tools/pet_recon/known_cmds.txt` | 抓包时“已见过的命令号”，`capture.py peek` 据此只报新号 |

## 抓命令工具（tools/pet_recon）

| 文档 | 内容 |
| --- | --- |
| [capture-guide.md](capture-guide.md) | **抓新命令的完整流程**：环境（Root + adb + debug 版模块 + Python 3）、`capture.py on/peek/raw/off`、`packet op=on` 开关、静态扫描、把结论写回仓库 |
| [pet-command-recon.md](pet-command-recon.md) | 命令语义还原的方法论（三层来源、证据分级、历史结论） |

脚本速查：`capture.py`（动态抓包与解码）、`scan_apk.py` / `pull_bundles.py` / `scan_bundles.py` / `rollup.py`（静态扫描）、
`drill.py` / `who_uses.py` / `class_body.py` / `find_values.py`（定位与辅助）、`make_command_table.py`（生成总表）。

## 设计与策略

| 文档 | 内容 |
| --- | --- |
| [CONTRACT.md](CONTRACT.md) | 设置项契约（键名、默认值、哪一侧读写） |
| [game-economy-model.md](game-economy-model.md) | 游戏经济模型 |
| [study-work-optimizer.md](study-work-optimizer.md) | 学习/打工最优调度 |
| [design/DESIGN.md](design/DESIGN.md) | 界面设计规范 |

## 开发与构建

- 环境：JDK 17+（CI 用 21）、Android SDK（compileSdk 37）。不需要 NDK。
- 构建：`./gradlew assembleDebug`（带抓包/调试命令）或 `./gradlew assembleRelease`（不混淆，仅裁剪）。
- 单元测试：`./gradlew testDebugUnitTest`（含 Robolectric + Roborazzi 截图测试）。
- 签名：仓库不带任何签名材料。在仓库根放自己的 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）即用你的 key；
  没有时自动用默认 debug 签名。**自签包与官方包签名不同，从官方版换过来要先卸载再装。**
- 应用内许可协议：改根目录 `EULA.md` 后运行 `python tools/sync_eula.py` 同步到 `app/src/main/assets/eula.md`（`--check` 校验）。
- 源码公开版停用了 App 开屏前的协议确认页（代码以注释形式保留在 `ui/App.kt`、`ui/data/Backend.kt`）。
