package io.github.rufid.payload

import android.content.Context
import android.os.Build
import io.github.rufid.BuildConfig
import java.io.File
import java.util.zip.ZipFile

enum class PayloadKind {
    Asset,
    NativeLibrary,
}

data class PayloadItem(
    val id: String,
    val label: String,
    val kind: PayloadKind,
    val packagedPath: String,
    val upstream: String,
    val license: String,
    val buildScript: String,
)

data class PayloadStatus(
    val payload: PayloadItem,
    val packaged: Boolean,
) {
    val summary: String
        get() = buildString {
            append(payload.label)
            append('\n')
            append(if (packaged) "Packaged in this APK" else "Not packaged in this APK")
            append('\n')
            append("Path: ${payload.packagedPath}")
            append('\n')
            append("Upstream: ${payload.upstream}")
            append('\n')
            append("License: ${payload.license}")
            append('\n')
            append("Build script: ${payload.buildScript}")
        }
}

object PayloadCatalog {
    private val wimSplitLibraryFiles = listOf("libwimutils.so", "librufidwim.so")
    private val ntfsRuntimeLibraryFiles = listOf("librufidmkntfs.so", "librufidntfsstream.so")

    val payloads: List<PayloadItem> = listOf(
        PayloadItem(
            id = "uefi-ntfs",
            label = "UEFI:NTFS image",
            kind = PayloadKind.Asset,
            packagedPath = "payloads/uefi/uefi-ntfs.img",
            upstream = "https://github.com/pbatard/uefi-ntfs",
            license = "GPL-2.0",
            buildScript = "scripts/payloads/build-uefi-ntfs.sh",
        ),
        PayloadItem(
            id = "freedos-image",
            label = "Source-built FreeDOS boot image",
            kind = PayloadKind.Asset,
            packagedPath = "payloads/dos/freedos.img",
            upstream = "https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/distributions/1.4/",
            license = "FreeDOS package licenses; kernel, FreeCOM, SYS, and image are built from source",
            buildScript = "scripts/payloads/build-freedos-from-source.sh",
        ),
        PayloadItem(
            id = "freedos-archive",
            label = "Source-built FreeDOS archive",
            kind = PayloadKind.Asset,
            packagedPath = "payloads/dos/freedos.7z",
            upstream = "https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/distributions/1.4/",
            license = "FreeDOS package licenses; archive contains the source-built Rufid FreeDOS image",
            buildScript = "scripts/payloads/build-freedos-from-source.sh",
        ),
        PayloadItem(
            id = "wimlib",
            label = "wimlib native library",
            kind = PayloadKind.NativeLibrary,
            packagedPath = "lib/<abi>/libwimutils.so",
            upstream = "https://github.com/ebiggers/wimlib",
            license = "LGPL-3.0-or-later or GPL-3.0-or-later, depending on build options",
            buildScript = "scripts/payloads/build-wimlib-android.sh",
        ),
        PayloadItem(
            id = "rufid-wim-bridge",
            label = "Rufid wimlib JNI bridge",
            kind = PayloadKind.NativeLibrary,
            packagedPath = "lib/<abi>/librufidwim.so",
            upstream = "Rufid source tree plus https://github.com/ebiggers/wimlib",
            license = "GPL-3.0-or-later; links to staged wimlib",
            buildScript = "scripts/payloads/build-wimlib-android.sh",
        ),
        PayloadItem(
            id = "ntfs3g-mkntfs",
            label = "NTFS-3G mkntfs (source-built PIE)",
            kind = PayloadKind.NativeLibrary,
            packagedPath = "lib/<abi>/librufidmkntfs.so",
            upstream = "https://github.com/tuxera/ntfs-3g tag 2026.7.7",
            license = "GPL-2.0-or-later",
            buildScript = "scripts/payloads/build-ntfs3g-android.sh",
        ),
        PayloadItem(
            id = "ntfs3g-stream",
            label = "Rufid NTFS stream tool (static libntfs-3g)",
            kind = PayloadKind.NativeLibrary,
            packagedPath = "lib/<abi>/librufidntfsstream.so",
            upstream = "Rufid app/src/main/cpp/rufid_ntfs_stream.c + NTFS-3G 2026.7.7",
            license = "GPL-2.0-or-later; links statically to libntfs-3g",
            buildScript = "scripts/payloads/build-ntfs3g-android.sh",
        ),
        PayloadItem(
            id = "sevenzipjbinding",
            label = "7-Zip-JBinding native library",
            kind = PayloadKind.NativeLibrary,
            packagedPath = "lib/<abi>/lib7-Zip-JBinding.so",
            upstream = "https://github.com/borisbrodski/sevenzipjbinding",
            license = "LGPL-2.1-or-later plus 7-Zip notices; RAR/unRAR codecs excluded",
            buildScript = "scripts/payloads/build-sevenzipjbinding-android.sh",
        ),
        PayloadItem(
            id = "rufid-rescue-linux",
            label = "Rufid rescue Linux image",
            kind = PayloadKind.Asset,
            packagedPath = "payloads/linux/rufid-rescue-linux.img",
            upstream = "https://buildroot.org/downloads/buildroot-2026.05.tar.xz",
            license = "Buildroot/Linux/BusyBox package licenses",
            buildScript = "scripts/payloads/build-rescue-linux-buildroot.sh",
        ),
    )

    fun statuses(context: Context): List<PayloadStatus> =
        payloads.map { payload ->
            PayloadStatus(payload, packaged = isPackaged(context, payload))
        }

    fun summary(context: Context): String =
        buildString {
            appendLine("Payload packaging flag: ${BuildConfig.INCLUDE_PAYLOADS}")
            appendLine()
            statuses(context).forEach { status ->
                appendLine(status.summary)
                appendLine()
            }
        }.trim()

    fun isPackaged(context: Context, id: String): Boolean =
        payloads.firstOrNull { it.id == id }
            ?.let { payload -> isPackaged(context, payload) }
            ?: false

    fun wimSplitBridgePackaged(context: Context): Boolean =
        nativeLibraryDirContainsAll(context, wimSplitLibraryFiles) ||
            apkContainsAllNativeLibraries(
                context.applicationInfo.sourceDir,
                wimSplitLibraryFiles,
                Build.SUPPORTED_ABIS.asList(),
            )

    fun ntfsRuntimePackaged(context: Context): Boolean =
        nativeLibraryDirContainsAll(context, ntfsRuntimeLibraryFiles) ||
            apkContainsAllNativeLibraries(
                context.applicationInfo.sourceDir,
                ntfsRuntimeLibraryFiles,
                Build.SUPPORTED_ABIS.asList(),
            )

    private fun isPackaged(context: Context, payload: PayloadItem): Boolean =
        when (payload.kind) {
            PayloadKind.Asset -> context.assets.list(payload.packagedPath.substringBeforeLast('/'))
                ?.contains(payload.packagedPath.substringAfterLast('/')) == true
            PayloadKind.NativeLibrary -> {
                val fileName = payload.packagedPath.substringAfterLast('/')
                nativeLibraryDirContains(context, fileName) ||
                    apkContainsNativeLibrary(context.applicationInfo.sourceDir, fileName)
            }
        }

    private fun nativeLibraryDirContains(context: Context, fileName: String): Boolean =
        File(context.applicationInfo.nativeLibraryDir).list()
            ?.contains(fileName) == true

    private fun nativeLibraryDirContainsAll(context: Context, fileNames: Collection<String>): Boolean {
        val packagedNames = File(context.applicationInfo.nativeLibraryDir).list()?.toSet().orEmpty()
        return fileNames.all(packagedNames::contains)
    }

    internal fun apkContainsNativeLibrary(
        apkPath: String?,
        fileName: String,
        supportedAbis: Collection<String> = Build.SUPPORTED_ABIS.asList(),
    ): Boolean = apkContainsAllNativeLibraries(apkPath, listOf(fileName), supportedAbis)

    internal fun apkContainsAllNativeLibraries(
        apkPath: String?,
        fileNames: Collection<String>,
        supportedAbis: Collection<String> = Build.SUPPORTED_ABIS.asList(),
    ): Boolean =
        if (apkPath.isNullOrBlank()) {
            false
        } else {
            runCatching {
                ZipFile(File(apkPath)).use { zip ->
                    val librariesByAbi = zip.entries().asSequence()
                        .filterNot { it.isDirectory }
                        .filter { it.name.startsWith("lib/") }
                        .map { it.name.split('/') }
                        .filter { it.size == 3 && it[1] in supportedAbis }
                        .groupBy(keySelector = { it[1] }, valueTransform = { it[2] })
                    librariesByAbi.values.any { libraries -> fileNames.all(libraries::contains) }
                }
            }.getOrDefault(false)
        }
}
