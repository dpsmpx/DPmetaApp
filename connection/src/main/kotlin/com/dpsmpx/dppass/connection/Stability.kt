package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.config.ServerProfile
import com.dpsmpx.dppass.config.TransportClass
import com.dpsmpx.dppass.config.transportClass
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.network.NetworkAssessment
import com.dpsmpx.dppass.network.Reachability
import com.dpsmpx.dppass.network.RestrictionClass
import com.dpsmpx.dppass.storage.BlobStore
import kotlin.math.abs
import kotlin.math.min

/**
 * Online stability learner for DPpass 0.5.0.
 *
 * It uses a Bayesian success prior plus EWMA latency/jitter/stall estimators.
 * The model is intentionally small, deterministic and explainable; it stores
 * only aggregate observations and never domains, IPs, payloads or secrets.
 *
 * Objective order: reliability > jitter stability > RTT > throughput.
 */
data class StabilitySample(
    val success: Boolean,
    val rttMillis: Long? = null,
    val stalled: Boolean = false,
    val timestampMillis: Long,
)

data class StabilityStats(
    val attempts: Long = 0,
    val successes: Long = 0,
    val ewmaRttMillis: Double = 0.0,
    val ewmaJitterMillis: Double = 0.0,
    val ewmaStall: Double = 0.0,
    val lastRttMillis: Double? = null,
    val badStreak: Int = 0,
    val lastUpdatedMillis: Long = 0,
) {
    val failureRate: Double
        get() = if (attempts == 0L) 0.0 else 1.0 - successes.toDouble() / attempts

    val successRateWithPrior: Double
        get() = (successes + 2.0) / (attempts + 4.0)

    val hasObservation: Boolean
        get() = attempts > 0

    fun stabilityScore(): Double {
        if (attempts == 0L) return 0.62
        val reliability = successRateWithPrior
        val rtt = if (ewmaRttMillis <= 0.0) 0.72 else 1.0 / (1.0 + ewmaRttMillis / 250.0)
        val jitter = if (ewmaJitterMillis <= 0.0) 0.85 else 1.0 / (1.0 + ewmaJitterMillis / 40.0)
        val stall = 1.0 - min(1.0, ewmaStall)
        return 0.58 * reliability + 0.22 * jitter + 0.15 * rtt + 0.05 * stall
    }
}

/** Coarse context only: no destination names, addresses, payloads or secrets. */
data class StabilityContext(
    val network: String,
    val restriction: RestrictionClass,
    val tls: Reachability,
    val udp: Reachability,
    val bulk: Reachability,
) {
    override fun toString(): String =
        listOf(network, restriction.name, tls.name, udp.name, bulk.name).joinToString("~")

    companion object {
        fun from(assessment: NetworkAssessment?): StabilityContext = StabilityContext(
            network = assessment?.type?.name ?: "UNKNOWN",
            restriction = assessment?.restriction ?: RestrictionClass.UNKNOWN,
            tls = assessment?.tls ?: Reachability.UNKNOWN,
            udp = assessment?.udp ?: Reachability.UNKNOWN,
            bulk = assessment?.bulk ?: Reachability.UNKNOWN,
        )
    }
}

/**
 * Persistent aggregate learner. The Android instance receives the encrypted
 * DPpass BlobStore, so its durable representation is encrypted on disk.
 */
class StabilityBook(
    private val store: BlobStore? = null,
) {
    private val map = LinkedHashMap<String, StabilityStats>()

    init {
        restore()
    }

    @Synchronized
    fun observe(
        profileId: ProfileId,
        sample: StabilitySample,
        context: StabilityContext = StabilityContext.from(null),
    ) {
        val key = key(profileId, context)
        val previous = map[key] ?: StabilityStats()
        val rtt = sample.rttMillis?.coerceIn(1L, 60_000L)?.toDouble()
        val jitter = if (rtt != null && previous.lastRttMillis != null) {
            abs(rtt - previous.lastRttMillis)
        } else {
            0.0
        }
        val alpha = 0.25
        val nextRtt = when {
            rtt == null -> previous.ewmaRttMillis
            previous.ewmaRttMillis <= 0.0 -> rtt
            else -> previous.ewmaRttMillis * (1.0 - alpha) + rtt * alpha
        }
        val nextJitter = if (previous.ewmaJitterMillis <= 0.0) {
            jitter
        } else {
            previous.ewmaJitterMillis * (1.0 - alpha) + jitter * alpha
        }
        val stallValue = if (sample.stalled) 1.0 else 0.0
        val nextStall = previous.ewmaStall * (1.0 - alpha) + stallValue * alpha
        val bad = if (
            !sample.success ||
            sample.stalled ||
            jitter >= BAD_JITTER_MILLIS ||
            (rtt ?: 0.0) >= BAD_RTT_MILLIS
        ) {
            previous.badStreak + 1
        } else {
            0
        }
        map[key] = StabilityStats(
            attempts = (previous.attempts + 1).coerceAtMost(MAX_ATTEMPTS),
            successes = (previous.successes + if (sample.success) 1 else 0).coerceAtMost(MAX_ATTEMPTS),
            ewmaRttMillis = nextRtt.coerceIn(0.0, 60_000.0),
            ewmaJitterMillis = nextJitter.coerceIn(0.0, 60_000.0),
            ewmaStall = nextStall.coerceIn(0.0, 1.0),
            lastRttMillis = rtt,
            badStreak = bad.coerceAtMost(MAX_BAD_STREAK),
            lastUpdatedMillis = sample.timestampMillis,
        )
        trim()
    }

    @Synchronized
    fun stats(profileId: ProfileId, context: StabilityContext = StabilityContext.from(null)): StabilityStats =
        map[key(profileId, context)] ?: StabilityStats()

    @Synchronized
    fun score(profile: ServerProfile, assessment: NetworkAssessment?): Double {
        val context = StabilityContext.from(assessment)
        val learned = exactOrLatest(profile.id, context)
        // D-37: без наблюдений оценка у всех одна, без надбавки по классу — порядок решает D-15.
        if (!learned.hasObservation) return learned.stabilityScore()
        return (learned.stabilityScore() + contextBias(profile, assessment)).coerceIn(0.0, 1.0)
    }

    @Synchronized
    fun shouldRecover(profileId: ProfileId, assessment: NetworkAssessment? = null): Boolean =
        shouldRecover(profileId, StabilityContext.from(assessment))

    @Synchronized
    fun shouldRecover(profileId: ProfileId, context: StabilityContext): Boolean {
        val s = stats(profileId, context)
        return s.hasObservation && s.badStreak >= 2 && (s.stabilityScore() < RECOVERY_SCORE || (s.badStreak >= 1 && s.stabilityScore() < SEVERE_RECOVERY_SCORE))
    }

    @Synchronized
    fun persist(): Outcome<Unit> {
        val target = store ?: return Outcome.Success(Unit)
        val body = buildString {
            append("dppass-adaptive-v1\n")
            for ((key, s) in map.toSortedMap()) {
                append(key).append('|')
                    .append(s.attempts).append('|')
                    .append(s.successes).append('|')
                    .append(s.ewmaRttMillis).append('|')
                    .append(s.ewmaJitterMillis).append('|')
                    .append(s.ewmaStall).append('|')
                    .append(s.lastRttMillis ?: -1.0).append('|')
                    .append(s.badStreak).append('|')
                    .append(s.lastUpdatedMillis).append('\n')
            }
        }
        return target.write(STORAGE_KEY, body.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun contextCount(): Int = map.size

    private fun restore() {
        val target = store ?: return
        val bytes = when (val r = target.read(STORAGE_KEY)) {
            is Outcome.Failure -> return
            is Outcome.Success -> r.value ?: return
        }
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return
        val lines = text.lineSequence()
        if (lines.firstOrNull() != "dppass-adaptive-v1") return
        for (line in lines.drop(1).take(MAX_RECORDS)) {
            val p = line.split('|')
            // Ключ записи — «профиль|контекст», сам содержит '|': полей 10, не 9.
            if (p.size != 10) continue
            val stats = runCatching {
                StabilityStats(
                    attempts = p[2].toLong().coerceIn(0, MAX_ATTEMPTS),
                    successes = p[3].toLong().coerceIn(0, MAX_ATTEMPTS),
                    ewmaRttMillis = p[4].toDouble().coerceIn(0.0, 60_000.0),
                    ewmaJitterMillis = p[5].toDouble().coerceIn(0.0, 60_000.0),
                    ewmaStall = p[6].toDouble().coerceIn(0.0, 1.0),
                    lastRttMillis = p[7].toDouble().takeIf { it >= 0.0 }?.coerceAtMost(60_000.0),
                    badStreak = p[8].toInt().coerceIn(0, MAX_BAD_STREAK),
                    lastUpdatedMillis = p[9].toLong(),
                )
            }.getOrNull() ?: continue
            if (p[0].isNotEmpty() && p[1].count { it == '~' } == 4) map[p[0] + "|" + p[1]] = stats
        }
    }

    private fun exactOrLatest(profileId: ProfileId, context: StabilityContext): StabilityStats {
        val exact = map[key(profileId, context)]
        if (exact != null) return exact
        val prefix = profileId.value + "|"
        return map.asSequence()
            .filter { it.key.startsWith(prefix) }
            .maxByOrNull { it.value.lastUpdatedMillis }
            ?.value
            ?: StabilityStats()
    }

    private fun trim() {
        if (map.size <= MAX_RECORDS) return
        val stale = map.entries.sortedBy { it.value.lastUpdatedMillis }.take(map.size - MAX_RECORDS)
        stale.forEach { map.remove(it.key) }
    }

    private fun key(profileId: ProfileId, context: StabilityContext): String =
        profileId.value + "|" + context.toString()

    private fun contextBias(profile: ServerProfile, assessment: NetworkAssessment?): Double {
        if (assessment == null) return 0.0
        if (assessment.restriction == RestrictionClass.ALLOWLIST_LIKE) {
            return if (profile.hints.reachableUnderAllowlist) 0.08 else -0.35
        }
        return when (profile.transportClass) {
            TransportClass.TCP_STREAM ->
                if (assessment.tls != Reachability.OK || assessment.bulk == Reachability.DEGRADED) -0.12 else 0.0
            TransportClass.HTTP_CDN ->
                if (assessment.tcp == Reachability.BLOCKED && assessment.tls != Reachability.OK) -0.08 else 0.03
            TransportClass.UDP_QUIC ->
                if (assessment.udp == Reachability.OK) 0.04 else -0.16
        }
    }

    companion object {
        private const val STORAGE_KEY = "adaptive/stability"
        private const val MAX_RECORDS = 128
        private const val MAX_ATTEMPTS = 100_000L
        private const val MAX_BAD_STREAK = 64
        private const val BAD_JITTER_MILLIS = 150.0
        private const val BAD_RTT_MILLIS = 3_000.0
        private const val RECOVERY_SCORE = 0.50
        private const val SEVERE_RECOVERY_SCORE = 0.30
    }
}
