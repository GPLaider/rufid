package io.github.rufid

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.rufid.core.BlockDeviceBackup
import io.github.rufid.core.BlockDeviceVerifier
import io.github.rufid.core.BootMediaInspector
import io.github.rufid.core.ByteFormatter
import io.github.rufid.core.CancellationToken
import io.github.rufid.core.CapacityProbe
import io.github.rufid.core.ImageClassifier
import io.github.rufid.core.LastErrorReport
import io.github.rufid.core.OperationCancelledException
import io.github.rufid.core.RawImageWriter
import io.github.rufid.core.ReadBenchmark
import io.github.rufid.core.ReinitializeConfirmation
import io.github.rufid.core.WritePlan
import io.github.rufid.download.DirectDownloadWriter
import io.github.rufid.download.OfficialImage
import io.github.rufid.download.OfficialImageCatalog
import io.github.rufid.archive.ArchiveKind
import io.github.rufid.archive.ArchivePlan
import io.github.rufid.archive.ZipArchiveExtractor
import io.github.rufid.format.ExFatVolumeBuilder
import io.github.rufid.format.Fat32VolumeBuilder
import io.github.rufid.format.RecoveryVolumeLabel
import io.github.rufid.format.UsbRecoveryFormatter
import io.github.rufid.format.UsbRecoveryPlan
import io.github.rufid.format.UsbRecoveryPlanner
import io.github.rufid.format.displayName
import io.github.rufid.payload.PayloadCatalog
import io.github.rufid.partition.BootPayloadKind
import io.github.rufid.partition.FileSystemType
import io.github.rufid.partition.MbrTable
import io.github.rufid.partition.PartitionPlan
import io.github.rufid.partition.PartitionTableType
import io.github.rufid.storage.AndroidUriImageSource
import io.github.rufid.storage.SafTreeArchiveSink
import io.github.rufid.usb.UsbDeviceOpener
import io.github.rufid.usb.UsbMassStorageDevice
import io.github.rufid.windows.WindowsIsoPlan
import java.io.OutputStream

private data class UiPalette(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val field: Int,
    val text: Int,
    val muted: Int,
    val stroke: Int,
    val primary: Int,
    val primarySoft: Int,
    val danger: Int,
    val dangerSoft: Int,
    val success: Int,
    val onAccent: Int,
)

private fun Context.uiPalette(): UiPalette {
    val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
        UiPalette(
            isDark = true,
            background = Color.rgb(13, 18, 19),
            surface = Color.rgb(22, 29, 30),
            surfaceAlt = Color.rgb(30, 39, 41),
            field = Color.rgb(16, 23, 24),
            text = Color.rgb(235, 241, 240),
            muted = Color.rgb(159, 173, 174),
            stroke = Color.rgb(48, 60, 62),
            primary = Color.rgb(72, 203, 188),
            primarySoft = Color.rgb(18, 55, 52),
            danger = Color.rgb(255, 136, 111),
            dangerSoft = Color.rgb(65, 34, 29),
            success = Color.rgb(101, 214, 144),
            onAccent = Color.rgb(7, 26, 27),
        )
    } else {
        UiPalette(
            isDark = false,
            background = Color.rgb(247, 249, 249),
            surface = Color.rgb(255, 255, 255),
            surfaceAlt = Color.rgb(239, 245, 245),
            field = Color.rgb(255, 255, 255),
            text = Color.rgb(29, 35, 36),
            muted = Color.rgb(91, 105, 108),
            stroke = Color.rgb(216, 224, 224),
            primary = Color.rgb(0, 109, 119),
            primarySoft = Color.rgb(229, 246, 245),
            danger = Color.rgb(174, 64, 49),
            dangerSoft = Color.rgb(252, 235, 232),
            success = Color.rgb(45, 106, 79),
            onAccent = Color.WHITE,
        )
    }
}

private enum class BootMode {
    Image,
    Url,
    FreeDos,
}

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var usbManager: UsbManager
    private lateinit var ui: UiPalette

    private var selectedImage: AndroidUriImageSource? = null
    private var selectedBackupOutput: Uri? = null
    private var devices: List<UsbMassStorageDevice> = emptyList()
    private var selectedDevice: UsbMassStorageDevice? = null
    private var bootMode: BootMode = BootMode.Image
    @Volatile
    private var currentOperation: CancellationToken? = null

    private lateinit var statusText: TextView
    private lateinit var imageText: TextView
    private lateinit var deviceText: TextView
    private lateinit var bootModeText: TextView
    private lateinit var urlInput: EditText
    private lateinit var urlRow: View
    private lateinit var officialIsoRow: View
    private lateinit var imageModeButton: Button
    private lateinit var urlModeButton: Button
    private lateinit var freeDosModeButton: Button
    private lateinit var progressBar: ProgressBar

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        setStatus("USB permission granted.")
                        refreshDevices()
                    } else {
                        setStatus("USB permission denied.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshDevices()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = uiPalette()
        styleSystemBars()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerUsbReceiver()
        setContentView(buildUi())
        refreshDevices()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = ui.background
        window.navigationBarColor = ui.background

        var flags = 0
        if (!ui.isDark) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (!ui.isDark && Build.VERSION.SDK_INT >= 26) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    @Deprecated("Uses classic Activity result API to avoid adding dependencies.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        safeAction("Handling selected document") {
            val uri = data?.data
            if (uri == null) {
                setStatus("No document URI was returned.")
                return@safeAction
            }

            when (requestCode) {
                REQ_PICK_IMAGE -> {
                    contentResolver.takePersistableUriPermissionSafe(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    selectedImage = AndroidUriImageSource.from(contentResolver, uri)
                    bootMode = BootMode.Image
                    renderSelection()
                }
                REQ_CREATE_BACKUP -> {
                    contentResolver.takePersistableUriPermissionSafe(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    selectedBackupOutput = uri
                    val device = selectedDevice
                    if (device == null) {
                        setStatus("Backup target selected, but no USB device is selected.")
                    } else {
                        createBackup(uri, device)
                    }
                }
                REQ_PICK_EXTRACT_TREE -> {
                    contentResolver.takePersistableUriPermissionSafe(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    extractZipToTree(uri)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(ui.background)
            isFillViewport = false
            clipToPadding = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
        }
        root.addView(content)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottomInset = maxOf(insets.systemWindowInsetBottom, dp(48))
            view.setPadding(0, insets.systemWindowInsetTop, 0, bottomInset)
            content.setPadding(dp(14), dp(14), dp(14), dp(20))
            insets
        }

        content.addView(headerView())

        imageText = formValueText("No boot image selected.")
        deviceText = formValueText("No USB device selected.")
        bootModeText = formValueText("Disk or ISO image")
        statusText = infoText("Ready.")
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(ui.primary)
            progressBackgroundTintList = ColorStateList.valueOf(ui.stroke)
        }

        content.addView(panel("Drive properties") {
            addView(formRow(
                label = "Device",
                value = deviceText,
                action = actionButton("SCAN", ButtonTone.Secondary) { refreshDevices() },
            ))
            addView(buttonRow(
                actionButton("CHOOSE", ButtonTone.Secondary) { chooseUsbDevice() },
                actionButton("PERMISSION", ButtonTone.Secondary) { requestSelectedUsbPermission() },
            ))
            addView(formRow(
                label = "Boot selection",
                value = imageText,
                action = actionButton("SELECT", ButtonTone.Primary) {
                    setBootMode(BootMode.Image)
                    pickImage()
                },
            ))
            addView(TextView(this@MainActivity).apply {
                text = "Image option"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ui.muted)
                includeFontPadding = false
                setPadding(0, dp(12), 0, dp(4))
            })
            imageModeButton = actionButton("ISO / IMG", ButtonTone.Secondary) { setBootMode(BootMode.Image) }
            urlModeButton = actionButton("URL", ButtonTone.Secondary) { setBootMode(BootMode.Url) }
            freeDosModeButton = actionButton("FreeDOS", ButtonTone.Secondary) { setBootMode(BootMode.FreeDos) }
            addView(buttonRow(imageModeButton, urlModeButton, freeDosModeButton))
            urlInput = EditText(this@MainActivity).apply {
                hint = "Direct image URL"
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                textSize = 14f
                setSingleLine(true)
                setTextColor(ui.text)
                setHintTextColor(ui.muted)
                background = roundedDrawable(ui.field, ui.stroke, dp(8))
                minHeight = dp(42)
                setPadding(dp(12), 0, dp(12), 0)
            }
            urlRow = formRow(
                label = "Download",
                value = urlInput,
            ).apply {
                visibility = View.GONE
            }
            addView(urlRow)
            officialIsoRow = actionButton("or select current ISO", ButtonTone.Primary) {
                showOfficialImages()
            }.apply {
                visibility = View.GONE
            }
            addView(officialIsoRow)
        })

        content.addView(panel("Format options") {
            addView(compactOptionRow(
                "Partition scheme",
                "MBR",
                "Target system",
                "BIOS or UEFI",
            ))
            addView(compactOptionRow(
                "File system",
                "Image-defined",
                "Volume label",
                "Auto",
            ))
        })

        content.addView(statusPanel())

        content.addView(panel("Write") {
            addView(buttonRow(
                actionButton("START", ButtonTone.Danger) { startSelectedBootWrite() },
                actionButton("VERIFY", ButtonTone.Primary) { verifySelectedImage() },
            ))
            addView(buttonRow(
                actionButton("TOOLS", ButtonTone.Secondary) { showTools() },
                actionButton("CANCEL", ButtonTone.Secondary) { cancelCurrentOperation() },
            ))
        })

        return root
    }

    private fun headerView(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                rightMargin = dp(12)
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Rufid"
                    textSize = 26f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ui.text)
                    includeFontPadding = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Android USB writer"
                    textSize = 13f
                    setTextColor(ui.muted)
                    includeFontPadding = false
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(this@MainActivity).apply {
                text = "Local"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (ui.isDark) ui.primary else ui.success)
                gravity = Gravity.CENTER
                background = roundedDrawable(ui.primarySoft, ui.primary, dp(8))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                includeFontPadding = false
            })
        }

    private fun statusPanel(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(ui.surface, ui.stroke, dp(8))
            elevation = if (ui.isDark) 0f else dp(1).toFloat()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(sectionHeader("Status"))
            addView(progressBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8),
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            })
            addView(statusText)
        }.also { it.layoutParams = spacedParams(bottom = dp(14)) }

    private fun panel(title: String, configure: LinearLayout.() -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(ui.surface, ui.stroke, dp(8))
            elevation = if (ui.isDark) 0f else dp(1).toFloat()
            setPadding(dp(12), dp(10), dp(12), dp(12))
            addView(sectionHeader(title))
            configure()
        }.also { it.layoutParams = spacedParams(bottom = dp(10)) }

    private fun sectionHeader(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ui.text)
            includeFontPadding = false
            setPadding(0, 0, 0, dp(8))
        }

    private fun infoText(initial: String): TextView =
        TextView(this).apply {
            text = initial
            textSize = 14f
            setTextColor(ui.muted)
            setLineSpacing(0f, 1.08f)
            setPadding(0, 0, 0, dp(10))
        }

    private fun formValueText(initial: String): TextView =
        TextView(this).apply {
            text = initial
            textSize = 13f
            setTextColor(ui.text)
            setLineSpacing(0f, 1.08f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(40)
            background = roundedDrawable(ui.field, ui.stroke, dp(8))
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }

    private fun buttonRow(vararg buttons: Button): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
        }.also { it.layoutParams = spacedParams(top = dp(8)) }

    private fun actionButton(label: String, tone: ButtonTone = ButtonTone.Secondary, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            isAllCaps = false
            textSize = 12f
            minHeight = dp(40)
            minimumHeight = dp(40)
            maxLines = 2
            setPadding(dp(8), 0, dp(8), 0)
            applyButtonStyle(this, tone)
            setOnClickListener { safeAction(label, action) }
            layoutParams = spacedParams(top = dp(8))
        }

    private fun formRow(label: String, value: View, action: Button? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ui.muted)
                includeFontPadding = false
                setPadding(0, 0, 0, dp(4))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(value, LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                if (action != null) {
                    addView(action, LinearLayout.LayoutParams(
                        dp(96),
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = dp(8)
                    })
                }
            })
        }.also { it.layoutParams = spacedParams(top = dp(6)) }

    private fun formValuePair(label: String, value: String): View =
        formRow(label, formValueText(value))

    private fun compactOptionRow(
        leftLabel: String,
        leftValue: String,
        rightLabel: String,
        rightValue: String,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(compactOption(leftLabel, leftValue), LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ))
            addView(compactOption(rightLabel, rightValue), LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                leftMargin = dp(8)
            })
        }.also { it.layoutParams = spacedParams(top = dp(6)) }

    private fun compactOption(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ui.muted)
                includeFontPadding = false
                setPadding(0, 0, 0, dp(4))
            })
            addView(formValueText(value))
        }

    private fun spacedParams(
        top: Int = 0,
        bottom: Int = 0,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun roundedDrawable(fillColor: Int, strokeColor: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            color = android.content.res.ColorStateList.valueOf(fillColor)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buttonStyle(tone: ButtonTone): ButtonStyle =
        when (tone) {
            ButtonTone.Primary -> ButtonStyle(ui.primary, ui.primary, ui.onAccent)
            ButtonTone.Secondary -> ButtonStyle(ui.surfaceAlt, ui.stroke, ui.text)
            ButtonTone.Danger -> ButtonStyle(ui.danger, ui.danger, if (ui.isDark) Color.rgb(42, 15, 12) else Color.WHITE)
        }

    private fun applyButtonStyle(button: Button, tone: ButtonTone) {
        val style = buttonStyle(tone)
        button.setTextColor(style.textColor)
        button.background = roundedDrawable(style.fillColor, style.strokeColor, dp(8))
    }

    private data class ButtonStyle(
        val fillColor: Int,
        val strokeColor: Int,
        val textColor: Int,
    )

    private enum class ButtonTone {
        Primary,
        Secondary,
        Danger,
    }

    private fun renderSelection() {
        renderBootSelection()

        val device = selectedDevice
        deviceText.text = if (device == null) {
            "No USB device selected."
        } else {
            val permission = if (usbManager.hasPermission(device.device)) "permission granted" else "permission needed"
            "USB: ${device.label}\nVID:PID ${device.device.vendorId}:${device.device.productId}\n$permission"
        }
    }

    private fun setBootMode(mode: BootMode) {
        bootMode = mode
        renderBootSelection()
        val message = when (mode) {
            BootMode.Image -> "Boot selection: disk or ISO image."
            BootMode.Url -> "Boot selection: direct download URL."
            BootMode.FreeDos -> "Boot selection: packaged FreeDOS LiteUSB."
        }
        setStatus(message)
    }

    private fun renderBootSelection() {
        val modeLabel = when (bootMode) {
            BootMode.Image -> "Disk or ISO image"
            BootMode.Url -> "Direct download image"
            BootMode.FreeDos -> "FreeDOS LiteUSB"
        }
        bootModeText.text = modeLabel
        if (::urlRow.isInitialized) {
            urlRow.visibility = if (bootMode == BootMode.Url) View.VISIBLE else View.GONE
        }
        if (::officialIsoRow.isInitialized) {
            officialIsoRow.visibility = if (bootMode == BootMode.Url) View.VISIBLE else View.GONE
        }
        if (::imageModeButton.isInitialized) {
            applyButtonStyle(imageModeButton, if (bootMode == BootMode.Image) ButtonTone.Primary else ButtonTone.Secondary)
            applyButtonStyle(urlModeButton, if (bootMode == BootMode.Url) ButtonTone.Primary else ButtonTone.Secondary)
            applyButtonStyle(freeDosModeButton, if (bootMode == BootMode.FreeDos) ButtonTone.Primary else ButtonTone.Secondary)
        }

        imageText.text = when (bootMode) {
            BootMode.Image -> {
                val image = selectedImage
                if (image == null) {
                    "No boot image selected."
                } else {
                    val kind = ImageClassifier.classify(image.name)
                    "${image.name}\n${ByteFormatter.format(image.size)} - $kind"
                }
            }
            BootMode.Url -> {
                val url = if (::urlInput.isInitialized) urlInput.text?.toString()?.trim().orEmpty() else ""
                if (url.isBlank()) "Enter a direct image URL below." else url
            }
            BootMode.FreeDos -> {
                val size = readPackagedAssetSize(FREEDOS_IMAGE_SIZE_ASSET)
                val packaged = PayloadCatalog.isPackaged(this, "freedos-image")
                if (packaged && size != null) {
                    "Packaged FreeDOS LiteUSB image\n${ByteFormatter.format(size)} - ${FREEDOS_IMAGE_ASSET}"
                } else {
                    "FreeDOS LiteUSB image is not packaged in this APK."
                }
            }
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_PICK_IMAGE)
    }

    private fun pickBackupOutput() {
        if (selectedDevice == null) {
            setStatus("Select a USB device first.")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "rufid-usb-backup.img")
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_CREATE_BACKUP)
    }

    private fun refreshDevices() {
        val previouslySelectedName = selectedDevice?.device?.deviceName
        devices = UsbMassStorageDevice.discover(usbManager)
        selectedDevice = devices.firstOrNull { it.device.deviceName == previouslySelectedName } ?: devices.firstOrNull()
        if (devices.isEmpty()) {
            setStatus("No USB mass-storage device found.")
        } else if (devices.size == 1) {
            setStatus("Found 1 USB mass-storage device.")
        } else {
            setStatus("Found ${devices.size} USB mass-storage devices. Choose the target before writing.")
        }
        renderSelection()
    }

    private fun chooseUsbDevice() {
        if (devices.isEmpty()) {
            refreshDevices()
            if (devices.isEmpty()) return
        }

        val labels = devices.map { device ->
            "${device.label}\nVID:PID ${device.device.vendorId}:${device.device.productId}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose USB device")
            .setItems(labels) { _, which ->
                selectedDevice = devices[which]
                setStatus("Selected ${devices[which].label}.")
                renderSelection()
            }
            .show()
    }

    private fun requestSelectedUsbPermission() {
        val device = selectedDevice
        if (device == null) {
            setStatus("No USB device selected.")
            return
        }
        if (usbManager.hasPermission(device.device)) {
            setStatus("USB permission already granted.")
            renderSelection()
            return
        }

        val intent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
        )
        usbManager.requestPermission(device.device, intent)
        setStatus("USB permission requested.")
    }

    private fun startSelectedBootWrite() {
        when (bootMode) {
            BootMode.Image -> confirmAndWriteImage()
            BootMode.Url -> confirmAndDownloadToUsb()
            BootMode.FreeDos -> confirmAndWriteFreeDos()
        }
    }

    private fun confirmAndWriteImage() {
        val image = selectedImage
        val device = selectedDevice
        if (image == null || device == null) {
            setStatus("Select both an image and a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Erase and write USB?")
            .setMessage("This will overwrite ${device.label} with ${image.name}.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Write") { _, _ ->
                safeAction("Write selected image to USB") { writeImage(image, device) }
            }
            .show()
    }

    private fun writeImage(image: AndroidUriImageSource, device: UsbMassStorageDevice) {
        runIo("Writing image") { token ->
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val plan = WritePlan(image.name, image.size, device.label, blockDevice.sizeBytes)
                plan.validate()
                val writer = RawImageWriter(blockDevice)
                image.open(contentResolver).use { input ->
                    writer.write(input, image.size, token) { progress ->
                        postProgress("Writing ${image.name}: ${progress.percent}% (${ByteFormatter.format(progress.bytesDone)})", progress.percent)
                    }
                }
            }
        }
    }

    private fun verifySelectedImage() {
        val image = selectedImage
        val device = selectedDevice
        if (image == null || device == null) {
            setStatus("Select both an image and a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        runIo("Verifying image") { token ->
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                WritePlan(image.name, image.size, device.label, blockDevice.sizeBytes).validate()
                image.open(contentResolver).use { input ->
                    val result = BlockDeviceVerifier(blockDevice).verify(input, image.size, token) { progress ->
                        postProgress("Verifying ${image.name}: ${progress.percent}% (${ByteFormatter.format(progress.bytesDone)})", progress.percent)
                    }
                    if (result.matched) {
                        postStatus("Verification matched ${ByteFormatter.format(result.checkedBytes)}.")
                    } else {
                        postStatus("Verification mismatch at ${ByteFormatter.format(result.mismatchOffset ?: 0L)}.")
                    }
                }
            }
        }
    }

    private fun runReadBenchmark() {
        val device = selectedDevice
        if (device == null) {
            setStatus("Select a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        runIo("Read benchmark") { token ->
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val result = ReadBenchmark(blockDevice).run(cancellationToken = token) { progress ->
                    postProgress("Benchmark: ${progress.percent}% read", progress.percent)
                }
                val speed = ByteFormatter.format(result.bytesPerSecond)
                postStatus("Read benchmark: $speed/s over ${ByteFormatter.format(result.bytesRead)}.")
            }
        }
    }

    private fun confirmAndDownloadToUsb() {
        val device = selectedDevice
        val url = urlInput.text?.toString()?.trim().orEmpty()
        if (device == null || url.isBlank()) {
            setStatus("Select a USB device and enter a download URL first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Download and overwrite USB?")
            .setMessage("This streams the URL directly to ${device.label}.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download") { _, _ ->
                safeAction("Download URL directly to USB") { downloadToUsb(url, device) }
            }
            .show()
    }

    private fun downloadToUsb(url: String, device: UsbMassStorageDevice) {
        runIo("Direct download") { token ->
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                DirectDownloadWriter(blockDevice).write(url, token) { progress ->
                    postProgress("Download/write: ${progress.percent}% (${ByteFormatter.format(progress.bytesDone)})", progress.percent)
                }
            }
        }
    }

    private fun showUsbDiagnostics() {
        val device = selectedDevice
        if (device == null) {
            setStatus("Select a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        runIo("USB diagnostics") {
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val message = buildString {
                    appendLine("Label: ${device.label}")
                    appendLine("VID:PID: ${device.vidPidLabel()}")
                    appendLine("Android USB device: ${device.device.deviceName}")
                    appendLine("Block size: ${blockDevice.blockSize} B")
                    appendLine("Capacity: ${ByteFormatter.format(blockDevice.sizeBytes)} (${blockDevice.sizeBytes} bytes)")
                    appendLine("Interfaces: ${device.device.interfaceCount}")
                }
                main.post { showMessage("USB diagnostics", message) }
            }
        }
    }

    private fun runSafeCapacityProbe() {
        val device = selectedDevice
        if (device == null) {
            setStatus("Select a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        runIo("Safe capacity probe") { token ->
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val result = CapacityProbe(blockDevice).run(token) { progress ->
                    postProgress("Capacity probe: ${progress.percent}% read", progress.percent)
                }
                val message = buildString {
                    appendLine("Read-only probe completed. This is not a destructive fake-capacity test.")
                    appendLine("Capacity: ${ByteFormatter.format(blockDevice.sizeBytes)}")
                    appendLine("Sampled bytes: ${ByteFormatter.format(result.checkedBytes)}")
                    result.samples.forEach { sample ->
                        appendLine("Offset ${ByteFormatter.format(sample.byteOffset)}: CRC32 ${sample.crc32.toHex8()}")
                    }
                }
                main.post { showMessage("Safe capacity probe", message) }
            }
        }
    }

    private fun previewPartitionPlan() {
        val device = selectedDevice
        if (device == null) {
            setStatus("Select a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        runIo("Partition preview") {
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val startSector = 2048L
                val sectorCount = (blockDevice.sizeBytes / blockDevice.blockSize) - startSector
                val fat32Plan = PartitionPlan(
                    tableType = PartitionTableType.Mbr,
                    fileSystemType = FileSystemType.Fat32,
                    bootPayloadKind = BootPayloadKind.None,
                    startSector = startSector,
                    sectorCount = sectorCount,
                    sectorSize = blockDevice.blockSize,
                )
                val exFatPlan = fat32Plan.copy(fileSystemType = FileSystemType.ExFat)
                fat32Plan.validate(blockDevice.sizeBytes)
                exFatPlan.validate(blockDevice.sizeBytes)
                val volumeLabel = RecoveryVolumeLabel.fromDeviceLabel(device.label)
                val mbr = MbrTable(fat32Plan).toBytes()
                val fat32 = Fat32VolumeBuilder(fat32Plan).layout()
                val exFat = ExFatVolumeBuilder(exFatPlan).layout()
                val message = buildString {
                    appendLine("Preview only. Nothing was written.")
                    appendLine("Target: ${device.label}")
                    appendLine("VID:PID: ${device.vidPidLabel()}")
                    appendLine("Capacity: ${ByteFormatter.format(blockDevice.sizeBytes)}")
                    appendLine("Table: ${fat32Plan.tableType}")
                    appendLine("Filesystem targets: FAT32, exFAT")
                    appendLine("Volume label: $volumeLabel")
                    appendLine("Start sector: ${fat32Plan.startSector}")
                    appendLine("Sector count: ${fat32Plan.sectorCount}")
                    appendLine("FAT32 cluster size: ${ByteFormatter.format(fat32.clusterSizeBytes)}")
                    appendLine("FAT32 clusters: ${fat32.clusterCount}")
                    appendLine("FAT32 sectors per FAT: ${fat32.sectorsPerFat}")
                    appendLine("exFAT cluster size: ${ByteFormatter.format(exFat.clusterSizeBytes)}")
                    appendLine("exFAT clusters: ${exFat.clusterCount}")
                    appendLine("exFAT FAT sectors: ${exFat.fatLength}")
                    appendLine("Generated MBR bytes: ${mbr.size}")
                }
                main.post { showMessage("Format / partition plan", message) }
            }
        }
    }

    private fun prepareUsbReinitialize(fileSystemType: FileSystemType) {
        val device = selectedDevice
        if (device == null) {
            setStatus("Select a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        runIo("Preparing USB recovery plan") {
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val plan = UsbRecoveryPlanner.create(blockDevice.sizeBytes, blockDevice.blockSize, fileSystemType)
                main.post { showReinitializePlan(device, plan) }
            }
        }
    }

    private fun showReinitializePlan(device: UsbMassStorageDevice, plan: UsbRecoveryPlan) {
        val fileSystemName = plan.partitionPlan.fileSystemType.displayName()
        val volumeLabel = RecoveryVolumeLabel.fromDeviceLabel(device.label)
        val confirmation = EditText(this).apply {
            hint = "Type R"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val message = buildString {
            appendLine("This destructive recovery flow will make the selected USB reusable as one $fileSystemName drive.")
            appendLine()
            appendLine("Target: ${device.label}")
            appendLine("VID:PID: ${device.vidPidLabel()}")
            appendLine("Capacity: ${ByteFormatter.format(plan.deviceSizeBytes)} (${plan.deviceSizeBytes} bytes)")
            appendLine("Block size: ${plan.blockSize} B")
            appendLine()
            appendLine("Plan:")
            appendLine("1. Quick wipe USB partition/boot metadata at the beginning and end of the drive.")
            appendLine("2. Create one MBR $fileSystemName partition.")
            appendLine("3. Format the partition as $fileSystemName with label $volumeLabel.")
            appendLine("4. ${plan.partitionPlan.fileSystemType.recoveryVerificationText()}")
            appendLine()
            appendLine("This is not secure erase and not a full-disk wipe. Existing file contents outside the rewritten metadata area may remain until overwritten later.")
            appendLine("Internal Android storage is not addressable by this flow; only the selected USB host mass-storage target is used.")
            appendLine()
            appendLine("Type R to continue.")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Reinitialize USB as $fileSystemName?")
            .setMessage(message)
            .setView(confirmation)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reinitialize", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = confirmation.text?.toString()?.trim().orEmpty()
                if (!ReinitializeConfirmation.accepts(typed)) {
                    confirmation.error = "Type R to continue."
                    return@setOnClickListener
                }
                dialog.dismiss()
                safeAction("Reinitialize USB as $fileSystemName") { reinitializeUsb(device, plan.partitionPlan.fileSystemType) }
            }
        }
        dialog.show()
    }

    private fun reinitializeUsb(device: UsbMassStorageDevice, fileSystemType: FileSystemType) {
        val fileSystemName = fileSystemType.displayName()
        val volumeLabel = RecoveryVolumeLabel.fromDeviceLabel(device.label)
        runIo("Reinitializing USB as $fileSystemName") { token ->
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val plan = UsbRecoveryPlanner.create(blockDevice.sizeBytes, blockDevice.blockSize, fileSystemType)
                UsbRecoveryFormatter(blockDevice).reinitialize(
                    plan = plan,
                    label = volumeLabel,
                    cancellationToken = token,
                ) { progress ->
                    postProgress(
                        "Reinitializing $fileSystemName: ${progress.percent}% (${progress.phase})",
                        progress.percent,
                    )
                }
                val inspection = BootMediaInspector(blockDevice).inspect()
                val message = buildRecoverySuccessMessage(device, plan, inspection)
                postStatus("USB reinitialized as $fileSystemName on ${device.label}.")
                main.post { showRecoverySuccessDialog(message) }
            }
        }
    }

    private fun buildRecoverySuccessMessage(
        device: UsbMassStorageDevice,
        plan: UsbRecoveryPlan,
        inspection: io.github.rufid.core.BootMediaInspection,
    ): String = buildString {
        appendLine("USB recovery completed.")
        appendLine()
        appendLine("Target: ${device.label}")
        appendLine("VID:PID: ${device.vidPidLabel()}")
        appendLine("Capacity: ${ByteFormatter.format(plan.deviceSizeBytes)}")
        appendLine("${plan.partitionPlan.fileSystemType.displayName()} partition start: LBA ${plan.partitionPlan.startSector}")
        appendLine("${plan.partitionPlan.fileSystemType.displayName()} partition size: ${ByteFormatter.format(plan.partitionSizeBytes)}")
        appendLine("Post-write verification: ${plan.partitionPlan.fileSystemType.recoveryVerifiedMetadata()} matched.")
        appendLine()
        appendLine("Inspection:")
        appendLine("MBR signature: ${if (inspection.hasMbrSignature) "55 AA" else "missing"}")
        appendLine("File system: ${inspection.bootSector.fileSystem ?: "unknown"}")
        appendLine("Volume label: ${inspection.bootSector.volumeLabel ?: "unknown"}")
        appendLine()
        appendLine("This was USB recovery/reinitialize, not secure erase.")
    }

    private fun showRecoverySuccessDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("USB recovery finished")
            .setMessage(message)
            .setNegativeButton("OK", null)
            .setNeutralButton("Inspect USB") { _, _ -> inspectBootMedia() }
            .setPositiveButton("Read benchmark") { _, _ -> runReadBenchmark() }
            .show()
    }

    private fun previewWindowsHelper() {
        val image = selectedImage
        if (image == null) {
            setStatus("Select a Windows ISO candidate first.")
            return
        }
        val plan = WindowsIsoPlan(
            installWimSize = null,
            targetFileSystemAllowsLargeFiles = false,
        )
        val message = buildString {
            appendLine("Image: ${image.name}")
            appendLine("Detected kind: ${ImageClassifier.classify(image.name)}")
            appendLine(plan.summary())
            appendLine("WIM split engine is intentionally not bundled yet; wimlib integration needs source distribution.")
        }
        showMessage("Windows ISO helper", message)
    }

    private fun previewArchivePlan() {
        val image = selectedImage
        if (image == null) {
            setStatus("Select an archive or image first.")
            return
        }
        val kind = ArchivePlan.classify(image.name)
        val message = when (kind) {
            io.github.rufid.archive.ArchiveKind.Zip ->
                "ZIP extraction can use platform-compatible code and is the first archive engine target."
            io.github.rufid.archive.ArchiveKind.Unknown ->
                "This file is not recognized as an archive."
            else ->
                "$kind extraction requires a reviewed dependency/source distribution plan before implementation."
        }
        showMessage("Archive extraction", "Image: ${image.name}\nArchive kind: $kind\n$message")
    }

    private fun showTools() {
        val labels = arrayOf(
            "Backup USB to image",
            "Inspect USB media",
            "Reinitialize USB / FAT32",
            "Reinitialize USB / exFAT",
            "Read benchmark",
            "USB diagnostics",
            "Safe capacity probe",
            "Format plan preview",
            "Windows ISO helper",
            "Archive plan",
            "Extract ZIP",
            "Payload status",
            "Last error report",
        )
        AlertDialog.Builder(this)
            .setTitle("Tools")
            .setItems(labels) { _, which ->
                safeAction(labels[which]) {
                    when (which) {
                        0 -> pickBackupOutput()
                        1 -> inspectBootMedia()
                        2 -> prepareUsbReinitialize(FileSystemType.Fat32)
                        3 -> prepareUsbReinitialize(FileSystemType.ExFat)
                        4 -> runReadBenchmark()
                        5 -> showUsbDiagnostics()
                        6 -> runSafeCapacityProbe()
                        7 -> previewPartitionPlan()
                        8 -> previewWindowsHelper()
                        9 -> previewArchivePlan()
                        10 -> pickZipExtractTree()
                        11 -> showPayloadStatus()
                        12 -> showLastErrorReport()
                    }
                }
            }
            .show()
    }

    private fun inspectBootMedia() {
        val device = selectedDevice
        if (device == null) {
            setStatus("Select a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }

        runIo("Inspecting boot media") {
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val inspection = BootMediaInspector(blockDevice).inspect()
                val message = buildString {
                    appendLine("Read-only inspection. Nothing was written.")
                    appendLine("Device: ${device.label}")
                    appendLine("VID:PID: ${device.vidPidLabel()}")
                    appendLine("Android USB device: ${device.device.deviceName}")
                    appendLine("Capacity: ${ByteFormatter.format(inspection.sizeBytes)} (${inspection.sizeBytes} bytes)")
                    appendLine("Block size: ${inspection.blockSize} B")
                    appendLine("MBR signature: ${if (inspection.hasMbrSignature) "55 AA" else "missing"}")
                    if (inspection.partitions.isEmpty()) {
                        appendLine("Partitions: none detected")
                    } else {
                        inspection.partitions.forEach { partition ->
                            val boot = if (partition.bootable) "bootable" else "not bootable"
                            val endSector = partition.startSector + partition.sectorCount - 1L
                            val sizeBytes = partition.sectorCount * inspection.blockSize.toLong()
                            appendLine(
                                "Partition ${partition.index}: ${partition.typeHex}, $boot, " +
                                    "start LBA ${partition.startSector}, end LBA $endSector, " +
                                    "${ByteFormatter.format(sizeBytes)}",
                            )
                        }
                    }
                    appendLine("Boot sector LBA: ${inspection.bootSector.lba}")
                    appendLine("Boot signature: ${if (inspection.bootSector.hasSignature) "55 AA" else "missing"}")
                    appendLine("OEM: ${inspection.bootSector.oemName.ifBlank { "unknown" }}")
                    appendLine("Volume label: ${inspection.bootSector.volumeLabel ?: "unknown"}")
                    appendLine("File system: ${inspection.bootSector.fileSystem ?: "unknown"}")
                    appendLine("FreeDOS: ${if (inspection.looksLikeFreeDos) "likely" else "not detected"}")
                    val detectedFileSystem = inspection.bootSector.fileSystem.orEmpty()
                    when {
                        inspection.partitions.any { it.typeHex == "0x0C" } &&
                            detectedFileSystem.contains("FAT32", ignoreCase = true) ->
                            appendLine("Recovery layout: MBR FAT32 media detected")
                        inspection.partitions.any { it.typeHex == "0x07" } &&
                            detectedFileSystem.contains("exFAT", ignoreCase = true) ->
                            appendLine("Recovery layout: MBR exFAT media detected")
                    }
                    inspection.freeDosEvidence.forEach { evidence ->
                        appendLine("Evidence: $evidence")
                    }
                }
                main.post { showMessage("Boot media inspection", message) }
            }
        }
    }

    private fun showOfficialImages() {
        val labels = OfficialImageCatalog.current.map { it.pickerLabel }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select current ISO")
            .setItems(labels) { _, which ->
                safeAction("Select current ISO") {
                    selectOfficialImage(OfficialImageCatalog.current[which])
                }
            }
            .show()
    }

    private fun selectOfficialImage(image: OfficialImage) {
        val directUrl = image.directImageUrl
        if (directUrl == null) {
            AlertDialog.Builder(this)
                .setTitle(image.name)
                .setMessage("${image.release}\n\n${image.note}\n\n${image.pageUrl}")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Use page URL") { _, _ ->
                    bootMode = BootMode.Url
                    urlInput.setText(image.pageUrl)
                    renderBootSelection()
                    setStatus("${image.name} official page URL selected.")
                }
                .setPositiveButton("Open official page") { _, _ ->
                    openUrl(image.pageUrl)
                }
                .show()
            return
        }

        bootMode = BootMode.Url
        urlInput.setText(directUrl)
        renderBootSelection()
        val checksum = image.checksumUrl?.let { "\nChecksum: $it" }.orEmpty()
        setStatus("${image.name} ISO URL selected.")
        showMessage(
            image.name,
            "${image.release}\n\nDirect ISO URL copied to the URL field.$checksum\n\n${image.note}",
        )
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showPayloadStatus() {
        showMessage("Payload status", PayloadCatalog.summary(this))
    }

    private fun confirmAndWriteFreeDos() {
        val device = selectedDevice
        if (device == null) {
            setStatus("Select a USB device first.")
            return
        }
        if (!usbManager.hasPermission(device.device)) {
            requestSelectedUsbPermission()
            return
        }
        if (!PayloadCatalog.isPackaged(this, "freedos-image")) {
            setStatus("FreeDOS boot image is not packaged in this APK.")
            return
        }

        val imageSize = readPackagedAssetSize(FREEDOS_IMAGE_SIZE_ASSET)
            ?: error("Missing FreeDOS image size sidecar.")

        AlertDialog.Builder(this)
            .setTitle("Erase and create FreeDOS USB?")
            .setMessage("This will overwrite ${device.label} with the packaged FreeDOS LiteUSB image (${ByteFormatter.format(imageSize)}).")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Write FreeDOS") { _, _ ->
                safeAction("Write FreeDOS USB") { writeFreeDosImage(device, imageSize) }
            }
            .show()
    }

    private fun writeFreeDosImage(device: UsbMassStorageDevice, imageSize: Long) {
        runIo("Writing FreeDOS USB") { token ->
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                WritePlan("FreeDOS LiteUSB", imageSize, device.label, blockDevice.sizeBytes).validate()
                assets.open(FREEDOS_IMAGE_ASSET).use { input ->
                    RawImageWriter(blockDevice).write(
                        image = input,
                        imageSize = imageSize,
                        cancellationToken = token,
                    ) { progress ->
                        postProgress(
                            "Writing FreeDOS: ${progress.percent}% (${ByteFormatter.format(progress.bytesDone)})",
                            progress.percent,
                        )
                    }
                }
                postStatus("FreeDOS USB image written to ${device.label}.")
            }
        }
    }

    private fun readPackagedAssetSize(path: String): Long? =
        runCatching {
            assets.open(path).bufferedReader().use { reader ->
                reader.readText().trim().toLong()
            }
        }.getOrNull()

    private fun pickZipExtractTree() {
        val image = selectedImage
        if (image == null) {
            setStatus("Select a ZIP archive first.")
            return
        }
        val kind = ArchivePlan.classify(image.name)
        if (kind != ArchiveKind.Zip) {
            setStatus("ZIP extraction is implemented; $kind requires a reviewed dependency plan.")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        startActivityForResult(intent, REQ_PICK_EXTRACT_TREE)
    }

    private fun extractZipToTree(treeUri: Uri) {
        val image = selectedImage
        if (image == null) {
            setStatus("Select a ZIP archive first.")
            return
        }
        runIo("Extracting ZIP") { token ->
            val sink = SafTreeArchiveSink(contentResolver, treeUri)
            image.open(contentResolver).use { input ->
                ZipArchiveExtractor().extract(
                    input = input,
                    sink = sink,
                    cancellationToken = token,
                    onEntry = { entry -> postStatus("Extracting ${entry.path}") },
                    onProgress = { progress ->
                        postProgress("Extracted ${ByteFormatter.format(progress.bytesDone)} from ${image.name}", progress.percent)
                    },
                )
            }
            postStatus("ZIP extraction finished for ${image.name}.")
        }
    }

    private fun createBackup(outputUri: Uri, device: UsbMassStorageDevice) {
        runIo("Creating backup") { token ->
            UsbDeviceOpener.open(device).useBlockDevice { blockDevice ->
                val output: OutputStream = requireNotNull(contentResolver.openOutputStream(outputUri)) {
                    "Unable to open backup output."
                }
                output.use {
                    BlockDeviceBackup(blockDevice).backup(it, cancellationToken = token) { progress ->
                        postProgress("Backup: ${progress.percent}% (${ByteFormatter.format(progress.bytesDone)})", progress.percent)
                    }
                }
            }
        }
    }

    private fun cancelCurrentOperation() {
        val token = currentOperation
        if (token == null) {
            setStatus("No operation is running.")
        } else {
            token.cancel()
            setStatus("Cancelling current operation...")
        }
    }

    private fun runIo(title: String, work: (CancellationToken) -> Unit) {
        if (currentOperation != null) {
            setStatus("Another operation is already running.")
            return
        }

        val token = CancellationToken.active()
        currentOperation = token
        setProgressBarValue(0)
        setStatus("$title started.")
        Thread {
            try {
                work(token)
                if (token.isCancelled) {
                    postStatus("$title cancelled.")
                } else {
                    postFinished(title)
                }
            } catch (_: OperationCancelledException) {
                postStatus("$title cancelled.")
            } catch (error: Throwable) {
                LastErrorReport.write(this, title, error)
                postStatus("$title failed: ${LastErrorReport.userMessage(error)}")
            } finally {
                main.post {
                    if (currentOperation === token) currentOperation = null
                }
            }
        }.start()
    }

    private fun safeAction(title: String, action: () -> Unit) {
        try {
            action()
        } catch (_: OperationCancelledException) {
            setStatus("$title cancelled.")
        } catch (error: Throwable) {
            LastErrorReport.write(this, title, error)
            setStatus("$title failed: ${LastErrorReport.userMessage(error)}")
        }
    }

    private fun postProgress(message: String, percent: Int) {
        main.post {
            progressBar.progress = percent.coerceIn(0, 100)
            statusText.text = message
        }
    }

    private fun postStatus(message: String) {
        main.post { setStatus(message) }
    }

    private fun postFinished(title: String) {
        main.post {
            progressBar.progress = 100
            if (statusText.text.toString() == "$title started.") {
                statusText.text = "$title finished."
            }
        }
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLastErrorReport() {
        val report = LastErrorReport.read(this) ?: "No local error report has been saved yet."
        showMessage("Last error report", report)
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun setProgressBarValue(percent: Int) {
        progressBar.progress = percent.coerceIn(0, 100)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun android.content.ContentResolver.takePersistableUriPermissionSafe(uri: Uri, flags: Int) {
        try {
            takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers grant one-shot access only.
        }
    }

    private inline fun <T> io.github.rufid.usb.UsbScsiBlockDevice.useBlockDevice(block: (io.github.rufid.usb.UsbScsiBlockDevice) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private fun UsbMassStorageDevice.vidPidLabel(): String =
        "0x%04X:0x%04X".format(device.vendorId, device.productId)

    companion object {
        private const val ACTION_USB_PERMISSION = "io.github.rufid.USB_PERMISSION"
        private const val REQ_PICK_IMAGE = 10
        private const val REQ_CREATE_BACKUP = 11
        private const val REQ_PICK_EXTRACT_TREE = 12
        private const val FREEDOS_IMAGE_ASSET = "payloads/dos/freedos.img"
        private const val FREEDOS_IMAGE_SIZE_ASSET = "payloads/dos/freedos.img.size"
    }
}

private fun Long.toHex8(): String =
    toString(16).uppercase().padStart(8, '0')

private fun FileSystemType.recoveryVerificationText(): String =
    when (this) {
        FileSystemType.Fat32 -> "Read back and verify the MBR, FAT32 boot sector, and FSInfo sector."
        FileSystemType.ExFat -> "Read back and verify the MBR, exFAT main boot sector, checksum sector, and backup boot sector."
        else -> "Read back and verify rewritten recovery metadata."
    }

private fun FileSystemType.recoveryVerifiedMetadata(): String =
    when (this) {
        FileSystemType.Fat32 -> "MBR, FAT32 boot sector, and FSInfo"
        FileSystemType.ExFat -> "MBR, exFAT main boot sector, checksum sector, and backup boot sector"
        else -> "rewritten recovery metadata"
    }
