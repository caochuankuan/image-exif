package com.compose.exifinfo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import com.compose.exifinfo.ui.theme.ExifInfoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier as ReflectModifier

private const val METADATA_SCAN_LIMIT_BYTES = 4 * 1024 * 1024
private const val TEXT_PREVIEW_LINE_COUNT = 200
private val INVALID_DEVICE_GUESS_MARKERS = listOf(
    "http://",
    "https://",
    ".com/",
    ".org/",
    ".net/",
    "/photos/",
    "/camera/",
    "xmlns",
    "ns.",
    "schema",
    "xmp",
    "gcamera=",
)
private val DEVICE_BRAND_WHITELIST = listOf(
    "apple",
    "iphone",
    "ipad",
    "huawei",
    "honor",
    "xiaomi",
    "redmi",
    "meizu",
    "vivo",
    "oppo",
    "oneplus",
    "realme",
    "iqoo",
    "nubia",
    "zte",
    "lenovo",
    "motorola",
    "moto",
    "smartisan",
    "samsung",
    "galaxy",
    "google",
    "pixel",
    "sony",
    "xperia",
    "lg",
    "htc",
    "nokia",
    "asus",
    "rog",
    "black shark",
    "blackberry",
    "nothing",
    "cmf",
    "tecno",
    "infinix",
    "itel",
    "lava",
    "micromax",
    "sharp",
    "panasonic",
    "leica",
    "hasseblad",
    "hasselblad",
    "canon",
    "nikon",
    "fujifilm",
    "olympus",
    "om system",
    "pentax",
    "ricoh",
    "sigma",
    "kodak",
    "phase one",
    "mamiya",
    "contax",
    "yashica",
    "minolta",
    "casio",
    "gopro",
    "insta360",
    "dji",
    "autel",
    "parrot",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExifInfoTheme {
                ExifInfoApp()
            }
        }
    }
}

private data class MetadataResult(
    val imageUri: Uri,
    val exifDeviceInfo: String?,
    val deviceGuess: String?,
    val deviceGuessContext: String?,
    val mappedInfo: List<Pair<String, String>>,
    val fullInfo: List<Pair<String, String>>,
    val previewLines: List<String>,
    val errorMessage: String? = null,
)

private data class DeviceGuessMatch(
    val guess: String,
    val context: String,
)

@Composable
private fun ExifInfoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var currentImageUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraImageUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var metadataResult by remember { mutableStateOf<MetadataResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            currentImageUriText = uri.toString()
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) {
            currentImageUriText = cameraImageUriText
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val outputUri = createCameraImageUri(context)
            cameraImageUriText = outputUri.toString()
            takePictureLauncher.launch(outputUri)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("没有相机权限，无法拍照")
            }
        }
    }

    LaunchedEffect(currentImageUriText) {
        val uriText = currentImageUriText ?: run {
            metadataResult = null
            return@LaunchedEffect
        }
        val uri = Uri.parse(uriText)
        isLoading = true
        metadataResult = withContext(Dispatchers.IO) {
            parseMetadata(context, uri)
        }
        isLoading = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ExifInfo",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "选择照片后自动解析图片内容、EXIF 信息、C2PA 相关文本和前 200 行 UTF-8 文本。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("从相册选择")
                    }
                    Button(
                        onClick = {
                            when {
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED -> {
                                    val outputUri = createCameraImageUri(context)
                                    cameraImageUriText = outputUri.toString()
                                    takePictureLauncher.launch(outputUri)
                                }

                                else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("拍照")
                    }
                }
            }

            if (isLoading) {
                item {
                    LoadingCard()
                }
            }

            val result = metadataResult
            if (result != null) {
                if (result.errorMessage != null) {
                    item {
                        CopyableSectionCard(
                            title = "解析结果",
                            contentToCopy = result.errorMessage,
                        ) {
                            Text(
                                text = result.errorMessage,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    item {
                        ImageSection(result.imageUri)
                    }
                    item {
                        DeviceGuessSection(
                            exifDeviceInfo = result.exifDeviceInfo,
                            deviceGuess = result.deviceGuess,
                            deviceGuessContext = result.deviceGuessContext,
                        )
                    }
                    item {
                        KeyValueSection(
                            title = "主要信息映射",
                            pairs = result.mappedInfo,
                            emptyText = "没有匹配到可映射的主要信息",
                        )
                    }
                    item {
                        KeyValueSection(
                            title = "完整 EXIF / C2PA",
                            pairs = result.fullInfo,
                            emptyText = "没有解析到 EXIF 或 C2PA 信息",
                        )
                    }
                    item {
                        LinesSection(
                            title = "照片前 200 行 UTF-8 文本",
                            lines = result.previewLines,
                            emptyText = "没有可显示的文本",
                        )
                    }
                }
            } else if (!isLoading) {
                item {
                    HintCard()
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun parseMetadata(context: Context, uri: Uri): MetadataResult {
    return runCatching {
        val sampledBytes = context.contentResolver.openInputStream(uri)?.use {
            readBytesCapped(it, METADATA_SCAN_LIMIT_BYTES)
        } ?: error("无法读取图片内容")
        val previewLines = buildPseudoLines(sampledBytes).take(TEXT_PREVIEW_LINE_COUNT)
        val rawJoinedText = previewLines.joinToString("\n")
        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        val exifPairs = buildExifPairs(exif)
        val c2paPairs = buildC2paPairs(sampledBytes)
        val mappedInfo = buildMappedInfo(exifPairs, c2paPairs)
        val exifDeviceInfo = buildExifDeviceInfo(exifPairs)
        val deviceGuessMatch = guessDevice(rawJoinedText, exifPairs)
        MetadataResult(
            imageUri = uri,
            exifDeviceInfo = exifDeviceInfo,
            deviceGuess = deviceGuessMatch?.guess,
            deviceGuessContext = deviceGuessMatch?.context,
            mappedInfo = mappedInfo,
            fullInfo = (exifPairs + c2paPairs).distinctBy { "${it.first}:${it.second}" },
            previewLines = previewLines,
        )
    }.getOrElse { error ->
        MetadataResult(
            imageUri = uri,
            exifDeviceInfo = null,
            deviceGuess = null,
            deviceGuessContext = null,
            mappedInfo = emptyList(),
            fullInfo = emptyList(),
            previewLines = emptyList(),
            errorMessage = error.message ?: "解析失败",
        )
    }
}

private fun readBytesCapped(
    inputStream: java.io.InputStream,
    maxBytes: Int,
): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
    var totalRead = 0

    while (totalRead < maxBytes) {
        val allowed = minOf(buffer.size, maxBytes - totalRead)
        val count = inputStream.read(buffer, 0, allowed)
        if (count <= 0) break
        output.write(buffer, 0, count)
        totalRead += count
    }

    return output.toByteArray()
}

private fun buildPseudoLines(bytes: ByteArray, lineWidth: Int = 64): List<String> {
    val rawText = bytes.toString(Charsets.UTF_8)
    val normalized = rawText.map { char ->
        when {
            char == '\r' || char == '\n' -> '\n'
            !char.isISOControl() || char == '\t' -> char
            else -> '�'
        }
    }.joinToString("")

    val explicitLines = normalized.split('\n')
    val result = mutableListOf<String>()
    for (line in explicitLines) {
        if (line.isBlank()) {
            if (result.lastOrNull()?.isNotBlank() == true) {
                result += ""
            }
            continue
        }
        line.chunked(lineWidth).forEach { chunk ->
            result += chunk.trimEnd()
        }
    }
    return result.filter { it.isNotEmpty() }
}

private fun buildExifPairs(exif: ExifInterface?): List<Pair<String, String>> {
    if (exif == null) return emptyList()
    val tagValues = linkedSetOf<Pair<String, String>>()
    val fields = ExifInterface::class.java.fields
        .filter { field ->
            field.name.startsWith("TAG_") &&
                field.type == String::class.java &&
                ReflectModifier.isStatic(field.modifiers)
        }
        .sortedBy { it.name }

    for (field in fields) {
        val tag = field.get(null) as? String ?: continue
        val value = exif.getAttribute(tag)?.trim().orEmpty()
        if (value.isNotEmpty()) {
            tagValues += formatTagName(field.name.removePrefix("TAG_")) to value
        }
    }

    val latLong = exif.latLong
    if (latLong != null) {
        tagValues += "Gps Latitude" to latLong[0].toString()
        tagValues += "Gps Longitude" to latLong[1].toString()
    }

    return tagValues.toList()
}

private fun buildC2paPairs(bytes: ByteArray): List<Pair<String, String>> {
    val printableStrings = extractPrintableStrings(bytes)
    val interesting = printableStrings.filter { value ->
        val lower = value.lowercase()
        lower.contains("c2pa") ||
            lower.contains("jumb") ||
            lower.contains("manifest") ||
            lower.contains("assertion") ||
            lower.contains("claim") ||
            lower.contains("xmp")
    }

    return interesting
        .distinct()
        .take(200)
        .mapIndexed { index, value ->
            "C2PA Text ${index + 1}" to value
        }
}

private fun extractPrintableStrings(
    bytes: ByteArray,
    minLength: Int = 4,
): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.length >= minLength) {
            result += current.toString()
        }
        current.clear()
    }

    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        if (value in 32..126) {
            current.append(value.toChar())
        } else {
            flush()
        }
    }
    flush()
    return result
}

private fun guessDevice(
    rawJoinedText: String,
    exifPairs: List<Pair<String, String>>,
): DeviceGuessMatch? {
    val normalized = rawJoinedText.replace('�', '|')
    val brandPattern = DEVICE_BRAND_WHITELIST
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    val pattern = Regex(
        pattern = "(?i)(?<![A-Za-z0-9])($brandPattern)(?:[ _+\\-./()]?[A-Za-z0-9][A-Za-z0-9 _+\\-.,()/]{0,48})?",
    )
    val candidates = mutableListOf<Pair<DeviceGuessMatch, Int>>()

    for (match in pattern.findAll(normalized)) {
        val guess = match.value
            .trim()
            .trim('|')
            .takeIf { it.length in 3..56 } ?: continue
        val contextRadius = 20
        val contextStart = (match.range.first - contextRadius).coerceAtLeast(0)
        val contextEndExclusive = (match.range.last + 1 + contextRadius).coerceAtMost(normalized.length)
        val context = normalized
            .substring(contextStart, contextEndExclusive)
            .replace('\n', ' ')
            .trim()
        val guessLower = guess.lowercase()
        val contextLower = context.lowercase()
        val isInvalidGuess = INVALID_DEVICE_GUESS_MARKERS.any { marker ->
            guessLower.contains(marker) || contextLower.contains(marker)
        }

        if (!isInvalidGuess) {
            val candidate = DeviceGuessMatch(
                guess = guess,
                context = context,
            )
            candidates += candidate to scoreDeviceGuess(candidate)
        }
    }

    return candidates
        .maxByOrNull { (_, score) -> score }
        ?.first
}

private fun scoreDeviceGuess(candidate: DeviceGuessMatch): Int {
    val guess = candidate.guess
    val lowerGuess = guess.lowercase()
    var score = 0

    if (guess.any { it.isDigit() }) score += 40
    if (' ' in guess || '-' in guess || '_' in guess) score += 12
    if (guess.length in 8..24) score += 10
    if (guess.length > 24) score -= 8
    if (guess.count { it.isDigit() } >= 2) score += 8
    if (guess.any { it.isUpperCase() } && guess.any { it.isLowerCase() }) score += 4

    val brand = DEVICE_BRAND_WHITELIST
        .firstOrNull { lowerGuess.startsWith(it) }
        .orEmpty()
    if (brand.isNotBlank() && lowerGuess.length > brand.length) {
        score += 20
    }
    if (brand.isNotBlank() && lowerGuess == brand) {
        score -= 25
    }

    if (lowerGuess.contains("ultra") || lowerGuess.contains("pro") || lowerGuess.contains("plus")) {
        score += 6
    }

    return score
}

private fun buildExifDeviceInfo(exifPairs: List<Pair<String, String>>): String? {
    val make = exifPairs.firstOrNull { it.first == "Make" }?.second?.trim().orEmpty()
    val model = exifPairs.firstOrNull { it.first == "Model" }?.second?.trim().orEmpty()
    return if (make.isNotBlank() && model.isNotBlank()) {
        "$make $model".trim()
    } else {
        null
    }
}

private fun buildMappedInfo(
    exifPairs: List<Pair<String, String>>,
    c2paPairs: List<Pair<String, String>>,
): List<Pair<String, String>> {
    val source = linkedMapOf<String, String>()
    exifPairs.forEach { (key, value) -> source[key] = value }

    val mappings = listOf(
        "设备品牌" to listOf("Make"),
        "设备型号" to listOf("Model"),
        "镜头型号" to listOf("Lens Model"),
        "拍摄时间" to listOf("Date Time Original", "Date Time Digitized", "Date Time"),
        "曝光时间" to listOf("Exposure Time"),
        "光圈" to listOf("F Number", "Aperture Value"),
        "ISO" to listOf("Photographic Sensitivity", "Iso Speed Ratings"),
        "焦距" to listOf("Focal Length"),
        "35mm 等效焦距" to listOf("Focal Length In 35mm Film"),
        "快门速度" to listOf("Shutter Speed Value"),
        "白平衡" to listOf("White Balance"),
        "闪光灯" to listOf("Flash"),
        "测光模式" to listOf("Metering Mode"),
        "曝光模式" to listOf("Exposure Mode"),
        "曝光补偿" to listOf("Exposure Bias Value"),
        "方向" to listOf("Orientation"),
        "宽度" to listOf("Image Width", "Pixel X Dimension"),
        "高度" to listOf("Image Length", "Pixel Y Dimension"),
        "色彩空间" to listOf("Color Space"),
        "GPS 纬度" to listOf("Gps Latitude"),
        "GPS 经度" to listOf("Gps Longitude"),
        "作者" to listOf("Artist"),
        "版权" to listOf("Copyright"),
        "软件" to listOf("Software"),
    )

    val result = mutableListOf<Pair<String, String>>()
    for ((label, candidates) in mappings) {
        val value = candidates.firstNotNullOfOrNull { candidate -> source[candidate] }
        if (!value.isNullOrBlank()) {
            result += label to value
        }
    }

    c2paPairs.firstOrNull()?.let { result += "C2PA 相关文本" to it.second }
    return result
}

private fun formatTagName(raw: String): String {
    return raw
        .lowercase()
        .split('_')
        .joinToString(" ") { part ->
            part.replaceFirstChar { char -> char.uppercase() }
        }
}

private fun createCameraImageUri(context: Context): Uri {
    val directory = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(directory, "photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}

private fun copyText(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制：$label", Toast.LENGTH_SHORT).show()
}

@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("正在解析图片信息…")
        }
    }
}

@Composable
private fun HintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "使用说明",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("1. 首页支持“从相册选择”和“拍照”两个入口。")
            Text("2. 选图后会依次展示图片、设备猜测、主要信息、完整 EXIF/C2PA、前 200 行 UTF-8 文本。")
            Text("3. 每个区块、每一项文本都支持长按复制。")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageSection(imageUri: Uri) {
    val context = LocalContext.current
    CopyableSectionCard(
        title = "图片预览",
        contentToCopy = imageUri.toString(),
    ) {
        AsyncImage(
            model = imageUri,
            contentDescription = "已选择图片",
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        copyText(context, imageUri.toString(), "图片 Uri")
                    },
                ),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.height(10.dp))
        CopyableText(
            label = "图片 Uri",
            value = imageUri.toString(),
        )
    }
}

@Composable
private fun DeviceGuessSection(
    exifDeviceInfo: String?,
    deviceGuess: String?,
    deviceGuessContext: String?,
) {
    CopyableSectionCard(
        title = "设备猜测",
        contentToCopy = listOfNotNull(
            exifDeviceInfo?.let { "EXIF 设备信息: $it" },
            deviceGuess?.let { "文本猜测: $it" },
            deviceGuessContext?.let { "命中上下文: $it" },
        ).joinToString("\n").ifBlank { "未匹配到设备型号" },
    ) {
        if (exifDeviceInfo != null) {
            Text(
                text = "EXIF 设备信息",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = exifDeviceInfo,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = "文本猜测",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = deviceGuess ?: "未匹配到设备型号",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (deviceGuessContext != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "命中上下文（前后各 20 字符）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = deviceGuessContext,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "规则：文本猜测始终基于前 200 行文本和品牌白名单执行；如果 EXIF 的设备品牌与设备型号都存在，也会额外展示在上方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeyValueSection(
    title: String,
    pairs: List<Pair<String, String>>,
    emptyText: String,
) {
    CopyableSectionCard(
        title = title,
        contentToCopy = pairs.joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { emptyText },
    ) {
        if (pairs.isEmpty()) {
            Text(emptyText)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pairs.forEach { (key, value) ->
                    CopyableKeyValueRow(key, value)
                }
            }
        }
    }
}

@Composable
private fun LinesSection(
    title: String,
    lines: List<String>,
    emptyText: String,
) {
    CopyableSectionCard(
        title = title,
        contentToCopy = lines.joinToString("\n").ifBlank { emptyText },
    ) {
        if (lines.isEmpty()) {
            Text(emptyText)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEachIndexed { index, line ->
                    CopyableText(
                        label = "第 ${index + 1} 行",
                        value = line,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyableSectionCard(
    title: String,
    contentToCopy: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    copyText(context, contentToCopy, title)
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyableKeyValueRow(key: String, value: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    copyText(context, "$key: $value", key)
                },
            )
            .padding(12.dp),
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyableText(label: String, value: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    copyText(context, value, label)
                },
            )
            .padding(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
