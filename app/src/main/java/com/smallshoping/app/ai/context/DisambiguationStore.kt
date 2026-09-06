package com.smallshoping.app.ai.context

/**
 * 待消歧问题存储端口（按设备会话，单槽位：一个设备同时只挂一个追问）。
 *
 * 过期未回答的追问自动失效，不阻塞后续操作。
 */
interface DisambiguationStore {

    /** 当前待消歧问题；不存在或已过期返回 null。 */
    fun pending(deviceId: String): PendingQuestion?

    /** 挂起一个新追问（覆盖旧追问）。 */
    fun propose(deviceId: String, question: PendingQuestion)

    /** 清除追问（已解决或被新任务取代）。 */
    fun clear(deviceId: String)
}
