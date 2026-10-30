package com.openminis.app.data.model

import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured content parts for agent loop messages.
 * Used to represent tool_use/tool_result blocks in the conversation history
 * that providers serialize into their native format.
 */
sealed class AgentContentPart {
    data class Text(val text: String) : AgentContentPart()

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JSONObject,
        // [T-android-gemini3-thoughtsig / #179] Opaque Gemini 3.x thought
        // signature captured at tool-call time; replayed on the historical
        // functionCall part (required by gemini-3.x or the request 400s). Null
        // for non-Gemini providers and for pre-fix / migrated history.
        val thoughtSignature: String? = null,
        /**
         * [T-offload-stub-system-reminder] (GH#374) True when the context
         * offloader replaced this call's `content` argument with a
         * pruned-argument notice because the real payload was moved to
         * `/var/minis/offloads/…`.
         *
         * Provenance, not presentation: the notice text is also detectable with
         * [com.openminis.app.data.ContextOffload.isOffloadPlaceholder], but that
         * is a string test on a payload the model can imitate, whereas this flag
         * is set only by the offloader. Defaults to false so every existing
         * construction site and all history persisted before this change decode
         * unchanged. Mirrors iOS `isOffloadedArgument`.
         */
        val isOffloadedArgument: Boolean = false,
    ) : AgentContentPart()

    data class ToolResult(
        val id: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        val imageData: ByteArray? = null,
        val imageMimeType: String? = null,
        /**
         * iSH-visible linux path for [imageData], if it was persisted. Used
         * by request-level image budgeting to emit a re-fetchable text
         * placeholder when the bytes are elided.
         */
        val imageLinuxPath: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ToolResult) return false
            return id == other.id && name == other.name && content == other.content &&
                isError == other.isError && imageData.contentEquals(other.imageData) &&
                imageMimeType == other.imageMimeType && imageLinuxPath == other.imageLinuxPath
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + isError.hashCode()
            result = 31 * result + (imageData?.contentHashCode() ?: 0)
            result = 31 * result + (imageMimeType?.hashCode() ?: 0)
            result = 31 * result + (imageLinuxPath?.hashCode() ?: 0)
            return result
        }
    }

    data class ImageData(
        val data: ByteArray,
        val mimeType: String,
        /**
         * iSH-visible linux path the bytes were originally persisted to, if
         * any. See [LLMMessage.ImagePart.linuxPath] for usage notes.
         */
        val linuxPath: String? = null,
        /**
         * [T-android-vision-group / GH#182] Provider substitutes this for the
         * pixels on the T264 no-native-vision path. See
         * [LLMMessage.ImagePart.noVisionPlaceholder]. Seeded by ChatViewModel
         * only when a Vision Group is configured; null → provider default literal.
         */
        val noVisionPlaceholder: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageData) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType &&
                linuxPath == other.linuxPath && noVisionPlaceholder == other.noVisionPlaceholder
        }

        override fun hashCode(): Int {
            var result = 31 * data.contentHashCode() + mimeType.hashCode()
            result = 31 * result + (linuxPath?.hashCode() ?: 0)
            result = 31 * result + (noVisionPlaceholder?.hashCode() ?: 0)
            return result
        }
    }
}

// ─── Merged from AgentToolDefinition.kt ──────────────────────────────────────────────────

/**
 * Provider-agnostic tool definition. Each tool registers with this structure,
 * and providers convert it to their native format (Anthropic input_schema,
 * Gemini function_declarations, OpenAI function calling).
 */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    /**
     * [T-port-nested-json-string] The arguments with every nested-schema
     * parameter that arrived as JSON-in-a-string decoded to the array / object
     * its schema declares. Some OpenAI-compatible stacks (DeepSeek V4 Flash via
     * SenseNova and Bailian, 5 of 7 user_ask calls on a Pixel 4a) send
     * `"questions":"[{...}]"` instead of a nested array. Returns the same
     * instance when nothing changed; a string that does not parse is left
     * as is, so the tool's own parser can tell the model exactly what is wrong.
     */
    fun coerceNestedJsonStrings(args: JSONObject): JSONObject {
        var out: JSONObject? = null
        for ((key, p) in parameters) {
            val node = p.schema ?: continue
            if (!args.has(key)) continue
            val v = args.opt(key)
            val c = node.coerce(v)
            if (c !== v) {
                if (out == null) out = JSONObject(args.toString())
                out.put(key, c)
            }
        }
        return out ?: args
    }

    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
    }

    /** Gemini format: {name, description, parameters: {type:OBJECT, properties, required}} */
    fun toGeminiJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toGeminiJson())
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** OpenAI format: {type:function, function: {name, description, parameters: {type:object, ...}}} */
    fun toOpenAIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }
    }
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
    /**
     * [T-port-nested-schema] Nested schema for a structured parameter (port of
     * iOS 5bbab0250). The flat scalar fields cannot express `user_ask.questions`
     * - an array of question objects, each with an array of option objects - so
     * such a parameter used to travel as a JSON string inside the JSON (as
     * browser_use `cookies` still does), which is fragile for a model to
     * produce. When set, [type] should be "array" (or "object") and every
     * provider renders the node in its own dialect. Scalars leave it null, and
     * their output stays byte-identical to before.
     */
    val schema: AgentSchemaNode? = null,
) {
    /** Standard JSON Schema: Anthropic input_schema, OpenAI Chat and Responses. */
    fun toJson(): JSONObject {
        schema?.let { return it.toJsonSchema(gemini = false).apply { put("description", description) } }
        return JSONObject().apply {
            put("type", type)
            put("description", description)
            if (enumValues != null) put("enum", JSONArray(enumValues))
        }
    }

    /** Gemini dialect (upper-case types). */
    fun toGeminiJson(): JSONObject {
        schema?.let { return it.toJsonSchema(gemini = true).apply { put("description", description) } }
        return JSONObject().apply {
            put("type", type.uppercase())
            put("description", description)
            if (enumValues != null) put("enum", JSONArray(enumValues))
        }
    }
}

/**
 * [T-port-nested-schema] A provider-neutral JSON Schema subset (iOS
 * `AgentSchemaNode`): string with enum, integer, boolean, array with item
 * bounds, object with properties + required.
 *
 * Bounds and nested `required` lists are advisory - not every provider honours
 * them - so a tool that uses a nested schema must re-validate its input in its
 * own parser. Unlike iOS (whose Anthropic SDK types cannot nest objects
 * outside array items) Android builds raw JSON, so every provider receives the
 * full tree, objects at any depth included.
 */
sealed class AgentSchemaNode {
    abstract val description: String?

    data class Str(override val description: String? = null, val enumValues: List<String>? = null) : AgentSchemaNode()
    data class Integer(override val description: String? = null) : AgentSchemaNode()
    data class Bool(override val description: String? = null) : AgentSchemaNode()
    data class Arr(
        override val description: String? = null,
        val items: AgentSchemaNode,
        val minItems: Int? = null,
        val maxItems: Int? = null,
    ) : AgentSchemaNode()
    data class Obj(
        override val description: String? = null,
        /** Ordered: rendered in this order, the order the model reads them in. */
        val properties: List<Pair<String, AgentSchemaNode>>,
        val required: List<String> = emptyList(),
    ) : AgentSchemaNode()

    /**
     * Standard JSON Schema, or with [gemini] the Gemini dialect used by its
     * scalar path on iOS: upper-case types, a string enum folded into the
     * description as "(values: a, b)", and no array bounds.
     */
    fun toJsonSchema(gemini: Boolean): JSONObject {
        fun t(s: String) = if (gemini) s.uppercase() else s
        val o = JSONObject()
        when (this) {
            is Str -> {
                o.put("type", t("string"))
                if (gemini && !enumValues.isNullOrEmpty()) {
                    o.put("description", "${description ?: ""} (values: ${enumValues.joinToString(", ")})")
                } else {
                    description?.let { o.put("description", it) }
                    enumValues?.let { o.put("enum", JSONArray(it)) }
                }
            }
            is Integer -> {
                o.put("type", t("integer"))
                description?.let { o.put("description", it) }
            }
            is Bool -> {
                o.put("type", t("boolean"))
                description?.let { o.put("description", it) }
            }
            is Arr -> {
                o.put("type", t("array"))
                description?.let { o.put("description", it) }
                o.put("items", items.toJsonSchema(gemini))
                if (!gemini) {
                    minItems?.let { o.put("minItems", it) }
                    maxItems?.let { o.put("maxItems", it) }
                }
            }
            is Obj -> {
                o.put("type", t("object"))
                description?.let { o.put("description", it) }
                val props = JSONObject()
                for ((name, node) in properties) props.put(name, node.toJsonSchema(gemini))
                o.put("properties", props)
                if (required.isNotEmpty()) o.put("required", JSONArray(required))
            }
        }
        return o
    }

    /**
     * [T-port-nested-json-string] [value] decoded where it is JSON-in-a-string
     * for an array or object node, recursively; the same instance when nothing
     * changed (scalars, already-native values, strings that do not parse).
     */
    fun coerce(value: Any?): Any? = when (this) {
        is Arr -> {
            val arr = when (value) {
                is JSONArray -> value
                is String -> parseJsonOrNull(value) as? JSONArray
                else -> null
            }
            if (arr == null) value else {
                var changed = arr !== value
                val mapped = (0 until arr.length()).map { i ->
                    val item = arr.opt(i)
                    items.coerce(item).also { if (it !== item) changed = true }
                }
                if (changed) JSONArray(mapped) else value
            }
        }
        is Obj -> {
            val obj = when (value) {
                is JSONObject -> value
                is String -> parseJsonOrNull(value) as? JSONObject
                else -> null
            }
            if (obj == null) value else {
                var out: JSONObject? = if (obj !== value) obj else null
                for ((name, node) in properties) {
                    if (!obj.has(name)) continue
                    val v = obj.opt(name)
                    val c = node.coerce(v)
                    if (c !== v) {
                        if (out == null) out = JSONObject(obj.toString())
                        out.put(name, c)
                    }
                }
                out ?: value
            }
        }
        else -> value
    }

    private fun parseJsonOrNull(s: String): Any? {
        val t = s.trim()
        return when {
            t.startsWith("[") -> runCatching { JSONArray(t) }.getOrNull()
            t.startsWith("{") -> runCatching { JSONObject(t) }.getOrNull()
            else -> null
        }
    }

    /** Every description, name and enum value, for the context-size estimate. */
    val estimationText: String
        get() = when (this) {
            is Str -> listOfNotNull(description, enumValues?.joinToString(" ")).joinToString(" ")
            is Integer -> description ?: ""
            is Bool -> description ?: ""
            is Arr -> (description ?: "") + " " + items.estimationText
            is Obj -> (description ?: "") + " " +
                properties.joinToString(" ") { (name, node) -> name + " " + node.estimationText }
        }
}

// ─── Merged from ContentPart.kt ──────────────────────────────────────────────────

@Serializable
data class MediaRef(
    val id: String,
    val relativePath: String,
    val mimeType: String,
    val originalFileName: String? = null,
)

@Serializable
data class ToolUse(
    val toolUseId: String,
    val name: String,
    val input: String,
    val description: String? = null,
)

@Serializable
data class ToolResult(
    val toolUseId: String,
    val output: String,
    val success: Boolean,
    val mediaRef: MediaRef? = null,
)

@Serializable(with = ContentPartSerializer::class)
sealed class ContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val value: String) : ContentPart()

    @Serializable
    @SerialName("mediaRef")
    data class Media(val value: MediaRef) : ContentPart()

    @Serializable
    @SerialName("toolUse")
    data class Tool(val value: ToolUse) : ContentPart()

    @Serializable
    @SerialName("toolResult")
    data class Result(val value: ToolResult) : ContentPart()
}

object ContentPartSerializer : JsonContentPolymorphicSerializer<ContentPart>(ContentPart::class) {
    override fun selectDeserializer(element: JsonElement) =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "text" -> ContentPart.Text.serializer()
            "mediaRef" -> ContentPart.Media.serializer()
            "toolUse" -> ContentPart.Tool.serializer()
            "toolResult" -> ContentPart.Result.serializer()
            else -> ContentPart.Text.serializer()
        }
}

val contentPartJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ─── Merged from LLMError.kt ──────────────────────────────────────────────────

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    /**
     * [T-fallback-5xx-status] [httpStatus] carries the real response code when
     * the error came from an HTTP response, null for errors that did not
     * (decoding, safety rejections, non-JSON image bodies). Structured on
     * purpose: the mappers format their messages three different ways
     * (`[503] …` for OpenAI JSON bodies, `HTTP 522: …` for non-JSON bodies,
     * `Gemini API error 500: …`, `[overloaded_error] …` for Anthropic), and
     * parsing the code back out of prose is exactly how a Cloudflare 522 with
     * an HTML body stopped triggering group fallback.
     */
    class ProviderError(val detail: String, val httpStatus: Int? = null) :
        LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited : LLMError("Rate limited — please try again later")
    /**
     * [T-android-503-fallback] Retry-worthy failure. [httpStatus] carries the
     * response code ONLY when the failure was a real HTTP response from the
     * provider; it stays null for failures that never got one — a dropped
     * connection, a TTFB timeout, an empty stream. That distinction is what
     * lets group fallback treat a provider-side 5xx as "this model is down,
     * try the next one" without also treating the user's own dead network as
     * a reason to switch models (a switch that could not possibly help).
     */
    class TransientError(val detail: String, val httpStatus: Int? = null) :
        LLMError("Transient error: $detail")
    /**
     * [T-android-tls-trust-error] The server's certificate was rejected
     * (com.openminis.app.provider.TlsFailure). Not retryable - the same
     * handshake fails the same way every time - but fallback may help, since
     * another group member is usually another host. The message names the host
     * and what to check, and keeps the raw reason for support.
     */
    class CertificateError(
        val host: String,
        val kind: com.openminis.app.provider.TlsFailure.Kind,
        cause: Throwable,
    ) : LLMError(CertificateErrorText.describe(kind, host) + "\n(${cause.message})", cause)
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    /** Should immediately fall back to the next model in the group — same model won't help. */
    val isFallbackable: Boolean get() =
        this is RateLimited || this is InvalidApiKey || this is ProviderError || this is CertificateError

    /**
     * [T-android-503-fallback] A confirmed HTTP 5xx from the provider, as a
     * structured status rather than a substring of the message.
     *
     * Two shapes carry one. [ProviderError] is how a 5xx that is known to be
     * permanent for this model arrives (e.g. OpenAI's 503 +
     * `no_available_providers`), and its status is only recoverable from the
     * `[503] …` prefix its mapper writes. [TransientError] carries the code
     * directly in [TransientError.httpStatus].
     *
     * Anchored parsing matters here: the previous check scanned the whole
     * detail with `[5][0-9]{2}`, so a message mentioning "5000 tokens" read as
     * a server error. Only the leading `[<code>]` written by `mapHttpError` is
     * accepted.
     */
    val httpServerErrorStatus: Int?
        get() = when (this) {
            is TransientError -> httpStatus?.takeIf { it in 500..599 }
            // Structured code first; the `[<code>]` prefix is only a fallback
            // for a ProviderError built somewhere that did not thread the
            // status through. It never matches a bare number inside prose.
            is ProviderError -> httpStatus?.takeIf { it in 500..599 }
                ?: LEADING_STATUS.find(detail)
                    ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 500..599 }
            else -> null
        }

    /** True for a failure the provider answered with a 5xx status code. */
    val isHttpServerError: Boolean get() = httpServerErrorStatus != null

    private companion object {
        /**
         * Matches only a status code in the leading `[<code>]` position that
         * `mapHttpError` emits — never a number that merely appears somewhere
         * inside the provider's prose.
         */
        val LEADING_STATUS = Regex("""^\[(\d{3})\]""")
    }

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is CertificateError -> "Certificate not trusted ($host)"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}

/**
 * [T-android-tls-trust-error] User-facing text for [LLMError.CertificateError].
 *
 * LLMError lives below any Context, so the localized strings are resolved
 * through a hook MinisApp installs at startup; without it (unit tests, a
 * process that never ran onCreate) the English text below is used.
 */
object CertificateErrorText {
    @Volatile var resolver: ((com.openminis.app.provider.TlsFailure.Kind, String) -> String)? = null

    fun describe(kind: com.openminis.app.provider.TlsFailure.Kind, host: String): String =
        runCatching { resolver?.invoke(kind, host) }.getOrNull() ?: english(kind, host)

    fun english(kind: com.openminis.app.provider.TlsFailure.Kind, host: String): String = when (kind) {
        com.openminis.app.provider.TlsFailure.Kind.UNTRUSTED ->
            "Couldn't verify the security certificate of $host (no trusted root). Common causes: a proxy, VPN " +
                "or packet-capture app re-signing HTTPS (official model services only trust system certificates), " +
                "a self-hosted or relay endpoint with a self-signed certificate or a missing intermediate " +
                "certificate, or a network that intercepts HTTPS. Turn off the capture tool or proxy and retry, " +
                "or check that endpoint's certificate setup."
        com.openminis.app.provider.TlsFailure.Kind.EXPIRED_OR_CLOCK ->
            "The certificate of $host is expired or not yet valid. Check that this phone's date and time are " +
                "correct (turn on automatic time); if they are, the service's certificate has expired."
        com.openminis.app.provider.TlsFailure.Kind.HOSTNAME_MISMATCH ->
            "$host answered with a certificate for a different name, so the connection is probably being " +
                "intercepted (a public Wi-Fi sign-in page, the carrier, or a proxy). Switch networks or turn off " +
                "the proxy and retry."
    }
}

// ─── Merged from LLMMessage.kt ──────────────────────────────────────────────────

data class LLMMessage(
    val role: Role,
    val content: String,
    val imageParts: List<ImagePart> = emptyList(),
    /**
     * Inline audio attachments for audio-input models (GH#67). Kept as the
     * caller's base64 string — payloads can be megabytes and every consumer
     * (OpenAI input_audio blocks) wants base64 back, so decode/re-encode
     * would be pure churn. Mirrors iOS `LLMMessage.audios`.
     */
    val audioParts: List<AudioPart> = emptyList(),
    val contentParts: List<AgentContentPart> = emptyList(),
    /**
     * Persisted DB row id this AgentMessage corresponds to, when applicable.
     * Mirrors iOS `AgentMessage.dbMessageId`. Used by compact logic to
     * resolve marker boundaries against the in-memory history without
     * relying on positional indexes (which break across reloads). `null`
     * for synthesized messages (e.g. injected `<context-summary>` heads).
     */
    val dbMessageId: String? = null,
    /**
     * Captured reasoning_content from the originating assistant turn
     * (DeepSeek V4 / Kimi / GLM / etc. — chat-completions interleaved
     * reasoning models). Mirrors iOS `AgentMessage.reasoningContent`. Only
     * used when echoing assistant history back to the model on a subsequent
     * turn — DeepSeek V4 rejects history where any assistant turn lacks
     * reasoning_content once `thinking` is enabled.
     */
    val reasoningContent: String? = null,
) {
    enum class Role(val value: String) {
        USER("user"),
        ASSISTANT("assistant"),
    }

    data class ImagePart(
        val data: ByteArray,
        val mimeType: String,
        /**
         * iSH-visible linux path the bytes were originally persisted to, if
         * any. Used by [ImageBudget.planRequestBudget] to emit an
         * agent-readable text placeholder (`[image elided… original at <path>;
         * re-fetch with read_image]`) instead of the bytes when the request
         * payload exceeds the per-request image budget. `null` for images
         * that were never offloaded (extremely rare; spillover writer in
         * ImageBudget handles those at drop time).
         *
         * Backward-compatible: existing call sites default to `null`; old
         * persisted history that round-trips through new code stays valid.
         */
        val linuxPath: String? = null,
        /**
         * [T-android-vision-group / GH#182] Text a provider substitutes for the
         * pixels when the target model has NO native image input (the T264
         * placeholder path). Seeded by ChatViewModel ONLY when a Vision Group is
         * configured: it names the image path and instructs the model to call
         * read_image, so a text-only model routes the image through the Vision
         * Group instead of being told "I can't see it". Null → provider emits its
         * default "does not support vision input" literal (current behaviour when
         * no Vision Group is set).
         */
        val noVisionPlaceholder: String? = null,
    )

    /** Mirrors iOS `LLMMessage.AudioAttachment` (GH#67). */
    data class AudioPart(
        val format: String,     // e.g. "wav", "mp3" (OpenAI input_audio.format)
        val base64Data: String, // base64-encoded audio bytes
    )
}

data class LLMResponse(
    val text: String,
    val stopReason: String?,
    val usage: LLMUsage?,
    val mediaAttachments: List<LLMMediaAttachment> = emptyList(),
)

/** Mirrors iOS LLMMediaAttachment (LLMTypes.swift). */
data class LLMMediaAttachment(
    val type: MediaType,
    val mimeType: String,
    val data: ByteArray,
) {
    enum class MediaType(val value: String) {
        IMAGE("image"),
        AUDIO("audio"),
        VIDEO("video"),
    }
}

// ─── Merged from LLMStreamChunk.kt ──────────────────────────────────────────────────

sealed class LLMStreamChunk {
    data object Started : LLMStreamChunk()
    data class Text(val text: String) : LLMStreamChunk()
    data class Usage(val usage: LLMUsage) : LLMStreamChunk()
    /**
     * [FIX-261018-12] `refusalCategory` / `refusalExplanation`: Anthropic's
     * `stop_details` when `stopReason` is "refusal" (e.g. category "cyber"),
     * so the reply can say WHY it was refused. Null for everything else.
     */
    data class Finished(
        val stopReason: String?,
        val refusalCategory: String? = null,
        val refusalExplanation: String? = null,
    ) : LLMStreamChunk()

    /** Thinking/reasoning streaming event */
    data class ThinkingDelta(val text: String) : LLMStreamChunk()

    /** Opaque accumulated reasoning content (DeepSeek/Kimi/QwQ `reasoning_content` field).
     *  Unlike ThinkingDelta (real-time increments), this is the full accumulated blob
     *  echoed back on subsequent turns to preserve the model's chain of thought. */
    data class ReasoningContent(val content: String) : LLMStreamChunk()

    /** Tool use streaming events */
    data class ToolUseStart(val id: String, val name: String) : LLMStreamChunk()
    data class ToolInputDelta(val id: String, val accumulated: String) : LLMStreamChunk()
    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        // [T-android-gemini3-thoughtsig / #179] Gemini 3.x returns a
        // `thoughtSignature` on each functionCall part; it MUST be replayed on
        // the historical functionCall or the next request 400s. Null for every
        // other provider (only Gemini populates it).
        val thoughtSignature: String? = null,
    ) : LLMStreamChunk()

    /**
     * [T-codex-gpt-image2-oauth-android] A model-generated media attachment
     * (e.g. an image from the Codex image_generation tool). Carried through the
     * stream so non-streaming callers (sendMessage → minis-model-use) can
     * collect it into LLMResponse.mediaAttachments. Image-output models are
     * one-shot, so this typically arrives once near the end of the stream.
     */
    data class MediaAttachment(val attachment: LLMMediaAttachment) : LLMStreamChunk()
}

// ─── Merged from LLMUsage.kt ──────────────────────────────────────────────────

@Serializable
data class LLMUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    /**
     * The total input token count reported by the API for this call.
     * Represents how much of the context window has been consumed.
     * Used by dynamicMaxTokens() to compute remaining space.
     * Mirrors iOS's latestContextTokens.
     */
    val latestContextTokens: Int = 0,
    /**
     * [T-android-dsml-leak] Reasoning tokens inside [outputTokens], when the
     * provider reports them (Chat Completions
     * `completion_tokens_details.reasoning_tokens`). Null when not reported.
     */
    val reasoningTokens: Int? = null,
    /**
     * [T-usage-step1] Whether the response carried any cache field, even
     * with the value 0. Null when the parser does not know (hand-built
     * usage). Persisted so the Usage page can tell "no cache hit" (0%) from
     * "this provider never reports a cache" (em dash).
     */
    val cacheReported: Boolean? = null,
    /**
     * [T-usage-step1] The block this usage was parsed from, with absent
     * fields still null, so a stream consumer can merge field by field
     * (com.openminis.app.usage.UsageChunkMerger). Not serialized.
     */
    @kotlinx.serialization.Transient
    val chunk: com.openminis.app.usage.UsageChunk? = null,
)

// ─── Merged from ModelAttributionSnapshot.kt ──────────────────────────────────────────────────

/**
 * [T-token-attribution-snapshot] An immutable record of which model produced a
 * message, captured when the message is written.
 *
 * ## Why a snapshot rather than an id to look up later
 *
 * The Usage page used to derive a message's model by joining
 * `sessions.model_id` — one mutable column per session, rewritten on every
 * model switch (and by automatic failover, which the user never sees). The
 * join has no time dimension, so a session's entire history was re-attributed
 * to whatever model it happened to point at when the page was opened: run a
 * billion tokens through deepseek, switch to grok, and the billion moved to
 * grok. Switch back and it moved back.
 *
 * Storing the id alone would only half-fix it. An id still has to be resolved
 * against the live provider configuration, and that configuration is also
 * mutable: deleting a provider instance also removes its `modelEntries`, which
 * for a CUSTOM model is the only place its display name exists. The history
 * would survive but become unreadable — "gpt-4o-mine" collapsing into an
 * Unknown bucket.
 *
 * So this carries everything the Usage page needs to render a row without
 * consulting current config at all. Configuration describes what you can use
 * now; usage describes what happened. The second must not depend on the first.
 *
 * The cost is ~40-60 bytes per message and the fact that renaming a model does
 * not retroactively rename it in history. The latter is intended: history
 * should show the name that was in effect at the time.
 */
data class ModelAttributionSnapshot(
    /** `LLMModel.id`, e.g. `"deepseek-v4-pro"`. Stable across config edits. */
    val modelId: String,
    /** Display name as of the request, e.g. `"DeepSeek V4 Pro"`. */
    val displayName: String,
    /**
     * `ProviderType` **rawValue** (e.g. `"openAI"`), never a localized display
     * name. Grouping on display strings is what left the iOS Usage page with
     * `"Google"` / `"Gemini"` / `"Google Gemini"` as three separate sections.
     */
    val providerTypeRaw: String,
    /**
     * The provider instance uuid. Diagnostics and disambiguation only (the
     * same model id can exist under two instances); the UI never resolves
     * through it, so a deleted instance costs nothing.
     */
    val providerInstanceId: String?,
)

// ─── Merged from ModelContextWindowHeuristic.kt ──────────────────────────────────────────────────

/**
 * T-ctxslider 54ab8e93: heuristic context-window inference used by the
 * model-group "Limit Context Window" slider when a member's
 * `LLMModel.contextWindow` field is missing or non-positive.
 *
 * IMPORTANT: this is intentionally NOT folded into [LLMModel.contextWindowTokens]
 * — that getter is read by the agent loop / token accounting paths and we
 * don't want to silently overwrite source-of-truth metadata. This helper is
 * used ONLY when computing the slider's "Unlimited" ceiling so that a group
 * whose members lack contextWindow metadata still gets a reasonable cap
 * instead of degrading to the 64K floor.
 *
 * Both functions return the model's own `contextWindow` when it is set, so
 * neither body runs for a model models.dev (or OpenRouter / Codex / Copilot /
 * a manual edit) has already given a window. They only ever see an id no
 * catalog has shipped yet — a just-released model, or a name a relay invented.
 *
 * SEPARATE IMPLEMENTATIONS, MATCHING VALUES. Keeping the two bodies apart is
 * deliberate (above), but where both know a family they must return the same
 * number, and the slider must never exceed the loop: a ceiling above the real
 * window lets a user pick a limit the model will reject. `ContextWindowDefaultsTest`
 * asserts that agreement over every family, so adding a branch to one function
 * and not the other fails there instead of shipping.
 */
internal fun inferContextWindowTokens(model: LLMModel): Int {
    model.contextWindow?.takeIf { it > 0 }?.let { return it }
    val idLower = model.id.lowercase()

    // 1M-class models — match before the generic claude-* / gemini-* fall-throughs.
    val millionClassPatterns = listOf(
        Regex("claude-.*-1m"),
        Regex("claude-opus-4-[5678]"),
        Regex("claude-sonnet-4-[567]"),
    )
    if (millionClassPatterns.any { it.containsMatchIn(idLower) }) return 1_000_000
    if (idLower.contains("gemini-2.5-pro") ||
        idLower.contains("gemini-2.0-pro") ||
        idLower.contains("gemini-1.5-pro") ||
        idLower.contains("gemini-2.5-flash") ||
        idLower.contains("gemini-3-pro") ||
        idLower.contains("gemini-3-flash")
    ) return 1_000_000

    if (idLower.startsWith("claude-") || idLower.contains("/claude-")) return 200_000

    if (idLower.startsWith("gpt-4o") || idLower.contains("/gpt-4o")) return 128_000
    if (idLower.startsWith("gpt-4-turbo") || idLower.contains("/gpt-4-turbo")) return 128_000
    // gpt-5 stays ahead of the bare gpt-4 / codex rows below, matching the
    // loop's order, so `gpt-5.3-codex` is a 400K gpt-5 rather than a 200K codex.
    if (idLower.startsWith("gpt-5") || idLower.contains("/gpt-5")) return 400_000

    // Bare gpt-4 is the 8K original. This row used to say 16_000 — the ONE
    // place the slider sat ABOVE the loop, i.e. offering a ceiling higher than
    // the real window, which is the harmful direction.
    if (idLower.startsWith("gpt-4") || idLower.contains("/gpt-4")) return 8_000
    if (idLower.startsWith("gpt-3.5") || idLower.contains("/gpt-3.5")) return 16_000

    // o-series reasoning models and Codex: 200K on both sides. Absent here
    // before, so an uncatalogued `o4-mini` collapsed to the trailing 128K
    // default and the slider could not be raised to the window the loop uses.
    if (idLower.startsWith("o3") || idLower.contains("/o3")) return 200_000
    if (idLower.startsWith("o4") || idLower.contains("/o4")) return 200_000
    if (idLower.startsWith("codex") || idLower.contains("/codex")) return 200_000

    if (idLower.startsWith("deepseek-") || idLower.contains("/deepseek-")) return 128_000

    // xAI Grok. [T-android-grok-context-underestimate] 33b028477 added this
    // branch to LLMModel.contextWindowTokens but not here, so the slider's
    // "Unlimited" ceiling for an uncatalogued Grok collapsed to the trailing
    // 128K default while the agent loop was already using 256K — a group limit
    // could not be raised to the window the loop actually honours. The two
    // heuristics are deliberately separate implementations (see the header),
    // but where they both know a family they must agree, so the values here
    // mirror the loop's exactly: Grok 2/3 are the 131K generation, Grok 4 and
    // later floor at 256K.
    if (idLower.startsWith("grok") || idLower.contains("/grok")) {
        if (idLower.contains("grok-2") || idLower.contains("grok-3")) return 131_072
        return 256_000
    }

    return 128_000
}

// ─── Merged from ProviderConfig.kt ──────────────────────────────────────────────────

@Serializable
enum class ProviderType(val displayName: String) {
    anthropic("Anthropic"),
    gemini("Google Gemini"),
    openAI("OpenAI"),
    openRouter("OpenRouter"),
    xAI("xAI (Grok)"),
    // [T-kimi-oauth] Kimi Code (Coding Plan) — RFC 8628 device-code OAuth,
    // OpenAI-compatible upstream at api.kimi.com/coding/v1. DB round-trip is
    // name-based (ProviderCredential.valueOf), so appending is migration-safe.
    kimiCode("Kimi Code"),

    /**
     * [T-copilot-provider] GitHub Copilot via device-flow OAuth. Chat is
     * OpenAI-compatible at api.githubcopilot.com, but auth is two-tier (see
     * CopilotOAuthManager), which is why it cannot reuse the API-key path.
     *
     * ⚠️ Unofficial: borrows the VS Code client identity, and GitHub's terms
     * treat proxying Copilot as grounds for account restriction. Gated behind
     * CopilotFeatureFlag (default off) and an explicit in-app risk notice.
     *
     * Appended last — DB round-trips are name-based (ProviderCredential.valueOf),
     * so adding a case is migration-safe.
     */
    githubCopilot("GitHub Copilot"),

    // [T-android-provider-type-parity] The cases below exist on iOS but were
    // missing here. They are declared so a cross-platform restore or sync can
    // DECODE them: without a case, kotlinx.serialization throws on the unknown
    // name and takes the whole provider_config.json with it — one iOS-only
    // provider silently cost the user every provider in the package,
    // credentials included.
    //
    // Declared but not offered: `addableProviderTypes` in AddProviderScreen is
    // a curated list, so these never appear as something the user can create
    // here; they arrive only from an iOS package or a newer build.

    /**
     * OpenAI Responses API (`/v1/responses`). iOS `openAIResponses`.
     *
     * Fully usable on Android: it is exactly "OpenAI, forced to the Responses
     * endpoint", which the existing OpenAI path already expresses through
     * `ProviderInstance.useResponsesAPI` (iOS spells the same thing
     * `forceResponsesAPI`). So a restored iOS instance of this type WORKS
     * rather than merely surviving the import.
     */
    openAIResponses("Responses API (v3)"),

    /**
     * iOS `antigravity`. Decode-only here — Android has no implementation, so
     * an instance restores and is visible but cannot serve a request.
     */
    antigravity("Antigravity"),

    /**
     * Sentinel for a provider type THIS build doesn't recognize — e.g. a newer
     * build's package naming a type added after this release. Decoding to this
     * preserves the instance (shown as unusable) instead of destroying the file
     * it arrived in. Mirrors iOS `ProviderType.unsupported`.
     *
     * Never write this back as an instance's type where the original string is
     * still available; it is a read-side fallback, not a real provider.
     */
    unsupported("Unsupported");

    /**
     * [T-android-provider-type-parity] True for types this build can decode and
     * display but cannot actually drive a request with. Callers that need a
     * working provider must check this rather than assuming every enum case is
     * usable.
     */
    val isUsable: Boolean
        get() = when (this) {
            // openAIResponses included: it routes through the OpenAI provider
            // with the Responses endpoint forced on.
            anthropic, gemini, openAI, openRouter, xAI, kimiCode, openAIResponses,
            githubCopilot -> true
            antigravity, unsupported -> false
        }

    val builtInModels: List<LLMModel>
        get() = when (this) {
            anthropic -> LLMModel.allAnthropic
            gemini -> LLMModel.allGemini
            openAI -> LLMModel.allOpenAI
            openRouter -> LLMModel.allOpenRouter
            xAI -> LLMModel.allXAI
            kimiCode -> LLMModel.allKimi
            // No built-in catalog for the decode-only types; models restored
            // alongside the instance still appear as custom entries.
            // [T-copilot-provider] Copilot's catalog is fetched live from
            // /models — a hardcoded list would offer models the signed-in
            // account may not be entitled to.
            githubCopilot, openAIResponses, antigravity, unsupported -> emptyList()
        }

    companion object {
        /**
         * [T-android-provider-type-parity] Decode a raw provider-type string,
         * never throwing: an unrecognized value maps to [unsupported].
         *
         * Mirrors iOS `ProviderType.decoded(_:)`. Use this anywhere a value
         * originates OUTSIDE this build — backup packages, sync payloads,
         * config files — so one unknown string can't fail the whole document.
         */
        fun decoded(raw: String): ProviderType =
            entries.firstOrNull { it.name == raw } ?: unsupported
    }
}

@Serializable
enum class ProviderCredential {
    apiKey,
    oauth,
}

@Serializable(with = ThinkingLevelSerializer::class)
enum class ThinkingLevel {
    // [T-android-thinking-level-arch] New cases MUST be appended at the end.
    // Kotlinx Serialization encodes enums by NAME string ("OFF"/"LOW"/...),
    // not declaration-order ordinal, so appending does not corrupt already-
    // persisted data. GPT-5.6 sol/terra reach ULTRA, luna reaches MAX.
    OFF, LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA;

    val isEnabled: Boolean get() = this != OFF

    val displayName: String
        get() = when (this) {
            OFF -> "Off"
            LOW -> "Low"
            MEDIUM -> "Medium"
            HIGH -> "High"
            XHIGH -> "XHigh"   // was "Max"; the label now belongs to the new MAX case
            MAX -> "Max"
            ULTRA -> "Ultra"
        }

    /** Intensity ordinal used for intersection / clamp comparisons —
     *  follows the enum declaration order. */
    val rank: Int get() = ordinal

    companion object {
        /**
         * [T-android-thinking-level-arch] Safe deserialization fallback for a
         * level string that this app build doesn't recognize — e.g. an older
         * build reading a token a NEWER build persisted (Room DB / JSON mirror).
         * Mirrors the `runCatching { … }.getOrNull()` guard already used for
         * ImageEndpointMode (ProviderConfigMapping.kt). NEVER throws: an unknown
         * value clamps to the highest level THIS build knows (XHIGH) rather than
         * letting the caller handle an exception or drop the whole config. Use
         * this for every "read from persisted data" path.
         */
        fun decoded(raw: String): ThinkingLevel = parseOrNull(raw) ?: XHIGH

        /**
         * [T-android-restore-thinking-level] Case-insensitive parse, or null
         * when the value names no level. Accepts both spellings of a level:
         * Android's enum name (`"HIGH"`) and iOS's raw value (`"high"`).
         */
        fun parseOrNull(raw: String): ThinkingLevel? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * [T-android-restore-thinking-level] Reads a level in either spelling; writes
 * the enum name, exactly as the generated enum serializer did.
 *
 * Both platforms define the same seven levels, but iOS encodes them as its
 * `String` raw values (`"high"`) and Android by enum name (`"HIGH"`). With the
 * generated serializer, an iOS value was an unknown enum constant — and
 * `BackupFormat.json`'s `coerceInputValues` turns that into `null` on a
 * nullable property, silently. Every group level, per-model ceiling and sub
 * agent override in an iOS backup restored on Android as "no level".
 *
 * Read side only, on purpose. The same serializer writes the Room blobs
 * (ModelOverrides, SubAgentDefinition) and the ProviderConfig JSON mirror, so
 * changing what is written would make an older build misread them after a
 * downgrade. An unrecognized value (a level from a newer build) becomes XHIGH,
 * the clamp [ThinkingLevel.decoded] and iOS `ThinkingLevel.decoded` already
 * use; it can no longer be coerced to null, because this descriptor is a plain
 * string, not an enum.
 */
object ThinkingLevelSerializer : KSerializer<ThinkingLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.data.model.ThinkingLevel", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ThinkingLevel) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ThinkingLevel = ThinkingLevel.decoded(decoder.decodeString())
}

@Serializable
enum class RoutingStrategy {
    fallback,
    loadBalance,
}

/**
 * [T-android-image-endpoint-mode] How an OpenAI-compatible provider routes
 * image-generation requests. Mirrors iOS `ImageEndpointMode`
 * (ProviderInstance.swift). Wire values (`images_generations` /
 * `chat_completions`) match iOS so JSON export/import interops cross-platform.
 *
 * - [auto]: try `/v1/images/generations` first; on a route-missing 4xx fall
 *   back to `/v1/chat/completions` and cache the working endpoint in
 *   `imageEndpointResolved` so the next call skips the probe.
 * - [imagesGenerations]: always `/v1/images/generations`.
 * - [chatCompletions]: always `/v1/chat/completions` (multimodal output) —
 *   i.e. the normal chat path, no special handling.
 */
@Serializable
enum class ImageEndpointMode {
    auto,
    @SerialName("images_generations") imagesGenerations,
    @SerialName("chat_completions") chatCompletions,
}

/**
 * Controls when fallback to the next model in the group is triggered.
 * - default: only on rate limiting (429) or server errors (5xx)
 * - always: on any error, including network errors, auth failures, etc.
 *
 * [B01 cross-platform read] iOS calls `default` `limited` (its
 * FallbackStrategy has `limited` and `always`), so an iOS backup's group
 * reads as `default` by name. Decoding only: Android still writes `default`
 * for its own config; the backup exporter writes `limited` for iOS.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
enum class FallbackStrategy {
    @kotlinx.serialization.json.JsonNames("limited") default,
    always,
}

@Serializable
data class ModelGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val memberEntryIds: MutableList<String> = mutableListOf(),
    var strategy: RoutingStrategy = RoutingStrategy.fallback,
    var fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
    // T312: per-group session defaults. Mirrors iOS ModelGroup fields.
    // null = "no default override" — sessions bound to this group keep
    // OFF / unlimited unless the user opts in. Pre-T312 persisted JSON
    // simply lacks both keys; kotlinx.serialization fills them with the
    // declared defaults so older configs round-trip cleanly.
    var defaultThinkingLevel: ThinkingLevel? = null,
    var contextLimitTokens: Int? = null,
    // T-ctxslider 54ab8e93: persisted memory of the last user-selected context
    // limit, so toggling the "Limit Context Window" switch OFF→leave→ON
    // restores the previous value instead of snapping back to 128K. Default
    // null lets old JSON deserialize cleanly (kotlinx.serialization).
    var lastContextLimitTokens: Int? = null,
)

@Serializable
data class ProviderInstance(
    val id: String,
    var label: String,
    val providerType: ProviderType,
    val credentialType: ProviderCredential,
    var isEnabled: Boolean = true,
    /**
     * [T-android-provider-iso8601-wire] Epoch millis in memory, but serialized
     * as an ISO-8601 string. iOS `ProviderInstance.createdAt` is a `Date`
     * decoded with `.iso8601`, so a bare epoch number made iOS's
     * `importProviders` fail to decode the whole `provider_config.json` and
     * report `Unreadable: 1` for the Providers category. Reads both forms, so
     * the existing local JSON mirror and older Android backups still load.
     */
    @Serializable(with = com.openminis.app.backup.Iso8601MillisSerializer::class)
    val createdAt: Long = System.currentTimeMillis(),
    var customBaseURL: String? = null,
    var appendV1Suffix: Boolean = true,
    // [T-provider-custom-user-agent] Optional per-provider User-Agent
    // override. Some relay gateways only accept requests whose UA looks like
    // an official client (e.g. Claude Code); Minis' default UA gets rejected.
    // null/blank → keep the default UA; non-blank → replace the User-Agent
    // header on every outbound request (chat / models / responses) for this
    // instance. Only surfaced in the UI for custom-base OpenAI-/Anthropic-
    // compat providers. Field name matches iOS for cross-platform
    // export/import interop.
    var customUserAgent: String? = null,
    // OpenAI-only: when true, traffic goes through /v1/responses instead of
    // /v1/chat/completions. Mirrors iOS `ProviderType.openAIResponses` flag,
    // but modeled here as a switch on the instance so an existing OpenAI
    // provider can be re-pointed without changing its type.
    var useResponsesAPI: Boolean = false,
    // [T-android-image-endpoint-mode] User-selected image-generation routing
    // for OpenAI-compatible providers (see ImageEndpointMode). Defaults keep
    // old persisted JSON (which lacks both keys) round-tripping cleanly:
    // kotlinx.serialization fills them with these declared defaults.
    var imageEndpointMode: ImageEndpointMode = ImageEndpointMode.auto,
    // Cached probe result for `auto` mode: the endpoint that last worked, so
    // subsequent calls skip the /images/generations probe. Cleared whenever
    // the user forces a mode. null = not yet probed.
    var imageEndpointResolved: ImageEndpointMode? = null,
    // [T-android-azure-openai] Azure OpenAI mode. When true, OpenAIProvider
    // auths with the `api-key:` header (not `Authorization: Bearer`) and treats
    // the custom base URL as an Azure endpoint (the user pastes the full Azure
    // URL including `?api-version=…`; the request routes as
    // {endpoint}/openai/deployments/{model}/{path}). Orthogonal to the
    // Chat/Responses API-format choice — both work under Azure. Defaults false
    // so existing OpenAI instances are completely unaffected. Field name
    // matches iOS for cross-platform export/import interop.
    var azureMode: Boolean = false,

) {
    /** Returns the effective API base URL, applying v1 suffix if configured. */
    val effectiveBaseURL: String?
        get() {
            val base = customBaseURL?.trimEnd('/') ?: return null
            return if (appendV1Suffix && !base.endsWith("/v1")) "$base/v1" else base
        }

    /**
     * [T-empty-key-compat-endpoints] Whether an EMPTY API key is a valid
     * configuration for this instance. True only for API-key-mode instances
     * pointing at a third-party OpenAI/Anthropic-compatible endpoint (custom
     * base URL set): local gateways, ollama, LM Studio, LiteLLM and many
     * relay deployments require no key, and forcing a dummy one is friction.
     * Deliberately NOT extended to official endpoints (no custom base URL —
     * an empty key against the official API is always a misconfiguration) or
     * OAuth instances (their credential is the token). Mirrors iOS
     * `ProviderInstance.allowsEmptyAPIKey`; Android has no `openAIResponses`
     * type — the `useResponsesAPI` flag rides on `.openAI`, so the type gate
     * is just {openAI, anthropic}.
     */
    val allowsEmptyAPIKey: Boolean
        get() = credentialType == ProviderCredential.apiKey &&
            !customBaseURL.isNullOrBlank() &&
            (providerType == ProviderType.openAI || providerType == ProviderType.anthropic)

    /**
     * [T-android-image-endpoint-mode] Whether the "Image Generation" endpoint
     * picker is surfaced for this instance. Mirrors iOS
     * `supportsImageEndpointSetting`. Android has no `openAIResponses` provider
     * type — an OpenAI instance carries the `useResponsesAPI` flag instead, so
     * the gate is just the three OpenAI-compatible types.
     */
    val supportsImageEndpointSetting: Boolean
        get() = providerType == ProviderType.openAI ||
            providerType == ProviderType.openRouter ||
            providerType == ProviderType.xAI

    /**
     * [T-android-azure-openai] Whether the Azure toggle applies to this
     * instance — only OpenAI instances using an API key (Azure auths with an
     * api-key header). Covers both Chat-Completions and Responses formats since
     * Android models Responses as the `useResponsesAPI` flag on an openAI
     * instance rather than a separate provider type. Mirrors iOS
     * supportsAzureMode.
     */
    val supportsAzureMode: Boolean
        get() = providerType == ProviderType.openAI && credentialType == ProviderCredential.apiKey


    /**
     * [T-android-thinking-rules-phase2 / parity with iOS 93eb4090] Whether custom
     * thinking rules are meaningful for this instance, i.e. its requests actually run
     * through the Chat Completions path that consults [ThinkingRuleResolver].
     *
     * Anthropic and Gemini use their own thinking emitters and never read the rule
     * registry. An OpenAI instance in Responses mode (useResponsesAPI) builds its
     * `reasoning` inline, and a Codex-shaped OAuth instance (oauth, no custom base)
     * resolves to the Responses backend — neither consults user rules. So on all of
     * those the UI must show an explanatory notice, NOT an interactive list that
     * promises behaviour the request path ignores.
     */
    val supportsCustomThinkingRules: Boolean
        get() = when (providerType) {
            ProviderType.anthropic, ProviderType.gemini -> false
            else -> {
                val codexOAuth = credentialType == ProviderCredential.oauth &&
                    customBaseURL.isNullOrBlank()
                !useResponsesAPI && !codexOAuth
            }
        }
}

@Serializable
data class ModelOverrides(
    val displayName: String? = null,
    val maxOutputTokens: Int? = null,
    // Override the baseModel's context window (custom models only on iOS, but
    // we allow it on any entry here — mirrors iOS LLMModel.contextWindowTokens
    // being writable on custom entries).
    val contextWindow: Int? = null,
    // Override reasoning support flag (custom entries only, per iOS behavior).
    val supportsReasoning: Boolean? = null,
    // Modality overrides — mirror iOS ModelModality flags. Non-null means the
    // user explicitly flipped this dimension; null means "inherit baseModel".
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    // [T-android-thinking-level-arch] User-set ceiling for this model's thinking
    // intensity. Highest-priority source in the resolution chain (overrides the
    // built-in ThinkingLevelCatalog rule). null = inherit the catalog default.
    // Adding an optional field is safe for kotlinx.serialization deserialization
    // of older JSON (unlike adding an enum case) — old configs simply lack the
    // key and it defaults to null.
    val maxThinkingLevel: ThinkingLevel? = null,
    // [T-android-model-custom-params] Per-model request tuning. All four are
    // nullable with a `= null` default, which is what lets an OLDER config or
    // backup — written before these keys existed — decode without throwing
    // MissingFieldException. kotlinx.serialization only tolerates a missing key
    // when the property has a default, so the default is load-bearing, not
    // decoration.
    //
    // null means "inherit / send nothing", NOT "use 0". That distinction
    // matters for temperature especially: 0.0 is a legitimate, meaningful value
    // (fully deterministic), so it cannot double as the absent marker.
    val temperature: Double? = null,
    val topP: Double? = null,
    /** Extra headers merged into this model's requests. null = none. */
    val customHeaders: Map<String, String>? = null,
    /** Raw JSON merged into the request body for provider-specific knobs. */
    val extraBodyParams: JsonObject? = null,
) {
    val isEmpty: Boolean
        get() = displayName == null
            && maxOutputTokens == null
            && contextWindow == null
            && supportsReasoning == null
            && inputModalities == null
            && outputModalities == null
            && maxThinkingLevel == null
            // [T-android-model-custom-params] The new fields MUST be part of
            // this predicate. `isEmpty` gates whether the overrides object is
            // written at all on export (ProviderRepository ~2734), so omitting
            // them here would make an entry carrying ONLY a temperature look
            // empty and get dropped on backup/round-trip.
            && temperature == null
            && topP == null
            && customHeaders == null
            && extraBodyParams == null
}

@Serializable
data class ModelEntry(
    val providerInstanceId: String,
    @SerialName("model")
    val baseModel: LLMModel,
    val overrides: ModelOverrides = ModelOverrides(),
    val isCustom: Boolean = false,
    val isHidden: Boolean = false,
    val uuid: String = UUID.randomUUID().toString(),
    /** [T-android-provider-iso8601-wire] See ProviderInstance.createdAt — iOS
     *  `ModelEntry.userModifiedAt` is a `Date?` decoded with `.iso8601`. */
    @Serializable(with = com.openminis.app.backup.Iso8601MillisNullableSerializer::class)
    val userModifiedAt: Long? = null,
    /**
     * [T-android-model-absence-grace] When the provider's /v1/models first
     * stopped listing this model, or null while it is still being reported.
     * Mirrors iOS `ModelEntry.absentSince`.
     *
     * Exists because "this refresh did not list the model" and "this model is
     * gone" are different facts, and [ProviderRepository.replaceEntries] used
     * to treat them as one: a catalog entry missing from a single response was
     * deleted outright, taking its [overrides] with it. Relay/aggregator
     * endpoints drop a model from the list transiently — upstream quota, a
     * backend rotation, a partial outage — and list it again minutes later.
     *
     * While set, the entry is kept and shown as unavailable rather than
     * deleted; it is only really removed once the absence outlives
     * [ProviderRepository.MODEL_ABSENCE_GRACE_MS]. A model that comes back is
     * cleared to null and is indistinguishable from one that never left,
     * overrides included.
     *
     * DELIBERATELY excluded from [isUserModified]: absence is a per-device
     * observation (each device refreshes on its own schedule against possibly
     * different upstream state), not user intent, so it must not make an
     * otherwise-untouched entry look user-modified. Same ISO-8601 wire shape as
     * [userModifiedAt] so the field round-trips with iOS rather than becoming a
     * platform-only dialect.
     */
    @Serializable(with = com.openminis.app.backup.Iso8601MillisNullableSerializer::class)
    val absentSince: Long? = null,
) {
    val id: String get() = uuid

    /** True while the provider is not currently listing this model. */
    val isUnavailableFromProvider: Boolean get() = absentSince != null

    /**
     * Effective model as seen by the rest of the app: baseModel with overrides
     * applied.
     *
     * [T-android-modality-provider-fallback] The two modality fields resolve
     * through `effective*Modalities`, i.e. the full chain
     *   user override > model's own declared list > provider default table.
     * Every other field keeps the plain "null override means inherit baseModel
     * verbatim" semantics — only modality has a provider-level default to fall
     * back to.
     *
     * This property is what the REST OF THE APP reads (chat gating, Vision
     * Group membership, hasImageInput and friends). 1be751fdc added the
     * fallback but only wired it into ModelEntryDetailScreen, so the detail
     * screen alone showed the corrected switches while every real consumer
     * still saw the raw nulls — which is why reinstalling and refreshing
     * models changed nothing for Fable 5.1.
     *
     * The `overrides.isEmpty` early return had to go, and that was the actual
     * bug: `isEmpty` only asks whether the USER set anything, so the common
     * case (new model, no override) returned baseModel without ever calling
     * copy(), leaving no opportunity to apply any fallback at all. It is now
     * gated on "nothing to apply from EITHER source" — no overrides AND no
     * modality fallback pending — so the allocation is still skipped for the
     * genuinely-unchanged case (every already-catalogued model, every provider
     * outside the default table).
     */
    val model: LLMModel
        get() {
            val effIn = baseModel.effectiveInputModalities
            val effOut = baseModel.effectiveOutputModalities
            // "Did the provider table contribute anything?" — asked as
            // was-null-and-now-is-not, NOT by comparing list identity:
            // effective* runs the list through normalizeModalities(), whose
            // `.map {}` allocates a fresh list every call, so a referential
            // check would be true even when nothing was added and the early
            // return would be dead code.
            val fallbackPending =
                (baseModel.inputModalities == null && effIn != null) ||
                    (baseModel.outputModalities == null && effOut != null)
            if (overrides.isEmpty && !fallbackPending) return baseModel
            return baseModel.copy(
                displayName = overrides.displayName ?: baseModel.displayName,
                maxOutputTokens = overrides.maxOutputTokens ?: baseModel.maxOutputTokens,
                contextWindow = overrides.contextWindow ?: baseModel.contextWindow,
                supportsReasoning = overrides.supportsReasoning ?: baseModel.supportsReasoning,
                // [T-android-modality-normalize-override] (GH#305) Normalize the
                // OVERRIDE too, not just the fallback.
                //
                // `effIn`/`effOut` come from effective*Modalities, which already
                // runs normalizeModalities(). The override branch did not, so a
                // user who ticked audio-input by hand stored the suffixed spelling
                // ("audio_input", the OpenAI/OpenRouter form the detail screen
                // writes) and every `contains("audio")` reader — hasAudioInput,
                // hasImageInput, the Vision Group filter — compared it against the
                // bare name and answered false. The switch showed ON while the
                // capability stayed off, so the manual override silently did
                // nothing: exactly the workaround a user reaches for when
                // inference misses their model.
                //
                // [T-android-modality-override-off] …but an EMPTY override is
                // user intent too: the detail screen saves only the ticked
                // non-text modalities, so switching the last one off stores
                // `[]`. normalizeModalities() maps empty to null, which fell
                // back to inference and made it impossible to switch a
                // mis-inferred TTS/ASR model OFF (the switch re-opened ON).
                // Only a null override (never set) falls back.
                inputModalities = overrides.inputModalities
                    ?.let { it.normalizeModalities() ?: emptyList() } ?: effIn,
                outputModalities = overrides.outputModalities
                    ?.let { it.normalizeModalities() ?: emptyList() } ?: effOut,
            )
        }

    /** True when this entry carries user intent beyond API-reported defaults. */
    val isUserModified: Boolean
        get() = isCustom || isHidden || !overrides.isEmpty
}

@Serializable
data class ProviderConfig(
    val instances: MutableList<ProviderInstance> = mutableListOf(),
    val modelEntries: MutableList<ModelEntry> = mutableListOf(),
    val modelGroups: MutableList<ModelGroup> = mutableListOf(),
    var defaultPrimaryGroupId: String? = null,
    var defaultSubGroupId: String? = null,
    // [T-android-provider-voice] Voice Input / Voice Output group bindings —
    // mirrors iOS ProviderConfig.voiceInputGroupId / voiceOutputGroupId
    // (per-device, provider_local_kv on iOS; meta KV rows here). Old persisted
    // JSON lacks the keys and deserializes to null (ignoreUnknownKeys +
    // declared defaults), so adding them is downgrade/round-trip safe.
    var voiceInputGroupId: String? = null,
    var voiceOutputGroupId: String? = null,
    // [T-android-vision-group / GH#182] Vision Group binding — the group whose
    // vision-capable members read images on behalf of a main model that cannot
    // see pixels. Per-device pointer at an ordinary ModelGroup, mirroring
    // voiceInputGroupId (meta KV row, not synced CRDT member maps). Absent in
    // old persisted JSON → deserializes to null (ignoreUnknownKeys + default).
    var visionGroupId: String? = null,
    // [T-sub-agents-v1] The named sub agents the main model can delegate to.
    // Mirrors iOS ProviderConfig.subAgents — deliberately carried on the
    // existing provider-config blob rather than as a new synced type, so it
    // rides the same path the groups above already do. Absent in old persisted
    // JSON → deserializes to an empty list (ignoreUnknownKeys + default), which
    // SubAgentRoster.normalize turns into "built-in only".
    val subAgents: MutableList<SubAgentDefinition> = mutableListOf(),
    // Models and groups exposed to the agent loop (minis-model-use terminal
    // command) — mirrors iOS agentLoopModelEntryIds / agentLoopGroupIds.
    val agentLoopModelEntryIds: MutableList<String> = mutableListOf(),
    val agentLoopGroupIds: MutableList<String> = mutableListOf(),
    // T273: bumped by ProviderRepository.saveConfig on every mutation so
    // data-class structural equals returns false even when callers mutate
    // inner MutableLists in place. Without this, MutableStateFlow's
    // distinct-until-changed (uses equals, not ===) suppresses emission
    // and ProviderDetailScreen / ModelGroupDetailScreen miss refreshes.
    // @Transient: revision is in-memory only, never persisted to prefs
    // or iCloud sync.
    //
    // [T-android-providerconfig-cme] Defaults to a globally-unique value, NOT
    // 0. equals() compares revision alone, so two configs sharing a revision
    // are indistinguishable — and with a 0 default, a freshly LOADED config
    // (revision 0) compared equal to the initial empty placeholder (revision
    // 0), so MutableStateFlow's distinct-until-changed silently DISCARDED the
    // assignment and the app ran with zero providers despite a populated DB.
    // Every construction now gets its own id; saveConfig still bumps it
    // explicitly, which remains correct because any new number is also unique.
    @kotlinx.serialization.Transient var revision: Long = nextRevision(),
) {
    companion object {
        private val revisionSeq = java.util.concurrent.atomic.AtomicLong(1L)

        /** Monotonic, process-unique id for a ProviderConfig value. */
        fun nextRevision(): Long = revisionSeq.getAndIncrement()
    }

    /**
     * [T-android-providerconfig-cme] Hand-written equals that tests [revision]
     * FIRST and never walks the mutable lists.
     *
     * The generated data-class equals compares fields in DECLARATION order, so
     * it reached `instances` / `modelEntries` / `modelGroups` long before the
     * revision short-circuit at the end could help. Those are MutableLists that
     * writers (addInstance, provider.import, replaceEntries…) mutate IN PLACE
     * on a background thread, while StateFlowImpl.collect calls equals() on the
     * main thread to decide whether to emit — so the comparison walked a list
     * that was being appended to and threw ConcurrentModificationException,
     * crashing the app from inside StateFlow's own emission path:
     *
     *   ArrayList.checkForComodification → ArrayList.equals
     *     → ProviderConfig.equals → StateFlowImpl.collect
     *
     * Reproduced by importing a provider over the debug server while a picker
     * was collecting config (crash-2026-08-16_19-59-40 / _20-00-21).
     *
     * revision is bumped by saveConfig on EVERY mutation, so it is already the
     * authoritative "did anything change" signal — comparing it alone is both
     * correct for change detection and immune to concurrent mutation. Identity
     * is checked first so a value always equals itself.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderConfig) return false
        return revision == other.revision
    }

    override fun hashCode(): Int = revision.hashCode()
}

// ─── Merged from SubAgentDefinition.kt ──────────────────────────────────────────────────

/**
 * [T-sub-agents-v1] Hard limits on the Sub Agent roster.
 *
 * Port of iOS `SubAgentLimits` (Providers/SubAgentDefinition.swift).
 *
 * These are not defensive "just in case" numbers: the roster is injected into
 * the main conversation's system prompt on every turn, so the count and the
 * description length ARE the fixed per-request cost. Bounding the input is what
 * removes the need for a runtime token warning — a user cannot configure a
 * roster that blows the budget. At the maximum (10 x (40 + 200) chars) the
 * roster is ~800 tokens.
 *
 * Shared by the editor UI, the load-time clamp and the roster generator, so the
 * three cannot drift apart.
 */
object SubAgentLimits {
    /** Including the built-in one. */
    const val MAX_COUNT = 10
    const val NAME_MAX_LENGTH = 40

    /** The only free text that reaches the main conversation. */
    const val DESCRIPTION_MAX_LENGTH = 200

    /** Child-session only; bounded so one definition cannot eat the child's context. */
    const val INSTRUCTIONS_MAX_LENGTH = 4000
}

/**
 * [T-sub-agents-v1] One named sub agent the main model can delegate to by name.
 *
 * Port of iOS `SubAgentDefinition`. Field names are the iOS wire names verbatim
 * — this rides the existing provider-config blob, which both platforms read.
 *
 * Replaces the old anonymous delegation, where a child's whole identity was the
 * free-text `task` string and its model was a binary primary/sub tier. A
 * definition now carries the identity (name, description, instructions) and the
 * model (a pinned group, or Auto).
 */
@Serializable
data class SubAgentDefinition(
    val id: String = UUID.randomUUID().toString(),
    /**
     * The wire identifier, NOT a label.
     *
     * This is the `enum` of `subagent_task.agent`, the value the model has to
     * emit, and the key [SubAgentRoster.resolve] matches on. It is deliberately
     * never localized: localizing it would put the UI language into the tool
     * schema, make the model emit non-ASCII identifiers, and — worse — break
     * every stored reference the moment the user switched language or synced to
     * a device set to another one. [displayName] localizes the built-in for
     * presentation only.
     */
    var name: String,
    /**
     * What the main model reads to decide whether to pick this agent. Bounded
     * because it is the only part that costs main-conversation tokens, and not
     * localized for the same reason as [name] — the model is not the user.
     */
    var description: String,
    /** Appended to the child session's brief. Empty = nothing appended. */
    var instructions: String = "",
    /** null = Auto: the delegating model chooses with `model_choice`. */
    var modelGroupId: String? = null,
    /**
     * [T-subagent-thinking-override] Reasoning intensity for runs of this sub
     * agent, overriding whatever the resolved model group defaults to.
     *
     * null = inherit (the group's `defaultThinkingLevel`, else the delegating
     * conversation) — the existing behaviour, and what every definition has
     * until the user sets one. Mirrors [ModelGroup.defaultThinkingLevel]: a
     * group sets the default for sessions bound to it, and this overrides that
     * for this sub agent, the same way a session-level pick overrides a group.
     */
    var thinkingLevelOverride: ThinkingLevel? = null,
    val isBuiltIn: Boolean = false,
    var sortOrder: Int = 0,
    /**
     * [T-android-provider-iso8601-wire] Epoch millis in memory, but
     * serialized as an ISO-8601 string on the wire (see
     * [com.openminis.app.backup.Iso8601MillisSerializer]). iOS
     * `SubAgentDefinition.updatedAt` is a `Date`, encoded with
     * `.iso8601` in the backup exporter — a bare Long here made a restore
     * of an iOS-produced provider_config.json fail JSON decoding entirely
     * (kotlinx.serialization aborts the whole document on one bad element),
     * dragging every provider/model/sub-agent in the same file down to
     * imported=0. The serializer still accepts a legacy plain numeric epoch
     * for old Android-written packages.
     */
    @kotlinx.serialization.Serializable(with = com.openminis.app.backup.Iso8601MillisSerializer::class)
    var updatedAt: Long = System.currentTimeMillis(),
) {
    /**
     * [T-sub-agents-v1] Localized label for the settings list and editor.
     *
     * ONLY the built-in has one — a user-created agent is shown exactly as the
     * user named it. This is presentation only: [name] stays canonical English
     * because it is the tool-schema enum value the model must emit.
     */
    fun displayName(context: android.content.Context): String =
        if (isBuiltIn) context.getString(com.openminis.app.R.string.sub_agent_builtin_name) else name

    /** Localized description for the settings screen, mirroring [displayName]. */
    fun displayDescription(context: android.content.Context): String =
        if (isBuiltIn) context.getString(com.openminis.app.R.string.sub_agent_builtin_description) else description

    /**
     * Field-level clamp applied on load and on save.
     *
     * Synced data can come from a future build or a hand-edited file, so the UI
     * limits alone are not enough — see [SubAgentRoster.normalize].
     */
    fun clamped(): SubAgentDefinition = copy(
        name = name.take(SubAgentLimits.NAME_MAX_LENGTH),
        description = description.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH),
        instructions = instructions.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH),
    )

    /** Whether any field was over its limit — used only to decide whether to log. */
    val exceedsLimits: Boolean
        get() = name.length > SubAgentLimits.NAME_MAX_LENGTH ||
            description.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH ||
            instructions.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH

    companion object {
        /**
         * The built-in definition's fixed id. Its display name is localizable
         * and user-editable; the id is what code and persisted payloads key on.
         */
        const val BUILT_IN_ID = "builtin.general"

        /**
         * [T-sub-agents-v1] The sub agent tool's wire name.
         *
         * Renamed from `delegate_task` so it reads as "the sub agent tool"
         * rather than a generic verb. The separate `agent_status` tool is
         * folded in as an `action`, so the model sees ONE tool for delegating
         * and for inspecting or stopping what it delegated.
         */
        const val TOOL_NAME = "subagent_task"

        /** The built-in's stored name, deliberately NOT localized. See [name]. */
        const val BUILT_IN_NAME = "General Sub Agent"

        /** Also not localized: this goes into the system-prompt roster. */
        const val BUILT_IN_DESCRIPTION =
            "Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, " +
                "digesting bulk output into a conclusion, or running independent branches in parallel."

        /** The built-in general sub agent, inserted by normalize when absent. */
        fun makeBuiltIn(sortOrder: Int = 0): SubAgentDefinition = SubAgentDefinition(
            id = BUILT_IN_ID,
            name = BUILT_IN_NAME,
            description = BUILT_IN_DESCRIPTION,
            instructions = "",
            modelGroupId = null,
            isBuiltIn = true,
            sortOrder = sortOrder,
        )
    }
}

/**
 * [T-sub-agents-v1] Roster-level rules: the built-in must exist, the list is
 * bounded, ordering is the disclosure order.
 *
 * Free functions on the list rather than a store type, so the load path, the
 * sync merge and the tests all share one implementation.
 */
object SubAgentRoster {

    /**
     * Normalise a decoded roster: guarantee the built-in, clamp every field,
     * bound the count, and renumber [SubAgentDefinition.sortOrder] densely
     * from 0.
     *
     * NEVER throws and never drops the built-in: this runs on data that may
     * have arrived from a possibly newer build, and a bad roster must not be
     * able to block startup. Anything discarded is logged.
     */
    fun normalize(
        input: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): List<SubAgentDefinition> {
        val list = input.toMutableList()

        // The built-in is pinned to the front regardless of its stored
        // sortOrder, so a synced roster that reordered it cannot bury it or
        // cost it its slot in the count bound below.
        val builtInIndex = list.indexOfFirst { it.id == SubAgentDefinition.BUILT_IN_ID }
        var builtIn: SubAgentDefinition
        if (builtInIndex >= 0) {
            builtIn = list.removeAt(builtInIndex)
            // The built-in's name and description are canonical English (they
            // are the tool-schema enum and the roster the model reads). Restore
            // them on every load: a row written before they were fixed carries
            // whatever the UI language was at the time, and one written by a
            // device set to another language would otherwise arrive here and
            // change what the model has to emit. The user's own fields — model
            // group, instructions, order — are untouched.
            if (builtIn.name != SubAgentDefinition.BUILT_IN_NAME ||
                builtIn.description != SubAgentDefinition.BUILT_IN_DESCRIPTION
            ) {
                log?.invoke("[SubAgents] restoring the built-in's canonical name/description")
                builtIn = builtIn.copy(
                    name = SubAgentDefinition.BUILT_IN_NAME,
                    description = SubAgentDefinition.BUILT_IN_DESCRIPTION,
                )
            }
        } else {
            builtIn = SubAgentDefinition.makeBuiltIn()
            log?.invoke("[SubAgents] built-in definition missing — reinserting")
        }

        // Custom entries keep the user's order; ties break by id so the result
        // is deterministic across devices.
        list.sortWith(compareBy({ it.sortOrder }, { it.id }))

        // Drop anything past the bound (the built-in already holds one slot).
        val allowedCustom = maxOf(0, SubAgentLimits.MAX_COUNT - 1)
        var custom: List<SubAgentDefinition> = list
        if (custom.size > allowedCustom) {
            val dropped = custom.drop(allowedCustom).map { it.name }
            log?.invoke(
                "[SubAgents] roster over the limit — keeping $allowedCustom of ${custom.size} " +
                    "custom definitions, dropping: ${dropped.joinToString(", ")}",
            )
            custom = custom.take(allowedCustom)
        }

        if (builtIn.exceedsLimits || custom.any { it.exceedsLimits }) {
            log?.invoke("[SubAgents] one or more definitions exceeded field limits — truncating")
        }

        val out = mutableListOf(builtIn.clamped().copy(sortOrder = 0))
        val seenNames = mutableSetOf(nameKey(builtIn.name))
        var next = 1
        for (def in custom) {
            // A custom definition must not claim the built-in flag: isBuiltIn
            // drives "cannot delete" in the UI, and a synced row could assert
            // it. Same for stealing the built-in's id.
            if (def.isBuiltIn || def.id == SubAgentDefinition.BUILT_IN_ID) continue
            // A nameless definition cannot be addressed: the name IS the enum
            // value the model emits and the key resolve() matches on. Left in,
            // it would advertise an empty string in the tool schema and put a
            // blank line in the roster the model reads. The settings screen
            // creates one the moment "Add" is tapped, so this is the ordinary
            // state of a row the user backed out of, not a corrupt one.
            if (def.name.isBlank()) continue
            // Two rows sharing a name make resolution ambiguous — first match
            // wins and the user cannot see why the other never runs.
            if (seenNames.contains(nameKey(def.name))) continue
            seenNames.add(nameKey(def.name))
            out.add(def.clamped().copy(sortOrder = next))
            next += 1
        }
        return out
    }

    /**
     * The definition a delegation should run under.
     *
     * Matching is case-insensitive and whitespace-trimmed because the name
     * comes back from a model. A null/blank name = the built-in. A name that
     * matches nothing returns null, which the caller turns into
     * `unknown_agent`.
     */
    fun resolve(name: String?, roster: List<SubAgentDefinition>): SubAgentDefinition? {
        val raw = name?.let { nameKey(it) }
        if (raw.isNullOrEmpty()) {
            return roster.firstOrNull { it.id == SubAgentDefinition.BUILT_IN_ID } ?: roster.firstOrNull()
        }
        return roster.firstOrNull { nameKey(it.name) == raw }
    }

    /**
     * The comparison form of a name: trimmed, case-folded and diacritic-folded.
     *
     * The name comes back from a MODEL, which may not reproduce accents exactly,
     * and the stored side is user-typed and may carry stray whitespace. Folding
     * both is what stops a roster entry named " Résumé-agent " from resolving on
     * one platform and failing with `unknown_agent` on the other. Matches iOS
     * SubAgentDefinition.nameKey.
     */
    fun nameKey(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()

    /** Hoisted: the merge path calls [nameKey] O(local × remote) times. */
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Outcome of [mergeBackup]: the roster to save plus the report counts. */
    data class BackupMerge(val roster: List<SubAgentDefinition>, val written: Int, val skipped: Int)

    /**
     * [T-android-backup-subagents] Fold a backup package's custom sub agents
     * into the local roster, with iOS's restore rules
     * (BackupImporter+Categories importSubAgents -> SubAgentRoster.merge):
     *  - an id not known locally is added, unless its NAME matches a local
     *    agent: the model picks agents by name, so two entries it cannot tell
     *    apart would make one unreachable; the local one stays;
     *  - a known id is replaced only when the package's copy is newer, so
     *    restoring an old package cannot roll back an agent edited since;
     *  - the built-in is never touched, and nothing is deleted.
     * The result is normalized (count bound, dense sortOrder).
     */
    fun mergeBackup(
        local: List<SubAgentDefinition>,
        incoming: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): BackupMerge {
        val out = local.toMutableList()
        var written = 0
        var skipped = 0
        for (r in incoming) {
            if (r.isBuiltIn || r.id == SubAgentDefinition.BUILT_IN_ID) { skipped++; continue }
            val at = out.indexOfFirst { it.id == r.id }
            if (at >= 0) {
                if (r.updatedAt > out[at].updatedAt) { out[at] = r.copy(isBuiltIn = false); written++ } else skipped++
                continue
            }
            if (out.any { nameKey(it.name) == nameKey(r.name) }) {
                log?.invoke("backup sub agent '${r.name}' skipped: a local agent already has that name")
                skipped++
                continue
            }
            out.add(r.copy(isBuiltIn = false))
            written++
        }
        return BackupMerge(normalize(out, log), written, skipped)
    }
}

// ─── Merged from SystemVoiceEntries.kt ──────────────────────────────────────────────────

/**
 * [T-android-provider-voice] Virtual System voice entries — Android port of
 * iOS UnifiedModelPicker's ModelEntry.systemASROnline / systemASROffline /
 * systemTTS extensions. These are NOT persisted in config.modelEntries: they
 * are synthesized on demand so voice group members like
 * "__builtin_system_speech__/system-asr-online" resolve to a real-looking
 * entry in pickers and group detail instead of rendering as a stale member.
 *
 * uuid is the composite "<sentinel>/<model-id>" — exactly the member id shape
 * ensureDefaultVoiceInputGroup/-OutputGroup write (and the shape iOS
 * ModelEntry.id produces), so `entry.id == memberId` holds everywhere.
 */
object SystemVoiceEntries {

    /** System ASR — Online (server-based). Higher accuracy, needs network. */
    val asrOnline = ModelEntry(
        providerInstanceId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        baseModel = LLMModel(
            id = SystemVoiceIds.SYSTEM_ASR_ONLINE,
            displayName = "System Recognition (Online)",
            provider = "system",
            inputModalities = listOf("audio"),
        ),
        isHidden = true,
        uuid = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
    )

    /** System ASR — Offline (on-device). No rate limits; audio stays local. */
    val asrOffline = ModelEntry(
        providerInstanceId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        baseModel = LLMModel(
            id = SystemVoiceIds.SYSTEM_ASR_OFFLINE,
            displayName = "System Recognition (Offline)",
            provider = "system",
            inputModalities = listOf("audio"),
        ),
        isHidden = true,
        uuid = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
    )

    /** System TTS — best installed voice per reply language. */
    val tts = ModelEntry(
        providerInstanceId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        baseModel = LLMModel(
            id = SystemVoiceIds.SYSTEM_TTS,
            displayName = "System Voice (Auto)",
            provider = "system",
            outputModalities = listOf("audio"),
        ),
        isHidden = true,
        uuid = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_TTS}",
    )

    val all: List<ModelEntry> = listOf(asrOnline, asrOffline, tts)

    /** Resolve a group member id to a virtual system entry, or null. */
    fun resolve(memberId: String): ModelEntry? = all.find { it.id == memberId }

    fun isSystemEntryId(id: String): Boolean =
        id.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)

    /**
     * Synthetic instance for pickers that group entries by provider — gives
     * the virtual entries a "System" section header. Never persisted.
     */
    // Label resolved by the caller (Composable) via stringResource so the
    // built-in provider name localizes like iOS's String(localized: "System").
    fun syntheticInstance(label: String = "System") = ProviderInstance(
        id = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        label = label,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = true,
    )
}

// ─── Merged from VoiceProviderTemplate.kt ──────────────────────────────────────────────────

/**
 * [T-android-provider-voice] Voice provider templates — Android port of iOS
 * VoiceProviderTemplate.swift (single source of truth for voice vendors).
 *
 * Voice-specialised vendors (MiniMax / Alibaba / Doubao / iFlytek / MiMo …)
 * are NOT distinct ProviderType cases — they ride on an OpenAI/Anthropic-
 * compatible instance identified by its base URL. A template:
 *   1. Preseeds the Add-Provider flow (underlying type + base URL).
 *   2. Carries mock voice models tagged with the exact single-flag SEED
 *      modality shape (see VoiceModality): ASR seed = inputs ["audio"] only,
 *      TTS seed = outputs ["audio"] only. These vendors have no OpenAI-style
 *      /models endpoint, so addInstance seeds these entries directly. Once
 *      seeded they are ordinary ModelEntry values.
 *
 * `baseURLMarkers` serve TWO narrow roles only: (1) seed the mock voice models
 * at add-time, (2) route voice REQUESTS to the right vendor adapter in
 * VoiceProviderFactory. They never classify an instance as "voice-only" —
 * voice visibility is per-model-modality (shadow view over audio entries).
 *
 * Model ids / display names / base URLs are kept byte-identical to iOS so a
 * config exported on one platform imports losslessly on the other.
 */
data class VoiceProviderTemplate(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val baseURL: String,
    val appendV1: Boolean,
    /** What the vendor can do — UI localizes the label from this. */
    val capability: Capability,
    val baseURLMarkers: List<String>,
    val mockModels: List<LLMModel>,
    /** Optional caveat surfaced in the UI (extra credentials etc.). */
    val note: String? = null,
) {
    enum class Capability { TTS, ASR, BOTH }

    companion object {
        /** The template whose base-URL markers match [baseURL], if any. */
        fun template(forBaseURL: String?): VoiceProviderTemplate? {
            val base = forBaseURL?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return all.firstOrNull { tpl -> tpl.baseURLMarkers.any { base.contains(it) } }
        }

        /** Build the seed ModelEntry list for an instance matching a template. */
        fun mockEntries(instance: ProviderInstance): List<ModelEntry> {
            val tpl = template(instance.customBaseURL) ?: return emptyList()
            return tpl.mockModels.map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
        }

        private fun tts(id: String, name: String, provider: String) = LLMModel(
            id = id, displayName = name, provider = provider,
            outputModalities = listOf("audio"),
        )

        private fun asr(id: String, name: String, provider: String) = LLMModel(
            id = id, displayName = name, provider = provider,
            inputModalities = listOf("audio"),
        )

        val all: List<VoiceProviderTemplate> = listOf(
            VoiceProviderTemplate(
                id = "elevenlabs",
                name = "ElevenLabs",
                providerType = ProviderType.openAI,
                baseURL = "https://api.elevenlabs.io",
                appendV1 = false,
                capability = Capability.TTS,
                baseURLMarkers = listOf("elevenlabs"),
                mockModels = listOf(
                    // The model `id` is the ElevenLabs voice_id.
                    tts("21m00Tcm4TlvDq8ikWAM", "Rachel (EN, F)", "elevenlabs"),
                    tts("pNInz6obpgDQGcFmaJgB", "Adam (EN, M)", "elevenlabs"),
                    tts("EXAVITQu4vr4xnSDxMaL", "Bella (EN, F)", "elevenlabs"),
                    tts("ErXwobaYiN019PkySvjV", "Antoni (EN, M)", "elevenlabs"),
                ),
            ),
            VoiceProviderTemplate(
                id = "deepgram",
                name = "Deepgram",
                providerType = ProviderType.openAI,
                baseURL = "https://api.deepgram.com",
                appendV1 = false,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("deepgram"),
                mockModels = listOf(
                    asr("nova-2", "Nova-2 (ASR)", "deepgram"),
                    asr("nova-3", "Nova-3 (ASR)", "deepgram"),
                    tts("aura-asteria-en", "Aura Asteria (TTS, EN F)", "deepgram"),
                    tts("aura-luna-en", "Aura Luna (TTS, EN F)", "deepgram"),
                    tts("aura-orion-en", "Aura Orion (TTS, EN M)", "deepgram"),
                ),
            ),
            VoiceProviderTemplate(
                id = "azure-tts",
                name = "Azure TTS",
                providerType = ProviderType.openAI,
                baseURL = "https://eastasia.tts.speech.microsoft.com",
                appendV1 = false,
                capability = Capability.TTS,
                baseURLMarkers = listOf("tts.speech.microsoft.com"),
                mockModels = listOf(
                    // Chinese (Mandarin)
                    tts("zh-CN-XiaoxiaoNeural", "Xiaoxiao (ZH, F)", "azure-tts"),
                    tts("zh-CN-YunxiNeural", "Yunxi (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunyangNeural", "Yunyang (ZH, M)", "azure-tts"),
                    tts("zh-CN-XiaoyiNeural", "Xiaoyi (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaochenNeural", "Xiaochen (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaohanNeural", "Xiaohan (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaomengNeural", "Xiaomeng (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaomoNeural", "Xiaomo (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaoruiNeural", "Xiaorui (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaoshuangNeural", "Xiaoshuang (ZH, F, Child)", "azure-tts"),
                    tts("zh-CN-XiaoyouNeural", "Xiaoyou (ZH, F, Child)", "azure-tts"),
                    tts("zh-CN-XiaozhenNeural", "Xiaozhen (ZH, F)", "azure-tts"),
                    tts("zh-CN-YunfengNeural", "Yunfeng (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunhaoNeural", "Yunhao (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunjianNeural", "Yunjian (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunxiaNeural", "Yunxia (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunyeNeural", "Yunye (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunzeNeural", "Yunze (ZH, M)", "azure-tts"),
                    // Chinese (Cantonese)
                    tts("zh-HK-HiuMaanNeural", "HiuMaan (HK, F)", "azure-tts"),
                    tts("zh-HK-WanLungNeural", "WanLung (HK, M)", "azure-tts"),
                    tts("zh-HK-HiuGaaiNeural", "HiuGaai (HK, F)", "azure-tts"),
                    // Chinese (Taiwanese)
                    tts("zh-TW-HsiaoChenNeural", "HsiaoChen (TW, F)", "azure-tts"),
                    tts("zh-TW-YunJheNeural", "YunJhe (TW, M)", "azure-tts"),
                    tts("zh-TW-HsiaoYuNeural", "HsiaoYu (TW, F)", "azure-tts"),
                    // English (US) — popular picks
                    tts("en-US-JennyNeural", "Jenny (EN, F)", "azure-tts"),
                    tts("en-US-GuyNeural", "Guy (EN, M)", "azure-tts"),
                    tts("en-US-AriaNeural", "Aria (EN, F)", "azure-tts"),
                    tts("en-US-DavisNeural", "Davis (EN, M)", "azure-tts"),
                    tts("en-US-AvaNeural", "Ava (EN, F)", "azure-tts"),
                    tts("en-US-AndrewNeural", "Andrew (EN, M)", "azure-tts"),
                    tts("en-US-EmmaNeural", "Emma (EN, F)", "azure-tts"),
                    tts("en-US-BrianNeural", "Brian (EN, M)", "azure-tts"),
                    // Japanese
                    tts("ja-JP-NanamiNeural", "Nanami (JA, F)", "azure-tts"),
                    tts("ja-JP-KeitaNeural", "Keita (JA, M)", "azure-tts"),
                    tts("ja-JP-AoiNeural", "Aoi (JA, F)", "azure-tts"),
                    tts("ja-JP-DaichiNeural", "Daichi (JA, M)", "azure-tts"),
                    tts("ja-JP-ShioriNeural", "Shiori (JA, F)", "azure-tts"),
                    // Korean
                    tts("ko-KR-SunHiNeural", "SunHi (KO, F)", "azure-tts"),
                    tts("ko-KR-InJoonNeural", "InJoon (KO, M)", "azure-tts"),
                ),
                note = "Enter the Azure Speech Services subscription key. Set the base URL to your region, e.g. https://eastasia.tts.speech.microsoft.com",
            ),
            VoiceProviderTemplate(
                id = "minimax",
                name = "MiniMax",
                providerType = ProviderType.anthropic,
                baseURL = "https://api.minimax.io",
                appendV1 = false,
                capability = Capability.TTS,
                baseURLMarkers = listOf("minimax"),
                mockModels = listOf(
                    tts("speech-2.8-hd", "MiniMax Speech 2.8 HD", "minimax"),
                    tts("speech-2.8-turbo", "MiniMax Speech 2.8 Turbo", "minimax"),
                ),
            ),
            VoiceProviderTemplate(
                id = "alibaba",
                name = "Alibaba Bailian",
                providerType = ProviderType.openAI,
                baseURL = "https://dashscope.aliyuncs.com/compatible-mode",
                appendV1 = true,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("dashscope"),
                mockModels = listOf(
                    asr("qwen3-asr-flash", "Qwen3 ASR Flash", "alibaba"),
                    tts("cosyvoice-v2", "CosyVoice v2", "alibaba"),
                ),
            ),
            VoiceProviderTemplate(
                id = "doubao",
                name = "Doubao (Volcano)",
                providerType = ProviderType.openAI,
                baseURL = "https://openspeech.bytedance.com",
                appendV1 = false,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("openspeech.bytedance", "volcano"),
                mockModels = listOf(
                    asr("bigmodel", "Doubao ASR (bigmodel)", "doubao"),
                    // Seed TTS 2.0 (big model, uranus)
                    tts("zh_female_cancan_uranus_bigtts", "灿灿 (通用, 女)", "doubao"),
                    tts("zh_female_vv_uranus_bigtts", "Vivi (表现力, 女)", "doubao"),
                    tts("zh_male_liufei_uranus_bigtts", "刘飞 (通用, 男)", "doubao"),
                    tts("zh_male_m191_uranus_bigtts", "云舟 (清爽, 男)", "doubao"),
                    // Seed TTS 1.0 (big model, moon)
                    tts("zh_female_shuangkuaisisi_moon_bigtts", "爽快思思 (爽朗, 女)", "doubao"),
                    tts("zh_female_sajiaonvyou_moon_bigtts", "撒娇女友 (撒娇, 女)", "doubao"),
                    tts("zh_female_gaolengyujie_moon_bigtts", "高冷御姐 (御姐, 女)", "doubao"),
                    tts("multi_female_shuangkuaisisi_moon_bigtts", "爽快思思 (多语, 女)", "doubao"),
                ),
                note = "Enter the API Key from the Volcano Engine new console.",
            ),
            VoiceProviderTemplate(
                id = "xunfei",
                name = "iFlytek (Xunfei)",
                providerType = ProviderType.openAI,
                baseURL = "https://iat-api.xfyun.cn",
                appendV1 = false,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("xfyun"),
                mockModels = listOf(
                    asr("iat", "iFlytek IAT (ASR)", "xunfei"),
                    tts("xiaoyan", "讯飞·晓燕 (TTS, 中文女)", "xunfei"),
                    tts("aisjiuxu", "讯飞·许久 (TTS, 中文男)", "xunfei"),
                ),
                note = "iFlytek needs App ID and API Secret — enter them as \"appId;apiKey;apiSecret\" in the API Key field.",
            ),
            VoiceProviderTemplate(
                id = "mimo",
                name = "Xiaomi MiMo",
                providerType = ProviderType.openAI,
                baseURL = "https://api.xiaomimimo.com",
                appendV1 = true,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("xiaomimimo"),
                mockModels = listOf(
                    asr("mimo-v2.5-asr", "MiMo ASR v2.5", "mimo"),
                    tts("mimo_default", "MiMo 默认 (Auto)", "mimo"),
                    tts("冰糖", "冰糖 (中文, 女)", "mimo"),
                    tts("茉莉", "茉莉 (中文, 女)", "mimo"),
                    tts("苏打", "苏打 (中文, 男)", "mimo"),
                    tts("白桦", "白桦 (中文, 男)", "mimo"),
                    tts("Mia", "Mia (EN, F)", "mimo"),
                    tts("Chloe", "Chloe (EN, F)", "mimo"),
                    tts("Milo", "Milo (EN, M)", "mimo"),
                    tts("Dean", "Dean (EN, M)", "mimo"),
                ),
            ),
        )
    }
}

// ─── Merged from AgentTypes.kt ──────────────────────────────────────────────────

/**
 * Agent-loop stream event — a spec-complete superset of [LLMStreamChunk] that
 * carries tool invocations, thinking, reasoning, and structured stop reasons.
 * Mirrors iOS `AgentStreamEvent` (AgentProvider.swift).
 *
 * Provider implementations may surface events via [LLMStreamChunk] (plain chat)
 * or via this channel (tool-using agent loops); the two coexist by design so
 * non-agent sendMessage callers don't pay for agent-specific plumbing.
 */
sealed class AgentStreamEvent {
    /** A new content block started (text or tool use). */
    data class ContentBlockStart(val start: AgentBlockStart) : AgentStreamEvent()
    /** Incremental text for an in-flight text block. */
    data class TextDelta(val delta: String) : AgentStreamEvent()
    /** Accumulated JSON preview for an in-flight tool_use input. */
    data class ToolInputDelta(val name: String, val accumulated: String) : AgentStreamEvent()
    /** A tool_use block finished assembling; args is fully parsed JSON. */
    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        val metadata: ToolCallMetadata? = null,
    ) : AgentStreamEvent()
    /** Token accounting (typically once at stream end). */
    data class Usage(val usage: LLMUsage) : AgentStreamEvent()
    /** Real-time thinking/extended-reasoning delta. */
    data class ThinkingDelta(val delta: String) : AgentStreamEvent()
    /** Accumulated opaque reasoning (DeepSeek/Kimi reasoning_content). */
    data class ReasoningContent(val content: String) : AgentStreamEvent()
    /** Stream ended with a structured stop reason. */
    data class Done(val stopReason: AgentStopReason) : AgentStreamEvent()
}

sealed class AgentBlockStart {
    data object Text : AgentBlockStart()
    data class ToolUse(val id: String, val name: String) : AgentBlockStart()
}

enum class AgentStopReason { END_TURN, TOOL_USE, MAX_TOKENS }

data class ToolCallMetadata(
    /** Gemini thought signature echoed back on the next turn. */
    val thoughtSignature: String? = null,
)

/**
 * Agent-layer message type — content is a list of typed parts (text / tool_use /
 * tool_result / image) rather than a flat string. Mirrors iOS `AgentMessage`.
 *
 * [isInterrupted] flags a stream that was cancelled mid-flight so the agent
 * loop knows whether to inject placeholder tool_result blocks.
 * [reasoningContent] preserves opaque chain-of-thought for reasoning models
 * (Kimi/DeepSeek/QwQ) that must be echoed back on later turns.
 */
data class AgentMessage(
    val role: Role,
    var parts: List<AgentContentPart>,
    var isInterrupted: Boolean = false,
    var reasoningContent: String? = null,
    var dbMessageId: String? = null,
) {
    enum class Role(val value: String) {
        USER("user"),
        ASSISTANT("assistant"),
    }
}

/**
 * Normalize a tool-use id to the regex Anthropic accepts (`^[a-zA-Z0-9_-]+$`).
 * OpenAI Responses can emit ids like `call_abc|fc_def` which Anthropic rejects
 * on the follow-up turn, so every provider echoes ids through this filter.
 */
fun sanitizeToolId(id: String): String = buildString(id.length) {
    for (ch in id) {
        append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '-')
    }
}

/** [B02] Longest tool id any request carries (strict gateways such as DeepSeek). */
const val WIRE_TOOL_ID_MAX = 40

/**
 * [B02] The tool id a request carries for a stored id. Used only when a request
 * is encoded; the stored history keeps the raw id, so a session switched back to
 * the Responses API still splits `call_…|fc_…` into its two halves.
 *
 * Same rule as iOS `wireToolId`, pinned by src/shared/tool-id vectors:
 *  1. keep the part before the first `|` (the call_id);
 *  2. replace every code point outside `[A-Za-z0-9_-]` with `-`;
 *  3. if that is empty or longer than [WIRE_TOOL_ID_MAX], use `call_` + the first
 *     35 hex chars of SHA-256 of the WHOLE raw id (UTF-8), 40 chars in all.
 * Deterministic, so a tool_use and its tool_result, encoded separately, pair up.
 * Two raw ids can map to one wire id (same call_id, different fc_id); a request
 * tells them apart with [WireToolIdMap].
 */
fun wireToolId(raw: String): String {
    val head = raw.substringBefore('|')
    val clean = buildString(head.length) {
        var i = 0
        while (i < head.length) {
            val cp = head.codePointAt(i)
            val ok = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code ||
                cp in '0'.code..'9'.code || cp == '_'.code || cp == '-'.code
            append(if (ok) cp.toChar() else '-')
            i += Character.charCount(cp)
        }
    }
    if (clean.isNotEmpty() && clean.length <= WIRE_TOOL_ID_MAX) return clean
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "call_" + hex.take(WIRE_TOOL_ID_MAX - "call_".length)
}

/**
 * [T-kimi-native-tool-id] Kimi's own tool-call id shape, `functions.<name>:<idx>`.
 * K2's chat template puts the id into the prompt verbatim and Moonshot's
 * tool-call guidance has the parser read the function name from it, so a
 * request to Kimi sends these ids unchanged instead of [wireToolId]'s
 * `functions-<name>-<idx>`. Anything else (a Responses-origin `call_…|fc_…`,
 * another provider's id) still goes through [wireToolId].
 */
private val KIMI_NATIVE_TOOL_ID = Regex("^functions\\.[A-Za-z0-9_-]+:[0-9]+$")

/** Longest Kimi-native id kept as is (tool names are at most 64 chars). */
const val KIMI_NATIVE_TOOL_ID_MAX = 80

fun isKimiNativeToolId(raw: String): Boolean =
    raw.length <= KIMI_NATIVE_TOOL_ID_MAX && KIMI_NATIVE_TOOL_ID.matches(raw)

/** [B02] [base] with `-n` appended, the base shortened so the whole stays within [WIRE_TOOL_ID_MAX]. */
fun wireToolIdWithSuffix(base: String, n: Int): String {
    val suffix = "-$n"
    return base.take((WIRE_TOOL_ID_MAX - suffix.length).coerceAtLeast(0)) + suffix
}

/**
 * [B02] The wire ids of one request. Each raw id gets [wireToolId]; when a
 * DIFFERENT raw id already holds that wire id in this request, it gets a `-N`
 * suffix instead, so calls that share a call_id stay distinct while a call and
 * its result (the same raw id) always get the same id. Order-independent per raw
 * id, deterministic for a given history.
 */
class WireToolIdMap(
    /** [T-kimi-native-tool-id] Raw ids sent unchanged (see [isKimiNativeToolId]). */
    private val keepAsIs: (String) -> Boolean = { false },
) {
    private val byRaw = HashMap<String, String>()
    private val taken = HashSet<String>()

    fun idFor(raw: String): String = byRaw.getOrPut(raw) {
        val base = if (keepAsIs(raw)) raw else wireToolId(raw)
        var id = base
        var n = 2
        while (!taken.add(id)) id = wireToolIdWithSuffix(base, n++)
        id
    }

    /** Raw ids whose wire id differs from the raw id (sanitised, shortened or renamed). */
    fun changed(): List<Pair<String, String>> = byRaw.filter { (raw, wire) -> raw != wire }.toList()
}

/**
 * [B02] Diagnostic line when a request rewrote tool ids: lengths and the
 * protocol only, never the ids (they can carry session details).
 */
fun logWireToolIds(protocol: String, ids: WireToolIdMap) {
    val changed = ids.changed()
    if (changed.isEmpty()) return
    android.util.Log.i(
        "ToolId",
        "[ToolId] protocol=$protocol rewritten=${changed.size} " +
            "rawLen=${changed.maxOf { it.first.length }} wireLen=${changed.maxOf { it.second.length }}",
    )
}

// ─── Merged from VoiceModality.kt ──────────────────────────────────────────────────

/**
 * [T-android-provider-voice] Voice-modality helpers — the Android port of the
 * iOS ModelModality voice-shape conventions (LLMTypes.swift).
 *
 * iOS models modality as one OptionSet; Android as two String lists
 * (inputModalities / outputModalities, bare names — see normalizeModalities()).
 * The cross-platform shape conventions are:
 *
 *   - Dedicated ASR model  = audio in → text out   (iOS [.audioInput, .textOutput])
 *   - Dedicated TTS model  = text in → audio out   (iOS [.textInput, .audioOutput])
 *   - TEMPLATE SEED        = exactly ONE audio flag and nothing else:
 *       ASR seed → inputs == ["audio"], outputs == null
 *       TTS seed → inputs == null,      outputs == ["audio"]
 *
 * The single-flag seed shape is the [T-voice-seed-shape-exact] discriminator:
 * no API-derived model is ever a single audio flag (inference and the models
 * APIs always attach the paired text side), so launch cleanup can safely
 * remove retired seeds by this exact shape without ever touching API models.
 */
/**
 * Sentinel ids for the on-device System speech engines (SpeechRecognizer /
 * TextToSpeech), used as virtual provider/model ids in voice groups. MUST stay
 * byte-identical to iOS (SystemVoiceProvider.builtinProviderId +
 * UnifiedModelPicker system entries) so voice-group member ids survive a
 * cross-platform config move.
 */
object SystemVoiceIds {
    const val BUILTIN_PROVIDER_ID = "__builtin_system_speech__"
    const val SYSTEM_ASR_ONLINE = "system-asr-online"
    const val SYSTEM_ASR_OFFLINE = "system-asr-offline"
    const val SYSTEM_TTS = "system-tts"
}

object VoiceModality {

    /** Substrings marking a DEDICATED speech-to-text (ASR) model. Mirrors iOS
     *  LLMTypes.asrInferencePatterns. */
    private val asrInferencePatterns = listOf(
        "-asr", "asr-", "_asr", "asr_", "whisper", "transcrib", "speech-to-text",
        "speech2text", "stt-", "-stt", "_stt", "stt_", "voice-input", "voice_input",
        // [T-asr-vendor-family-names] (GH#305) Families named after the model
        // ARCHITECTURE rather than the task. The list above only held
        // delimiter-bracketed forms of the literal "asr", so widely-used
        // Chinese ASR models matched nothing:
        //
        //   paraformer-realtime-v2  (Alibaba DashScope)
        //   SenseVoiceSmall         (FunAudioLLM)
        //
        // A non-match is not a loud failure — it falls through to the broad
        // modality inference, the model gets a general text shape, and the
        // voice pickers (which gate on the EXACT audio-in shape) simply never
        // list it. Silent absence, which is why it went unreported for so long.
        //
        // Kept byte-identical to iOS LLMTypes.asrInferencePatterns; the guard
        // test re-reads both sources so the two cannot drift apart.
        "paraformer", "sensevoice", "fun-asr", "qwen-asr",
    )

    /** Substrings marking a DEDICATED text-to-speech (TTS) model. Mirrors iOS
     *  LLMTypes.ttsInferencePatterns. */
    private val ttsInferencePatterns = listOf(
        "tts", "-tts", "_tts", "text-to-speech", "text2speech", "audio-gen",
        "audio-generation", "seed-tts", "voice-output", "voice_output",
    )

    /**
     * Infer a DEDICATED voice model's EXACT modality from its id/name, or null
     * when it isn't a dedicated ASR/TTS model. The returned pair is
     * (inputModalities, outputModalities) with BOTH sides populated — the
     * paired text side is mandatory so an inferred model never collides with
     * the single-flag template-seed shape. Mirrors iOS
     * LLMTypes.inferDedicatedVoiceModality.
     */
    fun inferDedicatedVoiceModality(id: String, displayName: String): Pair<List<String>, List<String>>? {
        val hay = "$id $displayName".lowercase()
        if (asrInferencePatterns.any { hay.contains(it) }) {
            return listOf("audio") to listOf("text")   // ASR: audio in → text out
        }
        if (ttsInferencePatterns.any { hay.contains(it) }) {
            return listOf("text") to listOf("audio")   // TTS: text in → audio out
        }
        return null
    }
}

/**
 * RAW normalized modalities — exactly what the model declares, with null
 * preserved as null.
 *
 * [T-android-modality-provider-fallback] Deliberately NOT the provider-default
 * fallback (`effectiveInputModalities`). The seed-shape predicate below matches
 * `null` and `["audio"]` exactly, so a fallback here would give every
 * modality-less model a synthetic `["text", …]` and the exact match could never
 * fire again. Capability questions use the effective lists; shape questions use
 * these.
 */
private val LLMModel.normalizedInputs: List<String>? get() = inputModalities.normalizeModalities()
private val LLMModel.normalizedOutputs: List<String>? get() = outputModalities.normalizeModalities()

/**
 * True when this model consumes audio (ASR or audio-capable chat model).
 *
 * [T-android-modality-provider-fallback] Reads the EFFECTIVE list, so a
 * Google model that models.dev has not catalogued yet still reports audio in —
 * matching iOS's `.fullMultimodal` default for that provider. No table entry
 * grants audio to Anthropic / OpenAI / OpenRouter, so this cannot start
 * reporting audio for a text-and-vision model.
 */
val LLMModel.hasAudioInput: Boolean
    get() = effectiveInputModalities?.contains("audio") == true

/** True when this model produces audio (TTS or omni model). No provider in the
 *  default table declares audio OUT, so the fallback never flips this on. */
val LLMModel.hasAudioOutput: Boolean
    get() = effectiveOutputModalities?.contains("audio") == true

/**
 * True when this model natively consumes images (a vision-capable model).
 * [T-android-vision-group] The Vision Group resolver filters group members by
 * this predicate. Normalizes so "image_input" (OpenAI/OpenRouter suffix form)
 * and bare "image" (models.dev) both match.
 *
 * [T-android-modality-provider-fallback] Effective list, so an un-catalogued
 * Anthropic/OpenAI/OpenRouter/Google model is vision-capable by provider
 * default instead of silently failing the Vision Group filter.
 */
val LLMModel.hasImageInput: Boolean
    get() = effectiveInputModalities?.contains("image") == true

/** True when this model has ANY audio modality — the "voice model" predicate
 *  behind Voice Services shadow visibility (iOS hasVoiceModels). */
val LLMModel.hasVoiceModality: Boolean
    get() = hasAudioInput || hasAudioOutput

/**
 * [T-openrouter-voice-catalog] OpenMinis#280. Values of [LLMModel.voiceRole],
 * and the OpenRouter chat-audio allowlist. Mirrors iOS `VoiceRole`; the guard
 * tests re-read both sources so the values cannot drift.
 *
 * OpenRouter's default `GET /models` lists only chat models. Its 18 speech
 * and 22 transcription models are served ONLY by
 * `?output_modalities=speech` / `?output_modalities=transcription`, which
 * share no id with the default list (checked against the live API). Before,
 * the voice pickers were built from the default list's audio flags, so they
 * held only false positives — chat models that merely hear audio (Muse Spark
 * as "STT") or music generators that emit it (Lyria as "TTS") — and none of
 * the real voice models.
 */
object VoiceRole {
    const val TTS = "tts"
    const val STT = "stt"
    const val NONE = "none"

    /** Chat models on OpenRouter that genuinely do both directions, via
     *  chat/completions (audio-preview for TTS, `input_audio` for ASR). */
    val OPENROUTER_CHAT_AUDIO_MODELS = setOf("openai/gpt-audio", "openai/gpt-audio-mini")
}

/**
 * [T-openrouter-voice-catalog] Voice INPUT (ASR) candidate — the predicate
 * behind voice pickers and voice-group resolution, deliberately separate from
 * [hasAudioInput], which still answers "can this chat model hear audio"
 * for multimodal chat.
 *
 * A catalog-tagged model is judged by its tag alone: "stt", or a chat-audio
 * allowlist entry. An untagged model (custom entry, other provider, legacy
 * save) keeps the modality rule — that is the manual-entry escape hatch.
 */
val LLMModel.isVoiceInputCandidate: Boolean
    get() = when (voiceRole) {
        null -> hasAudioInput
        VoiceRole.STT -> true
        else -> id in VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS
    }

/** Voice OUTPUT (TTS) counterpart of [isVoiceInputCandidate]. */
val LLMModel.isVoiceOutputCandidate: Boolean
    get() = when (voiceRole) {
        null -> hasAudioOutput
        VoiceRole.TTS -> true
        else -> id in VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS
    }

/** Either direction — the Voice Services visibility predicate. */
val LLMModel.isVoiceCandidate: Boolean
    get() = isVoiceInputCandidate || isVoiceOutputCandidate

/**
 * [T-voice-seed-shape-exact] True when this model carries the exact single-flag
 * VOICE-TEMPLATE SEED shape (see VoiceModality doc). Template mockModels are
 * authored this way on purpose; API models always carry the paired text side.
 *
 * [T-android-modality-provider-fallback] Uses the RAW lists on purpose — the
 * whole discriminator is "exactly one audio flag and nothing on the other
 * side", and the provider-default fallback would replace both nulls with a
 * synthetic text entry, making this permanently false and stranding retired
 * seeds that launch cleanup is supposed to remove.
 */
val LLMModel.isVoiceTemplateSeedShape: Boolean
    get() {
        val ins = normalizedInputs
        val outs = normalizedOutputs
        val asrSeed = ins == listOf("audio") && outs == null
        val ttsSeed = ins == null && outs == listOf("audio")
        return asrSeed || ttsSeed
    }

/**
 * Apply dedicated-voice modality inference when the model has no explicit
 * modality info. Mirrors the dedicated-voice step of iOS
 * LLMModel.withInferredModality (models.dev enrichment stays where it is —
 * this only fills the exact ASR/TTS shape from id/name patterns).
 */
fun LLMModel.withInferredVoiceModality(): LLMModel {
    if (inputModalities != null || outputModalities != null) return this
    val (ins, outs) = VoiceModality.inferDedicatedVoiceModality(id, displayName) ?: return this
    return copy(inputModalities = ins, outputModalities = outs)
}

// ─── Merged from LLMModel.kt ──────────────────────────────────────────────────

@Serializable
data class LLMModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    // [OpenMinis#438] True when [contextWindow] / [maxOutputTokens] were read
    // from the provider's own /v1/models answer (a custom OpenAI-compatible
    // endpoint reporting context_length / max_output_tokens). Such values are
    // authoritative: models.dev only fills the one the endpoint left out
    // (see ModelsDevApi.applyDevData). Mirrors iOS
    // LLMModel.limitsReportedByEndpoint. Nullable so models saved before the
    // field existed decode as null - "not reported", the previous behaviour.
    val limitsReportedByEndpoint: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val interleavedReasoningField: String? = null,
    // [T-reasoning-effort-data-driven] Effort tiers this model accepts, from the
    // models.dev `reasoning_options` entry of type `effort` (e.g. ["high","max"]
    // for zhipuai glm-5.2). Mirrors iOS LLMModel.reasoningEffortValues.
    //
    // Presence (non-null, non-empty) means "controlled by reasoning_effort" and
    // replaces the old hardcoded deepseek/glm/kimi/minimax skip list; the
    // contents are the ALLOWED tiers, which the request builder clamps onto
    // (the catalog's sets vary: ["low","medium","high"], ["high","max"], …).
    val reasoningEffortValues: List<String>? = null,
    // [OpenMinis#163] The catalog affirmatively declares NO effort tiers for
    // this model — it reasons, but takes no `reasoning_effort` parameter.
    // Mirrors iOS LLMModel.declaresNoEffortTiers.
    //
    // Distinct from `reasoningEffortValues == null`, which also covers "the
    // catalog has never heard of this model". Only the affirmative case may
    // suppress the field; the unknown case stays permissive so third-party
    // relays keep working.
    //
    // Nullable (not a plain Boolean) so decoding a model persisted before this
    // field existed yields null — "unknown", the pre-existing behaviour —
    // rather than a synthesized `false` that would read as a real answer.
    val declaresNoEffortTiers: Boolean? = null,
    // Input/output modalities from models.dev (e.g. "text", "image", "audio", "video", "pdf").
    // Mirrors iOS ModelModality flags. When null, treat as text-in/text-out only.
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    // [T-openrouter-voice-catalog] OpenMinis#280. Which OpenRouter catalog
    // endpoint this model came from: "tts" (?output_modalities=speech), "stt"
    // (?output_modalities=transcription) or "none" (the default chat
    // catalog). null = not catalog-sourced (custom entries, other providers,
    // models saved before this field) — those keep the modality-based voice
    // rules. See VoiceRole. Nullable so older persisted models decode as null.
    // Mirrors iOS LLMModel.voiceRole.
    val voiceRole: String? = null,
    /**
     * [T-ultrafast-catalog-tiers] Service-tier ids the Codex model catalog
     * (/backend-api/codex/models `service_tiers[].id`, or the deprecated
     * `additional_speed_tiers`) declares for this model, e.g. ["priority"].
     * null = no catalog said anything (a static or models.dev model); an
     * empty list = the catalog declared none. Read by
     * SpeedTiers.supportsUltrafast: the official Codex client only sends a
     * tier the catalog declares.
     */
    val serviceTiers: List<String>? = null,
) {
    companion object {
        // Anthropic — mirrors iOS LLMTypes.swift allAnthropic.
        // [T-android-claude-opus48-thinking-toggle] (Sow Sow 38845/38850) Every
        // Claude 4.x model supports extended thinking, so hard-stamp
        // supportsReasoning = true (same as the OpenAI gpt-5.x catalog). Without
        // it the built-in fallback list — used when the /v1/models fetch fails
        // on a direct-Anthropic instance — lands with supportsReasoning=null and
        // ChatViewModel's `== true` gate hides the Deep Thinking toggle (the
        // reported Opus 4.8 bug). The dynamic /v1/models path stamps it the same
        // way via AnthropicProvider.supportsThinking.
        // [T-anthropic-fable5-catalog-android] Claude Fable 5 (2026-06-09,
        // first GA Mythos-class model; API id has no dated variant). Context
        // window / max output deliberately unset — 1M ctx is third-party
        // reported, not confirmed on Anthropic's model page; models.dev /
        // dynamic lookup fills them in once catalogued, same as the sibling
        // entries. 5-series adaptive thinking + no-temperature handling
        // comes from parseClaudeVersion (243dadf3). Claude Mythos 5 has no
        // public API id (Project Glasswing) and is intentionally absent.
        // [T-anthropic-context-window] Explicit context/output caps per
        // Anthropic's catalog (mirrors iOS): modern Opus/Sonnet 4.x & 5 and
        // Fable 5 are 1M context; Haiku 4.5 is 200K. Output: 128K (Opus/Fable),
        // 64K (Sonnet/Haiku). Set explicitly so the values don't depend on the
        // id heuristic; models.dev enrich can still override at runtime.
        // [T-anthropic-fable51-android] Fable 5.1 (2026-09-01). Same specs as
        // Fable 5 — 1M context, 128K output, 5-series adaptive thinking and
        // no-temperature handling, all derived from the id by
        // parseClaudeVersion (which reads "claude-fable-5-1" as major=5,
        // minor=1).
        //
        // Adaptive-thinking ONLY: deliberately no manual thinking budget is
        // modelled. Declaring a min/max budget makes the server read the model
        // as hybrid-thinking and reject the request upstream. LLMModel has no
        // budget fields today, so this holds by construction; the note is here
        // so a future refactor that adds them does not quietly extend them to
        // this model.
        //
        // Reaching it also needs the claude-cli User-Agent at >= 2.1.251 (the
        // pinned value is now 2.1.280, which Opus 5.5 requires) — see
        // AnthropicProvider's fingerprint block.
        val claudeFable51 = LLMModel("claude-fable-5-1", "Claude Fable 5.1", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeFable5 = LLMModel("claude-fable-5", "Claude Fable 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        // [T-anthropic-opus55-catalog] Claude Opus 5.5 — 5-series, so
        // parseClaudeVersion already routes it to adaptive thinking and away
        // from both `temperature` and the literal `thinking.type="disabled"`
        // (which this model 400s on). Requires claude-cli >= 2.1.280 in the
        // mimicry User-Agent; below that the backend refuses the model in a
        // way that reads like a catalog error rather than a version gate.
        val claudeOpus55 = LLMModel("claude-opus-5-5", "Claude Opus 5.5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus48 = LLMModel("claude-opus-4-8", "Claude Opus 4.8", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus46 = LLMModel("claude-opus-4-6", "Claude Opus 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        // [T-anthropic-sonnet55] Claude Sonnet 5.5 (CLIProxyAPI registry
        // 9a3b869f, iOS 965ff194d): 1M context, 128K output (not Sonnet 5's
        // 64K), text + image in via the Anthropic provider default, thinking
        // low…max. parseClaudeVersion reads (5,5), so it takes the same Claude-5
        // wire path as Sonnet 5: no `temperature`, adaptive thinking. "Thinking
        // off" is `thinking: {"type": "between_tools"}` — the only model that
        // takes it; `disabled` 400s here, and an absent field thinks at the
        // default high effort (AnthropicProvider.offThinking answers
        // BETWEEN_TOOLS, [T-claude5-thinking-off]). Served under the unchanged
        // claude-cli/2.1.280 fingerprint, the same one upstream uses for it.
        val claudeSonnet55 = LLMModel("claude-sonnet-5-5", "Claude Sonnet 5.5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        // [T-anthropic-sonnet5-catalog-android] Claude Sonnet 5 — same 5-series
        // adaptive-thinking + no-temperature handling (parseClaudeVersion) and
        // identical modalities/capabilities as the Sonnet 4.6 entry below.
        val claudeSonnet5 = LLMModel("claude-sonnet-5", "Claude Sonnet 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true)
        val claudeSonnet46 = LLMModel("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true)
        val claudeHaiku45 = LLMModel("claude-haiku-4-5", "Claude Haiku 4.5", "Anthropic", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true)

        // Newest first, matching iOS's ordering (LLMTypes.swift `allAnthropic`).
        val allAnthropic = listOf(claudeFable51, claudeFable5, claudeOpus55, claudeOpus48, claudeOpus46, claudeSonnet55, claudeSonnet5, claudeSonnet46, claudeHaiku45)

        // Gemini
        val gemini3Pro = LLMModel("gemini-3-pro-preview", "Gemini 3 Pro (Preview)", "Google")
        val gemini3Flash = LLMModel("gemini-3-flash-preview", "Gemini 3 Flash (Preview)", "Google")
        val gemini25Pro = LLMModel("gemini-2.5-pro", "Gemini 2.5 Pro", "Google")
        val gemini25Flash = LLMModel("gemini-2.5-flash", "Gemini 2.5 Flash", "Google")
        val gemini25FlashLite = LLMModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Google")

        val allGemini = listOf(gemini3Pro, gemini3Flash, gemini25Pro, gemini25Flash, gemini25FlashLite)

        // OpenAI — GPT-5.x and o-series ALWAYS support reasoning
        // (`reasoning_effort` field is required on Codex-OAuth and respected
        // by /v1/responses for these models). Without the explicit
        // `supportsReasoning = true` here the catalog falls back to `null`,
        // ChatViewModel.currentModelSupportsReasoning resolves to `false`,
        // and the Thinking pill in the composer is disabled — the user
        // can't pick high/medium/low even though the provider plumbing
        // honours it. Mirrors iOS LLMTypes.swift defaults plus the
        // OpenAIAgentProvider `supportsReasoning ?? true` GPT-5.x
        // assumption (T119).
        val gpt55 = LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true)
        val gpt53Codex = LLMModel("gpt-5.3-codex", "GPT-5.3 Codex", "OpenAI", supportsReasoning = true)
        val gpt52Codex = LLMModel("gpt-5.2-codex", "GPT-5.2 Codex", "OpenAI", supportsReasoning = true)
        val gpt51CodexMax = LLMModel("gpt-5.1-codex-max", "GPT-5.1 Codex Max", "OpenAI", supportsReasoning = true)
        val gpt52 = LLMModel("gpt-5.2", "GPT-5.2", "OpenAI", supportsReasoning = true)
        val gpt4o = LLMModel("gpt-4o", "GPT-4o", "OpenAI")
        val gpt4oMini = LLMModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI")
        val o3 = LLMModel("o3", "o3", "OpenAI", supportsReasoning = true)
        val o4Mini = LLMModel("o4-mini", "o4 Mini", "OpenAI", supportsReasoning = true)
        val codexMini = LLMModel("codex-mini-latest", "Codex Mini", "OpenAI", supportsReasoning = true)

        val allOpenAI = listOf(gpt55, gpt53Codex, gpt52Codex, gpt51CodexMax, gpt52, gpt4o, gpt4oMini, o3, o4Mini, codexMini)

        // OpenRouter (matching iOS built-in set)
        val orClaudeSonnet4 = LLMModel("anthropic/claude-sonnet-4", "Claude Sonnet 4", "OpenRouter")
        val orGemini25Flash = LLMModel("google/gemini-2.5-flash", "Gemini 2.5 Flash", "OpenRouter")
        val orGpt4o = LLMModel("openai/gpt-4o", "GPT-4o", "OpenRouter")
        val orLlamaMaverick = LLMModel("meta-llama/llama-4-maverick", "Llama 4 Maverick", "OpenRouter")

        val allOpenRouter = listOf(orClaudeSonnet4, orGemini25Flash, orGpt4o, orLlamaMaverick)

        // xAI (Grok) — OAuth-only path uses these as the built-in catalog.
        // Source of truth = XAIModelsAPI; this list is what surfaces in the
        // Add Provider → Models step before any models-cache call.
        //
        // Catalog ordering = default-pick order. grok-4.3 stays the
        // flagship on top. T-xai-models-refresh dropped grok-3-* slugs
        // (xAI server-side now redirects those to grok-4.3, so showing
        // them in the picker is just noise) and added the multi-agent
        // / build / fast / code-fast variants listed in the xAI docs
        // (docs.x.ai/docs/models; port iOS db973552).
        // Official xAI catalog (docs.x.ai/docs/models) - synced from CLIProxyAPI models.json
        // [T-provider-dynamic-catalog-reconcile] grok-4.6 added for GH#265.
        // Note this list is now a SEED/FALLBACK, not the whole story: since
        // that fix refreshModels does a real GET /v1/models against api.x.ai,
        // so a model released after this build still shows up. Keeping the
        // list current only improves the pre-network first paint.
        val grok46 = LLMModel("grok-4.6", "Grok 4.6", "xAI", supportsReasoning = true)
        val grok45 = LLMModel("grok-4.5", "Grok 4.5", "xAI", supportsReasoning = true)
        val grok43 = LLMModel("grok-4.3", "Grok 4.3", "xAI", supportsReasoning = true)
        val grok420Reasoning = LLMModel("grok-4.20-0309-reasoning", "Grok 4.20 Reasoning", "xAI", supportsReasoning = true)
        val grok420NonReasoning = LLMModel("grok-4.20-0309-non-reasoning", "Grok 4.20", "xAI")
        val grok420MultiAgent = LLMModel("grok-4.20-multi-agent-0309", "Grok 4.20 Multi-Agent", "xAI", supportsReasoning = true)
        val grokBuild01 = LLMModel("grok-build-0.1", "Grok Build 0.1", "xAI")
        val grok3Mini = LLMModel("grok-3-mini", "Grok 3 Mini", "xAI", supportsReasoning = true)
        val grok3MiniFast = LLMModel("grok-3-mini-fast", "Grok 3 Mini Fast", "xAI", supportsReasoning = true)
        val grokComposer25Fast = LLMModel("grok-composer-2.5-fast", "Grok Composer 2.5 Fast", "xAI")
        // High-frequency fast / code variants (docs.x.ai/docs/models).
        val grok4Fast = LLMModel("grok-4-fast", "Grok 4 Fast", "xAI", supportsReasoning = true)
        val grok4FastNonReasoning = LLMModel("grok-4-fast-non-reasoning", "Grok 4 Fast (Non-Reasoning)", "xAI")
        val grokCodeFast1 = LLMModel("grok-code-fast-1", "Grok Code Fast 1", "xAI", supportsReasoning = true)

        val allXAI = listOf(
            grok46,
            grok45,
            grok43,
            grok420Reasoning,
            grok420NonReasoning,
            grok420MultiAgent,
            grokBuild01,
            grok3Mini,
            grok3MiniFast,
            grokComposer25Fast,
            grok4Fast,
            grok4FastNonReasoning,
            grokCodeFast1,
        )

        // [T-kimi-oauth] Kimi Code (Coding Plan) built-in fallback — deliberately
        // minimal and non-speculative (iOS parity): only confirmed current-gen
        // models. kimi-k3 verified present as the first entry of a live
        // GET /coding/v1/models fetch (2026-07-23). The real catalog replaces
        // this via refreshModels after login — the upstream lineup shifts
        // (K2 → K3 → …), so we never hand-author a "complete" list.
        val kimiK3 = LLMModel("kimi-k3", "Kimi K3", "Kimi")
        val kimiK2 = LLMModel("kimi-k2", "Kimi K2", "Kimi")

        val allKimi = listOf(kimiK3, kimiK2)

        val allModels = allAnthropic + allGemini + allOpenAI + allOpenRouter + allXAI + allKimi

        /**
         * Heuristic display-name formatter for API model ids.
         * Mirrors iOS `modelDisplayName(from:)`: splits on `/` and `-`, preserves a
         * small set of uppercase acronyms, applies brand-name capitalization for
         * well-known vendors (OpenAI, DeepSeek, etc.), and title-cases the rest.
         */
        fun modelDisplayName(fromId: String): String {
            if (fromId.isBlank()) return fromId
            // [T-android-modeldisplayname-ios-parity] Kept byte-identical to
            // iOS `modelDisplayName(from:)` (LLMTypes.swift) — "xxl" was the
            // one entry Android was missing.
            val upperTokens = setOf(
                "gpt", "glm", "oss", "ai", "xl", "xxl", "vl", "llm", "moe", "api",
                "hd", "sd", "rp", "sft", "rl", "dpo", "gguf", "fp16", "bf16", "int4", "int8",
            )
            val brandRewrites = mapOf(
                "openai" to "OpenAI",
                "deepseek" to "DeepSeek",
                "chatgpt" to "ChatGPT",
                "llama" to "Llama",
                "gemma" to "Gemma",
                "phi" to "Phi",
                "mistral" to "Mistral",
                "mixtral" to "Mixtral",
                "qwen" to "Qwen",
                "yi" to "Yi",
            )
            // [T-android-modeldisplayname-ios-parity] Reflow BOTH separators
            // into spaces, exactly as iOS does.
            //
            // This previously split on '-' and joined back with '-', so the
            // function only re-cased tokens and left the machine-readable
            // separators in place. The same DeepSeek model therefore read
            // "DeepSeek-Flash" on Android and "DeepSeek Flash" on iOS — a
            // user-visible inconsistency reported from the field. DeepSeek's
            // official /v1/models sends no `name` field, so every one of its
            // models lands on this fallback and showed the hyphenated form.
            //
            // `filter { it.isNotEmpty() }` stands in for Swift's
            // `split(separator:)`, which drops empty subsequences — without it
            // a doubled separator ("org//model") would emit a blank token and
            // produce a double space.
            return fromId
                .replace('/', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { token ->
                    val lower = token.lowercase()
                    when {
                        brandRewrites.containsKey(lower) -> brandRewrites[lower]!!
                        upperTokens.contains(lower) -> lower.uppercase()
                        else -> token.replaceFirstChar { it.titlecase() }
                    }
                }
        }
    }

    /**
     * [T-newchat-default-model-fallback-android] True when this model can
     * produce a TEXT reply — the only kind a fresh chat should default to.
     * Per the field's documented convention (outputModalities null ⇒ "text
     * out only"), a null/empty list counts as text. A non-empty list must
     * contain "text" (normalized) to qualify — this excludes pure
     * image/audio/video generators (e.g. an image-only model whose
     * outputModalities is ["image"]). Mirrors iOS #636 isTextOutput.
     */
    val isTextOutput: Boolean
        get() {
            val out = outputModalities.normalizeModalities() ?: return true
            return "text" in out
        }

    /**
     * Effective context window in tokens. When `contextWindow` is set (from
     * models.dev enrichment or the built-in catalog) use it; otherwise fall
     * back to a model-id heuristic. Mirrors iOS LLMModel.contextWindowTokens
     * (T-anthropic-context-window): the old "Claude → 200K" default wrongly
     * capped Sonnet 4.6 / Sonnet 5 / Opus 4.x / Fable 5, whose real window is
     * 1M — only Haiku and the legacy 2.x/3.x line are 200K.
     */
    val contextWindowTokens: Int
        get() {
            contextWindow?.let { if (it > 0) return it }
            val lid = id.lowercase()
            // Anthropic Claude — modern Opus/Sonnet 4.x & 5 and Fable/Mythos 5
            // ship 1M; Haiku and legacy 2.x/3.x are 200K.
            if (lid.contains("claude")) {
                if (lid.contains("haiku")) return 200_000
                if (lid.contains("claude-2") || lid.contains("claude-3")) return 200_000
                return 1_000_000
            }
            // Google Gemini — modern Gemini advertises 1M+; only 1.0 was 32K.
            if (lid.contains("gemini")) {
                if (lid.contains("1.0")) return 32_000
                return 1_000_000
            }
            // OpenAI family
            if (lid.contains("gpt-3.5")) return 16_000
            if (lid.contains("gpt-4o") || lid.contains("gpt-4-turbo")) return 128_000
            if (lid.contains("gpt-5")) return 400_000
            if (lid.contains("gpt-4")) return 8_000
            if (lid.contains("o3") || lid.contains("o4")) return 200_000
            if (lid.contains("codex")) return 200_000
            if (lid.contains("deepseek")) return 128_000
            // xAI Grok. [T-android-grok-context-underestimate] Without this
            // branch a Grok id missing from the models.dev catalog fell through
            // to the 128K default, and ContextPolicy turned that into
            // compactThreshold = 128K - 20K = 108K — so a model with a 256K-2M
            // window auto-compacted every ~20-30 tool calls. iOS field report
            // 2026-08-13: `grok-4.6` (still absent from the bundled catalog,
            // verified) compacted 6 times in 47 minutes.
            //
            // Grok 2/3 are the only 131K generation; Grok 4 and later are 256K
            // at minimum and the fast / 4.20 lines advertise 2M. 256K is the
            // conservative floor for an unknown Grok 4+. A handful of
            // relay-hosted grok-4 entries do declare 128K-200K, but every one
            // of them IS in the catalog, so the explicit `contextWindow` check
            // above wins and this heuristic never runs for them — it only ever
            // sees ids models.dev has not shipped yet, which is the whole
            // failure mode. Port of iOS d63e9b9c9.
            if (lid.contains("grok")) {
                if (lid.contains("grok-2") || lid.contains("grok-3")) return 131_072
                return 256_000
            }
            // Default: assume a modern long-context model rather than 64K so the
            // group context-limit slider doesn't collapse to a single stop.
            return 128_000
        }

    /**
     * Capability hint appended to the system prompt so the model knows exactly
     * what it can natively consume vs what it must route through shell tools.
     * Returns `null` for fully-multimodal models (no hint needed). Matches the
     * iOS `capabilityPromptFragment` wording so Android/iOS chats are identical
     * when routed through the same model.
     */
    fun capabilityPromptFragment(): String? {
        // [T-android-vision-native-check-misses-image_input] normalizeModalities,
        // not a bare lowercase: OpenAI / OpenRouter spell these "image_input" /
        // "audio_input", models.dev spells them bare. Lowercasing alone left the
        // suffix intact, so a vision model from an OpenAI-shaped catalog was told
        // in its own system prompt that it could NOT see images.
        val inputs = inputModalities.normalizeModalities() ?: emptyList()
        val hasImage = "image" in inputs
        val hasPdf = "pdf" in inputs
        val hasAudio = "audio" in inputs
        val hasVideo = "video" in inputs
        if (hasImage && hasPdf && hasAudio && hasVideo) return null

        val natives = buildList {
            if (hasImage) add("images")
            if (hasPdf) add("PDFs")
            if (hasAudio) add("audio")
            if (hasVideo) add("video")
        }
        val missing = buildList {
            if (!hasImage) add("images")
            if (!hasPdf) add("PDFs")
            if (!hasAudio) add("audio")
            if (!hasVideo) add("video")
        }

        val sb = StringBuilder()
        if (natives.isNotEmpty()) {
            sb.append("You can natively process ").append(natives.joinToString(", ")).append(". ")
        }
        if (missing.isNotEmpty()) {
            sb.append("You cannot natively process ").append(missing.joinToString(", "))
            sb.append(" — for those formats, call shell_execute with ffmpeg or similar tools to extract text/metadata first.")
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * Per-model-family agent-loop behavior hint. Gemini needs a reminder to
     * actually invoke tools via function calling; OpenAI Codex family needs a
     * push toward autonomous persistence ("don't stop at analysis").
     */
    fun agentBehaviorPromptFragment(): String? {
        val idLower = id.lowercase()
        val providerLower = provider.lowercase()

        if (providerLower == "google" || idLower.contains("gemini")) {
            return "When you need to use a tool, invoke it via the function-calling mechanism directly. Do not emit tool invocations as plain text — they will not be executed."
        }

        val isCodex = idLower.contains("codex") ||
            Regex("gpt-5(?:\\.\\d+)?-codex").containsMatchIn(idLower)
        if (isCodex) {
            return "Act autonomously: don't stop at analysis, don't ask for permission on reversible local actions, and never announce \"I will use tool X\" without actually calling X. Keep iterating until the task is fully complete."
        }
        return null
    }
}

/**
 * Normalize a modality string to its bare form. Provider APIs vary —
 * OpenAI / OpenRouter return "image_input" / "text_output" with _input/_output
 * suffixes; models.dev returns bare "image" / "text". Internally we always
 * use the bare form ("image", "pdf", "audio", "video", "text") so toggles,
 * `"image" in modalities` checks, and capability fragments work uniformly.
 */
fun String.normalizeModalityName(): String =
    // [T-android-modality-normalize-case-order] Lowercase FIRST, then strip.
    // The reverse order silently failed on any non-lowercase spelling:
    // removeSuffix("_input") matches literally, so "IMAGE_INPUT" kept its
    // suffix and normalized to "image_input", which then compared unequal to
    // "image" everywhere — hasImageInput / hasAudioInput / hasAudioOutput and
    // the Vision Group member filter all read such a model as lacking the
    // modality it actually declares.
    lowercase().removeSuffix("_input").removeSuffix("_output")

fun List<String>?.normalizeModalities(): List<String>? =
    this?.map { it.normalizeModalityName() }?.distinct()?.takeIf { it.isNotEmpty() }

/**
 * [T-android-modality-provider-fallback] Provider-level default modalities,
 * used ONLY when a model declares none at all.
 *
 * Android derived modality purely from the two stored lists, which are filled
 * either by a hand-written catalog literal or by ModelsDevApi.enrichModel. A
 * model that models.dev has not catalogued yet — i.e. every model in its first
 * days, Fable 5.1 being the case that surfaced this — has both lists null, and
 * every `contains("image")` check then answered false. The model detail screen
 * showed image / PDF / audio / video input all switched OFF for a model that
 * plainly supports images.
 *
 * iOS never had the gap because `capabilities` is computed and falls back to a
 * per-provider table (LLMTypes.swift `knownCapabilities`). This is the Android
 * port of that table, values copied rather than re-derived:
 *
 *   Anthropic  .vision          → text, image, pdf
 *   OpenAI     .vision          → text, image, pdf
 *   OpenRouter .vision          → text, image, pdf
 *   Google     .fullMultimodal  → text, image, pdf, audio, video
 *   xAI, Kimi, GitHub Copilot  .vision  → text, image, pdf
 *     ([T-provider-default-modality-key], iOS 97577e81d; see below)
 *   (unknown)  .textOnly        → text
 *
 * Providers absent from iOS's table are absent here too, and fall through to
 * text-only. That is deliberate: DeepSeek and every third-party
 * OpenAI-compatible endpoint (vLLM, Ollama, LiteLLM…, labelled "Custom" by
 * OpenAIModelsApi) keep deriving modality from what their /v1/models actually
 * returned. Inventing a default for them is exactly the regression this table
 * must not cause — a relay that serves a text-only model would start
 * advertising image input.
 */
private val VISION_INPUT = listOf("text", "image", "pdf")
private val FULL_MULTIMODAL_INPUT = listOf("text", "image", "pdf", "audio", "video")

private val PROVIDER_DEFAULT_INPUT_MODALITIES: Map<String, List<String>> = mapOf(
    "Anthropic" to VISION_INPUT,
    "OpenAI" to VISION_INPUT,
    "OpenRouter" to VISION_INPUT,
    "Google" to FULL_MULTIMODAL_INPUT,
    // [T-android-image-input-preflight] `LLMModel.provider` carries
    // ProviderType.displayName, and the Gemini type's display name is
    // "Google Gemini" — the "Google" row above never matched a real model, so
    // un-catalogued Gemini models fell through with no default at all.
    "Google Gemini" to FULL_MULTIMODAL_INPUT,
    // [T-provider-default-modality-key] Port of iOS 97577e81d. iOS's enum
    // (`ProviderType.defaultModality`) already said .vision for xAI, Kimi Code
    // and GitHub Copilot, but its capability table had no rows for them, and a
    // miss there is silent: it falls through to text-only. Android had the same
    // gap. The shape is GH#265's: a Grok released after the last models.dev
    // snapshot (grok-4.6 on its first day) declares no modalities, so it
    // reported no image input — the model detail screen showed every input
    // switch off and the image-input preflight refused photos for it.
    //
    // Keyed under BOTH spellings a real model can carry, the lesson of the
    // Gemini row above: the catalog and the models APIs write "xAI" / "Kimi" /
    // "GitHub Copilot" (the same strings iOS keys on), while the provider import
    // path stamps ProviderType.displayName ("xAI (Grok)", "Kimi Code"). A model
    // that declares its own list is unaffected; this only fills a null.
    "xAI" to VISION_INPUT,
    "xAI (Grok)" to VISION_INPUT,
    "Kimi" to VISION_INPUT,
    "Kimi Code" to VISION_INPUT,
    "GitHub Copilot" to VISION_INPUT,
)

/** Output side of the same table. Every entry above is input-multimodal only
 *  (iOS's `.fullMultimodal` is aliased to `.fullMultimodalInput`), so text out
 *  is the correct default across the board. */
private val PROVIDER_DEFAULT_OUTPUT_MODALITIES: Map<String, List<String>> = mapOf(
    "Anthropic" to listOf("text"),
    "OpenAI" to listOf("text"),
    "OpenRouter" to listOf("text"),
    "Google" to listOf("text"),
    "Google Gemini" to listOf("text"),
    "xAI" to listOf("text"),
    "xAI (Grok)" to listOf("text"),
    "Kimi" to listOf("text"),
    "Kimi Code" to listOf("text"),
    "GitHub Copilot" to listOf("text"),
)

/**
 * [T-android-modality-provider-fallback] Input modalities to USE, with the
 * provider default applied only as a last resort.
 *
 * Precedence, lowest priority last:
 *   1. the model's own list (a catalog literal, or models.dev enrichment)
 *   2. the provider default table
 *
 * The user's per-model override sits ABOVE both and is applied by the caller
 * (ModelEntryDetailScreen / ModelOverrides), which never reaches this property
 * when an override exists.
 *
 * Read this — not the raw field — for capability questions like "can it take an
 * image". Do NOT read it for the voice SHAPE predicates: those match the stored
 * lists exactly and must keep seeing null as null (see VoiceModality).
 */
val LLMModel.effectiveInputModalities: List<String>?
    get() = inputModalities.normalizeModalities()
        ?: PROVIDER_DEFAULT_INPUT_MODALITIES[provider]

val LLMModel.effectiveOutputModalities: List<String>?
    get() = outputModalities.normalizeModalities()
        ?: PROVIDER_DEFAULT_OUTPUT_MODALITIES[provider]
