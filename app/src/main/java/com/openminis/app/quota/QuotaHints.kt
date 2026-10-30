package com.openminis.app.quota

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// [T-provider-quota P2] What the model picker, the Model Group rows and the
// composer may say about quota. Pure rules, port of iOS
// QuotaPickerHintRules / QuotaComposerHintRules (QuotaPersistence.swift).

// MARK: - Model picker hints

/**
 * Deliberately quiet: nothing unless there is real data AND a warning, never a
 * percent for a prepaid balance, never an "available" / green state.
 */
object QuotaPickerHintRules {
    /**
     * The tightest constraint is a pool bound to THIS model. [detail] is e.g.
     * "5h 92%"; null when no percent exists.
     */
    data class ModelSpecificHint(val status: QuotaStatus, val detail: String?, val isManual: Boolean)

    /** Provider section rows: model-specific warnings only (the header shows the account). */
    fun modelRow(v: ModelQuotaView): ModelSpecificHint? {
        val d = v.deciding ?: return null
        if (!v.effective.isWarning || d.isShared) return null
        return ModelSpecificHint(d.status.status, detail(d.status), d.isManual)
    }

    /**
     * [T-quota-group-member-pill] Model Group member rows (picker group rows
     * and the Model Group settings page) have no provider header, so they show
     * one short figure after the model name, which opens the quota sheet:
     * - the model's OWN pool first, else the account's - the tightest of the kind;
     * - the used percent when a reliable percent exists, else the remaining
     *   amount in its unit ("$1.12", "CN¥36.51");
     * - nothing without real, unexpired data.
     */
    data class MemberPill(
        val text: String,
        val tone: QuotaTone,
        val poolKey: QuotaPoolKey,
        val isModelSpecific: Boolean,
        val isManual: Boolean,
        /** true = [text] is a used percent; false = a remaining amount. */
        val isPercent: Boolean,
        /** [T-quota-tag-level] Yellow / red tag. */
        val level: QuotaLevel = QuotaLevel.normal,
    )

    fun memberPill(
        v: ModelQuotaView,
        pools: Map<QuotaPoolKey, QuotaPool>,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): MemberPill? {
        fun usable(b: ModelQuotaView.BoundPool) =
            b.status.status != QuotaStatus.unknown && b.status.freshness != QuotaFreshness.expired &&
                pools[b.poolKey]?.let { it.kind != QuotaPool.Kind.rateLimit } == true
        // [ISS-035] Same exception as the section header: an emptied money
        // balance does not stand in for an account that still holds another.
        val known = QuotaHeadline.withoutSpentCurrencyBalances(
            v.bound.filter(::usable), pool = { pools[it.poolKey] }, status = { it.status.status })
        val chosen = known.filter { !it.isShared }.maxWithOrNull(QuotaModelResolver.boundComparator)
            ?: known.filter { it.isShared }.maxWithOrNull(QuotaModelResolver.boundComparator)
            ?: return null
        val pool = pools[chosen.poolKey] ?: return null
        val status = chosen.status
        val u = status.utilization
        val remaining = pool.amounts?.remaining
        val total = pool.amounts?.total
        val used = pool.amounts?.used
        val (text, isPercent) = when {
            u != null -> QuotaFormat.percentUsedText(u.percent, strings) to true
            remaining != null -> QuotaFormat.amount(remaining, pool.unit, locale, strings) to false
            total != null && used != null -> QuotaFormat.amount(total.subtract(used), pool.unit, locale, strings) to false
            else -> return null
        }
        return MemberPill(
            text = text,
            tone = QuotaToneRules.pillTone(status),
            poolKey = chosen.poolKey,
            isModelSpecific = !chosen.isShared,
            isManual = chosen.isManual,
            isPercent = isPercent,
            level = QuotaLevelRules.level(v, pools),
        )
    }

    /** Section header: the account's short text, only with real, non-expired data. */
    fun headerText(
        pool: QuotaPool,
        status: PoolStatus,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): String? {
        if (status.status == QuotaStatus.unknown || status.freshness == QuotaFreshness.expired) return null
        return QuotaFormat.shortText(pool, status, locale, strings)
    }

    private fun detail(s: PoolStatus): String? {
        val u = s.utilization ?: return null
        return s.decidingWindowId?.let { "$it " }.orEmpty() + QuotaFormat.percent(u.percent)
    }
}

// MARK: - Composer placeholder quota segment  [T-quota-composer-hint]

/**
 * The short quota figure appended to the composer's context-usage line
 * ("Context 62% used · 124k / 200k · Balance CN¥36.51"). It follows the
 * Model Group pill's choice exactly ([QuotaPickerHintRules.memberPill]): the
 * model's own pool first, else its account's; nothing without real data.
 */
object QuotaComposerHintRules {
    data class Segment(
        /** "Quota 14% used" / "Balance CN¥36.51" / "$4.20 left". */
        val text: String,
        /** The figure inside [text], tinted when [level] is not normal. */
        val highlight: String,
        val level: QuotaLevel,
    )

    fun segment(
        v: ModelQuotaView,
        pools: Map<QuotaPoolKey, QuotaPool>,
        now: Long = System.currentTimeMillis(),
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): Segment? {
        val pill = QuotaPickerHintRules.memberPill(v, pools, locale, strings) ?: return null
        val pool = pools[pill.poolKey]
        if (pill.isPercent && pool != null) {
            windowsFigure(pool, now, freshness)?.let { windows ->
                // [T-quota-composer-windows] A subscription with a 5h AND a 7d
                // limit shows both, compactly: "Quota 5h 14% / 7d 32% used".
                return Segment("${strings.quota()} ${strings.used(windows)}", windows, pill.level)
            }
        }
        val text = when {
            // "%@ used" already carries the meaning; the prefix says whose.
            pill.isPercent -> "${strings.quota()} ${pill.text}"
            pool?.kind == QuotaPool.Kind.prepaidBalance -> strings.balance(pill.text)
            else -> strings.left(pill.text)
        }
        return Segment(text, pill.text, pill.level)
    }

    /**
     * "5h 14% / 7d 32%": every live window of the pool that has a percent,
     * shortest first. null unless there are at least two - a single window
     * keeps the plain "14% used" wording.
     */
    fun windowsFigure(pool: QuotaPool, now: Long, freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy()): String? {
        val rated: List<Pair<QuotaWindow, BigDecimal>> = pool.windows.mapNotNull { w ->
            if (freshness.freshness(w, now) == QuotaFreshness.expired) return@mapNotNull null
            val u = w.utilization(pool.kind, pool.unlimited) ?: return@mapNotNull null
            w to u.percent
        }
        if (rated.size < 2) return null
        return rated.sortedWith(
            compareBy<Pair<QuotaWindow, BigDecimal>> { it.first.durationSeconds ?: Long.MAX_VALUE }
                .thenBy { it.first.id },
        ).joinToString(" / ") { "${it.first.id} ${QuotaFormat.percent(it.second)}" }
    }
}

// ─── Merged from QuotaModels.kt ──────────────────────────────────────────────────

// [T-provider-quota] Upstream quota / balance domain model. Port of iOS
// QuotaModels.swift (c30ff6d72 and follow-ups).
//
// "Quota" here means ONLY what the upstream model service itself reports or
// enforces: prepaid balances, credits, request/token allowances, subscription
// windows, rate limits. Minis's own token telemetry, cost estimates and relay
// usage logs are NOT quota and have no representation in this file - there is
// deliberately no QuotaSource case for them.
//
// Pure Kotlin (no Android types) so the JVM unit tests exercise it directly.
// Times are epoch milliseconds; durations are seconds, as on iOS.

// MARK: - Identity

/** The upstream vendor a provider instance's quota comes from ([QuotaVendorDetector]). */
@Suppress("EnumEntryName")
@Serializable
enum class QuotaVendor {
    deepSeek, moonshot, siliconFlow, openRouter,
    codexSubscription, openAIPlatform,
    claudeSubscription, anthropicPlatform,
    geminiAPIKey, geminiCodeAssist, antigravity,
    xAI, kimiCode, githubCopilot,

    /** Xiaomi MiMo: its balance is only exposed to the web console's login session. */
    xiaomiMiMo,

    /**
     * [ISS-031] MiniMax (api.minimax.io / api.minimaxi.com). Its Token Plan FAQ
     * documents `GET /v1/token_plan/remains`, but not the response fields, so
     * no adapter: console link only.
     */
    miniMax,

    /** [ISS-031] OpenCode Zen / Go. No usage or balance API is documented: console link only. */
    openCode,
    customRelay, unknown,
}

/**
 * Owner of a set of quota pools. Two instances resolving to the same subject
 * (same vendor + same API key) share every pool and are queried once.
 * [accountKey] is a SHA-256 prefix of the key, or "instance:<id>" - never the
 * credential itself.
 */
@Serializable
data class QuotaSubjectID(val vendor: QuotaVendor, val accountKey: String) {
    override fun toString(): String = "${vendor.name}:$accountKey"
}

/** Canonical pool key "<vendor>:<accountKey>:<family>[:<qualifier>]". */
@Serializable(with = QuotaPoolKeySerializer::class)
data class QuotaPoolKey(val rawValue: String) : Comparable<QuotaPoolKey> {
    override fun toString(): String = rawValue
    override fun compareTo(other: QuotaPoolKey): Int = rawValue.compareTo(other.rawValue)

    companion object {
        fun of(subject: QuotaSubjectID, family: String, qualifier: String? = null): QuotaPoolKey {
            var s = "${subject.vendor.name}:${subject.accountKey}:$family"
            if (!qualifier.isNullOrEmpty()) s += ":$qualifier"
            return QuotaPoolKey(s)
        }
    }
}

object QuotaPoolKeySerializer : KSerializer<QuotaPoolKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.quota.QuotaPoolKey", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: QuotaPoolKey) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): QuotaPoolKey = QuotaPoolKey(decoder.decodeString())
}

// MARK: - Subject

@Serializable
data class QuotaSubject(
    val id: QuotaSubjectID,
    val kind: Kind = Kind.credential,
    val parent: QuotaSubjectID? = null,
    /** Every instance resolving here (usually one). */
    val instanceIds: Set<String> = emptySet(),
    /** Masked key ("sk-...a1b2") - never the full credential. */
    val accountLabel: String? = null,
    val plan: QuotaPlan? = null,
    val state: State = State.Available,
    val lastRefresh: QuotaRefreshRecord? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind { account, credential, organization, project }

    @Serializable
    sealed class State {
        /** No upstream quota signal this build can read. */
        @Serializable @SerialName("unsupported")
        data class Unsupported(val reason: UnsupportedReason) : State()

        /** Passive-only vendor, nothing observed yet (reserved). */
        @Serializable @SerialName("awaitingFirstObservation")
        data object AwaitingFirstObservation : State()

        @Serializable @SerialName("notConfigured")
        data class NotConfigured(val reason: NotConfiguredReason) : State()

        /** Has pools (individual pools may be stale). */
        @Serializable @SerialName("available")
        data object Available : State()

        /** Tried, failed, nothing to show. */
        @Serializable @SerialName("failed")
        data object Failed : State()
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class UnsupportedReason {
        /** The vendor has no quota/balance interface. */
        vendorHasNoQuotaAPI,

        /** Unknown vendor or a custom endpoint whose capability probe found nothing. */
        unknownVendor,

        /** The provider type/credential shape does not fit any adapter. */
        providerTypeUnsupported,

        /** The vendor only exposes quota passively and that is not implemented yet. */
        passiveNotImplemented,

        /** Capability probe or a query answered 404 / an unreadable structure. */
        capabilityProbeFailed,
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class NotConfiguredReason { missingCredential, oauthNotSupported, instanceDisabled, relayProbeOff }
}

@Serializable
data class QuotaPlan(val rawId: String, val tierName: String? = null)

// MARK: - Snapshot (one fetch / one observation)

/** The unit of one fetch; the store merges it ([QuotaMerge]). */
@Serializable
data class UpstreamQuotaSnapshot(
    val subjectId: QuotaSubjectID,
    val provenance: QuotaProvenance,
    /** Local clock, epoch ms. */
    val capturedAt: Long,
    /** server - local in ms, from the response `Date` header. */
    val serverClockOffset: Long? = null,
    val plan: QuotaPlan? = null,
    val pools: List<QuotaPool>,
    val bindings: List<QuotaPoolBinding>,
    /** Upstream says the whole account can serve requests (DeepSeek `is_available`). */
    val accountAvailable: Boolean? = null,
)

/** Persisted digest of the latest snapshot per (subject, source). No bodies, no headers. */
@Serializable
data class QuotaSnapshotSummary(
    val subjectId: QuotaSubjectID,
    val source: QuotaSource,
    val adapterId: String,
    val capturedAt: Long,
    val poolKeys: List<QuotaPoolKey>,
)

@Serializable
data class QuotaProvenance(
    val source: QuotaSource,
    val confidence: QuotaConfidence,
    val adapterId: String,
    /** Host + path only; never query strings or secrets. */
    val endpoint: String? = null,
    /** Set when the data came from / through a relay. */
    val relayHost: String? = null,
)

@Suppress("EnumEntryName")
@Serializable
enum class QuotaSource {
    /** Vendor balance / quota endpoint. */
    officialQuery,

    /** Headers on a real model response. */
    officialHeaders,

    /** e.g. codex.rate_limits. */
    officialStreamEvent,

    /** 429 body, e.g. Gemini QuotaFailure. */
    officialErrorSignal,

    /** Vendor dashboard / console API that is not documented as public. */
    providerDashboardAPI,

    /** A compatible or reverse-engineered interface. */
    compatibleInterface,

    /** Relay's own enforced quota for this key (One/New API billing). */
    relayAccountLedger,

    /** Minis-computed from the above. */
    derived,
}

@Suppress("EnumEntryName")
@Serializable
enum class QuotaConfidence { documented, observedClient, unverified, thirdParty, userDeclared }

// MARK: - Pool & windows

@Serializable
data class QuotaPool(
    val id: QuotaPoolKey,
    val subjectId: QuotaSubjectID,
    val kind: Kind,
    val unit: QuotaUnit,
    /** Raw name from upstream ("GPT-5.3-Codex-Spark", "CNY balance"). */
    val vendorLabel: String? = null,
    /** Non-windowed amounts (prepaid balances, spend caps). null for pure window pools. */
    val amounts: QuotaAmounts? = null,
    /** Zero or more windows; never merged into one number. */
    val windows: List<QuotaWindow> = emptyList(),
    val verdict: Verdict? = null,
    val unlimited: Boolean = false,
    val provenance: QuotaProvenance,
    /** Newest of amounts/windows, epoch ms. */
    val observedAt: Long,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind {
        /** Money/credits held, can be topped up -> never a percent. */
        prepaidBalance,

        /** Cap on spend (OpenRouter key limit, relay hard limit). */
        spendCap,

        /** Sized periodic allowance. */
        allowance,

        /** Subscription rolling windows (Codex, Claude). */
        usageWindow,

        /** Throughput (RPM/TPM/concurrency) - NOT consumable quota. */
        rateLimit,
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class Verdict { allowed, warning, rejected }
}

@Serializable
sealed class QuotaUnit {
    /** ISO 4217. */
    @Serializable @SerialName("currency")
    data class Currency(val code: String) : QuotaUnit()

    /** Not convertible unless the vendor states a rate. */
    @Serializable @SerialName("credits")
    data class Credits(val name: String? = null) : QuotaUnit()

    @Serializable @SerialName("requests")
    data object Requests : QuotaUnit()

    @Serializable @SerialName("tokens")
    data class Tokens(val kind: TokenKind) : QuotaUnit()

    /** Upstream gives only a fraction / percent. */
    @Serializable @SerialName("percentOnly")
    data object PercentOnly : QuotaUnit()

    @Serializable @SerialName("rateLimit")
    data class RateLimit(val metric: RateMetric) : QuotaUnit()

    @Serializable @SerialName("custom")
    data class Custom(val name: String) : QuotaUnit()

    @Suppress("EnumEntryName")
    @Serializable
    enum class TokenKind { input, output, total }

    @Suppress("EnumEntryName")
    @Serializable
    enum class RateMetric { requests, tokens, inputTokens, outputTokens, concurrency }
}

/**
 * All in the pool's unit; all optional. Decimals are persisted as strings so
 * no amount ever round-trips through a Double.
 */
@Serializable
data class QuotaAmounts(
    @Serializable(with = QuotaDecimalSerializer::class) val total: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val used: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val remaining: BigDecimal? = null,
    /** Same-unit components: granted / toppedUp / voucher / cash. */
    val breakdown: List<Part> = emptyList(),
) {
    @Serializable
    data class Part(
        val role: Role,
        @Serializable(with = QuotaDecimalSerializer::class) val amount: BigDecimal,
        val expiresAt: Long? = null,
    )

    @Serializable
    sealed class Role {
        @Serializable @SerialName("granted") data object Granted : Role()
        @Serializable @SerialName("toppedUp") data object ToppedUp : Role()
        @Serializable @SerialName("voucher") data object Voucher : Role()
        @Serializable @SerialName("cash") data object Cash : Role()
        @Serializable @SerialName("other") data class Other(val name: String) : Role()
    }
}

@Serializable
data class QuotaWindow(
    /** "5h", "7d", "day", "month", "300m" - stable per pool. */
    val id: String,
    val durationSeconds: Long? = null,
    val calendarPeriod: CalendarPeriod? = null,
    val isRolling: Boolean,
    /** For sized windows (requests/tokens per period). */
    val amounts: QuotaAmounts? = null,
    /** Verbatim from upstream (0...1). */
    @Serializable(with = QuotaDecimalSerializer::class) val reportedUsedFraction: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val reportedRemainingFraction: BigDecimal? = null,
    /** Already corrected by the snapshot's server clock offset, epoch ms. */
    val resetsAt: Long? = null,
    val observedAt: Long,
    /** Windows of one pool may come from different channels. */
    val provenance: QuotaProvenance,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class CalendarPeriod { day, week, month }
}

// MARK: - Binding (many-to-many)

@Serializable
data class QuotaPoolBinding(
    val poolKey: QuotaPoolKey,
    val target: Target,
    val evidence: Evidence,
) {
    @Serializable
    sealed class Target {
        /** Every model under the subject. */
        @Serializable @SerialName("subject") data object Subject : Target()

        /** Normalized upstream model key - ONLY with explicitUpstream or userManualMapping. */
        @Serializable @SerialName("model") data class Model(val key: String) : Target()

        /** Glob - ONLY with explicitUpstream or vendorDocumented. */
        @Serializable @SerialName("modelFamily") data class ModelFamily(val pattern: String) : Target()

        /** Non-model product ("code_review"). */
        @Serializable @SerialName("product") data class Product(val name: String) : Target()
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class Evidence {
        /** Upstream named the model/product. */
        explicitUpstream,

        /** Vendor docs say account-wide. */
        vendorDocumented,

        /** Scope unstated -> treated as shared (default). */
        assumedSubjectWide,

        /**
         * The USER declared this model draws on the pool. Never produced by an
         * adapter, so it can never overwrite or masquerade as an upstream binding.
         */
        userManualMapping;

        /** Higher wins when de-duplicating the same (pool, target). */
        val rank: Int
            get() = when (this) {
                explicitUpstream -> 3
                vendorDocumented -> 2
                assumedSubjectWide -> 1
                userManualMapping -> 0
            }
    }
}

// MARK: - Capability

/** "Provider type + normalized base URL" - the unit of capability probing, once per day. */
@Serializable
data class QuotaCapabilityKey(
    val providerType: String,
    /**
     * Lower-cased scheme://host[:port][/path] with trailing "/" and "/v1"
     * removed; "default" for the provider's built-in endpoint.
     */
    val baseURL: String,
) {
    override fun toString(): String = "$providerType@$baseURL"

    companion object {
        fun normalize(raw: String?): String {
            var s = raw?.trim().orEmpty()
            if (s.isEmpty()) return "default"
            s = s.lowercase()
            s = s.trimEnd('/')
            if (s.endsWith("/v1")) s = s.dropLast(3)
            return s.trimEnd('/')
        }
    }
}

/** What an adapter can do for an instance. */
data class QuotaCapability(
    /** Can actively query upstream. */
    val active: Boolean,
    val source: QuotaSource,
    val confidence: QuotaConfidence,
    val manualRefresh: Boolean,
    /** When `active == false`: why. */
    val unsupportedReason: QuotaSubject.UnsupportedReason?,
) {
    companion object {
        fun unsupported(reason: QuotaSubject.UnsupportedReason) = QuotaCapability(
            active = false, source = QuotaSource.officialQuery, confidence = QuotaConfidence.documented,
            manualRefresh = false, unsupportedReason = reason,
        )

        fun activeQuery(
            source: QuotaSource = QuotaSource.officialQuery,
            confidence: QuotaConfidence = QuotaConfidence.documented,
        ) = QuotaCapability(
            active = true, source = source, confidence = confidence,
            manualRefresh = true, unsupportedReason = null,
        )
    }
}

/** Outcome of the (at most daily) capability probe for one [QuotaCapabilityKey]. */
@Serializable
data class QuotaCapabilityRecord(
    val key: QuotaCapabilityKey,
    val adapterId: String,
    val status: Status,
    val source: QuotaSource? = null,
    val confidence: QuotaConfidence? = null,
    val probedAt: Long,
    /** Short, non-sensitive reason ("HTTP 404", "unreadable response"). */
    val detail: String? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Status {
        /** Queries may run. */
        supported,

        /** Nothing to query for this endpoint. */
        unsupported,

        /** A probe or query hit 404 / an unsupported structure: stop until the next daily probe. */
        failed,
    }
}

// MARK: - Refresh bookkeeping

@Serializable
data class QuotaRefreshRecord(
    val attemptedAt: Long,
    val succeededAt: Long? = null,
    val error: QuotaError? = null,
    /** Earliest time an AUTOMATIC refresh may run again. */
    val nextAllowedAt: Long? = null,
    /** Server-imposed cooldown (Retry-After) - binds manual refreshes too. */
    val serverCooldownUntil: Long? = null,
    /** Consecutive failures, for the backoff. */
    val consecutiveFailures: Int = 0,
    /** Last user-forced attempt, for the manual debounce. */
    val lastForcedAt: Long? = null,
)

@Serializable
data class QuotaError(
    val kind: Kind,
    /** Short, non-sensitive, NOT localized (the UI localizes by [kind]). */
    val detail: String,
    val httpStatus: Int? = null,
    val at: Long,
    val retryAfter: Long? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind {
        network, unauthorized, forbidden, notFound, rateLimited, server,
        badResponse, unsupportedResponse, missingCredential,
    }
}

// MARK: - Derived views (computed, never persisted)

data class QuotaUtilization(
    /** May exceed 100 on overage. */
    val percent: BigDecimal,
    val basis: Basis,
) {
    @Suppress("EnumEntryName")
    enum class Basis { reported, reportedComplement, computed }
}

/** Severity-ordered: [unknown] lowest. */
@Suppress("EnumEntryName")
enum class QuotaStatus {
    unknown, available, low, rateLimited, exhausted;

    val severity: Int get() = ordinal

    /** Worth surfacing in compact UI. */
    val isWarning: Boolean get() = this == low || this == rateLimited || this == exhausted
}

@Suppress("EnumEntryName")
enum class QuotaFreshness { fresh, stale, expired }

data class PoolStatus(
    val status: QuotaStatus,
    /** Tightest window; null for non-windowed. */
    val decidingWindowId: String?,
    /** Of that window, if any. */
    val utilization: QuotaUtilization?,
    val freshness: QuotaFreshness,
)

data class ModelQuotaView(
    val instanceId: String,
    val modelKey: String,
    val subjectId: QuotaSubjectID?,
    /** Every applicable pool. */
    val bound: List<BoundPool>,
    val effective: QuotaStatus,
    /** Tightest constraint. */
    val deciding: BoundPool?,
    /** Some bound pools unknown. */
    val isIncomplete: Boolean,
    /** Any binding with a model / modelFamily / product target. */
    val hasModelSpecificPool: Boolean,
) {
    data class BoundPool(
        val poolKey: QuotaPoolKey,
        val binding: QuotaPoolBinding,
        val status: PoolStatus,
    ) {
        val isShared: Boolean get() = binding.target == QuotaPoolBinding.Target.Subject
        val isManual: Boolean get() = binding.evidence == QuotaPoolBinding.Evidence.userManualMapping
    }

    companion object {
        fun empty(instanceId: String, modelKey: String) = ModelQuotaView(
            instanceId = instanceId, modelKey = modelKey, subjectId = null, bound = emptyList(),
            effective = QuotaStatus.unknown, deciding = null, isIncomplete = false, hasModelSpecificPool = false,
        )
    }
}

data class QuotaThresholds(
    val lowPercent: BigDecimal = BigDecimal(80),
    val exhaustedPercent: BigDecimal = BigDecimal(100),
    /**
     * Optional per-subject, per-currency user thresholds for prepaid balances.
     * Empty by default: without one a prepaid balance is never judged "low" by
     * the pool status (the level rules judge it instead).
     */
    val lowBalance: Map<QuotaSubjectID, Map<String, BigDecimal>> = emptyMap(),
)

// MARK: - Decimal coding

/**
 * Decimals are persisted as strings: a JSON number read through a Double can
 * change a stored amount.
 */
object QuotaDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.quota.QuotaDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.quotaString)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val raw = decoder.decodeString()
        return QuotaDecimalParser.parseString(raw) ?: throw SerializationException("not a decimal: $raw")
    }
}

/**
 * Trailing zeros dropped, so equal amounts compare equal as values ("110.00"
 * and "110" are one amount, as a Swift Decimal is) and survive a store round trip.
 */
fun BigDecimal.canonical(): BigDecimal = if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()

/** Locale-independent canonical text ("110.00" is "110", "-0.5" stays "-0.5"). */
val BigDecimal.quotaString: String
    get() = if (signum() == 0) "0" else stripTrailingZeros().toPlainString()

/**
 * Parses upstream amounts. Strings and JSON numbers are accepted; anything
 * unparseable is null - NEVER zero.
 */
object QuotaDecimalParser {
    private val allowed = Regex("^[0-9.+\\-eE]+$")

    /** A JSON value: string or number. A boolean or null is never an amount. */
    fun parse(value: JsonElement?): BigDecimal? {
        if (value == null || value is JsonNull || value !is JsonPrimitive) return null
        if (!value.isString && value.booleanOrNull != null) return null
        return parseString(value.content)
    }

    fun parseString(raw: String?): BigDecimal? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // Whole-string validation: a numeric PREFIX ("12abc") must not become a number.
        if (!allowed.matches(s) || s.none { it.isDigit() }) return null
        return try {
            BigDecimal(s).canonical()
        } catch (_: NumberFormatException) {
            null
        }
    }
}

// ─── Merged from QuotaRules.kt ──────────────────────────────────────────────────

// [T-provider-quota] Pure rules over the quota model: percent derivation,
// freshness, pool / model status, snapshot merging, and the refresh / probe
// rate gates. Port of iOS QuotaRules.swift. No I/O and no Android types.

private val HUNDRED = BigDecimal(100)
private val MINUS_ONE = BigDecimal(-1)

internal fun BigDecimal.percentOf(total: BigDecimal): BigDecimal =
    divide(total, MathContext.DECIMAL128).multiply(HUNDRED)

// MARK: - Percent

/**
 * `computed` utilization - only for capped pool kinds, a positive total, and a
 * used/remaining figure in the same amounts (hence the same unit).
 */
fun QuotaAmounts.computedUtilization(kind: QuotaPool.Kind, unlimited: Boolean): QuotaUtilization? {
    if (unlimited) return null
    when (kind) {
        QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance, QuotaPool.Kind.usageWindow -> Unit
        // Prepaid balances can be topped up without limit, and rate limits are
        // not consumable quota: neither gets a percent.
        QuotaPool.Kind.prepaidBalance, QuotaPool.Kind.rateLimit -> return null
    }
    val total = total ?: return null
    if (total.signum() <= 0) return null
    val used = used ?: remaining?.let { total.subtract(it) } ?: return null
    return QuotaUtilization(used.percentOf(total), QuotaUtilization.Basis.computed)
}

fun QuotaWindow.utilization(poolKind: QuotaPool.Kind, unlimited: Boolean): QuotaUtilization? {
    if (unlimited) return null
    if (poolKind == QuotaPool.Kind.prepaidBalance) return null
    reportedUsedFraction?.let { return QuotaUtilization(it.multiply(HUNDRED), QuotaUtilization.Basis.reported) }
    reportedRemainingFraction?.let {
        return QuotaUtilization(BigDecimal.ONE.subtract(it).multiply(HUNDRED), QuotaUtilization.Basis.reportedComplement)
    }
    return amounts?.computedUtilization(poolKind, unlimited)
}

// MARK: - Freshness

data class QuotaFreshnessPolicy(
    /** Active queries refresh at most every 10 minutes; anything younger is current. */
    val activeFreshMs: Long = 15 * 60_000L,
    /** Balances / spend caps: shown greyed with "N min ago" up to this age. */
    val activeAmountsStaleMs: Long = 6 * 3_600_000L,
    /** Allowances / windows move faster; hide their numbers sooner. */
    val activeWindowStaleMs: Long = 3_600_000L,
) {
    fun freshnessOfAmounts(pool: QuotaPool, now: Long): QuotaFreshness {
        val age = now - pool.observedAt
        if (age < activeFreshMs) return QuotaFreshness.fresh
        return if (age <= activeAmountsStaleMs) QuotaFreshness.stale else QuotaFreshness.expired
    }

    fun freshness(window: QuotaWindow, now: Long): QuotaFreshness {
        window.resetsAt?.let { if (it <= now) return QuotaFreshness.expired }
        val age = now - window.observedAt
        if (age < activeFreshMs) return QuotaFreshness.fresh
        return if (age <= activeWindowStaleMs) QuotaFreshness.stale else QuotaFreshness.expired
    }

    /** A pool is as fresh as its freshest part (amounts or any live window). */
    fun freshness(pool: QuotaPool, now: Long): QuotaFreshness {
        val parts = mutableListOf<QuotaFreshness>()
        if (pool.amounts != null || pool.windows.isEmpty()) parts += freshnessOfAmounts(pool, now)
        pool.windows.mapTo(parts) { freshness(it, now) }
        if (QuotaFreshness.fresh in parts) return QuotaFreshness.fresh
        if (QuotaFreshness.stale in parts) return QuotaFreshness.stale
        return QuotaFreshness.expired
    }
}

// MARK: - Status

object QuotaStatusRules {
    fun status(utilization: QuotaUtilization, thresholds: QuotaThresholds): QuotaStatus {
        if (utilization.percent >= thresholds.exhaustedPercent) return QuotaStatus.exhausted
        if (utilization.percent >= thresholds.lowPercent) return QuotaStatus.low
        return QuotaStatus.available
    }

    fun poolStatus(
        pool: QuotaPool,
        now: Long,
        policy: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): PoolStatus {
        val freshness = policy.freshness(pool, now)
        if (freshness == QuotaFreshness.expired) {
            return PoolStatus(QuotaStatus.unknown, null, null, QuotaFreshness.expired)
        }
        if (pool.verdict == QuotaPool.Verdict.rejected) {
            return PoolStatus(QuotaStatus.exhausted, null, null, freshness)
        }

        if (pool.kind == QuotaPool.Kind.rateLimit) {
            val limited = pool.windows.firstOrNull { w ->
                if (policy.freshness(w, now) == QuotaFreshness.expired) return@firstOrNull false
                val rem = w.amounts?.remaining
                rem != null && rem.signum() == 0 && (w.resetsAt?.let { it > now } ?: false)
            }
            return PoolStatus(
                if (limited != null) QuotaStatus.rateLimited else QuotaStatus.available,
                limited?.id, null, freshness,
            )
        }

        // Windows: worst window wins; windows are never averaged.
        var bestStatus: QuotaStatus? = null
        var bestWindow: QuotaWindow? = null
        var bestUtil: QuotaUtilization? = null
        for (w in pool.windows) {
            if (policy.freshness(w, now) == QuotaFreshness.expired) continue
            val u = w.utilization(pool.kind, pool.unlimited)
            val remaining = w.amounts?.remaining
            val s = when {
                u != null -> status(u, thresholds)
                remaining != null -> if (remaining.signum() <= 0) QuotaStatus.exhausted else QuotaStatus.available
                else -> QuotaStatus.unknown
            }
            val current = bestStatus
            if (current == null) {
                bestStatus = s; bestWindow = w; bestUtil = u
            } else {
                val tighter = s > current ||
                    (s == current && (u?.percent ?: MINUS_ONE) > (bestUtil?.percent ?: MINUS_ONE))
                if (tighter) { bestStatus = s; bestWindow = w; bestUtil = u }
            }
        }

        // Non-windowed amounts.
        var amountStatus = QuotaStatus.unknown
        var amountUtil: QuotaUtilization? = null
        val amounts = pool.amounts
        if (amounts != null) {
            when (pool.kind) {
                QuotaPool.Kind.prepaidBalance -> {
                    val remaining = amounts.remaining
                    if (remaining != null) {
                        val unit = pool.unit
                        val low = (unit as? QuotaUnit.Currency)
                            ?.let { thresholds.lowBalance[pool.subjectId]?.get(it.code.uppercase()) }
                        amountStatus = when {
                            remaining.signum() <= 0 -> QuotaStatus.exhausted
                            low != null && remaining < low -> QuotaStatus.low
                            else -> QuotaStatus.available
                        }
                    }
                }
                QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance, QuotaPool.Kind.usageWindow -> {
                    val u = amounts.computedUtilization(pool.kind, pool.unlimited)
                    val remaining = amounts.remaining
                    when {
                        // [T-quota-low-dot] Checked first: an unlimited cap reporting
                        // 0 "remaining" (no cap to subtract from) is not exhausted.
                        pool.unlimited -> amountStatus = QuotaStatus.available
                        u != null -> { amountUtil = u; amountStatus = status(u, thresholds) }
                        remaining != null ->
                            amountStatus = if (remaining.signum() <= 0) QuotaStatus.exhausted else QuotaStatus.available
                    }
                }
                QuotaPool.Kind.rateLimit -> Unit
            }
        }

        var result = PoolStatus(amountStatus, null, amountUtil, freshness)
        val bs = bestStatus
        if (bs != null && bs >= result.status && bs != QuotaStatus.unknown) {
            result = PoolStatus(bs, bestWindow?.id, bestUtil, freshness)
        }
        if (pool.verdict == QuotaPool.Verdict.warning && result.status < QuotaStatus.low) {
            result = result.copy(status = QuotaStatus.low)
        }
        return result
    }
}

// MARK: - Model key matching and effective status

object QuotaModelKey {
    /** Lower-cased slug: "GPT-5.3-Codex-Spark" and "gpt-5.3-codex-spark" match. */
    fun normalize(s: String): String =
        s.trim().lowercase().replace(" ", "-").replace("_", "-")

    /** `*` glob over normalized keys. */
    fun matches(pattern: String, key: String): Boolean {
        val p = normalize(pattern)
        val k = normalize(key)
        val parts = p.split("*")
        if (parts.size <= 1) return p == k
        var rest = k
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            when (i) {
                0 -> {
                    if (!rest.startsWith(part)) return false
                    rest = rest.substring(part.length)
                }
                parts.size - 1 -> return rest.endsWith(part)
                else -> {
                    val idx = rest.indexOf(part)
                    if (idx < 0) return false
                    rest = rest.substring(idx + part.length)
                }
            }
        }
        return true
    }
}

object QuotaModelResolver {
    /** The model's view over every pool that applies to it. */
    fun view(
        instanceId: String,
        modelId: String,
        subjectId: QuotaSubjectID?,
        pools: Map<QuotaPoolKey, QuotaPool>,
        bindings: List<QuotaPoolBinding>,
        manualBindings: List<QuotaPoolBinding> = emptyList(),
        now: Long,
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): ModelQuotaView {
        val modelKey = QuotaModelKey.normalize(modelId)
        if (subjectId == null) return ModelQuotaView.empty(instanceId, modelKey)

        val chosen = LinkedHashMap<QuotaPoolKey, QuotaPoolBinding>()
        fun consider(b: QuotaPoolBinding) {
            val pool = pools[b.poolKey] ?: return
            if (pool.subjectId != subjectId) return
            val applies = when (val t = b.target) {
                QuotaPoolBinding.Target.Subject -> true
                is QuotaPoolBinding.Target.Model -> QuotaModelKey.normalize(t.key) == modelKey
                is QuotaPoolBinding.Target.ModelFamily -> QuotaModelKey.matches(t.pattern, modelKey)
                // Products are not models: code-review style pools never bind to a model.
                is QuotaPoolBinding.Target.Product -> false
            }
            if (!applies) return
            // A pool that binds both as shared and model-specific is reported by
            // its most specific binding.
            val existing = chosen[b.poolKey]
            if (existing == null ||
                specificity(b) > specificity(existing) ||
                (specificity(b) == specificity(existing) && b.evidence.rank > existing.evidence.rank)
            ) {
                chosen[b.poolKey] = b
            }
        }
        bindings.forEach(::consider)
        manualBindings.forEach(::consider)

        val bound = chosen.values.mapNotNull { b ->
            val pool = pools[b.poolKey] ?: return@mapNotNull null
            ModelQuotaView.BoundPool(b.poolKey, b, QuotaStatusRules.poolStatus(pool, now, freshness, thresholds))
        }.sortedBy { it.poolKey.rawValue }

        val known = bound.filter { it.status.status != QuotaStatus.unknown }
        val deciding = known.maxWithOrNull(boundComparator)
        return ModelQuotaView(
            instanceId = instanceId,
            modelKey = modelKey,
            subjectId = subjectId,
            bound = bound,
            effective = deciding?.status?.status ?: QuotaStatus.unknown,
            deciding = deciding,
            isIncomplete = known.isNotEmpty() && known.size < bound.size,
            hasModelSpecificPool = bound.any { !it.isShared },
        )
    }

    /** Tighter = worse status, then the higher percent. */
    internal val boundComparator = Comparator<ModelQuotaView.BoundPool> { a, b ->
        if (a.status.status != b.status.status) a.status.status.compareTo(b.status.status)
        else (a.status.utilization?.percent ?: MINUS_ONE).compareTo(b.status.utilization?.percent ?: MINUS_ONE)
    }

    private fun specificity(b: QuotaPoolBinding): Int = when (b.target) {
        QuotaPoolBinding.Target.Subject -> 0
        is QuotaPoolBinding.Target.ModelFamily -> 1
        is QuotaPoolBinding.Target.Product -> 1
        is QuotaPoolBinding.Target.Model -> 2
    }
}

// MARK: - Merging snapshots

object QuotaMerge {
    /**
     * Upsert a snapshot's pools by key (windows merged by id, newer wins) and
     * its bindings by (pool, target) (higher evidence wins).
     *
     * `authoritative` snapshots - a complete active query - also retire pools
     * the SAME adapter produced for this subject earlier but no longer returns
     * (e.g. a currency the account no longer holds).
     */
    fun ingest(
        snapshot: UpstreamQuotaSnapshot,
        authoritative: Boolean,
        pools: MutableMap<QuotaPoolKey, QuotaPool>,
        bindings: MutableList<QuotaPoolBinding>,
    ) {
        if (authoritative) {
            val returned = snapshot.pools.map { it.id }.toSet()
            val retired = pools.values.filter {
                it.subjectId == snapshot.subjectId &&
                    it.provenance.adapterId == snapshot.provenance.adapterId &&
                    it.id !in returned
            }.map { it.id }.toSet()
            retired.forEach { pools.remove(it) }
            if (retired.isNotEmpty()) bindings.removeAll { it.poolKey in retired }
        }

        for (incoming in snapshot.pools) {
            val existing = pools[incoming.id]
            if (existing == null) {
                pools[incoming.id] = incoming
                continue
            }
            var merged = existing
            if (incoming.observedAt >= existing.observedAt) {
                merged = merged.copy(
                    kind = incoming.kind,
                    unit = incoming.unit,
                    vendorLabel = incoming.vendorLabel ?: existing.vendorLabel,
                    amounts = incoming.amounts,
                    verdict = incoming.verdict,
                    unlimited = incoming.unlimited,
                    provenance = incoming.provenance,
                )
            }
            val byId = LinkedHashMap<String, QuotaWindow>()
            existing.windows.forEach { byId.putIfAbsent(it.id, it) }
            for (w in incoming.windows) {
                val old = byId[w.id]
                if (old != null && old.observedAt > w.observedAt) continue
                byId[w.id] = w
            }
            val windows = existing.windows.mapNotNull { byId.remove(it.id) } +
                incoming.windows.mapNotNull { byId.remove(it.id) }
            pools[incoming.id] = merged.copy(
                windows = windows,
                observedAt = maxOf(existing.observedAt, incoming.observedAt),
            )
        }

        for (b in snapshot.bindings) {
            if (b.evidence == QuotaPoolBinding.Evidence.userManualMapping) continue
            val i = bindings.indexOfFirst { it.poolKey == b.poolKey && it.target == b.target }
            if (i >= 0) {
                if (b.evidence.rank > bindings[i].evidence.rank) bindings[i] = b
            } else {
                bindings += b
            }
        }
    }
}

// MARK: - Rate gates

data class QuotaRefreshPolicy(
    /** Normal refresh: at most once per canonical pool set / credential. */
    val normalIntervalMs: Long = 10 * 60_000L,
    /** A user-forced refresh bypasses [normalIntervalMs] but not this debounce. */
    val forcedMinIntervalMs: Long = 30_000L,
    /**
     * [T-quota-attention-refresh] A provider nobody is looking at is refreshed
     * at most this often; [normalIntervalMs] applies only while it is on screen.
     */
    val unattendedIntervalMs: Long = 3_600_000L,
    /**
     * Failure backoff steps (5 min -> 60 min). Starts at 5 min so a failing
     * provider on screen cannot be retried in a tight loop.
     */
    val backoffMs: List<Long> = listOf(300_000L, 600_000L, 1_200_000L, 1_800_000L, 3_600_000L),
    /** Capability probe: at most once per day per provider type + base URL. */
    val capabilityProbeIntervalMs: Long = 24 * 3_600_000L,
) {
    companion object {
        /**
         * A subscription (Claude / ChatGPT OAuth) refreshes on a 5-minute window
         * measured from the last update by EITHER source - a reply's headers or
         * an active query. Every other vendor keeps the 10-minute interval.
         */
        fun forVendor(vendor: QuotaVendor): QuotaRefreshPolicy = when (vendor) {
            QuotaVendor.claudeSubscription, QuotaVendor.codexSubscription ->
                QuotaRefreshPolicy(normalIntervalMs = SUBSCRIPTION_FRESH_WINDOW_MS)
            else -> QuotaRefreshPolicy()
        }

        const val SUBSCRIPTION_FRESH_WINDOW_MS = 5 * 60_000L
    }
}

sealed class QuotaRefreshDecision {
    data object Allowed : QuotaRefreshDecision()

    /** Retry-After from the server - binds forced refreshes too. */
    data class ServerCooldown(val until: Long) : QuotaRefreshDecision()

    /** Normal interval not yet elapsed. */
    data class TooSoon(val until: Long) : QuotaRefreshDecision()

    /** Failure backoff not yet elapsed. */
    data class Backoff(val until: Long) : QuotaRefreshDecision()

    /** Forced refresh within the debounce. */
    data class ForcedDebounce(val until: Long) : QuotaRefreshDecision()

    /** Credential rejected; automatic refresh stops until the key changes or the user forces one. */
    data object CredentialRejected : QuotaRefreshDecision()

    val isAllowed: Boolean get() = this == Allowed
}

object QuotaRefreshGate {
    /**
     * `attended` = the provider is on screen right now: the normal interval
     * applies. Otherwise the unattended hour does too, counted from the last
     * update by any source.
     */
    fun decide(
        record: QuotaRefreshRecord?,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
        attended: Boolean = true,
    ): QuotaRefreshDecision {
        if (record == null) return QuotaRefreshDecision.Allowed
        record.serverCooldownUntil?.let { if (it > now) return QuotaRefreshDecision.ServerCooldown(it) }
        if (forced) {
            // [T-quota-manual-refresh] Debounced only against the user's own
            // previous manual refresh; an automatic request never blocks the button.
            val lastForced = record.lastForcedAt ?: return QuotaRefreshDecision.Allowed
            val until = lastForced + policy.forcedMinIntervalMs
            return if (until > now) QuotaRefreshDecision.ForcedDebounce(until) else QuotaRefreshDecision.Allowed
        }
        val kind = record.error?.kind
        if (kind == QuotaError.Kind.unauthorized || kind == QuotaError.Kind.forbidden) {
            return QuotaRefreshDecision.CredentialRejected
        }
        record.nextAllowedAt?.let { next ->
            if (next > now) {
                return if (record.error != null) QuotaRefreshDecision.Backoff(next) else QuotaRefreshDecision.TooSoon(next)
            }
        }
        if (!attended) {
            val last = maxOf(record.succeededAt ?: Long.MIN_VALUE, record.attemptedAt)
            val until = last + policy.unattendedIntervalMs
            if (until > now) return QuotaRefreshDecision.TooSoon(until)
        }
        return QuotaRefreshDecision.Allowed
    }

    fun recordSuccess(
        record: QuotaRefreshRecord?,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord = (record ?: QuotaRefreshRecord(attemptedAt = now)).copy(
        attemptedAt = now,
        succeededAt = now,
        error = null,
        consecutiveFailures = 0,
        serverCooldownUntil = null,
        nextAllowedAt = now + policy.normalIntervalMs,
        lastForcedAt = if (forced) now else record?.lastForcedAt,
    )

    /**
     * [T-quota-claude-subscription] Fresh data from a passive observation
     * (response headers) counts as a refresh for the scheduler. A server
     * cooldown, the forced debounce and a longer pending backoff are kept.
     */
    fun recordObservation(
        record: QuotaRefreshRecord?,
        now: Long,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord {
        val r = record ?: QuotaRefreshRecord(attemptedAt = Long.MIN_VALUE)
        return r.copy(
            succeededAt = now,
            error = null,
            consecutiveFailures = 0,
            nextAllowedAt = maxOf(r.nextAllowedAt ?: Long.MIN_VALUE, now + policy.normalIntervalMs),
        )
    }

    fun recordFailure(
        record: QuotaRefreshRecord?,
        error: QuotaError,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord {
        val base = record ?: QuotaRefreshRecord(attemptedAt = now)
        val failures = base.consecutiveFailures + 1
        val step = policy.backoffMs[minOf(failures - 1, policy.backoffMs.size - 1)]
        var next = now + step
        var cooldown = base.serverCooldownUntil
        error.retryAfter?.let {
            cooldown = it
            next = maxOf(next, it)
        }
        // Never retry a failure sooner than a success would have been.
        if (error.kind == QuotaError.Kind.notFound || error.kind == QuotaError.Kind.unsupportedResponse) {
            next = maxOf(next, now + policy.normalIntervalMs)
        }
        return base.copy(
            attemptedAt = now,
            error = error,
            consecutiveFailures = failures,
            serverCooldownUntil = cooldown,
            nextAllowedAt = next,
            lastForcedAt = if (forced) now else base.lastForcedAt,
        )
    }
}

object QuotaCapabilityGate {
    /** Whether the capability for `record.key` may be (re-)probed now. */
    fun shouldProbe(
        record: QuotaCapabilityRecord?,
        now: Long,
        userRequested: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): Boolean {
        if (record == null) return true
        // [T-quota-manual-refresh] A manual refresh always re-checks a paused
        // endpoint; repeated taps are debounced by the refresh gate.
        if (userRequested) return true
        return now - record.probedAt >= policy.capabilityProbeIntervalMs
    }
}

// MARK: - Headline pool

object QuotaHeadline {
    /**
     * The pool that best represents a subject in one short line: the worst
     * status first; when everything is fine, the busiest window, then a prepaid
     * balance, then a sized allowance. Rate limits never headline.
     *
     * [ISS-035] One exception to "worst first": an emptied money balance does
     * not headline while the same account still holds money in another
     * currency (see [withoutSpentCurrencyBalances]).
     */
    fun pick(
        pools: List<QuotaPool>,
        now: Long,
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): Pair<QuotaPool, PoolStatus>? {
        val all = pools.filter { it.kind != QuotaPool.Kind.rateLimit }
            .map { it to QuotaStatusRules.poolStatus(it, now, freshness, thresholds) }
            .filter { it.second.status != QuotaStatus.unknown }
        val rated = withoutSpentCurrencyBalances(all, pool = { it.first }, status = { it.second.status })
        if (rated.isEmpty()) return null
        fun rank(p: QuotaPool): Int = when (p.kind) {
            QuotaPool.Kind.usageWindow -> 3
            QuotaPool.Kind.prepaidBalance -> 2
            QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance -> 1
            QuotaPool.Kind.rateLimit -> 0
        }
        return rated.maxWithOrNull { a, b ->
            when {
                a.second.status != b.second.status -> a.second.status.compareTo(b.second.status)
                (a.second.utilization?.percent ?: MINUS_ONE).compareTo(b.second.utilization?.percent ?: MINUS_ONE) != 0 ->
                    (a.second.utilization?.percent ?: MINUS_ONE).compareTo(b.second.utilization?.percent ?: MINUS_ONE)
                rank(a.first) != rank(b.first) -> rank(a.first).compareTo(rank(b.first))
                // Lower key wins the tie (iOS: `a.id > b.id` means a sorts below).
                else -> b.first.id.rawValue.compareTo(a.first.id.rawValue)
            }
        }
    }

    /** A prepaid balance held as money ([QuotaUnit.Currency]), not credits or relay units. */
    fun isCurrencyBalance(pool: QuotaPool): Boolean =
        pool.kind == QuotaPool.Kind.prepaidBalance && pool.unit is QuotaUnit.Currency

    /**
     * [ISS-035] Removes the emptied money balances from a one-line choice when
     * another money balance of the same set still has funds (available or low).
     *
     * Why: DeepSeek keeps one balance per currency, so an account with
     * CN¥7.62 and US$0.00 has an exhausted USD pool next to an available CNY
     * pool. Under "worst first", US$0.00 headlined and read as "no money
     * left", while the account-level dot ([QuotaLevelRules.level], which judges
     * money balances together) correctly said otherwise.
     *
     * Scope, kept deliberately narrow (same as iOS):
     * - only prepaid balances in a currency; credits, spend caps, allowances
     *   and windows keep "worst first", so an exhausted window still headlines;
     * - a balance exhausted because the vendor rejected the account
     *   (verdict rejected) is never dropped: it is a refusal, not a zero amount;
     * - when every money balance is exhausted, nothing is dropped.
     * Among several funded currencies the caller's usual ordering decides (no
     * exchange rate is applied here); the detail sheet lists them all.
     */
    fun <T> withoutSpentCurrencyBalances(
        items: List<T>,
        pool: (T) -> QuotaPool?,
        status: (T) -> QuotaStatus,
    ): List<T> {
        fun spent(item: T): Boolean {
            val p = pool(item) ?: return false
            return isCurrencyBalance(p) && status(item) == QuotaStatus.exhausted &&
                p.verdict != QuotaPool.Verdict.rejected
        }
        val funded = items.any { item ->
            val p = pool(item)
            p != null && isCurrencyBalance(p) &&
                (status(item) == QuotaStatus.available || status(item) == QuotaStatus.low)
        }
        return if (funded) items.filterNot(::spent) else items
    }
}
