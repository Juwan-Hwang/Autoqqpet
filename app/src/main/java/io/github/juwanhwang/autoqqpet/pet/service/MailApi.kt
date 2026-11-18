package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.Mail
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers

/**
 * 宠物信箱族（`pet_feed_history` 实为 PetMailBoxRepository）。**全族 sub=0**（`_1` 一律 150，交接 §9.7）。
 *  - 0x9c11_0 `{1:petId}` 邮件列表（附件 = 可领）；
 *  - 0x9c19_0 `{1:petId, 100:2}` 一键全部领取；无可领回 136610（调用方当“无事”处理）；
 *  - 0x9c17 单封领取**未实测，不用**。
 */
class MailApi(private val s: PetSession) {
    fun list(): List<Mail> = Parsers.mails(s.read(Commands.MAIL_LIST, pb { str(1, s.ensurePetId()) }))

    fun claimableCount(): Long = list().count { it.hasAttachment }.toLong()

    /** 一键领取：返回原始回包（结构未抓到成功样本，只做日志与“非空即领到”参考）。 */
    fun batchClaim(): ByteArray = s.write(Commands.MAIL_BATCH_CLAIM) { pb { str(1, s.ensurePetId()); num(100, Proto.OUTDOOR_V) } }
}
