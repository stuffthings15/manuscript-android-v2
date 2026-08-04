package com.echolink.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ContractManifest(
    val version: String,
    val operations: Set<String>,
    val clientTargets: Set<String>,
)

data class ContractPrivacy(
    val loopbackModelAllowed: Boolean,
    val textRetention: String,
)

data class ContractRequest(
    val requestId: String,
    val operation: String,
    val privacy: ContractPrivacy,
    val input: JsonObject,
)

object SharedContractV1 {
    const val VERSION = "1.0.0"
    private val json = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
        ignoreUnknownKeys = false
    }
    private val operations = linkedSetOf(
        "capabilities",
        "dictation_cleanup",
        "developer_format",
        "protected_tokens",
        "writing_command",
    )
    private val privacyKeys = setOf(
        "local_only",
        "external_network_allowed",
        "loopback_model_allowed",
        "retain_audio",
        "text_retention",
        "telemetry_allowed",
    )

    fun parseManifest(raw: String): ContractManifest {
        val root = parseObject(raw)
        requireString(root, "contract_name", 1, 100)
        val version = requireString(root, "contract_version", 1, 20)
        require(version == VERSION) { "Unsupported contract version." }
        require(requireString(root, "wire_format", 1, 40) == "UTF-8 JSON") {
            "Unsupported wire format."
        }
        val manifestOperations = requireStringSet(root, "operations", 1, 16, 80)
        require(manifestOperations == operations) { "Contract operations do not match v1." }
        val targets = requireStringSet(root, "client_targets", 1, 8, 32)
        require("android" in targets) { "The contract does not declare Android support." }
        return ContractManifest(version, manifestOperations, targets)
    }

    fun parseRequest(raw: String): ContractRequest {
        val root = parseObject(raw)
        requireExactKeys(root, setOf("contract_version", "request_id", "operation", "privacy", "input"))
        require(requireString(root, "contract_version", 1, 20) == VERSION) {
            "Unsupported contract version."
        }
        val requestId = requireString(root, "request_id", 1, 128)
        val operation = requireString(root, "operation", 1, 80)
        require(operation in operations) { "Unsupported operation." }
        val privacy = parsePrivacy(requireObject(root, "privacy"))
        val input = requireObject(root, "input")
        validateInput(operation, input)
        return ContractRequest(requestId, operation, privacy, input)
    }

    fun capabilityRequest(
        requestId: String,
        clientVersion: String,
        requestedOperations: Set<String> = operations,
    ): String {
        require(requestId.isNotEmpty() && requestId.length <= 128)
        require(clientVersion.isNotEmpty() && clientVersion.length <= 64)
        require(requestedOperations.isNotEmpty() && requestedOperations.all { it in operations })
        val payload = buildJsonObject {
            put("contract_version", VERSION)
            put("request_id", requestId)
            put("operation", "capabilities")
            put("privacy", strictPrivacyJson())
            put("input", buildJsonObject {
                put("client_platform", "android")
                put("client_version", clientVersion)
                put("supported_contract_versions", buildJsonArray { add(JsonPrimitive(VERSION)) })
                put("requested_operations", buildJsonArray {
                    requestedOperations.forEach { add(JsonPrimitive(it)) }
                })
            })
        }
        return payload.toString()
    }

    private fun parseObject(raw: String): JsonObject =
        json.parseToJsonElement(raw).jsonObject

    private fun parsePrivacy(value: JsonObject): ContractPrivacy {
        requireExactKeys(value, privacyKeys)
        require(requireBoolean(value, "local_only")) { "local_only must be true." }
        require(!requireBoolean(value, "external_network_allowed")) {
            "external_network_allowed must be false."
        }
        val loopback = requireBoolean(value, "loopback_model_allowed")
        require(!requireBoolean(value, "retain_audio")) { "retain_audio must be false." }
        val retention = requireString(value, "text_retention", 1, 32)
        require(retention == "none" || retention == "explicit_opt_in") {
            "Unsupported text retention."
        }
        require(!requireBoolean(value, "telemetry_allowed")) {
            "telemetry_allowed must be false."
        }
        return ContractPrivacy(loopback, retention)
    }

    private fun validateInput(operation: String, input: JsonObject) {
        when (operation) {
            "capabilities" -> {
                requireExactKeys(input, setOf(
                    "client_platform",
                    "client_version",
                    "supported_contract_versions",
                    "requested_operations",
                ))
                require(requireString(input, "client_platform", 1, 32) in setOf("windows", "swift", "android"))
                requireString(input, "client_version", 1, 64)
                requireStringList(input, "supported_contract_versions", 1, 256, 500)
                val requested = requireStringList(input, "requested_operations", 0, 5, 80)
                require(requested.toSet().size == requested.size && requested.all { it in operations })
            }
            "dictation_cleanup", "developer_format" -> {
                requireExactKeys(input, setOf("text", "vocabulary", "append_trailing_space"))
                requireString(input, "text", 1, 200_000)
                requireTextMap(input, "vocabulary")
                requireBoolean(input, "append_trailing_space")
            }
            "protected_tokens" -> {
                requireExactKeys(input, setOf("text", "protected_terms"))
                requireString(input, "text", 1, 200_000)
                requireUniqueStringList(input, "protected_terms", 0, 256, 500)
            }
            "writing_command" -> {
                requireExactKeys(input, setOf(
                    "text",
                    "command",
                    "profile",
                    "instruction",
                    "language",
                    "model",
                    "protected_terms",
                ))
                requireString(input, "text", 1, 200_000)
                require(requireString(input, "command", 1, 32) in setOf(
                    "Proofread", "Improve", "Rephrase", "Rewrite", "Shorten", "Expand",
                    "Summarize", "Formal", "Friendly", "Professional", "Diplomatic",
                    "Empathetic", "Cooperative", "Assertive", "Translate",
                ))
                require(requireString(input, "profile", 1, 32) in setOf(
                    "General", "Email", "Meeting", "Marketing", "Technical", "Code-safe",
                ))
                requireString(input, "instruction", 0, 2_000)
                requireString(input, "language", 0, 80)
                requireString(input, "model", 1, 128)
                requireUniqueStringList(input, "protected_terms", 0, 256, 500)
            }
        }
    }

    private fun strictPrivacyJson(): JsonObject = buildJsonObject {
        put("local_only", true)
        put("external_network_allowed", false)
        put("loopback_model_allowed", false)
        put("retain_audio", false)
        put("text_retention", "none")
        put("telemetry_allowed", false)
    }

    private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
        require(value.keys == expected) { "Unexpected or missing fields." }
    }

    private fun requireObject(value: JsonObject, key: String): JsonObject =
        value[key]?.jsonObject ?: throw IllegalArgumentException("$key must be an object.")

    private fun requireBoolean(value: JsonObject, key: String): Boolean =
        value[key]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("$key must be a Boolean.")

    private fun requireString(value: JsonObject, key: String, minimum: Int, maximum: Int): String {
        val element = value[key] ?: throw IllegalArgumentException("Missing $key.")
        require(element is JsonPrimitive && element.isString) { "$key must be a string." }
        val text = element.contentOrNull ?: throw IllegalArgumentException("$key must be a string.")
        require(text.length in minimum..maximum) { "$key has an invalid length." }
        return text
    }

    private fun requireStringList(
        value: JsonObject,
        key: String,
        minimum: Int,
        maximum: Int,
        itemMaximum: Int,
    ): List<String> {
        val array = value[key] as? JsonArray
            ?: throw IllegalArgumentException("$key must be an array.")
        require(array.size in minimum..maximum) { "$key has an invalid size." }
        return array.map { item ->
            require(item is JsonPrimitive && item.isString) { "$key must contain strings." }
            item.content.also { require(it.isNotEmpty() && it.length <= itemMaximum) }
        }
    }

    private fun requireUniqueStringList(
        value: JsonObject,
        key: String,
        minimum: Int,
        maximum: Int,
        itemMaximum: Int,
    ): List<String> = requireStringList(value, key, minimum, maximum, itemMaximum).also {
        require(it.toSet().size == it.size) { "$key must contain unique values." }
    }

    private fun requireStringSet(
        value: JsonObject,
        key: String,
        minimum: Int,
        maximum: Int,
        itemMaximum: Int,
    ): Set<String> = requireUniqueStringList(value, key, minimum, maximum, itemMaximum).toSet()

    private fun requireTextMap(value: JsonObject, key: String) {
        val map = requireObject(value, key)
        require(map.size <= 1_000) { "$key is too large." }
        for ((mapKey, mapValue) in map) {
            require(mapKey.isNotEmpty() && mapKey.length <= 200)
            require(mapValue is JsonPrimitive && mapValue.isString)
            require(mapValue.content.isNotEmpty() && mapValue.content.length <= 500)
        }
    }
}
