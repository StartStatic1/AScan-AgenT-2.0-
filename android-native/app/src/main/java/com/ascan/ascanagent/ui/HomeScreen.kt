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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: ScanViewModel) {
    val clipboard = LocalClipboardManager.current
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

    val pickCombo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) vm.loadComboFromUri(uri)
    }

    if (vm.showProxyPaste) {
        AlertDialog(
            onDismissRequest = { vm.showProxyPaste = false },
            title = { Text("Proxy offline") },
            text = {
                Column {
                    Text("Cole a lista do seu gerador (um por linha: ip:porta)", color = Muted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vm.proxyPasteText,
                        onValueChange = { vm.proxyPasteText = it },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        placeholder = { Text("1.2.3.4:8080\n5.6.7.8:3128", color = Muted) },
                        colors = fieldColors
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.applyProxyPaste(vm.proxyPasteText) }) { Text("Usar lista") } },
            dismissButton = { TextButton(onClick = { vm.showProxyPaste = false }) { Text("Cancelar") } }
        )
    }

    if (vm.showUpdate && vm.updateInfo != null) {
        val info = vm.updateInfo!!
        AlertDialog(
            onDismissRequest = { if (!info.force && vm.downloadProgress < 0) vm.dismissUpdate() },
            title = { Text("Atualização ${info.version}") },
            text = {
                Column {
                    Text(info.message)
                    if (vm.downloadProgress in 0..100) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(if (vm.downloadProgress >= 100) "Pronto — confirme no instalador" else "Baixando… ${vm.downloadProgress}%", color = Green)
                    }
                    if (vm.downloadProgress == -2 && vm.downloadError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(vm.downloadError, color = Red)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.applyUpdate() }, enabled = vm.downloadProgress < 0 || vm.downloadProgress == -2) {
                    Text(if (vm.downloadProgress in 0..99) "…" else "Atualizar")
                }
            },
            dismissButton = {
                if (!info.force) {
                    TextButton(onClick = { vm.dismissUpdate() }, enabled = vm.downloadProgress < 0 || vm.downloadProgress == -2) { Text("Depois") }
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Purple), contentAlignment = Alignment.Center) {
                Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("AScan Agent", color = PurpleSoft, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Native · ${com.ascan.ascanagent.data.AppConfig.VERSION}", color = Muted, fontSize = 11.sp)
            }
            StatusChip(vm.statusText, vm.running)
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!scanning) {
                item {
                    CardBox {
                        Text("CONFIGURAÇÃO", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = vm.server1, onValueChange = { vm.server1 = it }, label = { Text("Servidor 1") }, placeholder = { Text("host:porta", color = Muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = vm.server2, onValueChange = { vm.server2 = it }, label = { Text("Servidor 2 (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = vm.server3, onValueChange = { vm.server3 = it }, label = { Text("Servidor 3 (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = vm.server4, onValueChange = { vm.server4 = it }, label = { Text("Servidor 4 (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = vm.server5, onValueChange = { vm.server5 = it }, label = { Text("Servidor 5 (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = vm.threads, onValueChange = { vm.threads = it.filter { ch -> ch.isDigit() }.take(2) }, label = { Text("Threads") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
                            ModeDropdown(vm, fieldColors, Modifier.weight(1.4f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OrderDropdown(vm, fieldColors, Modifier.weight(1.2f))
                            Button(
                                onClick = { vm.testServers() },
                                enabled = !vm.running && !vm.probing,
                                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                                modifier = Modifier.weight(1f).height(56.dp)
                            ) {
                                if (vm.probing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                else Text("Testar hosts", fontSize = 13.sp)
                            }
                        }
                    }
                }
                item {
                    CardBox {
                        val nLoaded = vm.loadedCombos.size
                        Text(
                            "COMBOS  " + nLoaded + "/" + AppConfig.MAX_COMBOS,
                            color = Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Online ou Celular adiciona na lista (não substitui)",
                            color = Muted,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        for (slotIdx in 0 until AppConfig.MAX_COMBOS) {
                            val slot = vm.loadedCombos.getOrNull(slotIdx)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (slot != null) Card2 else Input)
                                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${slotIdx + 1}/" + AppConfig.MAX_COMBOS,
                                    color = PurpleSoft,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(36.dp)
                                )
                                if (slot != null) {
                                    val src = when (slot.source) {
                                        "local" -> "📱"
                                        else -> "☁"
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "$src ${slot.name}",
                                            color = Green,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                        Text(
                                            "${slot.items.size} linhas",
                                            color = Muted,
                                            fontSize = 10.sp
                                        )
                                    }
                                    TextButton(onClick = { vm.removeCombo(slotIdx) }) {
                                        Text("✕", color = Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text(
                                        "vazio — use Online ou Celular",
                                        color = Muted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (slotIdx < AppConfig.MAX_COMBOS - 1) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        if (nLoaded > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Total: ${vm.comboCount} linhas",
                                color = Green,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = vm.selectedCombo.ifEmpty { "Selecione online" },
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = fieldColors
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                vm.comboList.forEach { (name, _) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { vm.selectedCombo = name; expanded = false }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.loadSelectedCombo() },
                                enabled = !vm.loadingCombo && nLoaded < AppConfig.MAX_COMBOS,
                                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (vm.loadingCombo) CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                ) else Text("Online", fontSize = 13.sp)
                            }
                            Button(
                                onClick = { pickCombo.launch("text/*") },
                                enabled = !vm.loadingCombo && nLoaded < AppConfig.MAX_COMBOS,
                                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                                modifier = Modifier.weight(1f)
                            ) { Text("Celular", fontSize = 13.sp) }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.refreshCombos() },
                                colors = ButtonDefaults.buttonColors(containerColor = Card2),
                                modifier = Modifier.weight(1f)
                            ) { Text("Atualizar lista", fontSize = 12.sp) }
                            Button(
                                onClick = { vm.clearCombo() },
                                colors = ButtonDefaults.buttonColors(containerColor = Red.copy(alpha = 0.85f)),
                                modifier = Modifier.weight(1f)
                            ) { Text("Limpar todos", fontSize = 12.sp) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        ComboModeDropdown(vm, fieldColors, Modifier.fillMaxWidth())
                    }
                }
                item {
                    CardBox {
                        Text("PROXY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            when {
                                vm.proxyLoading -> "Baixando proxies..."
                                vm.proxyCount > 0 -> "Pronto · ${vm.proxyCount} proxies"
                                else -> "Sem proxy (direto)"
                            },
                            color = when {
                                vm.proxyLoading -> Orange
                                vm.proxyCount > 0 -> Green
                                else -> Muted
                            },
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Online = net · Offline = colar TXT · Repo = pasta proxies/", color = Muted, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { vm.loadProxiesOnline() }, enabled = !vm.proxyLoading, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.weight(1f)) { Text(if (vm.proxyLoading) "..." else "Online", fontSize = 12.sp) }
                            Button(onClick = { vm.showProxyPaste = true }, enabled = !vm.proxyLoading, colors = ButtonDefaults.buttonColors(containerColor = Purple), modifier = Modifier.weight(1f)) { Text("Offline", fontSize = 12.sp) }
                            Button(onClick = { vm.loadProxiesFromRepo() }, enabled = !vm.proxyLoading, colors = ButtonDefaults.buttonColors(containerColor = Card2), modifier = Modifier.weight(1f)) { Text("Repo", fontSize = 12.sp) }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(onClick = { vm.clearProxies() }, colors = ButtonDefaults.buttonColors(containerColor = Red), modifier = Modifier.fillMaxWidth()) { Text("Limpar proxies") }
                    }
                }
            } else {
                item {
                    CardBox {
                        if (vm.comboCount > 0) {
                            Text("✓ ${vm.comboName} — ${vm.comboCount} linhas", color = Green, fontSize = 13.sp)
                            if (vm.stats.comboProgress.isNotBlank()) {
                                Text(vm.stats.comboProgress, color = Orange, fontSize = 11.sp)
                            }
                        }
                        Text(if (vm.proxyCount > 0) "Proxy · ${vm.proxyCount}" else "Direto (sem proxy)", color = Muted, fontSize = 12.sp)
                    }
                }
            }

            item {
                Button(
                    onClick = { if (vm.running) vm.stop() else vm.start() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (vm.running) Red else Purple)
                ) {
                    Icon(if (vm.running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (vm.running) "PARAR SCAN" else "INICIAR SCAN", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionBtn("PAUSAR", Orange, Modifier.weight(1f)) { vm.togglePause() }
                    ActionBtn("COPIAR", Blue, Modifier.weight(1f)) {
                        clipboard.setText(AnnotatedString(vm.hitsUserPass()))
                        vm.log("Hits copiados (user:pass)")
                    }
                    ActionBtn("M3U", Card2, Modifier.weight(1f)) {
                        if (vm.lastM3u.isNotBlank()) {
                            clipboard.setText(AnnotatedString(vm.lastM3u))
                            vm.log("M3U copiado")
                        } else vm.log("Nenhum M3U ainda")
                    }
                }
            }

            item {
                CardBox {
                    Text("STATS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text("RANKING", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    if (vm.ranking.isEmpty()) {
                        Text("Sem dados ainda", color = Muted, fontSize = 12.sp)
                    } else {
                        vm.ranking.forEach { s ->
                            val color = when (s.state) {
                                "ON", "ONLINE" -> Green
                                "PROT" -> Orange
                                "OFF", "TIMEOUT" -> Red
                                "SCAN" -> PurpleSoft
                                "DONE" -> Muted
                                else -> Text
                            }
                            Text("${s.state} · ${s.host} · hits ${s.hits} ${s.detail}", color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            item {
                CardBox {
                    Text("HITS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    if (vm.hits.isEmpty()) Text("Nenhum hit ainda", color = Muted, fontSize = 12.sp)
                    else vm.hits.take(30).forEach { h ->
                        Text("${h.user}:${h.pass} · ${h.server}", color = Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            item {
                CardBox {
                    Text("LOG", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    vm.logs.take(25).forEach { line ->
                        Text(line, color = Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
            .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp)
    ) { content() }
}

@Composable
private fun StatusChip(text: String, running: Boolean) {
    val bg = if (running) Green.copy(alpha = 0.15f) else Card2
    val fg = if (running) Green else Muted
    Box(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun ActionBtn(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = color)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
