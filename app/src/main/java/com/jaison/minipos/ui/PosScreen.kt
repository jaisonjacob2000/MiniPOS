@file:OptIn(ExperimentalMaterial3Api::class)

package com.jaison.minipos.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaison.minipos.domain.MenuItem
import com.jaison.minipos.domain.Money
import com.jaison.minipos.pos.PosState
import com.jaison.minipos.pos.PosViewModel
import com.jaison.minipos.printer.BluetoothEscPosPrinter
import com.jaison.minipos.printer.FakeReceiptPrinter
import com.jaison.minipos.printer.PrinterStatus
import com.jaison.minipos.printer.ReceiptPrinter

@SuppressLint("UnusedBoxWithConstraintsScope") // maxWidth is used to choose the layout
@Composable
fun PosScreen(vm: PosViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val printer by vm.printer.collectAsStateWithLifecycle()
    val status by vm.printerStatus.collectAsStateWithLifecycle(initialValue = PrinterStatus.DISCONNECTED)
    var showPrinterDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Mini POS  ·  Order #" + state.orderNumber.toString().padStart(4, '0')
                    )
                },
                actions = {
                    PrinterChip(printer.name, status) { showPrinterDialog = true }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val isTablet = maxWidth >= 600.dp
            if (isTablet) {
                // Tablet: menu on the left, order on the right, like a real POS terminal.
                Row(Modifier.fillMaxSize()) {
                    MenuGrid(
                        vm.menu, enabled = !state.isPrinting, onAdd = vm::addItem,
                        modifier = Modifier.weight(0.6f).fillMaxHeight()
                    )
                    CartPanel(
                        state, onRemoveOne = vm::removeOne, onCheckout = vm::checkout,
                        modifier = Modifier.weight(0.4f).fillMaxHeight()
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    MenuGrid(
                        vm.menu, enabled = !state.isPrinting, onAdd = vm::addItem,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    CartPanel(
                        state, onRemoveOne = vm::removeOne, onCheckout = vm::checkout,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }


    state.lastReceipt?.let { receipt ->
        ReceiptPreviewDialog(receipt, onDismiss = vm::dismissReceipt)
    }

    if (showPrinterDialog) {
        PrinterDialog(vm, current = printer, onDismiss = { showPrinterDialog = false })
    }
}

@Composable
private fun MenuGrid(
    menu: List<MenuItem>,
    enabled: Boolean,
    onAdd: (MenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(menu, key = { it.id }) { item ->
            Card(onClick = { onAdd(item) }, enabled = enabled) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(item.price.format(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun CartPanel(
    state: PosState,
    onRemoveOne: (MenuItem) -> Unit,
    onCheckout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val order = state.order
    Surface(modifier = modifier, tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp)) {
            Text("Current order", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (order.isEmpty) {
                Text("Tap menu items to add them.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(order.lines, key = { it.item.id }) { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${line.quantity} x ${line.item.name}", Modifier.weight(1f))
                        Text(line.lineTotal.format())
                        TextButton(onClick = { onRemoveOne(line.item) }, enabled = !state.isPrinting) {
                            Text("−", fontSize = 20.sp)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TotalRow("Subtotal", order.subtotal)
            TotalRow("Tax", order.tax)
            TotalRow("Total", order.total, bold = true)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onCheckout,
                enabled = !order.isEmpty && !state.isPrinting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.isPrinting) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Printing…")
                } else {
                    Text("Pay & Print  ${order.total.format()}")
                }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: Money, bold: Boolean = false) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontWeight = weight)
        Text(amount.format(), fontWeight = weight)
    }
}

@Composable
private fun PrinterChip(name: String, status: PrinterStatus, onClick: () -> Unit) {
    val dotColor = when (status) {
        PrinterStatus.READY -> Color(0xFF2E7D32)
        PrinterStatus.CONNECTING, PrinterStatus.PRINTING -> Color(0xFFF9A825)
        PrinterStatus.ERROR -> Color(0xFFC62828)
        PrinterStatus.DISCONNECTED -> Color(0xFF9E9E9E)
    }
    AssistChip(
        onClick = onClick,
        label = { Text("$name · ${status.name.lowercase()}") },
        leadingIcon = { Spacer(Modifier.size(10.dp).clip(CircleShape).background(dotColor)) },
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun ReceiptPreviewDialog(receipt: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Receipt") },
        text = {
            Surface(color = Color.White, shape = MaterialTheme.shapes.small) {
                Text(
                    receipt,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.Black,
                    modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                )
            }
        },
    )
}

@Composable
private fun PrinterDialog(vm: PosViewModel, current: ReceiptPrinter, onDismiss: () -> Unit) {
    // Bumped after the permission prompt so the paired-device list re-reads.
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionRefresh++ }
    val hasPermission = remember(permissionRefresh) { vm.hasBluetoothPermission() }
    val devices = remember(permissionRefresh) { vm.pairedDevices() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Printer") },
        text = {
            Column {
                PrinterOption("Simulated printer", selected = current is FakeReceiptPrinter) {
                    vm.useSimulatedPrinter()
                }
                if (current is FakeReceiptPrinter) {
                    TextButton(onClick = vm::simulatePaperOutOnNextPrint) {
                        Text("Simulate paper out on next print")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Paired Bluetooth printers", style = MaterialTheme.typography.titleSmall)
                when {
                    !hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        TextButton(onClick = {
                            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }) { Text("Allow Bluetooth access") }
                    }
                    devices.isEmpty() -> Text(
                        "No paired devices. Pair the printer in Android's Bluetooth settings, then reopen this dialog.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> devices.forEach { device ->
                        val selected = (current as? BluetoothEscPosPrinter)?.address == device.address
                        PrinterOption(device.name, selected) { vm.useBluetoothPrinter(device) }
                    }
                }
            }
        },
    )
}

@Composable
private fun PrinterOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}
