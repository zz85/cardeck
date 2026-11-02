package io.github.shaonialife.cardeck.video

/**
 * Re-prime state for a receiver whose decoder was torn down (wireless adapter re-entry).
 *
 * The reference sender (unapple-phone 853f400/1d7a4e6) resends VideoConfig + an IDR on
 * `requestUI`/`forceKeyFrame` and makes the following frames continue from that IDR: a cold
 * decoder fed `config + IDR` followed by a P-frame of the old GOP shows black. With MediaCodec
 * the IDR arrives a frame or two after the sync request, so frames until then are held back
 * and the cached config is resent right in front of the IDR.
 */
internal class RePrimeGate(
    private val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MS,
) {
    enum class Decision {
        /** Normal frame. */
        SEND,

        /** Old-GOP frame while waiting for the requested IDR. */
        DROP,

        /** The IDR we asked for: resend VideoConfig first, then this frame. */
        SEND_WITH_CONFIG,

        /** No IDR within [maxWaitMillis]: give up holding back, resend config and send anyway. */
        SEND_WITH_CONFIG_TIMEOUT,
    }

    @Volatile
    private var requestedAt: Long = -1L

    val pending: Boolean get() = requestedAt >= 0

    fun request(nowMillis: Long) {
        if (requestedAt < 0) requestedAt = nowMillis
    }

    fun onFrame(
        keyFrame: Boolean,
        nowMillis: Long,
    ): Decision {
        val since = requestedAt
        if (since < 0) return Decision.SEND
        return when {
            keyFrame -> {
                requestedAt = -1L
                Decision.SEND_WITH_CONFIG
            }
            nowMillis - since >= maxWaitMillis -> {
                requestedAt = -1L
                Decision.SEND_WITH_CONFIG_TIMEOUT
            }
            else -> Decision.DROP
        }
    }

    companion object {
        /** The encoder emits a periodic IDR every second anyway; 1.5 s means the request was lost. */
        const val DEFAULT_MAX_WAIT_MS = 1_500L
    }
}
