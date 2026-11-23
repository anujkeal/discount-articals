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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val Green = Color(0xFF148447)
private val Red = Color(0xFFBA2525)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
                 else lightColorScheme(primary = Color(0xFF176B3A), background = Color(0xFFF6F7F5), surface = Color.White)
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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it; denied = !it
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("BF DISCOUNT SCANNER", fontSize = 19.sp, fontWeight = FontWeight.Black)
                Text("Black Friday • Offline lookup", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onSearch) { Text("Search") }
        }
        if (permission) {
            CameraPreview(onBarcode = { raw ->
                onResult(raw)
                runCatching {
                    val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31)
                        (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    else @Suppress("DEPRECATION") (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator)
                    if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE))
                    else vibrator.vibrate(65)
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).apply {
                        startTone(ToneGenerator.TONE_PROP_BEEP, 70)
                    }
                }
            }, Modifier.fillMaxWidth().weight(1f))
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Camera access required", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Allow camera access to scan product barcodes. Scanning works offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text(if (denied) "Grant camera permission" else "Enable camera") }
                }
            }
        }
        if (item != null) ResultCard(item, scannedValue, Modifier.fillMaxWidth())
        else Box(Modifier.fillMaxWidth().height(130.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Text("Point the camera at a product barcode", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CameraPreview(onBarcode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lastScanAt = remember { AtomicLong(0L) }
    val lastValue = remember { mutableStateOf("") }
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
            try { provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
        view
    })
}

@Composable
private fun ResultCard(item: DiscountItem, scannedValue: String, modifier: Modifier = Modifier) {
    val found = item.hasDiscount
    Column(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (found) item.discount else "NO DISCOUNT", fontSize = if (found) 42.sp else 30.sp, fontWeight = FontWeight.Black, color = if (found) Green else Red)
        if (item.name.isNotBlank()) Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text("EAN  ${item.ean.ifBlank { scannedValue }}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.article.isNotBlank()) Text("ARTICLE  ${item.article}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchScreen(
    query: String, onQuery: (String) -> Unit, articleMode: Boolean, onMode: (Boolean) -> Unit,
    result: PrefixResult, onBack: () -> Unit, onPick: (DiscountItem) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Product search", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Camera") }
        }
        OutlinedTextField(value = query, onValueChange = { onQuery(it.filter(Char::isDigit)) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter first 3+ digits") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !articleMode, onClick = { onMode(false) }, label = { Text("EAN") })
            FilterChip(selected = articleMode, onClick = { onMode(true) }, label = { Text("Article") })
        }
        if (query.length < 3) Text("Type at least 3 digits to search.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        else Text("${result.total} items start with ${query}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(result.items, key = { "${it.ean}-${it.article}-${it.name}" }) { item ->
                Card(onClick = { onPick(item) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.discount, color = if (item.hasDiscount) Green else Red, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(item.name, fontWeight = FontWeight.SemiBold)
                        Text("EAN: ${item.ean}   •   Article: ${item.article}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
