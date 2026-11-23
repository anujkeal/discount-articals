package com.kealart.bfdiscount

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private val Green = Color(0xFF0A7D3B)
private val Red = Color(0xFFB3261E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        setContent { BFScannerApp() }
    }
}

@Composable
private fun BFScannerApp() {
    val context = LocalContext.current
    var repository by remember { mutableStateOf<DataRepository?>(null) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var searchMode by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<DiscountItem?>(null) }
    var scannedValue by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var articleMode by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(PrefixResult(emptyList(), 0)) }

    LaunchedEffect(Unit) {
        try { repository = DataRepository.load(context) }
        catch (e: Exception) { loadingError = e.message ?: "Unable to load discount data" }
    }
    LaunchedEffect(query, articleMode, repository) {
        repository?.let { matches = it.prefix(query, articleMode) }
    }
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFF75D99B), background = Color(0xFF101110), surface = Color(0xFF1C1E1C))
                 else lightColorScheme(primary = Green, background = Color(0xFFF6F6F4), surface = Color.White, onSurface = Color(0xFF1A1A1A), onSurfaceVariant = Color(0xFF70766F))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                repository == null && loadingError == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                    Text("Loading data…", Modifier.padding(top = 76.dp))
                }
                loadingError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Could not load discounts.json\n$loadingError", color = Red)
                }
                searchMode -> SearchScreen(query, { query = it }, articleMode, { articleMode = it }, matches,
                    onBack = { searchMode = false }, onPick = { latest = it; scannedValue = it.ean; searchMode = false })
                else -> CameraScreen(latest, scannedValue, onResult = { code ->
                    scannedValue = code
                    latest = repository?.exact(code) ?: DiscountItem(DataRepository.normalize(code), "", -1, "Product not found")
                }, onSearch = { searchMode = true })
            }
        }
    }
}

@Composable
private fun CameraScreen(item: DiscountItem?, scannedValue: String, onResult: (String) -> Unit, onSearch: () -> Unit) {
    val context = LocalContext.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val recentScans = remember { mutableStateListOf<String>() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it; denied = !it }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Recent scans", fontWeight = FontWeight.Bold) },
            text = {
                if (recentScans.isEmpty()) Text("Scanned products will appear here.")
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentScans.take(8).forEach { value -> Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } },
            dismissButton = { if (recentScans.isNotEmpty()) TextButton(onClick = { recentScans.clear() }) { Text("Clear") } }
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().height(88.dp).background(Color.Black).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(id = R.drawable.ic_launcher), contentDescription = "BF Discount Scanner logo", modifier = Modifier.size(54.dp))
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(54.dp).background(Color(0xFF555555)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("BF Discount Scanner", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("Black Friday • Offline lookup", color = Color(0xFFB8B8B8), fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { showHistory = true }, modifier = Modifier.size(52.dp).background(Color(0xFF111111), RoundedCornerShape(15.dp))) {
                Icon(Icons.Default.History, contentDescription = "Recent scans", tint = Color.White, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onSearch, modifier = Modifier.size(52.dp).background(Color(0xFFFF101B), RoundedCornerShape(15.dp))) {
                Icon(Icons.Default.Search, contentDescription = "Search products", tint = Color.White, modifier = Modifier.size(29.dp))
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            if (permission) {
                CameraPreview(onBarcode = { raw ->
                    onResult(raw)
                    if (recentScans.firstOrNull() != raw) recentScans.add(0, raw)
                    while (recentScans.size > 20) recentScans.removeAt(recentScans.lastIndex)
                    runCatching {
                        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31)
                            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                        else @Suppress("DEPRECATION") (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator)
                        if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE)) else vibrator.vibrate(65)
                        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).apply { startTone(ToneGenerator.TONE_PROP_BEEP, 70) }
                    }
                }, torchOn, Modifier.fillMaxSize())
                ScanGuide(Modifier.fillMaxSize())
                IconButton(
                    onClick = { torchOn = !torchOn },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                        .size(58.dp).background(Color(0xCC080808), CircleShape)
                ) {
                    Icon(if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff, contentDescription = "Toggle flashlight", tint = Color.White, modifier = Modifier.size(27.dp))
                }
            } else {
                Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Camera access required", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("Allow camera access to scan product barcodes. Scanning works offline.", color = Color(0xFFBFC6C0))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text(if (denied) "Grant camera permission" else "Enable camera") }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp).heightIn(min = 92.dp),
            color = Color(0xFF050505),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD8D8D8))
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(48.dp).background(Color(0xFF555555)))
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(
                        when {
                            item == null -> "Scan a product barcode"
                            item.hasDiscount -> item.discount + " discount found"
                            else -> "No discount"
                        },
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                    Text(
                        when {
                            item == null -> "Get price and discount details."
                            item.name.isNotBlank() && item.hasDiscount -> item.name
                            else -> scannedValue
                        },
                        color = Color(0xFFBDBDBD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier.padding(start = 10.dp).width(76.dp).height(92.dp)
                        .background(Color(0xFFFF101B), RoundedCornerShape(topStart = 30.dp, bottomStart = 42.dp, topEnd = 22.dp, bottomEnd = 22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("%", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun ScanGuide(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(0.18f, 0.82f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "scan-position")
    Canvas(modifier) {
        drawRect(Color.Black.copy(alpha = 0.16f))
        val w = size.width * 0.72f
        val h = size.height * 0.34f
        val l = (size.width - w) / 2f
        val t = (size.height - h) / 2f
        val r = l + w
        val b = t + h
        val c = size.width * 0.075f
        val stroke = 5.dp.toPx()
        val white = Color.White.copy(alpha = 0.96f)
        val corners = listOf(Triple(l,t,1), Triple(r,t,2), Triple(l,b,3), Triple(r,b,4))
        corners.forEach { (x,y,corner) ->
            val sx = if (corner == 1 || corner == 3) 1f else -1f
            val sy = if (corner == 1 || corner == 2) 1f else -1f
            drawLine(white, androidx.compose.ui.geometry.Offset(x,y), androidx.compose.ui.geometry.Offset(x+c*sx,y), stroke, cap = StrokeCap.Round)
            drawLine(white, androidx.compose.ui.geometry.Offset(x,y), androidx.compose.ui.geometry.Offset(x,y+c*sy), stroke, cap = StrokeCap.Round)
        }
        val y = t + h * progress
        drawLine(Color(0xFFFF3B30), androidx.compose.ui.geometry.Offset(l+8.dp.toPx(),y), androidx.compose.ui.geometry.Offset(r-8.dp.toPx(),y), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun CameraPreview(onBarcode: (String) -> Unit, torchOn: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lastScanAt = remember { AtomicLong(0L) }
    val lastValue = remember { mutableStateOf("") }
    val cameraState = remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(torchOn) { cameraState.value?.cameraControl?.enableTorch(torchOn) }
    AndroidView(modifier = modifier, factory = { ctx ->
        val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128
            ).build()
            val scanner = BarcodeScanning.getClient(options)
            val executor = Executors.newSingleThreadExecutor()
            val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val value = codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                        if (value != null) {
                            val now = System.currentTimeMillis()
                            if (value != lastValue.value || now - lastScanAt.get() > 1500L) {
                                lastValue.value = value
                                lastScanAt.set(now)
                                view.post { onBarcode(value) }
                            }
                        }
                    }.addOnCompleteListener { proxy.close() }
            }
            try { provider.unbindAll(); val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis); cameraState.value = camera; camera.cameraControl.enableTorch(torchOn) }
            catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
        view
    })
}

@Composable
private fun ResultCard(item: DiscountItem?, scannedValue: String, modifier: Modifier = Modifier) {
    val found = item?.hasDiscount == true
    val accent = if (item == null) MaterialTheme.colorScheme.primary else if (found) Green else Red
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.Center) {
                if (item == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
                        Text("Point the camera at a barcode", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    }
                    Text("Your discount will appear here", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 42.dp, top = 6.dp))
                } else if (found) {
                    Text(item.discount, fontSize = 54.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold, color = Green, maxLines = 1)
                    Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text("EAN  " + item.ean, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.article.isNotBlank()) Text("ARTICLE  " + item.article, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("No discount", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Red)
                    Text("EAN  " + (scannedValue.ifBlank { item.ean }), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    query: String, onQuery: (String) -> Unit, articleMode: Boolean, onMode: (Boolean) -> Unit,
    result: PrefixResult, onBack: () -> Unit, onPick: (DiscountItem) -> Unit
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Product search", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = { onQuery(it.filter(Char::isDigit)) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter first 3+ digits") }, singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } },
            shape = RoundedCornerShape(18.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("EAN" to false, "Article" to true).forEach { (label, article) ->
                val selected = articleMode == article
                Surface(onClick = { onMode(article) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp),
                    color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, shadowElevation = if (selected) 1.dp else 0.dp) {
                    Box(Modifier.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (query.length < 3) Text("Type at least 3 digits to search", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        else Text("${result.total} items start with " + query, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(result.items, key = { it.ean + "-" + it.article + "-" + it.name }) { item -> SearchResultRow(item) { onPick(item) } }
        }
    }
}

@Composable
private fun SearchResultRow(item: DiscountItem, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = if (item.hasDiscount) Green.copy(alpha = 0.11f) else Red.copy(alpha = 0.10f)) {
                Text(item.discount, Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    color = if (item.hasDiscount) Green else Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("EAN  " + item.ean, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("ARTICLE  " + item.article, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
