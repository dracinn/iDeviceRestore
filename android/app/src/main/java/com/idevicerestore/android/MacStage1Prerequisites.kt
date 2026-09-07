package com.idevicerestore.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Prepares the macOS-only inputs that upstream idevicerestore sends while iBoot is at Stage 1.
 *
 * This helper performs local IPSW extraction/personalization and the Apple TSS request for the
 * special empty Ap,LocalPolicy image. It performs no USB I/O. The caller remains responsible for
 * the bounded upload/command sequence and for stopping before restore-mode components.
 */
object MacStage1Prerequisites {
    data class PreparedComponent(
        val name: String,
        val file: File,
        val command: String
    )

    data class Prepared(
        val localPolicy: PreparedComponent,
        val stage1Firmware: List<PreparedComponent>
    )

    fun prepare(
        firmware: FirmwarePreparationStore.Context,
        ticket: TssTicketStore.Ticket,
        logger: (String) -> Unit = {}
    ): Prepared {
        require(firmware.matches(ticket.buildId, ticket.identityIndex)) {
            "Firmware preparation context does not match TSS ticket"
        }
        val ipsw = firmware.location.file
        require(ipsw.isFile) { "Selected IPSW is unavailable: ${ipsw.absolutePath}" }

        val identity = IpswBuildIdentityReader(logger).read(ipsw, ticket.identityIndex)
        val manifest = identity.identity.dict("Manifest")
            ?: error("Selected BuildIdentity has no Manifest dictionary")

        val buildRoot = ipsw.parentFile ?: error("IPSW build directory is unavailable")
        val rawDir = File(buildRoot, "Components/Stage1Prerequisites")
        val personalizedDir = File(buildRoot, "Personalized/Stage1Prerequisites")
        check(rawDir.isDirectory || rawDir.mkdirs()) { "Could not create Stage-1 component workspace" }
        check(personalizedDir.isDirectory || personalizedDir.mkdirs()) { "Could not create Stage-1 personalization workspace" }

        val localPolicyTicket = requestLocalPolicyTicket(ticket, logger)
        val localPolicyRaw = File(rawDir, "identity-${ticket.identityIndex}-Ap_LocalPolicy.raw")
        localPolicyRaw.writeBytes(LOCAL_POLICY_IM4P)
        val localPolicyFile = stitchSimpleImg4(
            rawFile = localPolicyRaw,
            ticket = localPolicyTicket,
            destination = File(personalizedDir, "identity-${ticket.identityIndex}-Ap_LocalPolicy.personalized.img4"),
            label = "Ap,LocalPolicy"
        )
        logger(
            "macOS Stage-1 preparation: Ap,LocalPolicy READY raw=${LOCAL_POLICY_IM4P.size} " +
                "ticket=${localPolicyTicket.size} personalized=${localPolicyFile.length()}"
        )

        val stage1Names = manifest.values.mapNotNull { (name, node) ->
            val entry = node as? PlistNode.Dict ?: return@mapNotNull null
            val info = entry.dict("Info") ?: return@mapNotNull null
            if (info.bool("IsLoadedByiBootStage1") == true && name !in EXPLICITLY_HANDLED_COMPONENTS) name else null
        }

        logger(
            "macOS Stage-1 preparation: manifest IsLoadedByiBootStage1=" +
                stage1Names.joinToString(",").ifBlank { "none" }
        )

        val extractor = IpswComponentExtractor(logger)
        val preparedFirmware = stage1Names.map { name ->
            val raw = extractor.extract(ipsw, firmware.preflight, name, rawDir)
            Image4StructureValidator.validateRawIm4p(raw.file, name)
            val tbm = ticket.componentTbm[name]
            require(tbm == null) {
                "$name requires component-specific TBM/IM4R; Stage-1 bounded test refuses an incomplete personalization"
            }
            val file = stitchSimpleImg4(
                rawFile = raw.file,
                ticket = ticket.apImg4Ticket,
                destination = File(personalizedDir, "identity-${ticket.identityIndex}-${safeName(name)}.personalized.img4"),
                label = name
            )
            logger(
                "macOS Stage-1 preparation: $name READY raw=${raw.bytes} " +
                    "ticket=${ticket.apImg4Ticket.size} personalized=${file.length()} command=firmware"
            )
            PreparedComponent(name, file, "firmware")
        }

        return Prepared(
            localPolicy = PreparedComponent("Ap,LocalPolicy", localPolicyFile, "lpolrestore"),
            stage1Firmware = preparedFirmware
        )
    }

    private fun requestLocalPolicyTicket(
        ticket: TssTicketStore.Ticket,
        logger: (String) -> Unit
    ): ByteArray {
        val f = ticket.foundation
        val request = PlistNode.Dict()
        request.values["@HostPlatformInfo"] = PlistNode.StringValue("mac")
        request.values["@VersionInfo"] = PlistNode.StringValue("libauthinstall-1104.0.9")
        request.values["@UUID"] = PlistNode.StringValue(UUID.randomUUID().toString().uppercase(Locale.US))
        request.values["@ApImg4Ticket"] = PlistNode.BoolValue(true)
        request.values["Ap,LocalBoot"] = PlistNode.BoolValue(false)
        request.values["ApECID"] = PlistNode.UnsignedIntegerValue(f.ecid)
        request.values["ApChipID"] = PlistNode.IntegerValue(f.apChipId)
        request.values["ApBoardID"] = PlistNode.IntegerValue(f.apBoardId)
        request.values["ApSecurityDomain"] = PlistNode.IntegerValue(f.apSecurityDomain)
        request.values["ApNonce"] = PlistNode.DataValue(f.apNonce.copyOf())
        request.values["ApSecurityMode"] = PlistNode.BoolValue(f.apSecurityMode)
        request.values["ApProductionMode"] = PlistNode.BoolValue(f.apProductionMode)

        val policy = PlistNode.Dict()
        policy.values["Digest"] = PlistNode.DataValue(sha384(LOCAL_POLICY_IM4P))
        policy.values["Trusted"] = PlistNode.BoolValue(true)
        request.values["Ap,LocalPolicy"] = policy
        request.values["Ap,NextStageIM4MHash"] = PlistNode.DataValue(sha384(ticket.apImg4Ticket))

        val wrapped = TssRequestBuilder.Result(
            request = request,
            componentCount = 1,
            identityIndex = ticket.identityIndex
        )
        logger(
            "macOS Stage-1 LocalPolicy TSS: requesting special policy ticket identity=${ticket.identityIndex} " +
                "policyDigest=sha384 nextStageIM4MHash=sha384"
        )
        val response = TssHttpTransport(logger).send(wrapped)
        require(response.status == 0) {
            "Apple TSS rejected LocalPolicy request: status=${response.status} message=${response.message}"
        }
        val policyTicket = response.apImg4Ticket
            ?: error("Apple TSS LocalPolicy response has no ApImg4Ticket")
        require(policyTicket.isNotEmpty()) { "Apple TSS LocalPolicy ticket is empty" }
        logger("macOS Stage-1 LocalPolicy TSS: ApImg4Ticket received (${policyTicket.size} bytes)")
        return policyTicket
    }

    private fun stitchSimpleImg4(
        rawFile: File,
        ticket: ByteArray,
        destination: File,
        label: String
    ): File {
        require(rawFile.isFile && rawFile.length() > 0L) { "$label raw IM4P is missing or empty" }
        require(ticket.isNotEmpty()) { "$label ticket is empty" }
        check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true) {
            "Could not create $label personalization directory"
        }

        val raw = rawFile.readBytes()
        require(raw.size.toLong() == rawFile.length()) { "$label could not be read completely" }
        val magic = derElement(TAG_IA5_STRING, IMG4_MAGIC)
        val ticketElement = derElement(TAG_CONTEXT_0_CONSTRUCTED, ticket)
        val bodyLength = magic.size.toLong() + raw.size.toLong() + ticketElement.size.toLong()
        require(bodyLength <= Int.MAX_VALUE) { "$label personalized IMG4 is too large" }
        val root = derHeader(TAG_SEQUENCE, bodyLength.toInt())

        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            temporary.outputStream().buffered().use { output ->
                output.write(root)
                output.write(magic)
                output.write(raw)
                output.write(ticketElement)
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace ${destination.absolutePath}")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not finalize ${destination.absolutePath}")
            }
        } catch (t: Throwable) {
            temporary.delete()
            throw t
        }
        return destination
    }

    private fun derElement(tag: Int, value: ByteArray): ByteArray =
        ByteArrayOutputStream(value.size + 8).apply {
            write(derHeader(tag, value.size))
            write(value)
        }.toByteArray()

    private fun derHeader(tag: Int, length: Int): ByteArray {
        require(length >= 0)
        val out = ByteArrayOutputStream(6)
        out.write(tag)
        when {
            length < 0x80 -> out.write(length)
            length <= 0xFF -> { out.write(0x81); out.write(length) }
            length <= 0xFFFF -> { out.write(0x82); out.write(length ushr 8); out.write(length) }
            length <= 0xFFFFFF -> { out.write(0x83); out.write(length ushr 16); out.write(length ushr 8); out.write(length) }
            else -> { out.write(0x84); out.write(length ushr 24); out.write(length ushr 16); out.write(length ushr 8); out.write(length) }
        }
        return out.toByteArray()
    }

    private fun sha384(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-384").digest(bytes)

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9,._-]+"), "_")

    private val LOCAL_POLICY_IM4P = byteArrayOf(
        0x30, 0x14, 0x16, 0x04, 0x49, 0x4d, 0x34, 0x50,
        0x16, 0x04, 0x6c, 0x70, 0x6f, 0x6c, 0x16, 0x03,
        0x31, 0x2e, 0x30, 0x04, 0x01, 0x00
    )
    private val EXPLICITLY_HANDLED_COMPONENTS = setOf("Ap,LocalPolicy", "iBSS", "iBEC")
    private val IMG4_MAGIC = "IMG4".toByteArray(Charsets.US_ASCII)
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_IA5_STRING = 0x16
    private const val TAG_CONTEXT_0_CONSTRUCTED = 0xA0
}
