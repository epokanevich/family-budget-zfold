package ru.pokanevich.familybudget

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.ai.AiBudgetReport
import ru.pokanevich.familybudget.ai.CategoryDiagnosis
import ru.pokanevich.familybudget.data.*
import ru.pokanevich.familybudget.scanner.QrScannerDialog
import ru.pokanevich.familybudget.service.BankPushParser
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = BudgetRepository.getInstance(applicationContext)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isUnfoldedFoldable = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4ADE80),
                    onPrimary = Color(0xFF052E16),
                    primaryContainer = Color(0xFF14532D),
                    onPrimaryContainer = Color(0xFFBBF7D0),
                    secondary = Color(0xFF38BDF8),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    surfaceVariant = Color(0xFF334155)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FamilyBudgetApp(
                        repository = repository,
                        isUnfoldedFoldable = isUnfoldedFoldable
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
    isUnfoldedFoldable: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val confirmedExpenses by repository.confirmedExpenses.collectAsState(initial = emptyList())
    val pendingPushExpenses by repository.pendingPushExpenses.collectAsState(initial = emptyList())
    val syncState by repository.syncState.collectAsState()

    var currentUserName by remember { mutableStateOf(repository.prefs.currentUserName) }
    var familySpaceCode by remember { mutableStateOf(repository.prefs.familySpaceCode) }
    var monthlyBudgetRub by remember { mutableStateOf(repository.prefs.monthlyBudgetRub) }
    var autoConfirmPush by remember { mutableStateOf(repository.prefs.autoConfirmBankPush) }
    var geminiApiKey by remember { mutableStateOf(repository.prefs.geminiApiKey) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var prefilledAmount by remember { mutableStateOf("") }
    var prefilledMerchant by remember { mutableStateOf("") }

    var selectedTab by remember { mutableIntStateOf(0) } // Для внешнего экрана Z Fold

    val aiReport = remember(confirmedExpenses, monthlyBudgetRub) {
        AiBudgetAdvisor.buildInstantLocalReport(confirmedExpenses, monthlyBudgetRub)
    }

    val isPushAccessGranted = remember(selectedTab, showSettingsDialog) {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Семейный Бюджет • $currentUserName",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${syncState.label} • Код: $familySpaceCode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { repository.syncWithCloudNow() } }) {
                        Icon(Icons.Default.Sync, contentDescription = "Синхронизировать с облаком")
                    }
                    IconButton(onClick = { showQrScanner = true }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Сканировать чек ФНС")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки семьи и пушей")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    prefilledAmount = ""
                    prefilledMerchant = ""
                    showAddDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Трата ₽") }
            )
        },
        bottomBar = {
            // На сложенном внешнем экране Z Fold показываем нижнюю навигацию,
            // а на разложенном внутреннем экране обе панели видны одновременно!
            if (!isUnfoldedFoldable) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = {
                            BadgedBox(badge = {
                                if (pendingPushExpenses.isNotEmpty()) {
                                    Badge { Text("${pendingPushExpenses.size}") }
                                }
                            }) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null)
                            }
                        },
                        label = { Text("Траты и пуши") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                        label = { Text("ИИ-Анализ") }
                    )
                }
            }
        }
    ) { innerPadding ->
        if (isUnfoldedFoldable) {
            // ДВУХПАНЕЛЬНЫЙ РЕЖИМ ДЛЯ РАЗЛОЖЕННОГО SAMSUNG GALAXY Z FOLD 4/5
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Левая панель: Входящие пуши Сбер/Mir Pay + Лента общих расходов
                Box(modifier = Modifier.weight(1.05f).fillMaxHeight()) {
                    ExpensesFeedPane(
                        confirmedExpenses = confirmedExpenses,
                        pendingPushExpenses = pendingPushExpenses,
                        isPushAccessGranted = isPushAccessGranted,
                        onOpenPushSettings = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onConfirmPending = { exp, cat ->
                            scope.launch { repository.confirmPendingExpense(exp, cat) }
                        },
                        onDeleteExpense = { exp ->
                            scope.launch { repository.softDeleteExpense(exp) }
                        },
                        onSimulateBankPush = {
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
                        }
                    )
                }

                // Правая панель: Сводка общего котла + ИИ-Аналитик (где перебор и где можно потратиться)
                Box(modifier = Modifier.weight(0.95f).fillMaxHeight()) {
                    AiAnalyticsPane(
                        report = aiReport,
                        geminiApiKey = geminiApiKey
                    )
                }
            }
        } else {
            // ОДНОПАНЕЛЬНЫЙ РЕЖИМ ДЛЯ ВНЕШНЕГО ЭКРАНА Z FOLD
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                if (selectedTab == 0) {
                    ExpensesFeedPane(
                        confirmedExpenses = confirmedExpenses,
                        pendingPushExpenses = pendingPushExpenses,
                        isPushAccessGranted = isPushAccessGranted,
                        onOpenPushSettings = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onConfirmPending = { exp, cat ->
                            scope.launch { repository.confirmPendingExpense(exp, cat) }
                        },
                        onDeleteExpense = { exp ->
                            scope.launch { repository.softDeleteExpense(exp) }
                        },
                        onSimulateBankPush = {
                            val sample = BankPushParser.parseNotification(
                                packageName = "ru.nspk.mirpay",
                                title = "Mir Pay",
                                text = "Оплата 1 280 ₽ Евроспар карта МИР-4821"
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
                        }
                    )
                } else {
                    AiAnalyticsPane(
                        report = aiReport,
                        geminiApiKey = geminiApiKey
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddExpenseDialog(
            initialAmount = prefilledAmount,
            initialMerchant = prefilledMerchant,
            currentUserName = currentUserName,
            onDismiss = { showAddDialog = false },
            onAdd = { amount, merchant, category ->
                scope.launch {
                    repository.addOrUpdateExpense(
                        ExpenseEntity(
                            amountRub = amount,
                            merchant = merchant.ifBlank { category.displayName },
                            categoryName = category.name,
                            authorName = currentUserName,
                            sourceType = if (prefilledAmount.isNotEmpty()) "QR_RECEIPT" else "MANUAL"
                        )
                    )
                    showAddDialog = false
                }
            }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onReceiptDetected = { qrData ->
                showQrScanner = false
                prefilledAmount = qrData.amountRub.toString()
                prefilledMerchant = "Чек ФНС"
                showAddDialog = true
            }
        )
    }

    if (showSettingsDialog) {
        FamilySettingsDialog(
            currentUserName = currentUserName,
            familySpaceCode = familySpaceCode,
            monthlyBudgetRub = monthlyBudgetRub,
            autoConfirmPush = autoConfirmPush,
            geminiApiKey = geminiApiKey,
            isPushAccessGranted = isPushAccessGranted,
            onOpenPushSettings = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onDismiss = { showSettingsDialog = false },
            onSave = { newName, newCode, newBudget, newAutoConfirm, newApiKey ->
                repository.prefs.currentUserName = newName
                repository.prefs.familySpaceCode = newCode
                repository.prefs.monthlyBudgetRub = newBudget
                repository.prefs.autoConfirmBankPush = newAutoConfirm
                repository.prefs.geminiApiKey = newApiKey

                currentUserName = newName
                familySpaceCode = newCode
                monthlyBudgetRub = newBudget
                autoConfirmPush = newAutoConfirm
                geminiApiKey = newApiKey

                repository.startRealtimeSync()
                showSettingsDialog = false
            }
        )
    }
}

@Composable
fun ExpensesFeedPane(
    confirmedExpenses: List<ExpenseEntity>,
    pendingPushExpenses: List<ExpenseEntity>,
    isPushAccessGranted: Boolean,
    onOpenPushSettings: () -> Unit,
    onConfirmPending: (ExpenseEntity, ExpenseCategory) -> Unit,
    onDeleteExpense: (ExpenseEntity) -> Unit,
    onSimulateBankPush: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("dd MMM, HH:mm", Locale("ru")) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        // Статус перехвата уведомлений СберБанка и Mir Pay
        if (!isPushAccessGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Включите чтение пушей СберБанка и Mir Pay",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Приложение слушает только Сбер и Mir Pay. Уведомления Т-Банка и личных мессенджеров игнорируются.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenPushSettings) {
                                Text("Дать доступ к уведомлениям")
                            }
                            OutlinedButton(onClick = onSimulateBankPush) {
                                Text("Тест пуша")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Автоперехват Сбер + Mir Pay активен (Т-Банк отключён)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = onSimulateBankPush) {
                        Text("+ Тест пуша")
                    }
                }
            }
        }

        // Очередь распознанных пушей на подтверждение в 1 тап
        if (pendingPushExpenses.isNotEmpty()) {
            item {
                Text(
                    text = "Новые покупки из пушей (${pendingPushExpenses.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }
            items(pendingPushExpenses, key = { it.id }) { pending ->
                PendingPushCard(
                    expense = pending,
                    onConfirm = { cat -> onConfirmPending(pending, cat) },
                    onDismiss = { onDeleteExpense(pending) }
                )
            }
        }

        item {
            Text(
                text = "Общая лента расходов семьи",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (confirmedExpenses.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Пока нет записанных трат",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Добавьте трату кнопкой «Трата ₽», отсканируйте QR чека или нажмите «Тест пуша».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(confirmedExpenses, key = { it.id }) { expense ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = expense.category.emoji,
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Column {
                                Text(
                                    text = expense.merchant,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val badge = when (expense.sourceType) {
                                    "PUSH_SBER" -> "Сбер Пуш"
                                    "PUSH_MIRPAY" -> "Mir Pay"
                                    "QR_RECEIPT" -> "QR чека"
                                    else -> "Вручную"
                                }
                                Text(
                                    text = "${expense.category.displayName} • ${expense.authorName} • $badge • ${dateFormatter.format(Date(expense.createdAtEpochMs))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${expense.amountRub.roundToInt()} ₽",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { onDeleteExpense(expense) }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Удалить",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PendingPushCard(
    expense: ExpenseEntity,
    onConfirm: (ExpenseCategory) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember(expense.id) { mutableStateOf(expense.category) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${expense.merchant} — ${expense.amountRub.roundToInt()} ₽",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(if (expense.sourceType == "PUSH_MIRPAY") "Mir Pay" else "Сбер")
                    }
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ExpenseCategory.entries) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text("${cat.emoji} ${cat.displayName}") }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Пропустить")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onConfirm(selectedCategory) }) {
                    Text("В бюджет (${selectedCategory.emoji})")
                }
            }
        }
    }
}

@Composable
fun AiAnalyticsPane(
    report: AiBudgetReport,
    geminiApiKey: String
) {
    val scope = rememberCoroutineScope()
    var cloudAdvice by remember { mutableStateOf<String?>(null) }
    var isLoadingCloudAi by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Главная карточка общего семейного котла
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Общий бюджет на месяц",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${report.totalSpentThisMonthRub.roundToInt()} ₽ из ${report.monthlyBudgetRub.roundToInt()} ₽",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold
                )

                val progress = (report.totalSpentThisMonthRub / report.monthlyBudgetRub.coerceAtLeast(1.0))
                    .toFloat()
                    .coerceIn(0f, 1f)

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                )

                Text(
                    text = report.headlineVerdict,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Где перебор с тратами
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🚨 Где перебор по темпу трат",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF87171)
                )
                if (report.overburnedCategories.isEmpty()) {
                    Text(
                        text = "Ни в одной категории нет перерасхода относительно дня месяца!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    report.overburnedCategories.forEach { diag ->
                        Text(
                            text = "${diag.category.emoji} ${diag.category.displayName}: ${diag.message}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Где есть запас и можно потратиться
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "✨ Где есть запас (можно потратиться)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (report.splurgeCategories.isEmpty()) {
                    Text(
                        text = "Все категории идут плотно к графику — лучше придерживаться дневного лимита ${report.safeDailySpendRemainingRub.roundToInt()} ₽/день.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    report.splurgeCategories.forEach { diag ->
                        Text(
                            text = "${diag.category.emoji} ${diag.category.displayName}: ${diag.message}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Облачный ИИ-советник (Gemini API Free Tier)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🤖 Глубокий ИИ-разбор недели",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        enabled = !isLoadingCloudAi && geminiApiKey.isNotBlank(),
                        onClick = {
                            isLoadingCloudAi = true
                            scope.launch {
                                val res = AiBudgetAdvisor.fetchDeepCloudAiAdvice(geminiApiKey, report)
                                cloudAdvice = res.getOrElse { "Ошибка запроса: ${it.message}" }
                                isLoadingCloudAi = false
                            }
                        }
                    ) {
                        Text(if (isLoadingCloudAi) "Анализ..." else "Спросить ИИ")
                    }
                }

                if (geminiApiKey.isBlank()) {
                    Text(
                        text = "Локальный ИИ-анализ темпа уже работает выше офлайн. Если хотите текстовые советы от нейросети, добавьте бесплатный ключ Gemini API в Настройках (⚙️).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (cloudAdvice != null) {
                    Text(
                        text = cloudAdvice!!,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(72.dp))
    }
}

@Composable
fun AddExpenseDialog(
    initialAmount: String,
    initialMerchant: String,
    currentUserName: String,
    onDismiss: () -> Unit,
    onAdd: (Double, String, ExpenseCategory) -> Unit
) {
    var amountText by remember { mutableStateOf(initialAmount) }
    var merchantText by remember { mutableStateOf(initialMerchant) }
    var selectedCategory by remember {
        mutableStateOf(ExpenseCategory.guessFromMerchant(initialMerchant))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая трата в общий бюджет") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Сумма в рублях (₽)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = merchantText,
                    onValueChange = {
                        merchantText = it
                        val guessed = ExpenseCategory.guessFromMerchant(it)
                        if (guessed != ExpenseCategory.OTHER) {
                            selectedCategory = guessed
                        }
                    },
                    label = { Text("Магазин или описание (например, Пятёрочка)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Категория:",
                    style = MaterialTheme.typography.labelMedium
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ExpenseCategory.entries) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text("${cat.emoji} ${cat.displayName}") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.replace(",", ".").toDoubleOrNull() ?: 0.0
                    if (amt > 0.0) {
                        onAdd(amt, merchantText.trim(), selectedCategory)
                    }
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun FamilySettingsDialog(
    currentUserName: String,
    familySpaceCode: String,
    monthlyBudgetRub: Double,
    autoConfirmPush: Boolean,
    geminiApiKey: String,
    isPushAccessGranted: Boolean,
    onOpenPushSettings: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Boolean, String) -> Unit
) {
    var name by remember { mutableStateOf(currentUserName) }
    var code by remember { mutableStateOf(familySpaceCode) }
    var budget by remember { mutableStateOf(monthlyBudgetRub.roundToInt().toString()) }
    var autoConfirm by remember { mutableStateOf(autoConfirmPush) }
    var apiKey by remember { mutableStateOf(geminiApiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки семейного бюджета") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Кто пользуется этим телефоном:",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = name == "Евгений",
                        onClick = { name = "Евгений" },
                        label = { Text("Евгений") }
                    )
                    FilterChip(
                        selected = name == "Анжелика",
                        onClick = { name = "Анжелика" },
                        label = { Text("Анжелика") }
                    )
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Общий код семьи (одинаковый на обоих телефонах)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = budget,
                    onValueChange = { budget = it },
                    label = { Text("Общий бюджет на месяц (₽)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Сразу заносить пуши Сбера/Mir Pay без подтверждения",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoConfirm,
                        onCheckedChange = { autoConfirm = it }
                    )
                }

                OutlinedButton(
                    onClick = onOpenPushSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isPushAccessGranted) "Доступ к пушам предоставлен ✓"
                        else "Открыть системный доступ к пушам"
                    )
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Ключ Gemini API (необязательно, для текстовых советов)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedBudget = budget.toDoubleOrNull() ?: 120000.0
                    onSave(name, code, parsedBudget, autoConfirm, apiKey)
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
