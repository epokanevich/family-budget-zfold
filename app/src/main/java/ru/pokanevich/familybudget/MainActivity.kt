package ru.pokanevich.familybudget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.CsvExporter
import ru.pokanevich.familybudget.data.FamilyPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.MoneyManagerImporter
import ru.pokanevich.familybudget.data.SyncState
import ru.pokanevich.familybudget.scanner.QrScannerDialog
import ru.pokanevich.familybudget.service.BankPushListenerService
import ru.pokanevich.familybudget.service.BankPushParser
import ru.pokanevich.familybudget.ui.components.AiAnalyticsPane
import ru.pokanevich.familybudget.ui.components.ExpensesFeedPane
import ru.pokanevich.familybudget.ui.sheets.AddEditExpenseSheet
import ru.pokanevich.familybudget.ui.sheets.CategoriesAndLimitsSheet
import ru.pokanevich.familybudget.ui.sheets.ExpenseDetailSheet
import ru.pokanevich.familybudget.ui.sheets.FamilySettingsSheet
import ru.pokanevich.familybudget.ui.theme.AppThemeMode
import ru.pokanevich.familybudget.ui.theme.AuthorAvatarCircle
import ru.pokanevich.familybudget.ui.theme.FamilyBudgetTheme
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodCalculator

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = BudgetRepository.getInstance(applicationContext)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isUnfoldedFoldable = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

            var themeMode by remember {
                mutableStateOf(AppThemeMode.fromPref(repository.prefs.themeMode))
            }

            val isDarkEffective = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }

            LaunchedEffect(isDarkEffective) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkEffective) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (isDarkEffective) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    }
                )
            }

            FamilyBudgetTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LocalFamilyPalette.current.background
                ) {
                    FamilyBudgetApp(
                        repository = repository,
                        isUnfoldedFoldable = isUnfoldedFoldable,
                        themeMode = themeMode,
                        onThemeModeChange = { newMode ->
                            themeMode = newMode
                            repository.prefs.themeMode = newMode.prefKey
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyBudgetApp(
    repository: BudgetRepository,
    isUnfoldedFoldable: Boolean,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit
) {
    val palette = LocalFamilyPalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val confirmedExpenses by repository.confirmedExpenses.collectAsState(initial = emptyList())
    val pendingPushExpenses by repository.pendingPushExpenses.collectAsState(initial = emptyList())
    val syncState by repository.syncState.collectAsState()
    val isSyncing by repository.isSyncing.collectAsState()
    val familyConfig by repository.familyConfig.collectAsState()
    val categoryRegistry by repository.categoryRegistry.collectAsState()

    var currentUserName by remember { mutableStateOf(repository.prefs.currentUserName) }
    var familySpaceCode by remember { mutableStateOf(repository.prefs.familySpaceCode) }
    val monthlyBudgetRub = familyConfig.monthlyBudgetRub
    var autoConfirmPush by remember { mutableStateOf(repository.prefs.autoConfirmBankPush) }
    var geminiApiKey by remember { mutableStateOf(repository.prefs.geminiApiKey) }

    // Period & Date navigation state
    var selectedPeriod by remember { mutableStateOf(BudgetPeriod.MONTH) }
    var anchorEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val periodWindow = remember(selectedPeriod, anchorEpochMs) {
        PeriodCalculator.buildWindow(selectedPeriod, anchorEpochMs)
    }

    // Dialog / BottomSheet states
    var showAddEditSheet by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var inspectingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showCategoriesAndLimitsSheet by remember { mutableStateOf(false) }
    var prefilledAmount by remember { mutableStateOf("") }
    var prefilledMerchant by remember { mutableStateOf("") }
    var isImportingBackup by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var pendingCsvBytesToExport by remember { mutableStateOf<ByteArray?>(null) }

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val bytes = pendingCsvBytesToExport
        pendingCsvBytesToExport = null
        if (uri != null && bytes != null) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(bytes)
                            stream.flush()
                        }
                    }.isSuccess
                }
                if (success) {
                    snackbarHostState.showSnackbar(
                        message = "CSV успешно сохранён",
                        duration = SnackbarDuration.Short
                    )
                } else {
                    snackbarHostState.showSnackbar(
                        message = "Ошибка при сохранении CSV",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    val checkBatteryUnrestricted = {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    var isPushAccessGranted by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        )
    }
    var isBatteryUnrestricted by remember { mutableStateOf(checkBatteryUnrestricted()) }

    DisposableEffect(lifecycleOwner, repository) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    repository.setForegroundActive(true)
                }
                Lifecycle.Event.ON_RESUME -> {
                    val granted = NotificationManagerCompat.getEnabledListenerPackages(context)
                        .contains(context.packageName)
                    isPushAccessGranted = granted
                    isBatteryUnrestricted = checkBatteryUnrestricted()
                    if (granted) {
                        runCatching {
                            NotificationListenerService.requestRebind(
                                ComponentName(context, BankPushListenerService::class.java)
                            )
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    repository.setForegroundActive(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            repository.setForegroundActive(false)
        }
    }

    val openBatteryExemption: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }.onFailure {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    val aiReport = remember(confirmedExpenses, monthlyBudgetRub, periodWindow, categoryRegistry) {
        AiBudgetAdvisor.buildInstantLocalReport(
            expenses = confirmedExpenses,
            monthlyBudgetRub = monthlyBudgetRub,
            window = periodWindow,
            registry = categoryRegistry
        )
    }

    val stepPeriod: (Int) -> Unit = { delta ->
        anchorEpochMs = PeriodCalculator.stepAnchor(selectedPeriod, anchorEpochMs, delta)
    }
    val jumpToCurrent: () -> Unit = {
        anchorEpochMs = System.currentTimeMillis()
    }

    Scaffold(
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Surface(color = palette.background) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.background,
                        titleContentColor = palette.textPrimary
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AuthorAvatarCircle(authorName = currentUserName, size = 36.dp)
                            Column {
                                Text(
                                    text = "Семейный котёл",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                SyncStatusPill(
                                    syncState = syncState,
                                    isSyncing = isSyncing,
                                    onClick = { scope.launch { repository.syncWithCloudNow() } }
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showQrScanner = true }) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(palette.surfaceCard),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.QrCodeScanner,
                                    contentDescription = "Сканировать чек ФНС",
                                    tint = palette.textPrimary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(palette.surfaceCard),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "Настройки семьи",
                                    tint = palette.textPrimary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingExpense = null
                    prefilledAmount = ""
                    prefilledMerchant = ""
                    showAddEditSheet = true
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = palette.accentEmerald,
                contentColor = Color.White,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Операция ₽", fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = {
            if (!isUnfoldedFoldable) {
                Column {
                    HorizontalDivider(thickness = 0.7.dp, color = palette.outlineSubtle)
                    NavigationBar(
                        containerColor = palette.surfaceCard,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = palette.accentEmerald,
                                selectedTextColor = palette.accentEmerald,
                                indicatorColor = palette.accentEmeraldSoft,
                                unselectedIconColor = palette.textMuted,
                                unselectedTextColor = palette.textMuted
                            ),
                            icon = {
                                BadgedBox(badge = {
                                    if (pendingPushExpenses.isNotEmpty()) {
                                        Badge(containerColor = palette.accentBlue) {
                                            Text("${pendingPushExpenses.size}", color = Color.White)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Rounded.ReceiptLong, contentDescription = null)
                                }
                            },
                            label = { Text("Лента", fontWeight = FontWeight.SemiBold) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = palette.accentEmerald,
                                selectedTextColor = palette.accentEmerald,
                                indicatorColor = palette.accentEmeraldSoft,
                                unselectedIconColor = palette.textMuted,
                                unselectedTextColor = palette.textMuted
                            ),
                            icon = { Icon(Icons.Rounded.PieChart, contentDescription = null) },
                            label = { Text("Аналитика", fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (isUnfoldedFoldable) {
            // Two-Pane Dashboard for unfolded Samsung Galaxy Z Fold 4/5
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.06f)
                        .fillMaxHeight()
                ) {
                    ExpensesFeedPane(
                        confirmedExpenses = confirmedExpenses,
                        pendingPushExpenses = pendingPushExpenses,
                        report = aiReport,
                        window = periodWindow,
                        showHeroInline = false,
                        isPushAccessGranted = isPushAccessGranted,
                        isBatteryUnrestricted = isBatteryUnrestricted,
                        onSelectPeriod = { selectedPeriod = it },
                        onPrevPeriod = { stepPeriod(-1) },
                        onNextPeriod = { stepPeriod(1) },
                        onJumpToCurrent = jumpToCurrent,
                        onOpenPushSettings = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onRequestBatteryExemption = openBatteryExemption,
                        onConfirmPending = { exp, categoryId ->
                            scope.launch { repository.confirmPendingExpense(exp, categoryId) }
                        },
                        onDismissPending = { exp ->
                            scope.launch { repository.softDeleteExpense(exp) }
                        },
                        onExpenseClick = { exp -> inspectingExpense = exp },
                        onAddClick = {
                            editingExpense = null
                            prefilledAmount = ""
                            prefilledMerchant = ""
                            showAddEditSheet = true
                        },
                        registry = categoryRegistry
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(0.94f)
                        .fillMaxHeight()
                ) {
                    AiAnalyticsPane(
                        report = aiReport,
                        geminiApiKey = geminiApiKey,
                        window = periodWindow,
                        showPeriodSwitcher = false,
                        showHeroAtTop = true,
                        onSelectPeriod = { selectedPeriod = it },
                        onPrevPeriod = { stepPeriod(-1) },
                        onNextPeriod = { stepPeriod(1) },
                        onJumpToCurrent = jumpToCurrent,
                        onOpenSettings = { showSettingsSheet = true }
                    )
                }
            }
        } else {
            // Single-Pane Compact Mode for folded Cover Screen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 14.dp, vertical = 2.dp)
            ) {
                if (selectedTab == 0) {
                    ExpensesFeedPane(
                        confirmedExpenses = confirmedExpenses,
                        pendingPushExpenses = pendingPushExpenses,
                        report = aiReport,
                        window = periodWindow,
                        showHeroInline = true,
                        isPushAccessGranted = isPushAccessGranted,
                        isBatteryUnrestricted = isBatteryUnrestricted,
                        onSelectPeriod = { selectedPeriod = it },
                        onPrevPeriod = { stepPeriod(-1) },
                        onNextPeriod = { stepPeriod(1) },
                        onJumpToCurrent = jumpToCurrent,
                        onOpenPushSettings = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onRequestBatteryExemption = openBatteryExemption,
                        onConfirmPending = { exp, categoryId ->
                            scope.launch { repository.confirmPendingExpense(exp, categoryId) }
                        },
                        onDismissPending = { exp ->
                            scope.launch { repository.softDeleteExpense(exp) }
                        },
                        onExpenseClick = { exp -> inspectingExpense = exp },
                        onAddClick = {
                            editingExpense = null
                            prefilledAmount = ""
                            prefilledMerchant = ""
                            showAddEditSheet = true
                        },
                        registry = categoryRegistry
                    )
                } else {
                    AiAnalyticsPane(
                        report = aiReport,
                        geminiApiKey = geminiApiKey,
                        window = periodWindow,
                        showPeriodSwitcher = true,
                        showHeroAtTop = true,
                        onSelectPeriod = { selectedPeriod = it },
                        onPrevPeriod = { stepPeriod(-1) },
                        onNextPeriod = { stepPeriod(1) },
                        onJumpToCurrent = jumpToCurrent,
                        onOpenSettings = { showSettingsSheet = true }
                    )
                }
            }
        }
    }

    // Expense Details Sheet
    if (inspectingExpense != null) {
        val target = inspectingExpense!!
        ExpenseDetailSheet(
            expense = target,
            allExpenses = confirmedExpenses,
            onSelectExpense = { sibling -> inspectingExpense = sibling },
            onDismiss = { inspectingExpense = null },
            onEdit = { exp ->
                inspectingExpense = null
                editingExpense = exp
                showAddEditSheet = true
            },
            onDelete = { exp ->
                inspectingExpense = null
                scope.launch {
                    repository.softDeleteExpense(exp)
                    val res = snackbarHostState.showSnackbar(
                        message = "Удалено: ${exp.merchant}",
                        actionLabel = "Отменить",
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        repository.restoreExpense(exp)
                    }
                }
            },
            registry = categoryRegistry
        )
    }

    // Add / Edit Expense Sheet
    if (showAddEditSheet) {
        AddEditExpenseSheet(
            editingExpense = editingExpense,
            initialAmount = prefilledAmount,
            initialMerchant = prefilledMerchant,
            defaultAuthorName = currentUserName,
            onOpenQrScanner = {
                showAddEditSheet = false
                showQrScanner = true
            },
            onDismiss = {
                showAddEditSheet = false
                editingExpense = null
            },
            onSave = { amount, merchant, categoryId, author, note, operationType ->
                val existing = editingExpense
                scope.launch {
                    if (existing != null) {
                        repository.addOrUpdateExpense(
                            existing.copy(
                                amountRub = amount,
                                merchant = merchant,
                                categoryName = categoryId,
                                authorName = author,
                                note = note,
                                operationType = operationType
                            )
                        )
                    } else {
                        repository.addOrUpdateExpense(
                            ExpenseEntity(
                                amountRub = amount,
                                merchant = merchant,
                                categoryName = categoryId,
                                authorName = author,
                                sourceType = if (prefilledAmount.isNotEmpty()) "QR_RECEIPT" else "MANUAL",
                                note = note,
                                operationType = operationType
                            )
                        )
                    }
                    showAddEditSheet = false
                    editingExpense = null
                    prefilledAmount = ""
                    prefilledMerchant = ""
                }
            },
            onSaveSplit = { totalAmount, merchant, author, note, parts ->
                val existing = editingExpense
                scope.launch {
                    repository.saveSplitExpenses(
                        original = existing,
                        parts = parts,
                        totalAmountRub = totalAmount,
                        merchant = merchant,
                        authorName = author,
                        note = note,
                        sourceType = if (prefilledAmount.isNotEmpty()) "QR_RECEIPT" else "MANUAL"
                    )
                    showAddEditSheet = false
                    editingExpense = null
                    prefilledAmount = ""
                    prefilledMerchant = ""
                }
            },
            registry = categoryRegistry
        )
    }

    // FNS QR Scanner Dialog
    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onReceiptDetected = { qrData ->
                showQrScanner = false
                editingExpense = null
                prefilledAmount = qrData.amountRub.toString()
                prefilledMerchant = "Чек ФНС"
                showAddEditSheet = true
            }
        )
    }

    // Family Settings Sheet
    if (showSettingsSheet) {
        FamilySettingsSheet(
            currentUserName = currentUserName,
            familySpaceCode = familySpaceCode,
            monthlyBudgetRub = monthlyBudgetRub,
            autoConfirmPush = autoConfirmPush,
            geminiApiKey = geminiApiKey,
            themeMode = themeMode,
            syncState = syncState,
            isPushAccessGranted = isPushAccessGranted,
            isBatteryUnrestricted = isBatteryUnrestricted,
            pushDebugLog = repository.prefs.pushDebugLog,
            isImportingBackup = isImportingBackup,
            importStatusMessage = importStatusMessage,
            onExportCsvCurrentPeriod = {
                val periodExpenses = confirmedExpenses.filter {
                    it.createdAtEpochMs in periodWindow.startMs..periodWindow.endMs
                }
                pendingCsvBytesToExport = CsvExporter.buildCsvBytes(periodExpenses, registry = categoryRegistry)
                val fileName = "family_budget_${selectedPeriod.name.lowercase()}.csv"
                csvExportLauncher.launch(fileName)
            },
            onExportCsvAllTime = {
                pendingCsvBytesToExport = CsvExporter.buildCsvBytes(confirmedExpenses, registry = categoryRegistry)
                val fileName = "family_budget_all.csv"
                csvExportLauncher.launch(fileName)
            },
            onOpenPushSettings = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onRequestBatteryExemption = openBatteryExemption,
            onSimulateTestPush = {
                val sample = BankPushParser.parseNotification(
                    packageName = "ru.sberbankmobile",
                    title = "СберБанк Онлайн",
                    text = "Покупка 1 650 ₽ Пятёрочка Баланс: 48 900 ₽"
                )
                if (sample != null) {
                    scope.launch {
                        repository.ingestBankNotification(
                            amountRub = sample.amountRub,
                            merchant = sample.merchant,
                            sourceType = sample.sourceType,
                            rawText = sample.rawText
                        )
                    }
                }
            },
            onImportBackupUri = { uri ->
                isImportingBackup = true
                importStatusMessage = null
                scope.launch {
                    val res = MoneyManagerImporter.importFromBackupUri(
                        context = context,
                        uri = uri,
                        defaultAuthorName = currentUserName,
                        repository = repository
                    )
                    importStatusMessage = res.fold(
                        onSuccess = { info ->
                            "Готово: найдено ${info.parsedTotal} (${info.expenseCount} расх., ${info.incomeCount} дох.), новых добавлено ${info.newlyAdded}"
                        },
                        onFailure = { err ->
                            "Ошибка импорта: ${err.message}"
                        }
                    )
                    isImportingBackup = false
                }
            },
            onSyncNow = {
                scope.launch { repository.syncWithCloudNow() }
            },
            onOpenCategoriesAndLimits = { showCategoriesAndLimitsSheet = true },
            onThemePreview = onThemeModeChange,
            onDismiss = { showSettingsSheet = false },
            onSave = { newName, newCode, newBudget, newAutoConfirm, newApiKey, newTheme ->
                val previousCode = FamilyPreferences.normalizeFamilyCode(familySpaceCode)
                repository.prefs.currentUserName = newName
                repository.prefs.familySpaceCode = newCode
                val savedCode = repository.prefs.familySpaceCode
                val codeChanged = FamilyPreferences.normalizeFamilyCode(savedCode) != previousCode
                repository.prefs.autoConfirmBankPush = newAutoConfirm
                repository.prefs.geminiApiKey = newApiKey
                repository.prefs.themeMode = newTheme.prefKey

                currentUserName = newName
                familySpaceCode = savedCode
                autoConfirmPush = newAutoConfirm
                geminiApiKey = repository.prefs.geminiApiKey
                onThemeModeChange(newTheme)

                repository.updateMonthlyBudget(newBudget)
                if (codeChanged) {
                    scope.launch { repository.syncWithCloudNow() }
                }
                showSettingsSheet = false
            }
        )
    }

    if (showCategoriesAndLimitsSheet) {
        CategoriesAndLimitsSheet(
            categoryRegistry = categoryRegistry,
            monthlyBudgetRub = monthlyBudgetRub,
            onSaveCategories = { updatedCategories ->
                repository.updateCategories(updatedCategories)
            },
            onDismiss = { showCategoriesAndLimitsSheet = false }
        )
    }
}

@Composable
private fun SyncStatusPill(
    syncState: SyncState,
    isSyncing: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "syncAngle"
    )

    val dotColor = when (syncState) {
        SyncState.CLOUD_CONNECTED -> palette.accentEmerald
        SyncState.SYNCING -> palette.accentBlue
        SyncState.OFFLINE_READY -> palette.accentAmber
    }
    val text = when {
        isSyncing -> "Синхронизация..."
        syncState == SyncState.CLOUD_CONNECTED -> "Облако подключено"
        else -> "Локально (офлайн)"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (isSyncing) {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                tint = palette.accentBlue,
                modifier = Modifier
                    .size(12.dp)
                    .rotate(angle)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
    }
}
