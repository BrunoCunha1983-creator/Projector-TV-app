package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.model.AppConfig
import com.example.data.model.MediaHistory
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TvDashboardScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onManualPlayPause: () -> Unit = {},
    onManualStop: () -> Unit = {}
) {
    val playerState by viewModel.playerState.collectAsState()
    val mediaTitle by viewModel.mediaTitle.collectAsState()
    val mediaSubtitle by viewModel.mediaSubtitle.collectAsState()
    val activeMediaUrl by viewModel.activeMediaUrl.collectAsState()
    val mediaDuration by viewModel.mediaDuration.collectAsState()
    val mediaPosition by viewModel.mediaPosition.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val mqttConnected by viewModel.mqttConnected.collectAsState()
    val mqttError by viewModel.mqttError.collectAsState()
    val haConnected by viewModel.haConnected.collectAsState()
    val haError by viewModel.haError.collectAsState()
    val uuid = viewModel.deviceUuid

    val configState = viewModel.appConfigFlow.collectAsState(initial = AppConfig())
    val config = configState.value ?: AppConfig()

    // -------------------------------------------------------------
    // Dynamic Shadow Colors for Projector UI Mode
    // -------------------------------------------------------------
    val isProjector = config.projectorUiMode
    val SleekBackground = if (isProjector) Color.Black else Color(0xFF1A1C1E)
    val SleekSurface = if (isProjector) Color(0xFF141414) else Color(0xFF2D3135)
    val SleekPanel = if (isProjector) Color(0xFF2D3033) else Color(0xFF42474E)
    val SleekText = if (isProjector) Color.White else Color(0xFFE2E2E6)
    val SleekTextSecondary = if (isProjector) Color(0xFFE5E5E5) else Color(0xFFC2C7CF)
    val SleekAccent = if (isProjector) Color(0xFFFBBF24) else Color(0xFF93C5FD) // Warm Amber-Yellow for wall projections
    val SleekAccentDark = if (isProjector) Color(0xFFFBBF24) else Color(0xFF60A5FA)
    val SleekTextStatusGreen = if (isProjector) Color(0xFF10B981) else Color(0xFF34D399)
    // -------------------------------------------------------------

    val historyState = viewModel.mediaHistoryFlow.collectAsState(initial = emptyList())
    val logs = historyState.value

    var showSettings by remember { mutableStateOf(false) }

    // Real-time time display for ambient dashboard background
    var currentTimeString by remember { mutableStateOf("") }
    var currentDateString by remember { mutableStateOf("") }

    // --- Tab-based Navigation States ---
    var activeTab by remember { mutableStateOf(0) } // 0=Geral, 1=Status, 2=ADB, 3=WoL, 4=Home Assistant, 5=Características
    var adbActive by remember { mutableStateOf(false) }
    var ramUsage by remember { mutableStateOf("") }
    var freeStorage by remember { mutableStateOf("") }
    var uptimeString by remember { mutableStateOf("") }
    val launchTime = remember { System.currentTimeMillis() }

    // Wake-On-LAN states
    var wolNameInput by remember { mutableStateOf("") }
    var wolMacInput by remember { mutableStateOf("") }
    var wolPresets by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var wolStatusMsg by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Direct characteristic edit settings states
    var settingsName by remember(config.deviceName) { mutableStateOf(config.deviceName) }
    var settingsPort by remember(config.serverPort) { mutableStateOf(config.serverPort.toString()) }
    var settingsMqttEnabled by remember(config.mqttEnabled) { mutableStateOf(config.mqttEnabled) }
    var settingsMqttBroker by remember(config.mqttBroker) { mutableStateOf(config.mqttBroker) }
    var settingsMqttPort by remember(config.mqttPort) { mutableStateOf(config.mqttPort.toString()) }
    var settingsMqttUser by remember(config.mqttUser) { mutableStateOf(config.mqttUser) }
    var settingsMqttPass by remember(config.mqttPassword) { mutableStateOf(config.mqttPassword) }
    var settingsHaEnabled by remember(config.haEnabled) { mutableStateOf(config.haEnabled) }
    var settingsHaAddress by remember(config.haAddress) { mutableStateOf(config.haAddress) }
    var settingsHaToken by remember(config.haToken) { mutableStateOf(config.haToken) }
    var settingsProjectorMode by remember(config.projectorUiMode) { mutableStateOf(config.projectorUiMode) }
    var settingsStatusMsg by remember { mutableStateOf("") }

    // Load Wake-on-LAN presets
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("tv_media_player_prefs", android.content.Context.MODE_PRIVATE)
        val saved = prefs.getString("wol_presets", "") ?: ""
        if (saved.isNotEmpty()) {
            val list = saved.split("||").mapNotNull {
                val parts = it.split("|")
                if (parts.size == 2) Pair(parts[0], parts[1]) else null
            }
            wolPresets = list
        } else {
            val defaults = listOf(
                "Servidor de Mídia" to "E8:40:F2:1A:BC:90",
                "PC de Jogos" to "D8:BB:C1:2F:3D:4E"
            )
            wolPresets = defaults
        }
    }

    // Periodic diagnostics check for UI stats
    LaunchedEffect(activeTab) {
        if (activeTab == 1 || activeTab == 2) {
            adbActive = checkAdbPortActive()
            
            // Memory stats
            val rt = Runtime.getRuntime()
            val total = rt.totalMemory() / (1024 * 1024)
            val free = rt.freeMemory() / (1024 * 1024)
            val max = rt.maxMemory() / (1024 * 1024)
            val used = total - free
            ramUsage = "${used}MB / ${max}MB"
            
            // Storage stats
            try {
                val path = android.os.Environment.getDataDirectory()
                val freeSpace = path.freeSpace / (1024 * 1024 * 1024)
                val totalSpace = path.totalSpace / (1024 * 1024 * 1024)
                freeStorage = "${freeSpace}GB livres de ${totalSpace}GB"
            } catch (e: Exception) {
                freeStorage = "N/A"
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshIp()
        while (true) {
            val cal = Calendar.getInstance()
            currentTimeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(cal.time)
            currentDateString = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(cal.time)
            kotlinx.coroutines.delay(1000)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(SleekSurface, SleekBackground),
                    center = androidx.compose.ui.geometry.Offset(900f, 500f),
                    radius = 1200f
                )
            )
    ) {
        // --- 1. Background Ambient Dashboard screen ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Info block
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(SleekPanel, RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "TV",
                            color = SleekAccent,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column {
                        Text(
                            text = config.deviceName,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekText,
                            letterSpacing = (-0.5).sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            // MQTT Status Indicator
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (config.mqttEnabled) {
                                                if (mqttConnected) SleekTextStatusGreen else Color(0xFFEF4444)
                                            } else Color.Gray,
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                                Text(
                                    text = "MQTT: ${if (config.mqttEnabled) (if (mqttConnected) "ON" else "ERR") else "OFF"}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (config.mqttEnabled && mqttConnected) SleekTextStatusGreen else SleekTextSecondary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            // HA WS Status Indicator
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (config.haEnabled) {
                                                if (haConnected) SleekTextStatusGreen else Color(0xFFEF4444)
                                            } else Color.Gray,
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                                Text(
                                    text = "HA WS: ${if (config.haEnabled) (if (haConnected) "ON" else "ERR") else "OFF"}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (config.haEnabled && haConnected) SleekTextStatusGreen else SleekTextSecondary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }

                // Clock block
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = currentTimeString,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = SleekText
                    )
                    Text(
                        text = "IP: $localIp",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = SleekTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Divider(color = SleekPanel, thickness = 1.dp)

            // --- Modern, D-pad navigable Top Tab Bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvTabItem(text = "Geral", icon = Icons.Default.Home, selected = activeTab == 0, onClick = { activeTab = 0 })
                TvTabItem(text = "Status do Sistema", icon = Icons.Default.Info, selected = activeTab == 1, onClick = { activeTab = 1 })
                TvTabItem(text = "Controle ADB", icon = Icons.Default.Build, selected = activeTab == 2, onClick = { activeTab = 2 })
                TvTabItem(text = "Wake-on-LAN", icon = Icons.Default.PlayArrow, selected = activeTab == 3, onClick = { activeTab = 3 })
                TvTabItem(text = "Home Assistant", icon = Icons.Default.Share, selected = activeTab == 4, onClick = { activeTab = 4 })
                TvTabItem(text = "Características", icon = Icons.Default.Settings, selected = activeTab == 5, onClick = { activeTab = 5 })
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic Tab Content Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    0 -> {
                        // Split Layout
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Left Column: TV Controller Options / HA instructions (Weight 1.3)
                            Column(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "INTEGRAÇÃO DO HOME ASSISTANT",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    letterSpacing = 1.sp
                                )

                                // Card of details
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                    border = BorderStroke(1.dp, SleekPanel),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "Auto-Descoberta MQTT Integrada",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekText
                                        )
                                        Text(
                                            text = "Ao ativar o MQTT nas configurações, o Home Assistant criará automaticamente um media_player para esta TV!",
                                            fontSize = 13.sp,
                                            color = SleekTextSecondary,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                        Text(
                                            text = "Entidade HA: media_player.tv_media_player_$uuid",
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = SleekTextStatusGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Card of alternative HTTP restful commands
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SleekBackground),
                                    border = BorderStroke(1.dp, SleekPanel),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Configuração Alternativa REST (Para YAML / Scripts):",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekTextSecondary,
                                            letterSpacing = 0.5.sp
                                        )
                                        Text(
                                            text = "service: rest_command.play_media\nurl: http://$localIp:${config.serverPort}/api/play_url?url=VIDEO_URL",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = SleekText,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                        Text(
                                            text = "service: rest_command.say_tv\nurl: http://$localIp:${config.serverPort}/api/say?text=Ola+Casa",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = SleekText
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                // Buttons Row (Remote/Settings) - Fully focusable for TV D-Pad!
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TvButton(
                                        text = "Configurações Rápidas",
                                        icon = Icons.Default.Settings,
                                        onClick = { showSettings = true }
                                    )

                                    TvButton(
                                        text = "Limpar Histórico",
                                        icon = Icons.Default.Delete,
                                        color = Color(0xFFEF4444),
                                        onClick = { viewModel.clearLogs() }
                                    )
                                }
                            }

                            // Right Column: Active logs / Played list (Weight 1)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "HISTÓRICO DE REPRODUÇÃO",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    letterSpacing = 1.sp
                                )

                                if (logs.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(SleekBackground, RoundedCornerShape(20.dp))
                                            .border(1.dp, SleekPanel, RoundedCornerShape(20.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                Icons.Default.Info,
                                                contentDescription = "Empty",
                                                tint = SleekTextSecondary,
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = "Nenhum evento registrado",
                                                fontSize = 14.sp,
                                                color = SleekTextSecondary
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(SleekBackground, RoundedCornerShape(20.dp))
                                            .border(1.dp, SleekPanel, RoundedCornerShape(20.dp))
                                            .padding(12.dp)
                                    ) {
                                        items(logs) { log ->
                                            HistoryRowItem(log)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        // Tab 1: Status Page
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Left Column: Connection Statuses
                            Column(
                                modifier = Modifier
                                    .weight(1.2f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "SERVIÇOS EM EXECUÇÃO",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    letterSpacing = 1.sp
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                    border = BorderStroke(1.dp, SleekPanel),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        // REST API
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.Share, contentDescription = null, tint = SleekAccent, modifier = Modifier.size(18.dp))
                                                Text("Servidor HTTP REST", color = SleekText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(SleekTextStatusGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("PORTA ${config.serverPort} OK", color = SleekTextStatusGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Divider(color = SleekPanel)

                                        // MQTT
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.Notifications, contentDescription = null, tint = SleekAccent, modifier = Modifier.size(18.dp))
                                                Text("Cliente MQTT Bridge", color = SleekText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            }
                                            val mqttStatusColor = if (config.mqttEnabled && mqttConnected) SleekTextStatusGreen else Color(0xFFEF4444)
                                            val mqttStatusLabel = if (config.mqttEnabled) {
                                                if (mqttConnected) "ATIVADO" else "ERRO CONEXÃO"
                                            } else "DESATIVADO"
                                            Box(
                                                modifier = Modifier
                                                    .background(mqttStatusColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(mqttStatusLabel, color = mqttStatusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Divider(color = SleekPanel)

                                        // HA WebSockets
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.Refresh, contentDescription = null, tint = SleekAccent, modifier = Modifier.size(18.dp))
                                                Text("Home Assistant WS", color = SleekText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            }
                                            val haStatusColor = if (config.haEnabled && haConnected) SleekTextStatusGreen else Color(0xFFEF4444)
                                            val haStatusLabel = if (config.haEnabled) {
                                                if (haConnected) "ATIVADO" else "ERRO CONEXÃO"
                                            } else "DESATIVADO"
                                            Box(
                                                modifier = Modifier
                                                    .background(haStatusColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(haStatusLabel, color = haStatusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Divider(color = SleekPanel)

                                        // ADB Port
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.Build, contentDescription = null, tint = SleekAccent, modifier = Modifier.size(18.dp))
                                                Text("Depuração de Rede (ADB)", color = SleekText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            }
                                            val adbStatusColor = if (adbActive) SleekTextStatusGreen else Color.Gray
                                            val adbStatusLabel = if (adbActive) "ATIVO (PORTA 5555)" else "PORTA 5555 FECHADA"
                                            Box(
                                                modifier = Modifier
                                                    .background(adbStatusColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(adbStatusLabel, color = adbStatusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            // Right Column: Hardware Metrics
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "HARDWARE E RECURSOS",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    letterSpacing = 1.sp
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SleekBackground),
                                    border = BorderStroke(1.dp, SleekPanel),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Memória JVM do App", color = SleekTextSecondary, fontSize = 13.sp)
                                            Text(ramUsage, color = SleekText, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                        }

                                        Divider(color = SleekPanel)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Espaço em Disco (TV)", color = SleekTextSecondary, fontSize = 13.sp)
                                            Text(freeStorage, color = SleekText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }

                                        Divider(color = SleekPanel)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Versão do Android", color = SleekTextSecondary, fontSize = 13.sp)
                                            Text("Android SDK ${android.os.Build.VERSION.SDK_INT}", color = SleekAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }

                                        Divider(color = SleekPanel)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Fabricante / Modelo", color = SleekTextSecondary, fontSize = 13.sp)
                                            Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", color = SleekText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }

                                        Divider(color = SleekPanel)

                                        val durationSec = (System.currentTimeMillis() - launchTime) / 1000
                                        val hours = durationSec / 3600
                                        val mins = (durationSec % 3600) / 60
                                        val secs = durationSec % 60
                                        uptimeString = "${hours}h ${mins}m ${secs}s"
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Tempo de Atividade App", color = SleekTextSecondary, fontSize = 13.sp)
                                            Text(uptimeString, color = SleekTextStatusGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        // Tab 2: ADB Control Page
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Left: ADB Diagnostics
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "STATUS DE DEBULAÇÃO ADB",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    letterSpacing = 1.sp
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                    border = BorderStroke(1.dp, SleekPanel),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(if (adbActive) SleekTextStatusGreen else Color.Gray, RoundedCornerShape(5.dp))
                                            )
                                            Text(
                                                text = if (adbActive) "Porta TCP 5555: Aberta" else "Porta TCP 5555: Fechada",
                                                color = SleekText,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                        }

                                        Text(
                                            text = if (adbActive) {
                                                "A depuração sem fio está ATIVA e escutando conexões na porta tradicional 5555. O Home Assistant se conectará sem falhas!"
                                            } else {
                                                "A porta 5555 está inativa ou bloqueada. Para habilitar o controle ADB, vá nas Opções do Desenvolvedor do Projetor/TV e ative a 'Depuração USB' e 'Depuração sem fio'."
                                            },
                                            color = SleekTextSecondary,
                                            fontSize = 12.sp
                                        )

                                        Divider(color = SleekPanel)

                                        Text(
                                            text = "Como Ativar o ADB no Projetor/TV:",
                                            fontWeight = FontWeight.Bold,
                                            color = SleekText,
                                            fontSize = 13.sp
                                        )

                                        Text(
                                            text = "1. Configurações -> Sobre -> Clique 7 vezes em 'Número da Versão'.\n" +
                                                   "2. Sistema -> Opções do Desenvolvedor.\n" +
                                                   "3. Ative 'Depuração USB' ou 'Wireless Debugging'.",
                                            color = SleekTextSecondary,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }

                            // Right: YAML Guide
                            Column(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "YAML INTEGRAÇÃO HOME ASSISTANT",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    letterSpacing = 1.sp
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SleekBackground),
                                    border = BorderStroke(1.dp, SleekPanel),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "Adicione esta configuração no seu configuration.yaml para controlar nativamente o player via ADB:",
                                            color = SleekTextSecondary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        Text(
                                            text = "media_player:\n" +
                                                   "  - platform: androidtv\n" +
                                                   "    name: \"Projetor TV ADB\"\n" +
                                                   "    host: $localIp\n" +
                                                   "    adb_server_ip: 127.0.0.1\n" +
                                                   "    state_detection_rules:\n" +
                                                   "      - 'com.example': 'playing'",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = SleekAccent,
                                            lineHeight = 15.sp,
                                            modifier = Modifier
                                                .background(SleekSurface, RoundedCornerShape(8.dp))
                                                .padding(12.dp)
                                                .fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = "Dica: O ADB permite ligar, desligar, ajustar o brilho do projetor, e simular pressionamentos de teclas por completo!",
                                            color = SleekTextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        // Tab 3: Wake-on-LAN client
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Left Column: Save custom WoL presets form
                            Column(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "ADICIONAR MÁQUINA LAN",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    letterSpacing = 1.sp
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                    border = BorderStroke(1.dp, SleekPanel),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = wolNameInput,
                                            onValueChange = { wolNameInput = it },
                                            label = { Text("Nome da Máquina (Ex: Servidor Plex)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = SleekText,
                                                unfocusedTextColor = SleekTextSecondary,
                                                focusedBorderColor = SleekAccent,
                                                unfocusedBorderColor = SleekPanel,
                                                focusedLabelColor = SleekAccent,
                                                unfocusedLabelColor = SleekTextSecondary
                                            )
                                        )

                                        OutlinedTextField(
                                            value = wolMacInput,
                                            onValueChange = { wolMacInput = it },
                                            label = { Text("Endereço MAC (Ex: 00:11:22:AA:BB:CC)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = SleekText,
                                                unfocusedTextColor = SleekTextSecondary,
                                                focusedBorderColor = SleekAccent,
                                                unfocusedBorderColor = SleekPanel,
                                                focusedLabelColor = SleekAccent,
                                                unfocusedLabelColor = SleekTextSecondary
                                            )
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        TvButton(
                                            text = "Salvar Máquina",
                                            icon = Icons.Default.Add,
                                            onClick = {
                                                val cleanName = wolNameInput.trim()
                                                val cleanMac = wolMacInput.trim().uppercase()
                                                val macPattern = "^([0-9A-FA-f]{2}[:-]){5}([0-9A-FA-f]{2})\$".toRegex()
                                                val cleanMacSimple = cleanMac.replace(":", "").replace("-", "")
                                                if (cleanName.isNotEmpty() && (macPattern.matches(cleanMac) || cleanMacSimple.length == 12)) {
                                                    val finalMac = if (cleanMacSimple.length == 12) {
                                                        cleanMacSimple.chunked(2).joinToString(":")
                                                    } else cleanMac

                                                    val updatedList = wolPresets + Pair(cleanName, finalMac)
                                                    wolPresets = updatedList
                                                    val serialized = updatedList.joinToString("||") { "${it.first}|${it.second}" }
                                                    val prefs = context.getSharedPreferences("tv_media_player_prefs", android.content.Context.MODE_PRIVATE)
                                                    prefs.edit().putString("wol_presets", serialized).apply()

                                                    wolNameInput = ""
                                                    wolMacInput = ""
                                                    wolStatusMsg = "Máquina '$cleanName' adicionada!"
                                                } else {
                                                    wolStatusMsg = "Erro: Nome vazio ou MAC inválido!"
                                                }
                                            }
                                        )

                                        if (wolStatusMsg.isNotEmpty()) {
                                            Text(
                                                text = wolStatusMsg,
                                                color = if (wolStatusMsg.startsWith("Erro")) Color(0xFFEF4444) else SleekTextStatusGreen,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Right Column: Preset List of Devices to send WoL to
                            Column(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "MÁQUINAS SALVAS NAS REDES",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    letterSpacing = 1.sp
                                )

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(SleekBackground, RoundedCornerShape(20.dp))
                                        .border(1.dp, SleekPanel, RoundedCornerShape(20.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(wolPresets) { target ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                            border = BorderStroke(1.dp, SleekPanel),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text(text = target.first, color = SleekText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    Text(text = target.second, color = SleekTextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                                }

                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val scope = rememberCoroutineScope()
                                                    TvButton(
                                                        text = "Acordar",
                                                        icon = Icons.Default.PlayArrow,
                                                        onClick = {
                                                            scope.launch {
                                                                val cleanMacBytesStr = target.second.replace(":", "").replace("-", "").trim()
                                                                try {
                                                                    val macBytes = ByteArray(6)
                                                                    for (i in 0..5) {
                                                                        macBytes[i] = cleanMacBytesStr.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                                                                    }
                                                                    val bytes = ByteArray(6 + 16 * 6)
                                                                    for (i in 0..5) {
                                                                        bytes[i] = 0xff.toByte()
                                                                    }
                                                                    for (i in 6 until bytes.size step 6) {
                                                                        System.arraycopy(macBytes, 0, bytes, i, 6)
                                                                    }
                                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                                        val address = java.net.InetAddress.getByName("255.255.255.255")
                                                                        val packet = java.net.DatagramPacket(bytes, bytes.size, address, 9)
                                                                        val socket = java.net.DatagramSocket()
                                                                        socket.broadcast = true
                                                                        socket.send(packet)
                                                                        socket.close()
                                                                    }
                                                                    wolStatusMsg = "Disparado WoL para ${target.first}!"
                                                                } catch (e: Exception) {
                                                                    wolStatusMsg = "Erro WoL: ${e.message}"
                                                                }
                                                            }
                                                        }
                                                    )

                                                    IconButton(
                                                        onClick = {
                                                            val updated = wolPresets.filterNot { it.first == target.first && it.second == target.second }
                                                            wolPresets = updated
                                                            val serialized = updated.joinToString("||") { "${it.first}|${it.second}" }
                                                            val prefs = context.getSharedPreferences("tv_media_player_prefs", android.content.Context.MODE_PRIVATE)
                                                            prefs.edit().putString("wol_presets", serialized).apply()
                                                            wolStatusMsg = "Máquina removida com sucesso."
                                                        }
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Remover", tint = Color(0xFFEF4444))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    4 -> {
                        // Tab 4: Beautiful, dedicated Home Assistant Configuration Screen
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Column 1: Config Form & Real-time Info (Weight: 1.2)
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1.2f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                item {
                                    Text(
                                        text = "CONEXÃO COM O HOME ASSISTANT",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekTextSecondary,
                                        letterSpacing = 1.sp
                                    )
                                }

                                // Connection Status badge
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                        border = BorderStroke(1.dp, SleekPanel),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(
                                                        color = if (settingsHaEnabled && haConnected) SleekTextStatusGreen else Color(0xFFEF4444),
                                                        shape = androidx.compose.foundation.shape.CircleShape
                                                    )
                                            )
                                            Column {
                                                Text(
                                                    text = if (settingsHaEnabled) {
                                                        if (haConnected) "Status de Conexão: Conectado via WS" else "Status de Conexão: Tentando conectar..."
                                                    } else {
                                                        "Status de Conexão: Integração Desativada"
                                                    },
                                                    color = SleekText,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                if (settingsHaEnabled && haError != null) {
                                                    Text(
                                                        text = "Erro: $haError",
                                                        color = Color(0xFFEF4444),
                                                        fontSize = 11.sp
                                                    )
                                                } else if (settingsHaEnabled && haConnected) {
                                                    Text(
                                                        text = "Conectado ao endereço: $settingsHaAddress",
                                                        color = SleekTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Enable Switch Row
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(SleekSurface, RoundedCornerShape(12.dp))
                                            .border(1.dp, SleekPanel, RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Ativar Conectividade WebSocket",
                                                color = SleekText,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                "Ativa sincronização de estado em tempo real",
                                                color = SleekTextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Switch(
                                            checked = settingsHaEnabled,
                                            onCheckedChange = { settingsHaEnabled = it },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = SleekAccent,
                                                checkedTrackColor = SleekPanel,
                                                uncheckedThumbColor = SleekTextSecondary,
                                                uncheckedTrackColor = SleekSurface
                                            )
                                        )
                                    }
                                }

                                if (settingsHaEnabled) {
                                    item {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                            border = BorderStroke(1.dp, SleekPanel),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                OutlinedTextField(
                                                    value = settingsHaAddress,
                                                    onValueChange = { settingsHaAddress = it },
                                                    label = { Text("Endereço IP / Hostname do Home Assistant (Ex: 192.168.1.100:8123)") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    placeholder = { Text("192.168.1.100:8123") },
                                                    singleLine = true,
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = SleekText,
                                                        unfocusedTextColor = SleekTextSecondary,
                                                        focusedBorderColor = SleekAccent,
                                                        unfocusedBorderColor = SleekPanel,
                                                        focusedLabelColor = SleekAccent,
                                                        unfocusedLabelColor = SleekTextSecondary
                                                    )
                                                )

                                                var showTokenSecret by remember { mutableStateOf(false) }

                                                OutlinedTextField(
                                                    value = settingsHaToken,
                                                    onValueChange = { settingsHaToken = it },
                                                    label = { Text("Token de Acesso de Longa Duração (Long-Lived)") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true,
                                                    visualTransformation = if (showTokenSecret) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                                    trailingIcon = {
                                                        IconButton(onClick = { showTokenSecret = !showTokenSecret }) {
                                                            Icon(
                                                                imageVector = if (showTokenSecret) Icons.Default.Info else Icons.Default.Settings,
                                                                contentDescription = if (showTokenSecret) "Ocultar" else "Mostrar",
                                                                tint = SleekAccent
                                                            )
                                                        }
                                                    },
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = SleekText,
                                                        unfocusedTextColor = SleekTextSecondary,
                                                        focusedBorderColor = SleekAccent,
                                                        unfocusedBorderColor = SleekPanel,
                                                        focusedLabelColor = SleekAccent,
                                                        unfocusedLabelColor = SleekTextSecondary
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }

                                // Save Action button
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TvButton(
                                            text = "Salvar e Conectar ao HA",
                                            icon = Icons.Default.Done,
                                            onClick = {
                                                val parsedPort = settingsPort.toIntOrNull() ?: 8080
                                                val parsedMqttPort = settingsMqttPort.toIntOrNull() ?: 1883
                                                val updated = config.copy(
                                                    deviceName = settingsName,
                                                    serverPort = parsedPort,
                                                    mqttEnabled = settingsMqttEnabled,
                                                    mqttBroker = settingsMqttBroker,
                                                    mqttPort = parsedMqttPort,
                                                    mqttUser = settingsMqttUser,
                                                    mqttPassword = settingsMqttPass,
                                                    haEnabled = settingsHaEnabled,
                                                    haAddress = settingsHaAddress,
                                                    haToken = settingsHaToken,
                                                    projectorUiMode = settingsProjectorMode
                                                )
                                                viewModel.saveConfig(updated)
                                                settingsStatusMsg = "Configurações do Home Assistant salvas!"
                                            }
                                        )

                                        if (settingsStatusMsg.isNotEmpty()) {
                                            Text(
                                                text = settingsStatusMsg,
                                                color = SleekTextStatusGreen,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            // Column 2: ZeroConf / mDNS Discovery info & Home Assistant Setup Guide (Weight: 1.2)
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1.2f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                item {
                                    Text(
                                        text = "AUTODESCORBERTA ZEROCONF / mDNS",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekTextSecondary,
                                        letterSpacing = 1.sp
                                    )
                                }

                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                        border = BorderStroke(1.dp, SleekPanel),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "ZeroConf",
                                                    tint = SleekAccent,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    "Anúncio mDNS Ativo",
                                                    color = SleekText,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                            }
                                            Text(
                                                "Este Smart Receiver transmite um anúncio de serviço com protocolo '_http._tcp.' na rede local usando o nome '${config.deviceName ?: "Projetor TV"}'.",
                                                color = SleekTextSecondary,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                "Porta REST Anunciada: ${config.serverPort} (TCP)",
                                                color = SleekText,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Divider(color = SleekPanel, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                                            Text(
                                                "O Home Assistant consegue descobrir este dispositivo de forma automática utilizando este anúncio!",
                                                color = SleekTextStatusGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                item {
                                    Text(
                                        text = "EXEMPLO DE CONFIGURAÇÃO DO REST PLAYER NO HA",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekTextSecondary
                                    )
                                }

                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = SleekBackground),
                                        border = BorderStroke(1.dp, SleekPanel),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Exemplo YAML (configuration.yaml):",
                                                color = SleekAccent,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "rest_command:\n" +
                                                        "  tv_play:\n" +
                                                        "    url: 'http://$localIp:${config.serverPort}/api/play'\n" +
                                                        "    method: POST\n" +
                                                        "    payload: '{\"url\":\"{{media_url}}\", \"title\":\"{{title}}\"}'\n" +
                                                        "  tv_volume:\n" +
                                                        "    url: 'http://$localIp:${config.serverPort}/api/volume?level={{level}}'\n" +
                                                        "    method: POST",
                                                color = SleekText,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 14.sp,
                                                modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp)).padding(8.dp)
                                            )
                                            Text(
                                                "Você também pode controlar e monitorar a reprodução de mídia diretamente se o WebSocket estiver ativo, o Smart Receiver enviará eventos de progresso e status de reprodução.",
                                                color = SleekTextSecondary,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    5 -> {
                        // Tab 4: Characteristics Setting Form Dashboard
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Column 1: Server and HA
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                item {
                                    Text(
                                        text = "CONFIGURAÇÕES GERAIS DISPOSITIVO",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekTextSecondary,
                                        letterSpacing = 1.sp
                                    )
                                }

                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                        border = BorderStroke(1.dp, SleekPanel),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedTextField(
                                                value = settingsName,
                                                onValueChange = { settingsName = it },
                                                label = { Text("Nome do Dispositivo (Ex: Projetor TV)") },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = SleekText,
                                                    unfocusedTextColor = SleekTextSecondary,
                                                    focusedBorderColor = SleekAccent,
                                                    unfocusedBorderColor = SleekPanel,
                                                    focusedLabelColor = SleekAccent,
                                                    unfocusedLabelColor = SleekTextSecondary
                                                )
                                            )

                                            OutlinedTextField(
                                                value = settingsPort,
                                                onValueChange = { settingsPort = it },
                                                label = { Text("Porta do Servidor REST API (Padrão: 8080)") },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = SleekText,
                                                    unfocusedTextColor = SleekTextSecondary,
                                                    focusedBorderColor = SleekAccent,
                                                    unfocusedBorderColor = SleekPanel,
                                                    focusedLabelColor = SleekAccent,
                                                    unfocusedLabelColor = SleekTextSecondary
                                                )
                                            )
                                        }
                                    }
                                }

                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(SleekSurface, RoundedCornerShape(12.dp))
                                            .border(1.dp, SleekPanel, RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            "Ativar Home Assistant Websocket",
                                            color = SleekText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = settingsHaEnabled,
                                            onCheckedChange = { settingsHaEnabled = it },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = SleekAccent,
                                                checkedTrackColor = SleekPanel,
                                                uncheckedThumbColor = SleekTextSecondary,
                                                uncheckedTrackColor = SleekSurface
                                            )
                                        )
                                    }
                                }

                                if (settingsHaEnabled) {
                                    item {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                            border = BorderStroke(1.dp, SleekPanel),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                OutlinedTextField(
                                                    value = settingsHaAddress,
                                                    onValueChange = { settingsHaAddress = it },
                                                    label = { Text("Endereço do Home Assistant (IP:8123)") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = SleekText,
                                                        unfocusedTextColor = SleekTextSecondary,
                                                        focusedBorderColor = SleekAccent,
                                                        unfocusedBorderColor = SleekPanel,
                                                        focusedLabelColor = SleekAccent,
                                                        unfocusedLabelColor = SleekTextSecondary
                                                    )
                                                )

                                                OutlinedTextField(
                                                    value = settingsHaToken,
                                                    onValueChange = { settingsHaToken = it },
                                                    label = { Text("Token de Acesso (Long-Lived)") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = SleekText,
                                                        unfocusedTextColor = SleekTextSecondary,
                                                        focusedBorderColor = SleekAccent,
                                                        unfocusedBorderColor = SleekPanel,
                                                        focusedLabelColor = SleekAccent,
                                                        unfocusedLabelColor = SleekTextSecondary
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Column 2: MQTT Broker details & save trigger
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                item {
                                    Text(
                                        text = "CONFIGURAÇÕES MQTT COMPLETO",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekTextSecondary,
                                        letterSpacing = 1.sp
                                    )
                                }

                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(SleekSurface, RoundedCornerShape(12.dp))
                                            .border(1.dp, SleekPanel, RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            "Ativar Client Bridge MQTT",
                                            color = SleekText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = settingsMqttEnabled,
                                            onCheckedChange = { settingsMqttEnabled = it },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = SleekAccent,
                                                checkedTrackColor = SleekPanel,
                                                uncheckedThumbColor = SleekTextSecondary,
                                                uncheckedTrackColor = SleekSurface
                                            )
                                        )
                                    }
                                }

                                if (settingsMqttEnabled) {
                                    item {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                            border = BorderStroke(1.dp, SleekPanel),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                OutlinedTextField(
                                                    value = settingsMqttBroker,
                                                    onValueChange = { settingsMqttBroker = it },
                                                    label = { Text("IP / Endereço Broker MQTT") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = SleekText,
                                                        unfocusedTextColor = SleekTextSecondary,
                                                        focusedBorderColor = SleekAccent,
                                                        unfocusedBorderColor = SleekPanel,
                                                        focusedLabelColor = SleekAccent,
                                                        unfocusedLabelColor = SleekTextSecondary
                                                    )
                                                )

                                                OutlinedTextField(
                                                    value = settingsMqttPort,
                                                    onValueChange = { settingsMqttPort = it },
                                                    label = { Text("Porta do Broker (Ex: 1883)") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = SleekText,
                                                        unfocusedTextColor = SleekTextSecondary,
                                                        focusedBorderColor = SleekAccent,
                                                        unfocusedBorderColor = SleekPanel,
                                                        focusedLabelColor = SleekAccent,
                                                        unfocusedLabelColor = SleekTextSecondary
                                                    )
                                                )

                                                OutlinedTextField(
                                                    value = settingsMqttUser,
                                                    onValueChange = { settingsMqttUser = it },
                                                    label = { Text("Usuário MQTT") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = SleekText,
                                                        unfocusedTextColor = SleekTextSecondary,
                                                        focusedBorderColor = SleekAccent,
                                                        unfocusedBorderColor = SleekPanel,
                                                        focusedLabelColor = SleekAccent,
                                                        unfocusedLabelColor = SleekTextSecondary
                                                    )
                                                )

                                                OutlinedTextField(
                                                    value = settingsMqttPass,
                                                    onValueChange = { settingsMqttPass = it },
                                                    label = { Text("Senha MQTT") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = SleekText,
                                                        unfocusedTextColor = SleekTextSecondary,
                                                        focusedBorderColor = SleekAccent,
                                                        unfocusedBorderColor = SleekPanel,
                                                        focusedLabelColor = SleekAccent,
                                                        unfocusedLabelColor = SleekTextSecondary
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }

                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(SleekSurface, RoundedCornerShape(8.dp))
                                                .padding(12.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Modo Projetor (Contraste Otimizado)", color = SleekText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text("Preto absoluto e destaque âmbar para projeções na parede", color = SleekTextSecondary, fontSize = 11.sp)
                                            }
                                            Switch(
                                                checked = settingsProjectorMode,
                                                onCheckedChange = { settingsProjectorMode = it },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = SleekAccent,
                                                    checkedTrackColor = SleekPanel,
                                                    uncheckedThumbColor = SleekTextSecondary,
                                                    uncheckedTrackColor = SleekSurface
                                                )
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        TvButton(
                                            text = "Salvar Características",
                                            icon = Icons.Default.Done,
                                            onClick = {
                                                val parsedPort = settingsPort.toIntOrNull() ?: 8080
                                                val parsedMqttPort = settingsMqttPort.toIntOrNull() ?: 1883
                                                val updated = config.copy(
                                                    deviceName = settingsName,
                                                    serverPort = parsedPort,
                                                    mqttEnabled = settingsMqttEnabled,
                                                    mqttBroker = settingsMqttBroker,
                                                    mqttPort = parsedMqttPort,
                                                    mqttUser = settingsMqttUser,
                                                    mqttPassword = settingsMqttPass,
                                                    haEnabled = settingsHaEnabled,
                                                    haAddress = settingsHaAddress,
                                                    haToken = settingsHaToken,
                                                    projectorUiMode = settingsProjectorMode
                                                )
                                                viewModel.saveConfig(updated)
                                                settingsStatusMsg = "Características atualizadas e salvas!"
                                            }
                                        )

                                        if (settingsStatusMsg.isNotEmpty()) {
                                            Text(
                                                text = settingsStatusMsg,
                                                color = SleekTextStatusGreen,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Footer styled exactly based on the Sleek Interface specs
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = SleekSurface),
                border = BorderStroke(1.dp, SleekPanel),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "DEVICE IDENTITY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "ATV-Legacy-${uuid.takeLast(4).uppercase(Locale.getDefault())}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SleekText
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "IP ADDRESS & PORT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$localIp:${config.serverPort}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SleekText,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Divider(color = SleekPanel, thickness = 1.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (config.mqttEnabled && mqttConnected) SleekAccent else Color.Gray,
                                            RoundedCornerShape(4.dp)
                                        )
                                )
                                Text(
                                    text = if (config.mqttEnabled) {
                                        if (mqttConnected) "MQTT Bridge Active" else "MQTT Bridge Disconnected (${mqttError ?: "Off"})"
                                    } else "MQTT Bridge Inactive",
                                    fontSize = 11.sp,
                                    color = SleekTextSecondary
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (config.haEnabled && haConnected) SleekAccent else Color.Gray,
                                            RoundedCornerShape(4.dp)
                                        )
                                )
                                Text(
                                    text = if (config.haEnabled) {
                                        if (haConnected) "Home Assistant WS Active" else "Home Assistant WS Disconnected (${haError ?: "Off"})"
                                    } else "Home Assistant WS Inactive",
                                    fontSize = 11.sp,
                                    color = SleekTextSecondary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .background(SleekPanel, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "UPTIME: ONLINE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekText,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }

        // --- 2. Overlay FULL-SCREEN MEDIA PLAYER (Video / Audio Overlay with gorgeous visual ripples) ---
        AnimatedVisibility(
            visible = (playerState == "playing" || playerState == "paused"),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SleekBackground)
            ) {
                // 1. Video placeholder background or cover art representation
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(SleekBackground, Color(0xFF101113))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (activeMediaUrl != null && (activeMediaUrl!!.endsWith(".mp3") || activeMediaUrl!!.contains("audio") || activeMediaUrl!!.contains("radio") || !activeMediaUrl!!.contains(".mp4"))) {
                        // Display clean spinning audio disc or wave animations for audio / radio stream
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(120.dp))
                                    .background(SleekSurface)
                                    .border(4.dp, SleekAccent, RoundedCornerShape(120.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Audio Disc",
                                    tint = SleekAccent,
                                    modifier = Modifier.size(96.dp)
                                )
                            }
                        }
                    }
                }

                // Playback Details & Progress overlaid at the bottom
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                            )
                        )
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mediaTitle,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = mediaSubtitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = SleekAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Playback Controls (Focusable on TV)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvIconButton(
                                icon = if (playerState == "playing") Icons.Default.Pause else Icons.Default.PlayArrow,
                                desc = "Play Pause",
                                onClick = onManualPlayPause
                            )

                            TvIconButton(
                                icon = Icons.Default.Stop,
                                desc = "Stop",
                                color = Color(0xFFEF4444),
                                onClick = onManualStop
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Media duration bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = formatSeconds(mediaPosition),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SleekTextSecondary
                        )

                        val progress = if (mediaDuration > 0) mediaPosition.toFloat() / mediaDuration else 0f
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = SleekAccent,
                            trackColor = SleekPanel
                        )

                        Text(
                            text = if (mediaDuration > 0) formatSeconds(mediaDuration) else "Live",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.LightGray
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Volume reference status overlay of TV
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "Volume",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Volume: $volume%",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        // --- 3. Settings Form overlay Dialog (Fully D-pad compliant) ---
        if (showSettings) {
            SettingsDialog(
                currentConfig = config,
                onDismiss = { showSettings = false },
                onSave = { updated ->
                    viewModel.saveConfig(updated)
                    showSettings = false
                }
            )
        }
    }
}

@Composable
fun TvButton(
    text: String,
    icon: ImageVector? = null,
    color: Color = SleekSurface,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(54.dp)
            .widthIn(min = 160.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        shape = RoundedCornerShape(27.dp),
        color = if (isFocused) Color.White else color,
        contentColor = if (isFocused) Color.Black else SleekText,
        border = if (isFocused) BorderStroke(3.dp, SleekAccent) else BorderStroke(1.dp, SleekPanel)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isFocused) Color.Black else SleekAccent
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TvIconButton(
    icon: ImageVector,
    desc: String,
    color: Color = SleekSurface,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        shape = RoundedCornerShape(32.dp),
        color = if (isFocused) Color.White else color,
        contentColor = if (isFocused) Color.Black else SleekText,
        border = if (isFocused) BorderStroke(3.dp, SleekAccent) else BorderStroke(1.dp, SleekPanel)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                modifier = Modifier.size(32.dp),
                tint = if (isFocused) Color.Black else SleekText
            )
        }
    }
}

@Composable
fun HistoryRowItem(log: MediaHistory) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SleekSurface.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .border(1.dp, SleekPanel, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon type
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (log.type == "MEDIA") SleekAccent.copy(alpha = 0.15f) else SleekTextStatusGreen.copy(alpha = 0.15f),
                    RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (log.type == "MEDIA") Icons.Default.PlayArrow else Icons.Default.Notifications,
                contentDescription = log.type,
                tint = if (log.type == "MEDIA") SleekAccent else SleekTextStatusGreen,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.subtitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = SleekText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = log.title,
                    fontSize = 11.sp,
                    color = SleekTextSecondary
                )
                Text(text = "•", fontSize = 10.sp, color = SleekPanel)
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp)),
                    fontSize = 11.sp,
                    color = SleekTextSecondary
                )
            }
        }

        Text(
            text = log.duration,
            fontSize = 12.sp,
            color = SleekTextSecondary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SettingsDialog(
    currentConfig: AppConfig,
    onDismiss: () -> Unit,
    onSave: (AppConfig) -> Unit
) {
    var devName by remember { mutableStateOf(currentConfig.deviceName) }
    var portValue by remember { mutableStateOf(currentConfig.serverPort.toString()) }
    var mqttEnabled by remember { mutableStateOf(currentConfig.mqttEnabled) }
    var mqttBroker by remember { mutableStateOf(currentConfig.mqttBroker) }
    var mqttPortValue by remember { mutableStateOf(currentConfig.mqttPort.toString()) }
    var mqttUser by remember { mutableStateOf(currentConfig.mqttUser) }
    var mqttPass by remember { mutableStateOf(currentConfig.mqttPassword) }
    var haEnabled by remember { mutableStateOf(currentConfig.haEnabled) }
    var haAddress by remember { mutableStateOf(currentConfig.haAddress) }
    var haToken by remember { mutableStateOf(currentConfig.haToken) }
    var projectorUiMode by remember { mutableStateOf(currentConfig.projectorUiMode) }

    // Dynamic color shadowing inside local Dialog
    val SleekBackground = if (projectorUiMode) Color.Black else Color(0xFF1A1C1E)
    val SleekSurface = if (projectorUiMode) Color(0xFF141414) else Color(0xFF2D3135)
    val SleekPanel = if (projectorUiMode) Color(0xFF2D3033) else Color(0xFF42474E)
    val SleekText = if (projectorUiMode) Color.White else Color(0xFFE2E2E6)
    val SleekTextSecondary = if (projectorUiMode) Color(0xFFE5E5E5) else Color(0xFFC2C7CF)
    val SleekAccent = if (projectorUiMode) Color(0xFFFBBF24) else Color(0xFF93C5FD)
    val SleekAccentDark = if (projectorUiMode) Color(0xFFFBBF24) else Color(0xFF60A5FA)
    val SleekTextStatusGreen = if (projectorUiMode) Color(0xFF10B981) else Color(0xFF34D399)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = SleekBackground),
            border = BorderStroke(1.dp, SleekPanel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Configurações do Smart Receiver",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekText,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = devName,
                            onValueChange = { devName = it },
                            label = { Text("Nome do Dispositivo (Ex: TV Sala)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SleekText,
                                unfocusedTextColor = SleekTextSecondary,
                                focusedBorderColor = SleekAccent,
                                unfocusedBorderColor = SleekPanel,
                                focusedLabelColor = SleekAccent,
                                unfocusedLabelColor = SleekTextSecondary
                            )
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = portValue,
                            onValueChange = { portValue = it },
                            label = { Text("Porta do Servidor REST API") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SleekText,
                                unfocusedTextColor = SleekTextSecondary,
                                focusedBorderColor = SleekAccent,
                                unfocusedBorderColor = SleekPanel,
                                focusedLabelColor = SleekAccent,
                                unfocusedLabelColor = SleekTextSecondary
                            )
                        )
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SleekSurface, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Modo Projetor (Contraste)", color = SleekText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Otimização para visualização em paredes/canvases", color = SleekTextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = projectorUiMode,
                                onCheckedChange = { projectorUiMode = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SleekAccent,
                                    checkedTrackColor = SleekPanel,
                                    uncheckedThumbColor = SleekTextSecondary,
                                    uncheckedTrackColor = SleekSurface
                                )
                            )
                        }
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SleekSurface, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                "Ativar Integração MQTT",
                                color = SleekText,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = mqttEnabled,
                                onCheckedChange = { mqttEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SleekAccent,
                                    checkedTrackColor = SleekPanel,
                                    uncheckedThumbColor = SleekTextSecondary,
                                    uncheckedTrackColor = SleekSurface
                                )
                            )
                        }
                    }

                    if (mqttEnabled) {
                        item {
                            OutlinedTextField(
                                value = mqttBroker,
                                onValueChange = { mqttBroker = it },
                                label = { Text("Broker MQTT IP/Endereço") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SleekText,
                                    unfocusedTextColor = SleekTextSecondary,
                                    focusedBorderColor = SleekAccent,
                                    unfocusedBorderColor = SleekPanel,
                                    focusedLabelColor = SleekAccent,
                                    unfocusedLabelColor = SleekTextSecondary
                                )
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = mqttPortValue,
                                onValueChange = { mqttPortValue = it },
                                label = { Text("Porta MQTT Broker") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SleekText,
                                    unfocusedTextColor = SleekTextSecondary,
                                    focusedBorderColor = SleekAccent,
                                    unfocusedBorderColor = SleekPanel,
                                    focusedLabelColor = SleekAccent,
                                    unfocusedLabelColor = SleekTextSecondary
                                )
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = mqttUser,
                                onValueChange = { mqttUser = it },
                                label = { Text("Usuário MQTT (Opcional)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SleekText,
                                    unfocusedTextColor = SleekTextSecondary,
                                    focusedBorderColor = SleekAccent,
                                    unfocusedBorderColor = SleekPanel,
                                    focusedLabelColor = SleekAccent,
                                    unfocusedLabelColor = SleekTextSecondary
                                )
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = mqttPass,
                                onValueChange = { mqttPass = it },
                                label = { Text("Senha MQTT (Opcional)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SleekText,
                                    unfocusedTextColor = SleekTextSecondary,
                                    focusedBorderColor = SleekAccent,
                                    unfocusedBorderColor = SleekPanel,
                                    focusedLabelColor = SleekAccent,
                                    unfocusedLabelColor = SleekTextSecondary
                                )
                            )
                        }
                    }

                    // --- HOME ASSISTANT WEBSOCKET SECTION ---
                    item {
                        Divider(
                            color = SleekPanel,
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SleekSurface, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                "Ativar Home Assistant WebSocket",
                                color = SleekText,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = haEnabled,
                                onCheckedChange = { haEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SleekAccent,
                                    checkedTrackColor = SleekPanel,
                                    uncheckedThumbColor = SleekTextSecondary,
                                    uncheckedTrackColor = SleekSurface
                                )
                            )
                        }
                    }

                    if (haEnabled) {
                        item {
                            OutlinedTextField(
                                value = haAddress,
                                onValueChange = { haAddress = it },
                                label = { Text("Endereço do Home Assistant (ex: IP:8123)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SleekText,
                                    unfocusedTextColor = SleekTextSecondary,
                                    focusedBorderColor = SleekAccent,
                                    unfocusedBorderColor = SleekPanel,
                                    focusedLabelColor = SleekAccent,
                                    unfocusedLabelColor = SleekTextSecondary
                                )
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = haToken,
                                onValueChange = { haToken = it },
                                label = { Text("Token de Acesso de Longa Duração (HA)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SleekText,
                                    unfocusedTextColor = SleekTextSecondary,
                                    focusedBorderColor = SleekAccent,
                                    unfocusedBorderColor = SleekPanel,
                                    focusedLabelColor = SleekAccent,
                                    unfocusedLabelColor = SleekTextSecondary
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvButton(
                        text = "Cancelar",
                        color = SleekPanel,
                        onClick = onDismiss
                    )

                    TvButton(
                        text = "Salvar",
                        onClick = {
                            val finalPort = portValue.toIntOrNull() ?: 8080
                            val finalMqttPort = mqttPortValue.toIntOrNull() ?: 1883
                            onSave(
                                AppConfig(
                                    id = 0,
                                    deviceName = devName,
                                    serverPort = finalPort,
                                    mqttEnabled = mqttEnabled,
                                    mqttBroker = mqttBroker,
                                    mqttPort = finalMqttPort,
                                    mqttUser = mqttUser,
                                    mqttPassword = mqttPass,
                                    haAddress = haAddress,
                                    haToken = haToken,
                                    haEnabled = haEnabled,
                                    projectorUiMode = projectorUiMode
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

private fun formatSeconds(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}

// Check wireless ADB port
private suspend fun checkAdbPortActive(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 300)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}

// Material 3 TV compliant focusable Tab item
@Composable
fun TvTabItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isFocused = remember { mutableStateOf(false) }
    val backgroundColor = when {
        selected -> SleekAccent
        isFocused.value -> SleekPanel
        else -> SleekSurface
    }
    val contentColor = when {
        selected -> SleekBackground
        else -> SleekText
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { isFocused.value = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (isFocused.value) SleekAccent else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ),
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                color = contentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}
