package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.DiaryEntry
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers

/** 日记族：按月列表（0x9762）与点赞（0x9ba3，已点过服务器静默忽略）。抓包实测 2026-11-11。 */
class DiaryApi(private val s: PetSession) {

    /** 0x9762：`{1:petId, 3:"YYYY-MM"}`，回包按天给日记 uuid。 */
    fun month(month: String): List<DiaryEntry> =
        Parsers.diaryMonth(s.read(Commands.DIARY_MONTH, pb { str(1, s.ensurePetId()); str(3, month) }))

    /** 0x9ba3：`{1:petId, 2:日记uuid}`；返回日记的感谢文案（已点过=空串）。 */
    fun like(uuid: String): String =
        Parsers.diaryLikeText(s.write(Commands.DIARY_LIKE) { pb { str(1, s.ensurePetId()); str(2, uuid) } })
}
