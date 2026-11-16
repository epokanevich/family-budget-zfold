package ru.pokanevich.familybudget

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.SyncState
import ru.pokanevich.familybudget.scanner.QrScannerDialog
import ru.pokanevich.familybudget.service.BankPushParser
import ru.pokanevich.familybudget.ui.components.AiAnalyticsPane
import ru.pokanevich.familybudget.ui.components.ExpensesFeedPane
import ru.pokanevich.familybudget.ui.sheets.AddEditExpenseSheet
import ru.pokanevich.familybudget.ui.sheets.ExpenseDetailSheet
import ru.pokanevich.familybudget.ui.sheets.FamilySettingsSheet
import ru.pokanevich.familybudget.ui.theme.AppThemeMode
import ru.pokanevich.familybudget.ui.theme.AuthorAvatarCircle
import ru.pokanevich.familybudget.ui.theme.FamilyBudgetTheme
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import java.util.Calendar

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

    var currentUserName by remember { mutableStateOf(repository.prefs.currentUserName) }
    var familySpaceCode by remember { mutableStateOf(repository.prefs.familySpaceCode) }
    var monthlyBudgetRub by remember { mutableStateOf(repository.prefs.monthlyBudgetRub) }
    var autoConfirmPush by remember { mutableStateOf(repository.prefs.autoConfirmBankPush) }
    var geminiApiKey by remember { mutableStateOf(repository.prefs.geminiApiKey) }

    // Month selection state
    val nowCal = remember { Calendar.getInstance() }
    val currentYear = nowCal.get(Calendar.YEAR)
    val currentMonth = nowCal.get(Calendar.MONTH)
    var selectedYear by remember { mutableIntStateOf(currentYear) }
    var selectedMonth by remember { mutableIntStateOf(currentMonth) }
    val isCurrentMonth = (selectedYear == currentYear && selectedMonth == currentMonth)

    // Dialog / BottomSheet states
    var showAddEditSheet by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var inspectingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var prefilledAmount by remember { mutableStateOf("") }
    var prefilledMerchant by remember { mutableStateOf("") }

    var selectedTab by remember { mutableIntStateOf(0) }

    var isPushAccessGranted by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPushAccessGranted = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val aiReport = remember(confirmedExpenses, monthlyBudgetRub, selectedYear, selectedMonth) {
        AiBudgetAdvisor.buildInstantLocalReport(
            expenses = confirmedExpenses,
            monthlyBudgetRub = monthlyBudgetRub,
            targetYear = selectedYear,
            targetMonth = selectedMonth
        )
    }

    val stepMonth: (Int) -> Unit = { delta ->
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, delta)
        }
        selectedYear = cal.get(Calendar.YEAR)
        selectedMonth = cal.get(Calendar.MONTH)
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
                text = { Text("Трата ₽", fontWeight = FontWeight.Bold) }
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
                        selectedYear = selectedYear,
                        selectedMonthZeroBased = selectedMonth,
                        isCurrentMonth = isCurrentMonth,
                        showHeroInline = false,
                        isPushAccessGranted = isPushAccessGranted,
                        onPrevMonth = { stepMonth(-1) },
                        onNextMonth = { stepMonth(1) },
                        onJumpToCurrentMonth = {
                            selectedYear = currentYear
                            selectedMonth = currentMonth
                        },
                        onOpenPushSettings = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onConfirmPending = { exp, cat ->
                            scope.launch { repository.confirmPendingExpense(exp, cat) }
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
                        }
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
                        selectedYear = selectedYear,
                        selectedMonthZeroBased = selectedMonth,
                        showHeroAtTop = true,
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
                        selectedYear = selectedYear,
                        selectedMonthZeroBased = selectedMonth,
                        isCurrentMonth = isCurrentMonth,
                        showHeroInline = true,
                        isPushAccessGranted = isPushAccessGranted,
                        onPrevMonth = { stepMonth(-1) },
                        onNextMonth = { stepMonth(1) },
                        onJumpToCurrentMonth = {
                            selectedYear = currentYear
                            selectedMonth = currentMonth
                        },
                        onOpenPushSettings = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onConfirmPending = { exp, cat ->
                            scope.launch { repository.confirmPendingExpense(exp, cat) }
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
                        }
                    )
                } else {
                    AiAnalyticsPane(
                        report = aiReport,
                        geminiApiKey = geminiApiKey,
                        selectedYear = selectedYear,
                        selectedMonthZeroBased = selectedMonth,
                        showHeroAtTop = true,
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
            }
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
            onSave = { amount, merchant, category, author ->
                val existing = editingExpense
                scope.launch {
                    if (existing != null) {
                        repository.addOrUpdateExpense(
                            existing.copy(
                                amountRub = amount,
                                merchant = merchant,
                                categoryName = category.name,
                                authorName = author
                            )
                        )
                    } else {
                        repository.addOrUpdateExpense(
                            ExpenseEntity(
                                amountRub = amount,
                                merchant = merchant,
                                categoryName = category.name,
                                authorName = author,
                                sourceType = if (prefilledAmount.isNotEmpty()) "QR_RECEIPT" else "MANUAL"
                            )
                        )
                    }
                    showAddEditSheet = false
                    editingExpense = null
                    prefilledAmount = ""
                    prefilledMerchant = ""
                }
            }
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
            onOpenPushSettings = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
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
            onSyncNow = {
                scope.launch { repository.syncWithCloudNow() }
            },
            onThemePreview = onThemeModeChange,
            onDismiss = { showSettingsSheet = false },
            onSave = { newName, newCode, newBudget, newAutoConfirm, newApiKey, newTheme ->
                repository.prefs.currentUserName = newName
                repository.prefs.familySpaceCode = newCode
                repository.prefs.monthlyBudgetRub = newBudget
                repository.prefs.autoConfirmBankPush = newAutoConfirm
                repository.prefs.geminiApiKey = newApiKey
                repository.prefs.themeMode = newTheme.prefKey

                currentUserName = newName
                familySpaceCode = newCode
                monthlyBudgetRub = newBudget
                autoConfirmPush = newAutoConfirm
                geminiApiKey = newApiKey
                onThemeModeChange(newTheme)

                repository.startRealtimeSync()
                showSettingsSheet = false
            }
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
