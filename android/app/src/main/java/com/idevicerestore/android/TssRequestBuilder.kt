package com.idevicerestore.android

import java.util.Locale
import java.util.UUID

/**
 * Builds the AP/Image4 TSS request using current libtatsu rules.
 * This is a pure builder: it performs no network request and sends nothing to USB.
 */
object TssRequestBuilder {
    data class Result(
        val request: PlistNode.Dict,
        val componentCount: Int,
        val identityIndex: Int
    ) {
        fun xml(): ByteArray = TssPlistSerializer.serialize(request)
        fun summary(): String =
            "TSS request: identity=$identityIndex components=$componentCount " +
                "ApImg4Ticket=true ApNonce=data SepNonce=${if (request.data("SepNonce") != null) "data" else "absent"}"
    }

    fun build(
        foundation: TssRequestFoundation.Parameters,
        identityResult: IpswBuildIdentityReader.Result
    ): Result {
        require(identityResult.identityIndex == foundation.identityIndex) {
            "BuildIdentity mismatch: foundation=${foundation.identityIndex} actual=${identityResult.identityIndex}"
        }

        val identity = identityResult.identity
        val manifest = identity.dict("Manifest")
            ?: error("Selected BuildIdentity has no Manifest dictionary")
        val parameters = buildParameters(foundation, identity, manifest)

        val request = PlistNode.Dict()
        request.values["@HostPlatformInfo"] = PlistNode.StringValue("mac")
        request.values["@VersionInfo"] = PlistNode.StringValue("libauthinstall-1104.0.9")
        request.values["@UUID"] = PlistNode.StringValue(UUID.randomUUID().toString().uppercase(Locale.US))

        addCommonTags(request, parameters)
        val componentCount = addApTags(request, parameters, manifest)
        addApImg4Tags(request, parameters)
        return Result(request, componentCount, identityResult.identityIndex)
    }

    private fun buildParameters(
        foundation: TssRequestFoundation.Parameters,
        identity: PlistNode.Dict,
        manifest: PlistNode.Dict
    ): PlistNode.Dict {
        val parameters = PlistNode.Dict()
        copyIfPresent(parameters, identity, "UniqueBuildID")
        IDENTITY_STRINGS.forEach { copyIfPresent(parameters, identity, it) }
        parameters.values["ApECID"] = PlistNode.UnsignedIntegerValue(foundation.ecid)
        parameters.values["ApChipID"] = PlistNode.IntegerValue(foundation.apChipId)
        parameters.values["ApBoardID"] = PlistNode.IntegerValue(foundation.apBoardId)
        parameters.values["ApSecurityDomain"] = PlistNode.IntegerValue(foundation.apSecurityDomain)
        parameters.values["ApProductionMode"] = PlistNode.BoolValue(foundation.apProductionMode)
        parameters.values["ApSecurityMode"] = PlistNode.BoolValue(foundation.apSecurityMode)
        parameters.values["ApSupportsImg4"] = PlistNode.BoolValue(foundation.apSupportsImg4)
        parameters.values["ApInRomDFU"] = PlistNode.BoolValue(true)
        parameters.values["ApNonce"] = PlistNode.DataValue(foundation.apNonce.copyOf())
        foundation.apSepNonce?.let { parameters.values["ApSepNonce"] = PlistNode.DataValue(it.copyOf()) }
        parameters.values["Manifest"] = manifest.deepCopy()
        identity.dict("Info")?.bool("RequiresUIDMode")?.let {
            parameters.values["RequiresUIDMode"] = PlistNode.BoolValue(it)
        }
        return parameters
    }

    private fun addCommonTags(request: PlistNode.Dict, parameters: PlistNode.Dict) {
        listOf("ApECID", "UniqueBuildID", "ApChipID", "ApBoardID", "ApSecurityDomain")
            .forEach { copyIfPresent(request, parameters, it) }
    }

    private fun addApImg4Tags(request: PlistNode.Dict, parameters: PlistNode.Dict) {
        IDENTITY_STRINGS.forEach { copyIfPresent(request, parameters, it) }
        copyRequired(request, parameters, "ApNonce")
        request.values["@ApImg4Ticket"] = PlistNode.BoolValue(true)
        copyRequired(request, parameters, "ApSecurityMode")
        copyRequired(request, parameters, "ApProductionMode")
        val sep = parameters["SepNonce"] ?: parameters["ApSepNonce"]
        if (sep != null) request.values["SepNonce"] = sep.deepCopy()
        if (parameters.bool("RequiresUIDMode") == true) {
            request.values["UID_MODE"] = PlistNode.BoolValue(false)
            request.values["Ap,SikaFuse"] = PlistNode.IntegerValue(0)
        }
    }

    private fun addApTags(
        request: PlistNode.Dict,
        parameters: PlistNode.Dict,
        manifest: PlistNode.Dict
    ): Int {
        var added = 0
        manifest.values.forEach { (name, rawNode) ->
            if (name in AP_SKIP_COMPONENTS) return@forEach
            val entry = rawNode as? PlistNode.Dict ?: return@forEach
            val info = entry.dict("Info") ?: return@forEach
            val trusted = entry.bool("Trusted") == true
            val rules = info.array("RestoreRequestRules")
            if (rules == null && !trusted) return@forEach
            if (info.bool("IsFTAB") == true) return@forEach

            val tssEntry = entry.copy()
            tssEntry.values.remove("Info")
            if (rules != null) {
                applyRestoreRequestRules(tssEntry, parameters, rules)
            } else {
                tssEntry.values["EPRO"] = PlistNode.BoolValue(parameters.bool("ApProductionMode") == true)
                tssEntry.values["ESEC"] = PlistNode.BoolValue(parameters.bool("ApSecurityMode") == true)
            }
            if (trusted && "Digest" !in tssEntry.values) {
                tssEntry.values["Digest"] = PlistNode.DataValue(ByteArray(0))
            }
            if (tssEntry.values.isNotEmpty()) {
                request.values[name] = tssEntry
                added++
            }
        }
        return added
    }

    private fun applyRestoreRequestRules(
        tssEntry: PlistNode.Dict,
        parameters: PlistNode.Dict,
        rules: PlistNode.ArrayValue
    ) {
        rules.values.forEach ruleLoop@ { ruleNode ->
            val rule = ruleNode as? PlistNode.Dict ?: return@ruleLoop
            val conditions = rule.dict("Conditions") ?: return@ruleLoop
            val fulfilled = conditions.values.all { (conditionName, expected) ->
                val parameterName = when (conditionName) {
                    "ApRawProductionMode", "ApCurrentProductionMode" -> "ApProductionMode"
                    "ApRawSecurityMode" -> "ApSecurityMode"
                    "ApRequiresImage4" -> "ApSupportsImg4"
                    "ApDemotionPolicyOverride" -> "DemotionPolicy"
                    "ApInRomDFU" -> "ApInRomDFU"
                    else -> return@all false
                }
                valuesEqual(expected, parameters[parameterName])
            }
            if (!fulfilled) return@ruleLoop
            val actions = rule.dict("Actions") ?: return@ruleLoop
            actions.values.forEach actionLoop@ { (key, value) ->
                val bool = value as? PlistNode.BoolValue ?: return@actionLoop
                tssEntry.values[key] = bool.copy()
            }
        }
    }

    private fun valuesEqual(left: PlistNode, right: PlistNode?): Boolean {
        if (right == null) return false
        return when {
            left is PlistNode.BoolValue && right is PlistNode.BoolValue -> left.value == right.value
            left is PlistNode.IntegerValue && right is PlistNode.IntegerValue -> left.value == right.value
            left is PlistNode.UnsignedIntegerValue && right is PlistNode.UnsignedIntegerValue -> left.value == right.value
            left is PlistNode.StringValue && right is PlistNode.StringValue -> left.value == right.value
            left is PlistNode.DataValue && right is PlistNode.DataValue -> left.value.contentEquals(right.value)
            else -> false
        }
    }

    private fun copyRequired(target: PlistNode.Dict, source: PlistNode.Dict, key: String) {
        val value = source[key] ?: error("Required TSS parameter missing: $key")
        target.values[key] = value.deepCopy()
    }

    private fun copyIfPresent(target: PlistNode.Dict, source: PlistNode.Dict, key: String) {
        source[key]?.let { target.values[key] = it.deepCopy() }
    }

    private val IDENTITY_STRINGS = listOf(
        "Ap,OSLongVersion", "Ap,OSReleaseType", "Ap,ProductMarketingVersion",
        "Ap,ProductType", "Ap,SDKPlatform", "Ap,Target", "Ap,TargetType", "Ap,Timestamp"
    )

    private val AP_SKIP_COMPONENTS = setOf(
        "BasebandFirmware", "SE,UpdatePayload", "BaseSystem", "Diags", "Ap,ExclaveOS"
    )
}
