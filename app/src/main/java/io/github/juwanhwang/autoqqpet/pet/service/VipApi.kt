package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.WebSsoDirect
import io.github.juwanhwang.autoqqpet.pet.VipGift
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import org.json.JSONObject

/** VIP/SVIP 族：每日礼包列表（0x9bd4）与领取（WebSSO 0x9377 直调，不依赖 Kuikly 页面实例）。 */
class VipApi(private val s: PetSession) {

    /** 0x9bd4：空 body。回包 `.4.1`=1 可领 / 2 已领。抓包实测 2026-11-10。 */
    fun receiveInfo(): VipGift = Parsers.vipGift(s.read(Commands.VIP_RECEIVE, ByteArray(0)))

    /**
     * 领取（抓包 2026-11-10 21:23）：WebSSO `MQUpdateSvc_com_qq_vip.web.OidbSvcTrpcJsapiTcp.0x9377_0`，
     * data `{SubActId, ActReqData:"{}", ExtInfo:{"0":"1","2":"1"}, ClientPlat:1}`。
     * 结果在回包 `.4` 的 JSON 里：`{"Code":0,…}` 成功；`{"Code":-4020,"Msg":"中了用户日限制"}` 今天已领。
     * [onResult] 收到的是那段 JSON（拿不到时为空串），回调线程是宿主网络回调线程。
     */
    fun claim(subActId: String, onResult: (String) -> Unit): String {
        val data = JSONObject().apply {
            put("SubActId", subActId)
            put("ActReqData", "{}")
            put("ExtInfo", JSONObject().apply { put("0", "1"); put("2", "1") })
            put("ClientPlat", 1)
        }
        val params = JSONObject().apply {
            put("cmd", "MQUpdateSvc_com_qq_vip.web.OidbSvcTrpcJsapiTcp.0x9377_0")
            put("data", data)
            put("withoutLogin", 0)
            put("timeout", 6)
        }
        return WebSsoDirect.send(params.toString()) { args -> onResult(claimResultJson(args)) }
    }

    /** 从回调参数里取 WebSSO 回包 `.4`（JSON 串）。 */
    private fun claimResultJson(args: Array<Any?>?): String {
        val bytes = args?.filterIsInstance<ByteArray>()?.maxByOrNull { it.size } ?: return ""
        return runCatching { ProtoBuf.firstString(ProtoBuf.parseMessage(bytes), 4) }.getOrDefault("")
    }
}
