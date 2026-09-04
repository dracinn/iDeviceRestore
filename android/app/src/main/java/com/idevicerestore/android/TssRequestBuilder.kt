package com.idevicerestore.android

import java.util.Locale
import java.util.UUID

/** Builds the AP IMG4 TSS request using current libtatsu semantics. */
object TssRequestBuilder {
    data class BuildResult(
        val request: PlistValue.Dict,
        val componentCount: Int,
        val identityIndex: Int
    ) {
        fun xml(): ByteArray = XmlPlistCodec.toXml(request)
    }

    fun build(
        foundation: TssRequestFoundation.Parameters,
        identity: PlistValue.Dict
    ): BuildResult {
        val parameters = identity.copyDeep()
        parameters.values["ApECID"] = PlistValue.IntegerValue(foundation.ecid.toLong())
        parameters.values["ApChipID"] = PlistValue.IntegerValue(foundation.apChipId)
        parameters.values["ApBoardID"] = PlistValue.IntegerValue(foundation.apBoardId)
        parameters.values["ApSecurityDomain"] = PlistValue.IntegerValue(foundation.apSecurityDomain)
        parameters.values["ApProductionMode"] = PlistValue.BoolValue(foundation.apProductionMode)
        parameters.values["ApSecurityMode"] = PlistValue.BoolValue(foundation.apSecurityMode)
        parameters.values["ApSupportsImg4"] = PlistValue.BoolValue(foundation.apSupportsImg4)
        parameters.values["ApNonce"] = PlistValue.DataValue(foundation.apNonce.copyOf())
        foundation.apSepNonce?.let { parameters.values["ApSepNonce"] = PlistValue.DataValue(it.copyOf()) }

        identity.dict("Info")?.bool("RequiresUIDMode")?.let {
            parameters.values["RequiresUIDMode"] = PlistValue.BoolValue(it)
        }

        val request = PlistValue.Dict(linkedMapOf(
            "@HostPlatformInfo" to PlistValue.StringValue("mac"),
            "@VersionInfo" to PlistValue.StringValue("libauthinstall-1104.0.9"),
            "@UUID" to PlistValue.StringValue(UUID.randomUUID().toString().uppercase(Locale.US))
        ))

        copyIfPresent(request, parameters, "ApECID")
        copyIfPresent(request, parameters, "UniqueBuildID")
        copyIfPresent(request, parameters, "ApChipID")
        copyIfPresent(request, parameters, "ApBoardID")
        copyIfPresent(request, parameters, "ApSecurityDomain")

        IMG4_STRING_KEYS.forEach { copyIfPresent(request, parameters, it) }
        copyRequired(request, parameters, "ApNonce")
        request.values["@ApImg4Ticket"] = PlistValue.BoolValue(true)
        copyRequired(request, parameters, "ApSecurityMode")
        copyRequired(request, parameters, "ApProductionMode")
        copyIfPresent(request, parameters, "SepNonce")
        if (request["SepNonce"] == null) {
            parameters["ApSepNonce"]?.let { request.values["SepNonce"] = it.copyDeepValue() }
        }
        OPTIONAL_IMG4_KEYS.forEach { copyIfPresent(request, parameters, it) }
        if (parameters["UID_MODE"] != null) {
            copyIfPresent(request, parameters, "UID_MODE")
        } else if (parameters.bool("RequiresUIDMode") == true) {
            request.values["UID_MODE"] = PlistValue.BoolValue(false)
        }
        if (parameters["ApSikaFuse"] != null) {
            request.values["Ap,SikaFuse"] = parameters["ApSikaFuse"]!!.copyDeepValue()
        } else if (parameters.bool("RequiresUIDMode") == true) {
            request.values["Ap,SikaFuse"] = PlistValue.IntegerValue(0)
        }

        val manifest = identity.dict("Manifest") ?: error("Selected BuildIdentity has no Manifest dictionary")
        var added = 0
        manifest.values.forEach { (name, rawEntry) ->
            val manifestEntry = rawEntry as? PlistValue.Dict
                ?: error("BuildManifest entry $name is not a dictionary")
            if (name in SKIPPED_COMPONENTS) return@forEach
            val info = manifestEntry.dict("Info") ?: return@forEach
            val trusted = manifestEntry.bool("Trusted") == true
            val rules = info.array("RestoreRequestRules")
            if (foundation.apSupportsImg4 && rules == null && !trusted) return@forEach
            if (info.bool("IsFTAB") == true) return@forEach

            val tssEntry = manifestEntry.copyDeep()
            tssEntry.values.remove("Info")
            if (rules != null) {
                applyRestoreRequestRules(tssEntry, parameters, rules)
            } else if (foundation.apSupportsImg4) {
                tssEntry.values["EPRO"] = PlistValue.BoolValue(foundation.apProductionMode)
                tssEntry.values["ESEC"] = PlistValue.BoolValue(foundation.apSecurityMode)
            }
            if (trusted && tssEntry["Digest"] == null) {
                tssEntry.values["Digest"] = PlistValue.DataValue(ByteArray(0))
            }
            if (tssEntry.values.isNotEmpty()) {
                request.values[name] = tssEntry
                added++
            }
        }

        return BuildResult(request, added, foundation.identityIndex)
    }

    private fun applyRestoreRequestRules(
        entry: PlistValue.Dict,
        parameters: PlistValue.Dict,
        rules: PlistValue.ArrayValue
    ) {
        rules.values.forEach { rawRule ->
            val rule = rawRule as? PlistValue.Dict ?: return@forEach
            val conditions = rule.dict("Conditions") ?: return@forEach
            val matched = conditions.values.all { (key, expected) ->
                val actual = when (key) {
                    "ApRawProductionMode", "ApCurrentProductionMode" -> parameters["ApProductionMode"]
                    "ApRawSecurityMode" -> parameters["ApSecurityMode"]
                    "ApRequiresImage4" -> parameters["ApSupportsImg4"]
                    "ApDemotionPolicyOverride" -> parameters["DemotionPolicy"]
                    "ApInRomDFU" -> parameters["ApInRomDFU"]
                    else -> null
                }
                actual != null && plistEquals(expected, actual)
            }
            if (!matched) return@forEach
            rule.dict("Actions")?.values?.forEach { (key, action) ->
                val bool = (action as? PlistValue.BoolValue)?.value ?: return@forEach
                entry.values[key] = PlistValue.BoolValue(bool)
            }
        }
    }

    private fun plistEquals(a: PlistValue, b: PlistValue): Boolean = when {
        a is PlistValue.BoolValue && b is PlistValue.BoolValue -> a.value == b.value
        a is PlistValue.IntegerValue && b is PlistValue.IntegerValue -> a.value == b.value
        a is PlistValue.StringValue && b is PlistValue.StringValue -> a.value == b.value
        a is PlistValue.DataValue && b is PlistValue.DataValue -> a.value.contentEquals(b.value)
        else -> a == b
    }

    private fun copyRequired(target: PlistValue.Dict, source: PlistValue.Dict, key: String) {
        target.values[key] = source[key]?.copyDeepValue() ?: error("Required TSS parameter $key is missing")
    }

    private fun copyIfPresent(target: PlistValue.Dict, source: PlistValue.Dict, key: String) {
        source[key]?.let { target.values[key] = it.copyDeepValue() }
    }

    private val IMG4_STRING_KEYS = listOf(
        "Ap,OSLongVersion",
        "Ap,OSReleaseType",
        "Ap,ProductMarketingVersion",
        "Ap,ProductType",
        "Ap,SDKPlatform",
        "Ap,Target",
        "Ap,TargetType",
        "Ap,Timestamp"
    )

    private val OPTIONAL_IMG4_KEYS = listOf(
        "NeRDEpoch",
        "PearlCertificationRootPub",
        "AllowNeRDBoot",
        "PermitNeRDPivot"
    )

    private val SKIPPED_COMPONENTS = setOf(
        "BasebandFirmware",
        "SE,UpdatePayload",
        "BaseSystem",
        "Diags",
        "Ap,ExclaveOS"
    )
}
