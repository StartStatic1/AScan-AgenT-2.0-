package com.ascan.ascanagent.ui

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ascan.ascanagent.data.AtkMode
import com.ascan.ascanagent.data.Hit
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
                        Text(
                            if (vm.downloadProgress >= 100) "Pronto — confirme no instalador"
                            else "Baixando… ${vm.downloadProgress}%",
                            color = Green
                        )
                    }
                    if (vm.downloadProgress == -2 && vm.downloadError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(vm.downloadError, color = Red)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.applyUpdate() },
                    enabled = vm.downloadProgress < 0 || vm.downloadProgress == -2
                ) { Text(if (vm.downloadProgress in 0..99) "…" else "Atualizar") }
            },
            dismissButton = {
                if (!info.force) {
                    TextButton(
                        onClick = { vm.dismissUpdate() },
                        enabled = vm.downloadProgress < 0 || vm.downloadProgress == -2
                    ) { Text("Depois") }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Purple),
                contentAlignment = Alignment.Center
            ) {
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // —— Config só quando NÃO está rodando ——
            if (!scanning) {
                item {
                    CardBox {
                        Text("CONFIGURAÇÃO", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = vm.server1,
                            onValueChange = { vm.server1 = it },
                            label = { Text("Servidor 1") },
                            placeholder = { Text("host:porta", color = Muted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = vm.server2,
                            onValueChange = { vm.server2 = it },
                            label = { Text("Servidor 2 (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = vm.server3,
                            onValueChange = { vm.server3 = it },
                            label = { Text("Servidor 3 (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = vm.threads,
                                onValueChange = { vm.threads = it.filter { ch -> ch.isDigit() }.take(2) },
                                label = { Text("Threads") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = fieldColors
                            )
                            ModeDropdown(vm, fieldColors, Modifier.weight(1.4f))
                        }
                    }
                }

                item {
                    CardBox {
                        Text("COMBO ONLINE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = vm.selectedCombo.ifEmpty { "Selecione" },
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
                                        onClick = {
                                            vm.selectedCombo = name
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.loadSelectedCombo() },
                                enabled = !vm.loadingCombo,
                                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (vm.loadingCombo) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Text("USAR COMBO")
                                }
                            }
                            Button(
                                onClick = { vm.refreshCombos() },
                                colors = ButtonDefaults.buttonColors(containerColor = Card2),
                                modifier = Modifier.weight(0.7f)
                            ) { Text("Atualizar") }
                        }
                        if (vm.comboCount > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("✓ ${vm.comboName} — ${vm.comboCount} credenciais", color = Green, fontSize = 13.sp)
                        }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.loadProxiesOnline() },
                                enabled = !vm.proxyLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                                modifier = Modifier.weight(1f)
                            ) { Text(if (vm.proxyLoading) "..." else "Online") }
                            Button(
                                onClick = { vm.clearProxies() },
                                colors = ButtonDefaults.buttonColors(containerColor = Red),
                                modifier = Modifier.weight(1f)
                            ) { Text("Limpar") }
                        }
                    }
                }
            } else {
                // Durante o scan: resumo mínimo
                item {
                    CardBox {
                        if (vm.comboCount > 0) {
                            Text("✓ ${vm.comboName} — ${vm.comboCount} credenciais", color = Green, fontSize = 13.sp)
                        }
                        Text(
                            if (vm.proxyCount > 0) "Proxy · ${vm.proxyCount}" else "Direto (sem proxy)",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Controles sempre visíveis
            item {
                Button(
                    onClick = { if (vm.running) vm.stop() else vm.start() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vm.running) Red else Purple
                    )
                ) {
                    Icon(if (vm.running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (vm.running) "PARAR SCAN" else "INICIAR SCAN",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ActionBtn("PAUSAR", Orange, Modifier.weight(1f)) { vm.togglePause() }
                    ActionBtn("COPIAR", Blue, Modifier.weight(1f)) {
                        val t = vm.hits.joinToString("\n") { "${it.user}:${it.pass}" }
                        clipboard.setText(AnnotatedString(t))
                        vm.log("Hits copiados")
                    }
                    ActionBtn("M3U", Card2, Modifier.weight(1f)) {
                        if (vm.lastM3u.isNotBlank()) {
                            clipboard.setText(AnnotatedString(vm.lastM3u))
                            vm.log("M3U copiado")
                        } else {
                            vm.log("Nenhum hit ainda")
                        }
                    }
                }
            }

            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatChip("STATUS", vm.statusText, Purple)
                    StatChip("TESTADAS", "${vm.stats.checks}", Text)
                    StatChip("HITS", "${vm.stats.hits}", Green)
                    StatChip("ILIMIT.", "${vm.stats.unlimited}", Green)
                    StatChip("CPM", "${vm.stats.cpm}", Orange)
                    StatChip("403", "${vm.stats.errors403}", Red)
                    StatChip("429", "${vm.stats.errors429}", Orange)
                    StatChip("TO", "${vm.stats.timeouts}", Muted)
                }
            }

            item {
                CardBox {
                    Text("RANKING", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "ON=vivo · SCAN=agora · WAIT=fila · PROT=403 · DONE=fim",
                        color = Muted,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (vm.ranking.isEmpty()) {
                        Text("—", color = Muted)
                    } else {
                        vm.ranking.take(8).forEachIndexed { i, s ->
                            val color = when (s.state) {
                                "ON" -> Green
                                "SCAN" -> PurpleSoft
                                "PROT" -> Orange
                                "DONE" -> Muted
                                "WAIT" -> Muted
                                "OFF" -> Red
                                else -> Muted
                            }
                            Text(
                                "${i + 1}. ${s.state} ${s.host}  ${s.hits} hits",
                                color = color,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(vm.hitsPath(), color = Muted, fontSize = 10.sp)
                }
            }

            item {
                CardBox {
                    Text("HITS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (vm.hits.isEmpty()) {
                        // logs de atividade quando sem hit
                        vm.logs.take(12).forEach { line ->
                            Text(
                                line,
                                color = when {
                                    line.startsWith("→") -> PurpleSoft
                                    line.startsWith("✓") -> Green
                                    line.startsWith("OK") -> Green
                                    else -> Muted
                                },
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                        if (vm.logs.isEmpty()) {
                            Text("Aguardando hits...", color = Muted, fontSize = 12.sp)
                        }
                    } else {
                        vm.hits.take(15).forEach { hit ->
                            HitCard(hit)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun HitCard(hit: Hit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Card2)
            .border(1.dp, Green.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        val head = when {
            hit.unlimited -> "✅ HIT ILIMITADO"
            hit.plan.equals("TRIAL", true) -> "✅ HIT TRIAL"
            else -> "✅ HIT ONLINE"
        }
        Text(head, color = Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text("🌐 ${hit.server}", color = Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("👤 ${hit.user}", color = Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("🔑 ${hit.pass}", color = Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            "📋 ${hit.plan}  ·  📶 ${hit.connections}  ·  ${hit.status}",
            color = PurpleSoft,
            fontSize = 12.sp
        )
        val expLine = buildString {
            append("⏰ ${hit.expires}")
            if (hit.daysLeft.isNotBlank()) append(hit.daysLeft)
        }
        Text(expLine, color = Orange, fontSize = 11.sp)
        if (hit.created.isNotBlank() && hit.created != "—" && hit.created != "0") {
            Text("📅 Criado ${hit.created}", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun StatusChip(text: String, running: Boolean) {
    val bg = if (running) Green.copy(alpha = 0.15f) else Card2
    val fg = if (running) Green else Muted
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun ActionBtn(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeDropdown(
    vm: ScanViewModel,
    fieldColors: androidx.compose.material3.TextFieldColors,
    modifier: Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = vm.mode.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Modo") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = fieldColors
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AtkMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    onClick = {
                        vm.mode = m
                        expanded = false
                    }
                )
            }
        }
    }
}
