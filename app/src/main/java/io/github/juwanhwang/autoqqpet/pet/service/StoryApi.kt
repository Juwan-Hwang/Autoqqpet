package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.CareerInfo
import io.github.juwanhwang.autoqqpet.pet.PageRules
import io.github.juwanhwang.autoqqpet.pet.SceneOption
import io.github.juwanhwang.autoqqpet.pet.Settlement
import io.github.juwanhwang.autoqqpet.pet.StartedStory
import io.github.juwanhwang.autoqqpet.pet.StoryProgress
import io.github.juwanhwang.autoqqpet.pet.StoryOutcome
import io.github.juwanhwang.autoqqpet.pet.StoryStatus
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers

/**
 * 主任务族（户外故事）：状态/进度/结算展示 + 目录 + 开始/结算/召回/鼓励 + 页面规则与行为上报。
 * 开课/开工/冒险/PK 全走 0x975e；结算与召回都走 0x9760（只差 action）。
 */
class StoryApi(private val s: PetSession) {

    /** STORY_STATUS(0x975a_1)：f1=petId, f100=2。 */
    fun status(): StoryStatus = Parsers.storyStatus(s.read(Commands.STORY_STATUS, pb { str(1, s.ensurePetId()); num(100, Proto.OUTDOOR_V) }))

    /**
     * 进度轮询 STORY_PROGRESS(0x975f_1)：`{1:storyId, 2:petId, 100:2}`（抓包实测 2026-10-14，打工中客户端每 7s 一次）。
     * 回包带叙事流水与搭档昵称；我们只在 tick/复查时读一次（不跟客户端 7s 一刷）。
     */
    fun progress(storyId: String): StoryProgress =
        Parsers.storyProgress(storyId, s.read(Commands.STORY_PROGRESS, pb { str(1, storyId); str(2, s.ensurePetId()); num(100, Proto.OUTDOOR_V) }))

    /**
     * 结算展示页 SETTLE_VIEW(0x9874_1)：`{1:petId, 2:storyId, 3:结算时间戳ms, 4:2, 100:2}`（抓包实测 2026-10-14 19:33:12，
     * 600s 打工到期那一秒）。回包带奖励明细（名称/数量/图标），是“这条任务实际拿到多少”的唯一直接来源。
     */
    fun settlementView(storyId: String, settledAtMs: Long): Settlement =
        Parsers.settlement(storyId, s.read(Commands.SETTLE_VIEW, pb {
            str(1, s.ensurePetId()); str(2, storyId); num(3, settledAtMs); num(4, 2); num(100, Proto.OUTDOOR_V)
        }))

    /**
     * 出门记录 OUTING_HISTORY(0x9876_1)：`{[1:游标 ms], 2:每页条数, 3:petId, 100:2}`（2026-10-16 抓包）。
     * 第一页不带游标；翻页时 f1 传上一页最后一条的时间戳（ms）。
     */
    fun history(pageSize: Int = 20, cursorMs: Long = 0L): io.github.juwanhwang.autoqqpet.pet.OutingPage =
        Parsers.outingHistory(s.read(Commands.OUTING_HISTORY, pb {
            if (cursorMs > 0) num(1, cursorMs)
            num(2, pageSize.toLong()); str(3, s.ensurePetId()); num(100, Proto.OUTDOOR_V)
        }))

    /**
     * 单条详情（0x9874_1，传任意 storyId 都行，进行中/已结束都能读）：结果枚举、属性奖励块、开始时间戳、场景、课程、PK 每日上限文案。
     * 请求体与结算展示页同形（时间戳传当前时刻即可）。
     */
    fun detail(storyId: String): io.github.juwanhwang.autoqqpet.pet.StoryDetail =
        Parsers.storyDetail(storyId, s.read(Commands.SETTLE_VIEW, pb {
            str(1, s.ensurePetId()); str(2, storyId); num(3, System.currentTimeMillis()); num(4, 2); num(100, Proto.OUTDOOR_V)
        }))

    /** 学园阶段 OUTDOOR_OVERVIEW(0x9b60_1)：f1=6100,f2=petId,f100=2 → f4。 */
    fun schoolStage(): Long = Parsers.schoolStage(s.read(Commands.OUTDOOR_OVERVIEW, pb {
        num(1, Proto.SCENE_SCHOOL); str(2, s.ensurePetId()); num(100, Proto.OUTDOOR_V)
    }))

    /** 课程目录 SCENE_CATALOG：f1=6100,f2=petId,f3="",f11=阶段,f100=2。 */
    fun courses(stage: Long): List<SceneOption> = Parsers.sceneOptions(s.read(Commands.SCENE_CATALOG, pb {
        num(1, Proto.SCENE_SCHOOL); str(2, s.ensurePetId()); str(3, ""); num(11, stage); num(100, Proto.OUTDOOR_V)
    }))

    /** 职业总览：f1=6400。 */
    fun careers(): List<CareerInfo> = Parsers.careers(s.read(Commands.OUTDOOR_OVERVIEW, pb {
        num(1, Proto.SCENE_WORK); str(2, s.ensurePetId()); num(100, Proto.OUTDOOR_V)
    }))

    /**
     * 岗位目录：f1=6400,f2=petId,f3=好友petId(查好友可做岗位，可空),f10=职业 type,f100=[version]。
     * 请求体核对（R3，2026-10-15）：客户端雇佣链抓包（10-15 00:04:17）发的就是 `{1:6400, 10:careerType, 100:2}`，
     * 而 joblist 用同一请求体读到过完整 32 档 → 岗位目录 **f100=2 是对的**；“0x9ab2 要 f100=3 才回全量”实锤的是**冒险目录**（6700）。
     * 读成功但一条都没有时，[CatalogReader] 会再用 f100=3 试一次（不增加正常路径的请求量）。
     */
    fun jobs(careerType: Long, hiredPetId: String = "", version: Long = Proto.OUTDOOR_V): List<SceneOption> = Parsers.sceneOptions(s.read(Commands.SCENE_CATALOG, pb {
        num(1, Proto.SCENE_WORK); str(2, s.ensurePetId()); str(3, hiredPetId); num(10, careerType); num(100, version)
    }))

    /** 冒险目录：f1=6700,**f100=3**（抓包实锤；给 2 只回 1 条）。 */
    fun adventures(hiredPetId: String = ""): List<SceneOption> = Parsers.sceneOptions(s.read(Commands.SCENE_CATALOG, pb {
        num(1, Proto.SCENE_ADVENTURE); str(2, s.ensurePetId()); str(3, hiredPetId); num(100, Proto.ADVENTURE_CATALOG_V)
    }))

    /**
     * 雇佣/PK 对手共用子消息 `f4={1:petId, 2:uin}`（抓包实测 2026-10-14 20:43:19，两处同一顺序）。
     * [hired] = (好友uin, 好友petId)。
     */
    private fun io.github.juwanhwang.autoqqpet.net.PbBuilder.partner(hired: Pair<String, String>) =
        msg(4) { str(1, hired.second); str(2, hired.first) }

    /** 开课：f1=6100,f2=petId,f3="",f6=课名,f7=subEvent,f100=2。 */
    fun startSchool(course: SceneOption): StartedStory = Parsers.startedStory(s.write(Commands.STORY_START) { pb {
        num(1, Proto.SCENE_SCHOOL); str(2, s.ensurePetId()); str(3, ""); str(6, course.name); num(7, course.subEventType); num(100, Proto.OUTDOOR_V)
    } })

    /** 开工：f1=6400,f2=petId,f3="",[f4=雇佣],f6=岗位名,f7=subEvent,f100=2。带 f4 即雇佣，雇人不收费。 */
    fun startWork(job: SceneOption, hired: Pair<String, String>? = null): StartedStory = Parsers.startedStory(s.write(Commands.STORY_START) { pb {
        num(1, Proto.SCENE_WORK); str(2, s.ensurePetId()); str(3, "")
        if (hired != null) partner(hired)
        str(6, job.name); num(7, job.subEventType); num(100, Proto.OUTDOOR_V)
    } })

    /** 冒险：f1=6700,f2=petId,f3="",[f4=雇佣],f6=名,[f7 仅 >0 时],f100=2。 */
    fun startAdventure(option: SceneOption, hired: Pair<String, String>? = null): StartedStory = Parsers.startedStory(s.write(Commands.STORY_START) { pb {
        num(1, Proto.SCENE_ADVENTURE); str(2, s.ensurePetId()); str(3, "")
        if (hired != null) partner(hired)
        str(6, option.name)
        if (option.subEventType > 0) num(7, option.subEventType)
        num(100, Proto.OUTDOOR_V)
    } })

    /** PK：f1=6900,f2=petId,f4={1:对手petId,2:对手uin},f6={10:75},f7=6901,f100=2。 */
    fun startPk(oppUin: String, oppPetId: String): StartedStory = Parsers.startedStory(s.write(Commands.STORY_START) { pb {
        num(1, Proto.SCENE_PK); str(2, s.ensurePetId()); partner(oppUin to oppPetId)
        msg(6) { num(10, 75) }; num(7, Proto.PK_SUB_EVENT); num(100, Proto.OUTDOOR_V)
    } })

    /**
     * 到期领奖 STORY_SETTLE(0x9760_1)：`{1:storyId, 2:1000, 3:petId, 100:2}`。
     * 三个样本（交接 §9.13）：人不在页面/人在页面都**不发也到账** → 这一发不是到账前提，只是页面顺手做的；
     * 由设置 `settle_claim_on` 决定发不发（默认发，与人在页面时的客户端一致）。回包同 0x9874 形状，可读结局。
     */
    fun settle(storyId: String): StoryOutcome = Parsers.storyOutcome(s.write(Commands.STORY_SETTLE) { pb {
        str(1, storyId); num(2, Proto.SETTLE_ACTION_CLAIM); str(3, s.ensurePetId()); num(100, Proto.OUTDOOR_V)
    } })

    /**
     * 召回/提前结束/PK 结算 = 同一条 0x9760_1 换 action=6000：`{1:storyId, 2:6000, 3:petId, 4:3, 100:2}`。
     * **不看 recallable**：被雇召回实测（2026-10-15 01:52）`.5` 缺省照样成功。
     * f4 必须发 3（见 [Proto.SETTLE_RECALL_F4]）：旧版发 0，打工召回能过、冒险召回被 135004 拒（2026-10-19 实测钉死）。
     * 回包 `.1.16.7` = 到账金币、`.1.12`“未获得完整收益”；PK 场景 `.1.18.8` = 大获全胜/惜败。
     */
    fun recall(storyId: String): StoryOutcome = Parsers.storyOutcome(s.write(Commands.STORY_SETTLE) { recallBody(storyId, s.ensurePetId()) })

    companion object {
        /** 召回包体（0x9760_1）：全仓召回都经 [recall] 拼这一份，包形只在这里出现一次。 */
        internal fun recallBody(storyId: String, petId: String): ByteArray = pb {
            str(1, storyId); num(2, Proto.SETTLE_ACTION_RECALL); str(3, petId); num(4, Proto.SETTLE_RECALL_F4); num(100, Proto.OUTDOOR_V)
        }
    }

    /** 结算展示页原始回包 → 结局（PK 输赢、雇佣名、召回金币都在这）。 */
    fun settlementOutcome(storyId: String, settledAtMs: Long): StoryOutcome =
        Parsers.storyOutcome(s.read(Commands.SETTLE_VIEW, pb {
            str(1, s.ensurePetId()); str(2, storyId); num(3, settledAtMs); num(4, 2); num(100, Proto.OUTDOOR_V)
        }))

    /** 鼓励 STORY_ENCOURAGE(0x9c44_1)：f1=petId,f2=storyId（仅学工）。 */
    fun encourage(storyId: String): String = Parsers.describe(s.write(Commands.STORY_ENCOURAGE) { pb { str(1, s.ensurePetId()); str(2, storyId) } })

    /** 页面规则 PAGE_RULES(0x96a4_1)：f2=petId,f3=页面,f4=空,f10=0,f99=0。 */
    fun pageRules(page: Long): PageRules = Parsers.pageRules(s.read(Commands.PAGE_RULES, pb {
        str(2, s.ensurePetId()); num(3, page); bytes(4, ByteArray(0)); num(10, 0); num(99, 0)
    }))

    /** 行为上报 REPORT_EVENT(0x96a6_1)：f1=petId(访问好友时为好友 petId),f2=好友uin,f3=path{page,event,sub},f4=空。成功回包常为空。 */
    fun reportEvent(page: Long, eventType: Long, subEvent: Long, friendUin: String = "", petId: String = s.ensurePetId()): String =
        Parsers.describe(s.write(Commands.REPORT_EVENT) { pb {
            str(1, petId); str(2, friendUin)
            msg(3) { num(1, page); num(2, eventType); num(3, subEvent) }
            bytes(4, ByteArray(0))
        } })
}
