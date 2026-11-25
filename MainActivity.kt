package com.example.focussetter

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CameraBleScreen()

            }
        }
    }
}

@Composable
private fun CameraBleScreen() {
    val context = LocalContext.current

    val adapter = remember {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter
    }

    val requiredPerms = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()
    }

    var permsGranted by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        permsGranted = requiredPerms.all { res[it] == true }
    }

    LaunchedEffect(Unit) {
        permsGranted = requiredPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permsGranted) permLauncher.launch(requiredPerms)
    }

    if (!permsGranted) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Brak wymaganych uprawnień (kamera/BLE).", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { permLauncher.launch(requiredPerms) }) { Text("Nadaj uprawnienia") }
        }
        return
    }


    val scanner = remember { BleScanner(context, adapter) }
    val uart = remember { BleUartClient(context = context) }

    val devices by scanner.devices.collectAsState()
    val bleState by uart.state.collectAsState()
    val bleLog by uart.log.collectAsState()



    var sharpnessRaw by remember { mutableStateOf(0.0) }
    var sharpnessEma by remember { mutableStateOf(0.0) }
    val alpha = 0.2 // 0.1–0.3
    val scope = rememberCoroutineScope()
    val af = remember { AutoFocusController(uart, scope) { sharpnessEma } }
    val afState by af.state.collectAsState()



    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }


    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {


        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {

            CameraPreviewWithTenengrad(
                analysisExecutor = analysisExecutor,
                onSharpness = { v ->
                    sharpnessRaw = v
                    sharpnessEma =
                        if (sharpnessEma == 0.0) v
                        else alpha * v + (1 - alpha) * sharpnessEma
                }
            )

            Surface(
                tonalElevation = 6.dp,
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopStart)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Tenengrad", style = MaterialTheme.typography.labelLarge)
                    Text("%.2f".format(sharpnessEma), style = MaterialTheme.typography.titleMedium)

                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scanner.start() },
                    enabled = BlePerms.hasScan(context)
                ) { Text("Scan") }

                OutlinedButton(onClick = { scanner.stop() }) { Text("Stop") }
                OutlinedButton(onClick = { uart.disconnect() }) { Text("Disconnect") }
            }

            Spacer(Modifier.height(8.dp))
            Text("BLE: $bleState")

            Spacer(Modifier.height(8.dp))
            val isConnected = bleState is BleUartClient.ConnState.Connected

            if (!isConnected) {
                Text("Devices", style = MaterialTheme.typography.titleMedium)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp, max = 160.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(devices, key = { it.address }) { d ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    try {
                                        val dev = adapter.getRemoteDevice(d.address)
                                        uart.connect(dev)
                                    } catch (_: SecurityException) {
                                    }
                                }
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(d.name ?: "(no name)")
                                Text(d.address, style = MaterialTheme.typography.bodySmall)
                                Text("RSSI: ${d.rssi}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                when (val st = bleState) {
                    is BleUartClient.ConnState.Connected -> {
                        LaunchedEffect(st.address) { scanner.stop() }

                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Connected", style = MaterialTheme.typography.titleMedium)
                                    Text(st.address, style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { uart.disconnect() }) { Text("Disconnect") }
                            }
                        }
                    }

                    else -> {

                    }
                }
            }



            Spacer(Modifier.height(8.dp))


            BleControlPanel(
                enabled = bleState is BleUartClient.ConnState.Connected,
                onSend = { uart.sendCommand(it) }
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = bleState is BleUartClient.ConnState.Connected,
                    onClick = { af.start() }
                ) { Text("AutoFocus") }

                OutlinedButton(
                    onClick = { af.stop() }
                ) { Text("Stop AF") }
            }

            Text("AF: $afState", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))
            Text("Log", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(bleLog.takeLast(200)) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

        }

    }

}

@Composable
private fun CameraPreviewWithTenengrad(

    analysisExecutor: java.util.concurrent.Executor,
    onSharpness: (Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current



    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor, TenengradAnalyzer(onSharpness))
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (_: Throwable) {

                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@Composable
private fun BleControlPanel(
    enabled: Boolean,
    onSend: (String) -> Unit
) {
    var steps by remember { mutableStateOf("1000") }
    var spd by remember { mutableStateOf("800") }


    val smallButtonModifier = Modifier.height(34.dp)
    val smallContentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                enabled = enabled,
                onClick = { onSend("PING") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("PING", fontSize = 12.sp) }

            OutlinedButton(
                enabled = enabled,
                onClick = { onSend("POS?") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("POS?", fontSize = 12.sp) }

            OutlinedButton(
                enabled = enabled,
                onClick = { onSend("ZERO") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("ZERO", fontSize = 12.sp) }

            OutlinedButton(
                enabled = enabled,
                onClick = { onSend("STOP") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("STOP", fontSize = 12.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = spd,
                onValueChange = { spd = it.filter(Char::isDigit) },
                label = { Text("SPD (us)", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
            Button(
                enabled = enabled,
                onClick = { onSend("SPD $spd") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("Set", fontSize = 12.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = steps,
                onValueChange = { steps = it.filter(Char::isDigit) },
                label = { Text("Steps", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )

            Button(
                enabled = enabled,
                onClick = { onSend("IN $steps") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("IN", fontSize = 12.sp) }

            Button(
                enabled = enabled,
                onClick = { onSend("OUT $steps") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("OUT", fontSize = 12.sp) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                enabled = enabled,
                onClick = { onSend("EN 1") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("Hold ON", fontSize = 12.sp) }

            OutlinedButton(
                enabled = enabled,
                onClick = { onSend("EN 0") },
                modifier = smallButtonModifier,
                contentPadding = smallContentPadding
            ) { Text("Hold OFF", fontSize = 12.sp) }
        }
    }
}

