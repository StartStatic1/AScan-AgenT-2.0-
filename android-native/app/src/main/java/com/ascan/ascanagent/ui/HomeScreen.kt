package com.ascan.ascanagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascan.ascanagent.data.AppConfig
import com.ascan.ascanagent.data.AtkMode
import com.ascan.ascanagent.data.ComboMode
import com.ascan.ascanagent.data.Hit
import com.ascan.ascanagent.data.ScanOrder
import com.ascan.ascanagent.data.XtreamApi
import com.ascan.ascanagent.ui.theme.Bg
import com.ascan.ascanagent.ui.theme.Blue
import com.ascan.ascanagent.ui.theme.Card
import com.ascan.ascanagent.ui.theme.Card2
import com.ascan.ascanagent.ui.theme.Green
import com.ascan.ascanagent.ui.theme.Input
import com.ascan.ascanagent.ui.theme.Line
import com.ascan.ascanagent.ui.theme.Muted
import com.ascan.ascanagent.ui.theme.Orange
import com.ascan.ascanagent.ui.theme.Purple
import com.ascan.ascanagent.ui.theme.PurpleSoft
import com.ascan.ascanagent.ui.theme.Red
import com.ascan.ascanagent.ui.theme.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: ScanViewModel) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Purple,
        unfocusedBorderColor = Line,
        focusedTextColor = Text,
        unfocusedTextColor = Text,
        cursorColor = Purple,
        focusedContainerColor = Input,
        unfocusedContainerColor = Input,
        focusedLabelColor = Muted,
        unfocusedLabelColor = Muted
    )
    val scanning = vm.running

    var infoDialog by remember { mutableStateOf<String?>(null) }
    var infoLoading by remember { mutableStateOf(false) }

    val pickCombo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) vm.loadComboFromUri(uri)
    }

    if (infoDialog != null) {
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            title = { Text("Info do servidor") },
            text = { Text(infoDialog ?: "") },
            confirmButton = { TextButton(onClick = { infoDialog = null }) { Text("Fechar") } }
        )
    }

    if (vm.showProxyPaste) {
        AlertDialog(
            onDismissRequest = { vm.showProxyPaste = false },
            title = { Text("Proxy offline") },
            text = {
                Column {
                    Text("Cole a lista (ip:porta)", color = Muted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = vm.proxyPasteText,
                        onValueChange = { vm.proxyPasteText = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("1.2.3.4:8080", color = Muted) },
                        colors = fieldColors
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.applyProxyPaste(vm.proxyPasteText) }) { Text("Usar") } },
            dismissButton = { TextButton(onClick = { vm.showProxyPaste = false }) { Text("Cancelar") } }
        )
    }

    if (vm.showUpdate && vm.updateInfo != null) {
        val info = vm.updateInfo!!
        AlertDialog(
            onDismissRequest = { if (!info.force && vm.downloadProgress < 0) vm.dismissUpdate() },
            title = { Text("Atualiza\u00e7\u00e3o ${info.version}") },
            text = {
                Column {
                    Text(info.message)
                    if (vm.downloadProgress in 0..100) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(if (vm.downloadProgress >= 100) "Pronto" else "Baixando\u2026 ${vm.downloadProgress}%", color = Green)
                    }
                    if (vm.downloadProgress == -2 && vm.downloadError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(vm.downloadError, color = Red)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.applyUpdate() }, enabled = vm.downloadProgress < 0 || vm.downloadProgress == -2) {
                    Text(if (vm.downloadProgress in 0..99) "\u2026" else "Atualizar")
                }
            },
            dismissButton = {
                if (!info.force) {
                    TextButton(onClick = { vm.dismissUpdate() }, enabled = vm.downloadProgress < 0 || vm.downloadProgress == -2) { Text("Depois") }
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Purple), contentAlignment = Alignment.Center) {
                Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("AScan Agent", color = PurpleSoft, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Native \u00b7 ${AppConfig.VERSION}", color = Muted, fontSize = 10.sp)
            }
            StatusChip(vm.statusText, vm.running)
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!scanning) {
                item {
                    CardBox {
                        Text("CONFIG", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = vm.scannerNick, onValueChange = { vm.scannerNick = it }, label = { Text("Apelido") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = vm.server1, onValueChange = { vm.server1 = it }, label = { Text("Servidor") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(value = vm.server2, onValueChange = { vm.server2 = it }, placeholder = { Text("+") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(value = vm.server3, onValueChange = { vm.server3 = it }, placeholder = { Text("+") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(value = vm.server4, onValueChange = { vm.server4 = it }, placeholder = { Text("+") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(value = vm.server5, onValueChange = { vm.server5 = it }, placeholder = { Text("+") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(value = vm.threads, onValueChange = { vm.threads = it.filter { ch -> ch.isDigit() }.take(2) }, label = { Text("Thr") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
                            ModeDropdown(vm, fieldColors, Modifier.weight(1.3f))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OrderDropdown(vm, fieldColors, Modifier.weight(1.1f))
                            Button(onClick = { vm.testServers() }, enabled = !vm.running && !vm.probing, colors = ButtonDefaults.buttonColors(containerColor = Orange), modifier = Modifier.weight(1f).height(52.dp)) {
                                if (vm.probing) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                else Text("Testar", fontSize = 12.sp)
                            }
                        }
                    }
                }
                item {
                    CardBox {
                        val nLoaded = vm.loadedCombos.size
                        Text("COMBOS $nLoaded/${AppConfig.MAX_COMBOS}", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        for (slotIdx in 0 until AppConfig.MAX_COMBOS) {
                            val slot = vm.loadedCombos.getOrNull(slotIdx)
                            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (slot != null) Card2 else Input).border(1.dp, Line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${slotIdx + 1}", color = PurpleSoft, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(28.dp))
                                if (slot != null) {
                                    val src = when (slot.source) { "local" -> "\ud83d\udcf1"; else -> "\u2601" }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("$src ${slot.name}", color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text("${slot.items.size} linhas", color = Muted, fontSize = 9.sp)
                                    }
                                    TextButton(onClick = { vm.removeCombo(slotIdx) }) { Text("\u2715", color = Red, fontSize = 12.sp) }
                                } else {
                                    Text("vazio", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                }
                            }
                            if (slotIdx < AppConfig.MAX_COMBOS - 1) Spacer(modifier = Modifier.height(4.dp))
                        }
                        if (nLoaded > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Total: ${vm.comboCount}", color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(value = vm.selectedCombo.ifEmpty { "Online" }, onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = fieldColors)
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                vm.comboList.forEach { (name, _) -> DropdownMenuItem(text = { Text(name) }, onClick = { vm.selectedCombo = name; expanded = false }) }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { vm.loadSelectedCombo() }, enabled = !vm.loadingCombo && nLoaded < AppConfig.MAX_COMBOS, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.weight(1f)) {
                                if (vm.loadingCombo) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White) else Text("Online", fontSize = 12.sp)
                            }
                            Button(onClick = { pickCombo.launch("text/*") }, enabled = !vm.loadingCombo && nLoaded < AppConfig.MAX_COMBOS, colors = ButtonDefaults.buttonColors(containerColor = Purple), modifier = Modifier.weight(1f)) { Text("Celular", fontSize = 12.sp) }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { vm.refreshCombos() }, colors = ButtonDefaults.buttonColors(containerColor = Card2), modifier = Modifier.weight(1f)) { Text("Atualizar", fontSize = 11.sp) }
                            Button(onClick = { vm.clearCombo() }, colors = ButtonDefaults.buttonColors(containerColor = Red.copy(alpha = 0.8f)), modifier = Modifier.weight(1f)) { Text("Limpar", fontSize = 11.sp) }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        ComboModeDropdown(vm, fieldColors, Modifier.fillMaxWidth())
                    }
                }
                item {
                    CardBox {
                        Text("PROXY", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(when {
                            vm.proxyLoading -> "Baixando..."
                            vm.proxyCount > 0 -> "${vm.proxyCount} proxies"
                            else -> "Direto"
                        }, color = when {
                            vm.proxyLoading -> Orange
                            vm.proxyCount > 0 -> Green
                            else -> Muted
                        }, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { vm.loadProxiesOnline() }, enabled = !vm.proxyLoading, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.weight(1f)) { Text(if (vm.proxyLoading) "..." else "Online", fontSize = 11.sp) }
                            Button(onClick = { vm.showProxyPaste = true }, enabled = !vm.proxyLoading, colors = ButtonDefaults.buttonColors(containerColor = Purple), modifier = Modifier.weight(1f)) { Text("Offline", fontSize = 11.sp) }
                            Button(onClick = { vm.loadProxiesFromRepo() }, enabled = !vm.proxyLoading, colors = ButtonDefaults.buttonColors(containerColor = Card2), modifier = Modifier.weight(1f)) { Text("Repo", fontSize = 11.sp) }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { vm.clearProxies() }, colors = ButtonDefaults.buttonColors(containerColor = Red), modifier = Modifier.fillMaxWidth()) { Text("Limpar", fontSize = 12.sp) }
                    }
                }
            } else {
                item {
                    CardBox {
                        if (vm.comboCount > 0) Text("\u2713 ${vm.comboCount} linhas", color = Green, fontSize = 12.sp)
                        Text(if (vm.proxyCount > 0) "Proxy \u00b7 ${vm.proxyCount}" else "Direto", color = Muted, fontSize = 11.sp)
                        if (vm.scannerNick.isNotBlank()) Text("Por: ${vm.scannerNick}", color = PurpleSoft, fontSize = 11.sp)
                    }
                }
            }

            item {
                Button(
                    onClick = { if (vm.running) vm.stop() else vm.start() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (vm.running) Red else Purple)
                ) {
                    Icon(if (vm.running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (vm.running) "PARAR" else "SCAN", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionBtn("PAUSAR", Orange, Modifier.weight(1f)) { vm.togglePause() }
                    ActionBtn("COPIAR", Blue, Modifier.weight(1f)) {
                        val all = vm.hits.joinToString("\n\n") { it.text.ifBlank { "${it.user}:${it.pass}" } }
                        if (all.isBlank()) vm.log("Nenhum hit")
                        else {
                            clipboard.setText(AnnotatedString(all))
                            vm.log("Copiados (${vm.hits.size})")
                        }
                    }
                    ActionBtn("M3U", Card2, Modifier.weight(1f)) {
                        if (vm.lastM3u.isNotBlank()) {
                            clipboard.setText(AnnotatedString(vm.lastM3u))
                            vm.log("M3U copiado")
                        } else vm.log("Sem M3U")
                    }
                }
            }

            item {
                CardBox {
                    Text("STATS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatChip("Checks", "${vm.stats.checks}", Text)
                        StatChip("Hits", "${vm.stats.hits}", Green)
                        StatChip("CPM", "${vm.stats.cpm}", PurpleSoft)
                        StatChip("403", "${vm.stats.errors403}", Orange)
                        StatChip("429", "${vm.stats.errors429}", Orange)
                        StatChip("TO", "${vm.stats.timeouts}", Muted)
                    }
                }
            }

            item {
                CardBox {
                    Text("RANKING", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (vm.ranking.isEmpty()) Text("Aguardando...", color = Muted, fontSize = 11.sp)
                    else vm.ranking.forEach { s ->
                        val (icon, color) = when (s.state) {
                            "ON", "ONLINE" -> "\ud83d\udfe2" to Green
                            "PROT" -> "\ud83d\udfe0" to Orange
                            "PROT_HARD" -> "\ud83d\udd34" to Red
                            "OFF", "TIMEOUT" -> "\ud83d\udd34" to Red
                            "SCAN" -> "\ud83d\udd35" to PurpleSoft
                            "DONE" -> "\u26aa" to Muted
                            else -> "\u26aa" to Text
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(icon, fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${s.state} \u00b7 ${s.host} \u00b7 ${s.hits} ${s.detail}", color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            }
                            if (s.progress > 0f) {
                                LinearProgressIndicator(
                                    progress = { s.progress },
                                    modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 1.dp),
                                    color = color.copy(alpha = 0.6f),
                                    trackColor = Line
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardBox {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("HITS ${vm.hits.size}", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (vm.hits.isNotEmpty()) {
                            TextButton(onClick = { vm.clearHits() }) { Text("Limpar", color = Muted, fontSize = 10.sp) }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (vm.hits.isEmpty()) Text("Nenhum ainda", color = Muted, fontSize = 11.sp)
                    else vm.hits.take(30).forEach { h ->
                        HitCard(h,
                            onCopy = {
                                clipboard.setText(AnnotatedString(h.text.ifBlank { "${h.user}:${h.pass}" }))
                                vm.log("Hit copiado")
                            },
                            onInfo = {
                                if (infoLoading) return@HitCard
                                infoLoading = true
                                scope.launch {
                                    val info = withContext(Dispatchers.IO) {
                                        XtreamApi.fetchFullInfo(h.server, h.user, h.pass)
                                    }
                                    infoLoading = false
                                    infoDialog = if (info != null) {
                                        buildString {
                                            appendLine("Live: ${info.live}")
                                            appendLine("S\u00e9ries: ${info.series}")
                                            appendLine("VOD: ${info.vod}")
                                            appendLine("Timezone: ${info.timezone}")
                                            appendLine("Protocolo: ${info.protocol}")
                                            appendLine("RTMP: ${info.rtmpPort}")
                                            appendLine("HTTPS: ${info.httpsPort}")
                                            append("Hora: ${info.timeNow}")
                                        }
                                    } else "N\u00e3o foi poss\u00edvel obter contagens (bloqueio ou timeout)."
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            item {
                CardBox {
                    Text("LOG", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    vm.logs.take(10).forEach { line ->
                        Text(line, color = Text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun HitCard(h: Hit, onCopy: () -> Unit, onInfo: () -> Unit) {
    val dias = h.daysLeft.filter { it.isDigit() }
    val expLine = buildString {
        append(h.expires)
        if (dias.isNotEmpty()) append(" \u00b7 $dias d")
        else if (h.unlimited) append(" \u00b7 \u221e")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Card2)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "\u2705 ${h.plan.ifBlank { "HIT" }}",
                color = Green,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onInfo) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = Blue)
                Spacer(modifier = Modifier.width(2.dp))
                Text("Info", color = Blue, fontSize = 11.sp)
            }
            TextButton(onClick = onCopy) {
                Text("Copiar", color = Blue, fontSize = 11.sp)
            }
        }
        Text("\ud83c\udf10 ${h.server}", color = PurpleSoft, fontSize = 11.sp)
        Text("\ud83d\udc64 ${h.user}  \ud83d\udd11 ${h.pass}", color = Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("\ud83d\udfe2 ${h.status.ifBlank { "ON" }} \u00b7 ${h.plan} \u00b7 ${h.connections}", color = Muted, fontSize = 10.sp)
        if (h.expires.isNotBlank() && h.expires != "-") {
            Text("\u23f0 $expLine", color = Muted, fontSize = 10.sp)
        }
        if (h.nick.isNotBlank() && h.nick != "-") {
            Text("\ud83d\udc64 ${h.nick}", color = PurpleSoft, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(12.dp)
    ) { content() }
}

@Composable
private fun StatusChip(text: String, running: Boolean) {
    val bg = if (running) Green.copy(alpha = 0.15f) else Card2
    val fg = if (running) Green else Muted
    Box(modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = Muted, fontSize = 9.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ActionBtn(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(40.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = color)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderDropdown(vm: ScanViewModel, fieldColors: androidx.compose.material3.TextFieldColors, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(value = vm.scanOrder.label, onValueChange = {}, readOnly = true, label = { Text("Ordem") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = fieldColors)
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScanOrder.entries.forEach { o ->
                DropdownMenuItem(text = { Text(o.label) }, onClick = { vm.scanOrder = o; expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeDropdown(vm: ScanViewModel, fieldColors: androidx.compose.material3.TextFieldColors, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(value = vm.mode.label, onValueChange = {}, readOnly = true, label = { Text("Modo") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = fieldColors)
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AtkMode.entries.forEach { m ->
                DropdownMenuItem(text = { Text(m.label) }, onClick = { vm.mode = m; expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComboModeDropdown(vm: ScanViewModel, fieldColors: androidx.compose.material3.TextFieldColors, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(value = vm.comboMode.label, onValueChange = {}, readOnly = true, label = { Text("Modo combo") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = fieldColors)
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ComboMode.entries.forEach { m ->
                DropdownMenuItem(text = { Text(m.label) }, onClick = { vm.comboMode = m; expanded = false })
            }
        }
    }
}
