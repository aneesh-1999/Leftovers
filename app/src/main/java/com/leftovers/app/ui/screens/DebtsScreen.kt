package com.leftovers.app.ui.screens

import com.leftovers.app.ui.components.Haptics
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.AccountRepository
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.Debt
import com.leftovers.app.data.MAX_DEBT_PHOTOS
import com.leftovers.app.data.PersonBalance
import com.leftovers.app.data.PlanningRepository
import com.leftovers.app.data.openBalances
import com.leftovers.app.data.photosToPath
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.icons.CategoryIcons
import com.leftovers.app.ui.components.sharedContainer
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.components.EmptyState
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SegmentedToggle
import com.leftovers.app.ui.components.appear
import com.leftovers.app.ui.components.frosted
import com.leftovers.app.ui.components.pressable
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.AmountInput
import com.leftovers.app.util.LocalMoney
import com.leftovers.app.util.ReceiptStore
import com.leftovers.app.util.friendlyLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

import com.leftovers.app.data.toAppCurrency
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.ListRow
import com.leftovers.app.util.currencyOptions

class DebtsViewModel(
    private val planning: PlanningRepository,
    accountRepository: com.leftovers.app.data.AccountRepository,
    private val settings: com.leftovers.app.data.SettingsRepository,
) : ViewModel() {
    /** How friends can pay you back, put in your reminders. */
    val pay: StateFlow<com.leftovers.app.util.SharedOwed.Pay?> =
        settings.payDetailsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setPay(pay: com.leftovers.app.util.SharedOwed.Pay) = viewModelScope.launch { settings.setPayDetails(com.leftovers.app.util.SharedOwed.clean(pay)) }

    val accounts: StateFlow<List<com.leftovers.app.data.AccountWithBalance>> =
        accountRepository.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val defaultAccountId: StateFlow<Long?> =
        settings.settings.map<com.leftovers.app.data.AppSettings, Long?> { it.defaultAccountId }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val rates: StateFlow<Map<String, String>> =
        settings.settings.map { it.foreignRates }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val appCurrency: StateFlow<String> =
        settings.settings.map { it.currencyCode }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "EUR")

    /** Null until loaded, so the screen doesn't flash "All square" first. */
    val balances: StateFlow<List<PersonBalance>?> =
        planning.debts.map { it.openBalances() }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everyone ever recorded, for quick picking. */
    val people: StateFlow<List<String>> =
        planning.debts.map { all -> all.map { it.person.trim() }.distinctBy { it.lowercase() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(debt: Debt) = viewModelScope.launch { planning.saveDebt(debt) }
    fun delete(debt: Debt) = viewModelScope.launch { planning.deleteDebt(debt) }
    fun settleEntry(debt: Debt) = viewModelScope.launch { planning.settleEntry(debt) }
    fun settle(person: String) = viewModelScope.launch { planning.settle(person) }
    fun setForeignRate(code: String, rate: String) = viewModelScope.launch { settings.setForeignRate(code, rate) }
    fun settleUp(person: String, amountMinor: Long, accountId: Long?, currency: String? = null, nativeMinor: Long? = null) =
        viewModelScope.launch { planning.settleUp(person, amountMinor, accountId, chosen = true, currency = currency, nativeMinor = nativeMinor) }
}

private val returnDateChipFormatter = DateTimeFormatter.ofPattern("MMM d")
private val returnDateFullFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")

/** Money lent to and borrowed from people. It never counts as spending or income. */
@Composable
fun DebtsScreen(onBack: () -> Unit, viewModel: DebtsViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val loadedBalances by viewModel.balances.collectAsStateWithLifecycle()
    val balances = loadedBalances.orEmpty()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val defaultAccountId by viewModel.defaultAccountId.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val appCurrency by viewModel.appCurrency.collectAsStateWithLifecycle()
    val c = LocalAppColors.current
    val money = LocalMoney.current
    var editing by remember { mutableStateOf<Debt?>(null) }
    var settling by remember { mutableStateOf<String?>(null) }
    val pay by viewModel.pay.collectAsStateWithLifecycle()
    var editingPay by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    val owedToYou = balances.filter { it.netMinor > 0 }.sumOf { it.netMinor }
    val youOwe = balances.filter { it.netMinor < 0 }.sumOf { -it.netMinor }
    val todayEpoch = LocalDate.now().toEpochDay()

    GlassScreen(
        title = "Money owed",
        subtitle = "Lent and borrowed · kept out of your budget",
        onBack = onBack,
        bottomBar = {
            Box(Modifier.frosted(fadeTop = true).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 16.dp)) {
                PrimaryButton("Add", { editing = Debt(person = "", amountMinor = 0) }, Modifier.fillMaxWidth(), icon = Lucide.Plus)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loadedBalances == null) return@LazyColumn
            if (balances.isEmpty()) {
                item {
                    Glass(Modifier.fillMaxWidth().appear(0)) {
                        EmptyState(
                            Lucide.HandCoins,
                            "All square",
                            "Note down money you lend or borrow, like splitting a dinner. Settle up when it's paid back.",
                        )
                    }
                }
                return@LazyColumn
            }
            item {
                Glass(Modifier.fillMaxWidth().appear(0), strong = true, shape = RoundedCornerShape(32.dp)) {
                    Row(Modifier.padding(22.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("You're owed", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                            Text(money.format(owedToYou), style = MaterialTheme.typography.headlineSmall, color = c.positive)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text("You owe", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                            Text(money.format(youOwe), style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                        }
                    }
                }
            }
            // Someone owes you: your UPI ID or payment link can go in the reminder, so paying you back is one tap for them.
            if (owedToYou > 0 && pay != null) {
                item(key = "pay") {
                    val p = pay!!
                    Glass(Modifier.fillMaxWidth().appear(1), shape = RoundedCornerShape(26.dp)) {
                        com.leftovers.app.ui.components.ListRow(
                            if (p.isEmpty) "Get paid back in one tap" else "Reminders say how to pay you",
                            subtitle = if (p.isEmpty) "Add your UPI ID or a payment link" else listOf(p.upi, p.linkHost).filter { it.isNotBlank() }.joinToString(" · "),
                            leading = { com.leftovers.app.ui.components.IconTile(Lucide.Wallet, c.positive, size = 38.dp) },
                            trailing = { androidx.compose.material3.Icon(Lucide.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp)) },
                            onClick = { editingPay = true },
                        )
                    }
                }
            }
            items(balances, key = { it.person.lowercase() }) { b ->
                val singleForeign = b.entries.mapNotNull { it.currency?.takeIf { cur -> cur != money.currency.currencyCode } }.distinct().singleOrNull()
                    ?.takeIf { sf -> b.entries.all { e -> e.currency == sf } }
                val balanceSubtitle = if (singleForeign != null) {
                    val fMoney = com.leftovers.app.util.Money(singleForeign)
                    val fNet = b.entries.sumOf { it.nativeAmount }
                    when {
                        fNet > 0 -> "Owes you ${fMoney.format(fNet)} (≈ ${money.format(b.netMinor)})"
                        fNet < 0 -> "You owe ${fMoney.format(-fNet)} (≈ ${money.format(-b.netMinor)})"
                        b.pendingOnly -> "Amount pending"
                        else -> "Even"
                    }
                } else {
                    when {
                        b.netMinor > 0 -> "Owes you ${money.format(b.netMinor)}"
                        b.netMinor < 0 -> "You owe ${money.format(-b.netMinor)}"
                        b.pendingOnly -> "Amount pending"
                        else -> "Even"
                    }
                }
                Glass(Modifier.sharedContainer("owed-${b.person.lowercase()}").fillMaxWidth().appear(1), shape = RoundedCornerShape(26.dp)) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.person, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(
                                    balanceSubtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (b.netMinor > 0) c.positive else c.textSecondary,
                                )
                            }
                            // Someone who owes you can be reminded, in a message you send from WhatsApp, SMS or any app.
                            if (b.netMinor > 0) {
                                val context = androidx.compose.ui.platform.LocalContext.current
                                Spacer(Modifier.padding(start = 12.dp))
                                val remindScope = rememberCoroutineScope()
                                val remFormat: (Long) -> String = if (singleForeign != null) {
                                    val fMoney = com.leftovers.app.util.Money(singleForeign)
                                    val fTotal = b.entries.sumOf { it.nativeAmount }
                                    val fn: (Long) -> String = { _ -> fMoney.format(fTotal) }
                                    fn
                                } else {
                                    { amt: Long -> money.format(amt) }
                                }
                                RoundButton(Lucide.Bell, "Remind ${b.person}", {
                                    remindScope.launch { com.leftovers.app.util.DebtReminder.share(context, com.leftovers.app.util.DebtReminder.withLink(context, b, money.currency.currencyCode, remFormat), b.person) }
                                })
                                Spacer(Modifier.padding(start = 8.dp))
                            }
                            // Nothing to pay back until an amount is in.
                            if (!b.pendingOnly) Chip(if (b.netMinor > 0) "Received" else "Paid back", { settling = b.person }, icon = Lucide.Check)
                        }
                        b.entries.forEach { d ->
                            RowDivider(inset = 16.dp)
                            Row(
                                Modifier.fillMaxWidth().pressable({ editing = d }, pressedScale = 0.985f).padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val isForeign = d.currency != null && d.currency != money.currency.currencyCode
                                Column(Modifier.weight(1f)) {
                                    val account = accounts.firstOrNull { it.id == d.accountId }
                                    val details = listOfNotNull(
                                        "Split".takeIf { d.splitOf != null },
                                        d.note.ifBlank { null },
                                        account?.let { if (d.amountMinor > 0) "From ${it.name}" else "To ${it.name}" },
                                        LocalDate.ofEpochDay(d.epochDay).friendlyLabel(),
                                    ).joinToString(" · ")
                                    Text(
                                        details,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textSecondary,
                                    )
                                    if (isForeign) {
                                        Text(
                                            "≈ ${money.format(abs(d.amountMinor))} at logged rate",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.textTertiary,
                                            modifier = Modifier.padding(top = 1.dp),
                                        )
                                    }
                                    if (d.dueDate != null) {
                                        val isOverdue = d.dueDateEpochDay!! < todayEpoch
                                        val isToday = d.dueDateEpochDay == todayEpoch
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(top = 2.dp),
                                        ) {
                                            Icon(
                                                if (isOverdue) Lucide.Clock else if (d.reminderEnabled) Lucide.Bell else Lucide.Calendar,
                                                contentDescription = null,
                                                tint = if (isOverdue) c.negative else if (isToday) c.accent else c.textTertiary,
                                                modifier = Modifier.size(11.dp),
                                            )
                                            Text(
                                                when {
                                                    isOverdue -> "Overdue (${d.dueDate!!.format(returnDateChipFormatter)})"
                                                    isToday -> "Due today"
                                                    else -> "Return by ${d.dueDate!!.format(returnDateChipFormatter)}"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isOverdue) c.negative else if (isToday) c.accent else c.textSecondary,
                                            )
                                        }
                                    }
                                }
                                if (d.photos.isNotEmpty()) {
                                    Icon(Lucide.Camera, contentDescription = if (d.photos.size == 1) "Has a photo" else "Has ${d.photos.size} photos", tint = c.textTertiary, modifier = Modifier.padding(end = 8.dp).size(14.dp))
                                }
                                val entryMoney = d.currency?.let { com.leftovers.app.util.Money(it) } ?: money
                                val displayAmount = abs(d.nativeAmount)
                                Text(
                                    if (d.isPending) (if (d.theyOwe) "Lent · amount pending" else "Borrowed · amount pending")
                                    else (if (d.repayment) (if (d.amountMinor < 0) "Received " else "Paid back ") else if (d.amountMinor > 0) "Lent " else "Borrowed ") + entryMoney.format(displayAmount),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = c.textPrimary,
                                )
                                // Like other rows that open something, it ends in a chevron.
                                Icon(Lucide.ChevronRight, contentDescription = "Edit", tint = c.textTertiary, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    balances.firstOrNull { it.person.equals(settling, ignoreCase = true) }?.let { b ->
        SettleSheet(b, accounts, onDismiss = { settling = null }) { amount, account, currency, nativeMinor ->
            viewModel.settleUp(b.person, amount, account, currency = currency, nativeMinor = nativeMinor)
            Haptics.confirm(view)
            settling = null
        }
    }

    if (editingPay) PayDetailsSheet(pay ?: com.leftovers.app.util.SharedOwed.Pay(), onSave = { viewModel.setPay(it); editingPay = false }, onDismiss = { editingPay = false })
    editing?.let { debt ->
        DebtEditor(
            initial = debt,
            people = people,
            accounts = accounts,
            defaultAccountId = defaultAccountId,
            rates = rates,
            onSaveRate = viewModel::setForeignRate,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
            onDelete = if (debt.id != 0L) {
                {
                    viewModel.delete(debt)
                    editing = null
                }
            } else {
                null
            },
            onSettle = {
                viewModel.settleEntry(it)
                editing = null
            },
        )
    }
}

/** A settled entry's story: what it was, when it was lent or borrowed and paid back, the account, and where it came from. */
@Composable
private fun SettledDetails(d: Debt, accounts: List<com.leftovers.app.data.AccountWithBalance>) {
    val c = LocalAppColors.current
    val money = com.leftovers.app.util.LocalMoney.current
    val lent = d.theyOwe
    val rows = listOfNotNull(
        (if (lent) "Lent" else "Borrowed") to money.format(abs(d.amountMinor)),
        "On" to LocalDate.ofEpochDay(d.epochDay).friendlyLabel(),
        "Settled" to (d.settledOn?.let { LocalDate.ofEpochDay(it).friendlyLabel() } ?: "Paid back"),
        (if (lent) "Paid from" else "Received into") to (accounts.firstOrNull { it.id == d.accountId }?.name ?: "No account"),
        ("From" to "A split bill").takeIf { d.splitOf != null },
        "Added" to java.time.Instant.ofEpochMilli(d.createdAt).atZone(java.time.ZoneId.systemDefault()).let {
            it.toLocalDate().friendlyLabel() + " · " + it.toLocalTime().format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
        },
    )
    com.leftovers.app.ui.components.Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Check, contentDescription = null, tint = c.positive, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (lent) "Received · settled" else "Paid back · settled", style = MaterialTheme.typography.titleSmall, color = c.positive)
            }
            rows.forEach { (label, value) ->
                Row {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DebtEditor(
    initial: Debt,
    people: List<String>,
    onDismiss: () -> Unit,
    onSave: (Debt) -> Unit,
    onDelete: (() -> Unit)?,
    /** This entry paid back in full, with any changes made here: Received for money lent, Paid back for borrowed. */
    onSettle: ((Debt) -> Unit)? = null,
    accounts: List<com.leftovers.app.data.AccountWithBalance> = emptyList(),
    defaultAccountId: Long? = null,
    rates: Map<String, String> = emptyMap(),
    onSaveRate: ((code: String, rate: String) -> Unit)? = null,
) {
    val c = LocalAppColors.current
    val homeMoney = LocalMoney.current
    val appCurrency = homeMoney.currency.currencyCode
    // New entries start on the default account; older ones made before this keep no account until one is picked.
    val known = accounts.map { it.id }
    var accountId by rememberSaveable {
        mutableStateOf(
            if (initial.id == 0L) initial.accountId ?: defaultAccountId?.takeIf { it in known } ?: known.firstOrNull()
            else initial.accountId,
        )
    }
    var lent by rememberSaveable { mutableStateOf(initial.id == 0L && initial.amountMinor == 0L || initial.theyOwe) }
    var person by rememberSaveable { mutableStateOf(initial.person) }

    // Multi-currency support
    var currency by rememberSaveable { mutableStateOf(initial.currency) }
    var rate by rememberSaveable { mutableStateOf(initial.currency?.let { rates[it] }.orEmpty()) }
    var pickCurrency by rememberSaveable { mutableStateOf(false) }

    val startMinor = if (initial.currency != null && initial.nativeMinor != null) abs(initial.nativeMinor) else abs(initial.amountMinor)
    var amountText by rememberSaveable { mutableStateOf(if (startMinor != 0L) AmountInput.fromMinor(startMinor) else "") }

    var note by rememberSaveable { mutableStateOf(initial.note) }
    var dueDateEpochDay by rememberSaveable { mutableStateOf(initial.dueDateEpochDay) }
    var reminderEnabled by rememberSaveable { mutableStateOf(initial.reminderEnabled) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    // Paths, one per line, so the list survives rotation like the other fields.
    var photoText by rememberSaveable { mutableStateOf(initial.receiptPath.orEmpty()) }
    val photos = photoText.split('\n').filter { it.isNotBlank() }
    val original = initial.photos
    var viewing by remember { mutableStateOf<String?>(null) }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (pickCurrency) {
        CurrencyPickerSheet(currency ?: appCurrency, onDismiss = { pickCurrency = false }) { code ->
            currency = code.takeIf { it != appCurrency }
            if (currency != null && rate.isBlank()) rate = rates[code].orEmpty()
            pickCurrency = false
        }
    }

    fun selectAccount(newAccountId: Long?) {
        accountId = newAccountId
        // If this is a new debt and user hasn't explicitly chosen a custom currency, adopt account's currency
        if (initial.id == 0L && currency == null) {
            val acc = accounts.firstOrNull { it.id == newAccountId }
            if (acc?.currency != null) {
                currency = acc.currency
                if (rate.isBlank()) rate = rates[acc.currency].orEmpty()
            }
        }
    }

    fun selectDueDate(date: LocalDate?) {
        dueDateEpochDay = date?.toEpochDay()
        if (date != null && !reminderEnabled) {
            reminderEnabled = true
        }
    }

    fun attach(uris: List<Uri>, cleanup: () -> Unit = {}) {
        scope.launch {
            val added = uris.mapNotNull { ReceiptStore.import(context, it) }
            cleanup()
            val all = photoText.split('\n').filter { it.isNotBlank() } + added
            all.drop(MAX_DEBT_PHOTOS).forEach { if (it !in original) ReceiptStore.delete(it) }
            photoText = all.take(MAX_DEBT_PHOTOS).joinToString("\n")
        }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_DEBT_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) attach(uris)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile
        if (ok && file != null) attach(listOf(Uri.fromFile(file))) { file.delete() } else file?.delete()
    }
    // Photos added but not saved are thrown away.
    val close = {
        photos.filter { it !in original }.forEach { ReceiptStore.delete(it) }
        onDismiss()
    }

    val selectedDueDate = dueDateEpochDay?.let { LocalDate.ofEpochDay(it) }
    val today = LocalDate.now()

    GlassSheet(close) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (initial.id == 0L) "Lent or borrowed" else initial.person, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            // Paid back already: what it was, when, and through which account, before anything to change.
            if (initial.settled && !initial.repayment) SettledDetails(initial, accounts)
            SegmentedToggle(listOf(true, false), lent, { if (it) "I lent" else "I borrowed" }, { lent = it }, Modifier.fillMaxWidth())
            GlassTextField(person, { person = it.take(30) }, placeholder = "e.g. Nick", label = if (lent) "To" else "From")
            val matches = people.filter { it.contains(person.trim(), ignoreCase = true) && !it.equals(person.trim(), ignoreCase = true) }.take(6)
            if (matches.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEach { Chip(it, { person = it }) }
                }
            }
            val curSymbol = currency?.let { com.leftovers.app.util.Money(it).symbol } ?: homeMoney.symbol
            val rawMinorInput = AmountInput.toMinor(amountText) ?: 0L
            MoneyField(amountText, { amountText = it }, label = if (rawMinorInput > 0) "Amount" else "Amount (leave empty if you don't know it yet)", symbol = curSymbol)

            ListRow(
                "Currency",
                subtitle = currency?.let { code -> currencyOptions.firstOrNull { it.code == code }?.let { "${it.name} (${it.code})" } ?: code }
                    ?: "Same as the app ($appCurrency)",
                leading = { IconTile(Lucide.Coins, c.textPrimary, size = 38.dp) },
                trailing = { Icon(Lucide.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp)) },
                onClick = { pickCurrency = true },
            )
            if (currency != null && currency != appCurrency) {
                GlassTextField(
                    rate,
                    { v -> rate = v.filter { it.isDigit() || it == '.' }.take(12) },
                    placeholder = "e.g. 1.08",
                    label = "1 $currency in ${homeMoney.symbol}",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                )
                val rawMinor = AmountInput.toMinor(amountText) ?: 0L
                val converted = if (rawMinor > 0 && rate.isNotBlank()) toAppCurrency(rawMinor, rate) else 0L
                if (converted > 0) {
                    Text(
                        "≈ ${homeMoney.format(converted)} in your main currency",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.accent,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            GlassTextField(note, { note = it.take(60) }, placeholder = "e.g. Dinner at Toit", label = "Note")

            // Account selection (deduct from or deposit into)
            if (accounts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (lent) "Paid from" else "Received into",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.textSecondary,
                        )
                        val selectedAcc = accounts.firstOrNull { it.id == accountId }
                        if (selectedAcc != null) {
                            Text(
                                selectedAcc.name + (selectedAcc.currency?.let { " ($it)" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = c.accent,
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        accounts.forEach { a ->
                            Chip(
                                text = a.name + (a.currency?.let { " ($it)" } ?: ""),
                                onClick = { selectAccount(if (accountId == a.id) null else a.id) },
                                icon = CategoryIcons[a.icon],
                                iconTint = Color(a.color),
                                selected = accountId == a.id,
                            )
                        }
                        Chip("No account", { selectAccount(null) }, selected = accountId == null)
                    }
                }
            }

            // Return / Due Date & Reminder Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (lent) "When they return" else "When I return",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textSecondary,
                    )
                    if (selectedDueDate != null) {
                        Text(
                            selectedDueDate.format(returnDateFullFormatter),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.accent,
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val tomorrow = today.plusDays(1)
                    val in1Week = today.plusWeeks(1)
                    val in2Weeks = today.plusWeeks(2)
                    val in1Month = today.plusMonths(1)

                    Chip("Tomorrow", { selectDueDate(tomorrow) }, selected = dueDateEpochDay == tomorrow.toEpochDay())
                    Chip("In 1 week", { selectDueDate(in1Week) }, selected = dueDateEpochDay == in1Week.toEpochDay())
                    Chip("In 2 weeks", { selectDueDate(in2Weeks) }, selected = dueDateEpochDay == in2Weeks.toEpochDay())
                    Chip("In 1 month", { selectDueDate(in1Month) }, selected = dueDateEpochDay == in1Month.toEpochDay())
                    Chip(
                        text = if (selectedDueDate != null && dueDateEpochDay !in listOf(tomorrow.toEpochDay(), in1Week.toEpochDay(), in2Weeks.toEpochDay(), in1Month.toEpochDay())) {
                            selectedDueDate.format(returnDateChipFormatter)
                        } else {
                            "Pick date"
                        },
                        onClick = { showDatePicker = true },
                        icon = Lucide.Calendar,
                        selected = selectedDueDate != null && dueDateEpochDay !in listOf(tomorrow.toEpochDay(), in1Week.toEpochDay(), in2Weeks.toEpochDay(), in1Month.toEpochDay()),
                    )
                    if (dueDateEpochDay != null) {
                        Chip("No date", { dueDateEpochDay = null; reminderEnabled = false }, icon = Lucide.X)
                    }
                }
            }

            // Notification Reminder Card
            if (dueDateEpochDay != null) {
                Glass(shape = RoundedCornerShape(18.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable({ reminderEnabled = !reminderEnabled })
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(if (reminderEnabled) c.accent.copy(alpha = 0.15f) else c.glassStrong, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Lucide.Bell,
                                contentDescription = null,
                                tint = if (reminderEnabled) c.accent else c.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Reminder notification",
                                style = MaterialTheme.typography.titleSmall,
                                color = c.textPrimary,
                            )
                            Text(
                                "Notify on return date at 9:00 AM",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }
                        Switch(
                            checked = reminderEnabled,
                            onCheckedChange = { reminderEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = c.accent,
                                uncheckedThumbColor = c.textTertiary,
                                uncheckedTrackColor = c.glassStrong,
                                uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }

            // Photos of the bill, chat screenshots or payment receipts. Everything wraps on narrow phones.
            if (photos.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    photos.forEach { p ->
                        ReceiptThumb(p, onOpen = { viewing = p }, size = 64.dp)
                    }
                }
            }
            if (photos.size < MAX_DEBT_PHOTOS) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Photo", {
                        val (uri, file) = ReceiptStore.newCameraUri(context)
                        cameraFile = file
                        takePhoto.launch(uri)
                    }, icon = Lucide.Camera)
                    Chip(if (photos.isEmpty()) "Screenshots" else "Add more", {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, icon = Lucide.ReceiptText)
                }
            }
            Spacer(Modifier.height(2.dp))
            val rawAmount = AmountInput.toMinor(amountText) ?: 0L
            fun edited(): Debt {
                original.filter { it !in photos }.forEach { ReceiptStore.delete(it) }
                val isForeign = currency != null && currency != appCurrency
                val convertedMinor = if (isForeign && rate.isNotBlank()) {
                    val conv = toAppCurrency(rawAmount, rate)
                    if (conv > 0) conv else rawAmount
                } else rawAmount

                if (isForeign && rate.isNotBlank()) {
                    onSaveRate?.invoke(currency!!, rate.trim())
                }

                return initial.copy(
                    person = person.trim(),
                    amountMinor = if (lent) convertedMinor else -convertedMinor,
                    currency = if (isForeign) currency else null,
                    nativeMinor = if (isForeign) (if (lent) rawAmount else -rawAmount) else null,
                    note = note.trim(),
                    receiptPath = photosToPath(photos),
                    dueDateEpochDay = dueDateEpochDay,
                    reminderEnabled = reminderEnabled && dueDateEpochDay != null,
                    accountId = accountId,
                    pending = if (rawAmount > 0) 0 else if (lent) 1 else -1,
                )
            }
            val ready = person.isNotBlank()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onSettle != null && initial.id != 0L && !initial.settled && !initial.repayment && rawAmount > 0) {
                    SecondaryButton(
                        if (lent) "Received" else "Paid back",
                        { if (ready) onSettle(edited()) },
                        Modifier.weight(1f),
                        icon = Lucide.Check,
                    )
                }
                PrimaryButton("Save", { onSave(edited()) }, Modifier.weight(1f), enabled = ready)
            }
            viewing?.let { p ->
                ReceiptViewer(p, onDismiss = { viewing = null }, onRemove = {
                    if (p !in original) ReceiptStore.delete(p)
                    photoText = photos.filter { it != p }.joinToString("\n")
                })
            }
            if (onDelete != null) {
                // Asked first: it can't be undone (its photos go with it).
                var askDelete by remember { mutableStateOf(false) }
                Text(
                    "Delete",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.negative,
                    modifier = Modifier.align(Alignment.CenterHorizontally).pressable({ askDelete = true }),
                )
                if (askDelete) {
                    GlassAlertDialog(
                        onDismissRequest = { askDelete = false },
                        title = { Text("Delete this entry?") },
                        text = { Text("It can't be brought back${if (photos.isNotEmpty()) ", and its photos go with it" else ""}.", color = c.textSecondary) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { askDelete = false; onDelete() }) { Text("Delete", color = c.negative) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { askDelete = false }) { Text("Cancel", color = c.textSecondary) }
                        },
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val initialMillis = (selectedDueDate ?: today.plusWeeks(1))
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        val picked = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        selectDueDate(picked)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}
/**
 * Paying back, all of it or part (#20): the amount starts at everything owed, and the account it went into
 * or came out of starts at the one their latest entry used. Entries from before accounts were tracked have
 * none, so their repayment starts with none too and balances stay as they were.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettleSheet(
    balance: com.leftovers.app.data.PersonBalance,
    accounts: List<com.leftovers.app.data.AccountWithBalance>,
    onDismiss: () -> Unit,
    onSettle: (amountMinor: Long, accountId: Long?, currency: String?, nativeMinor: Long?) -> Unit,
) {
    val c = LocalAppColors.current
    val money = LocalMoney.current
    val singleForeign = balance.entries.mapNotNull { it.currency?.takeIf { cur -> cur != money.currency.currencyCode } }.distinct().singleOrNull()
        ?.takeIf { sf -> balance.entries.all { e -> e.currency == sf } }
    val entryMoney = singleForeign?.let { com.leftovers.app.util.Money(it) } ?: money
    val totalOwedNative = if (singleForeign != null) abs(balance.entries.sumOf { it.nativeAmount }) else abs(balance.netMinor)
    val totalOwedBase = abs(balance.netMinor)
    val theyPay = balance.netMinor > 0
    var amountText by rememberSaveable { mutableStateOf(AmountInput.fromMinor(totalOwedNative)) }
    val noAccountYet = balance.entries.all { it.accountId == null }
    var accountId by rememberSaveable { mutableStateOf(balance.entries.firstNotNullOfOrNull { it.accountId }) }
    val typedAmount = AmountInput.toMinor(amountText) ?: 0L
    val all = typedAmount >= totalOwedNative
    GlassSheet(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (theyPay) "${balance.person} paid you back" else "You paid ${balance.person} back", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text(
                if (theyPay) "They owe you ${entryMoney.format(totalOwedNative)}. Change the amount if they paid part of it."
                else "You owe ${entryMoney.format(totalOwedNative)}. Change the amount if you paid part of it.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )
            MoneyField(amountText, { amountText = it }, label = "Amount", symbol = entryMoney.symbol)
            if (accounts.size > 1 || noAccountYet) {
                Text(
                    if (theyPay) "Received into" else "Paid from",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(start = 6.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { a ->
                        Chip(a.name + (a.currency?.let { " ($it)" } ?: ""), { accountId = a.id }, icon = com.leftovers.app.ui.icons.CategoryIcons[a.icon], iconTint = androidx.compose.ui.graphics.Color(a.color), selected = accountId == a.id)
                    }
                    if (noAccountYet) Chip("No account", { accountId = null }, selected = accountId == null)
                }
            }
            Spacer(Modifier.height(2.dp))
            PrimaryButton(
                if (all) "Settle up" else "Save ${entryMoney.format(typedAmount)} ${if (theyPay) "received" else "paid back"}",
                {
                    val paidNative = minOf(typedAmount, totalOwedNative)
                    val paidBase = if (totalOwedNative > 0) {
                        (totalOwedBase.toDouble() * (paidNative.toDouble() / totalOwedNative)).toLong().coerceIn(1, totalOwedBase)
                    } else paidNative
                    onSettle(paidBase, accountId, singleForeign, if (singleForeign != null) paidNative else null)
                },
                Modifier.fillMaxWidth(),
                enabled = typedAmount > 0,
                icon = Lucide.Check,
            )
            Text(
                if (all) "Everything with ${balance.person} is then settled." else "${entryMoney.format(totalOwedNative - typedAmount)} stays open.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
