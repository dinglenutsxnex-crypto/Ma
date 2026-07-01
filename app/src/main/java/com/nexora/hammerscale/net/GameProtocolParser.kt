package com.nexora.hammerscale.net

object GameProtocolParser {
    fun extractPayload(data: ByteArray): ByteArray? = null
    fun readProtoFields(data: ByteArray): Map<Int, Any> = emptyMap()
    fun extractClanRoundsFromStartResponse(frame: ByteArray): Int? = null
    fun extractBattleSeqFromServerStart(frame: ByteArray): Int? = null
    fun tryExtractFinishFight(data: ByteArray): Pair<Long, Long>? = null
    fun tryExtractClanFinishFight(data: ByteArray): Pair<Long, Long>? = null
    fun tryExtractRaidFightFinish(data: ByteArray): Boolean = false
    fun tryExtractBrawlerFinish(data: ByteArray): Boolean = false
    fun extractCounter(data: ByteArray): Long? = null
}
