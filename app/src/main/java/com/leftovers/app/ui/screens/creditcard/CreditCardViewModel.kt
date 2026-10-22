package com.leftovers.app.ui.screens.creditcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leftovers.app.data.AccountRepository
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.Category
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.TransactionRepository
import com.leftovers.app.data.creditcard.CreditCard
import com.leftovers.app.data.creditcard.CreditCardCurrencySummary
import com.leftovers.app.data.creditcard.CreditCardRepository
import com.leftovers.app.data.creditcard.CreditCardStatement
import com.leftovers.app.data.creditcard.CreditCardTransaction
import com.leftovers.app.data.creditcard.CreditCardTxType
import com.leftovers.app.data.creditcard.CreditCardWithSummary
import com.leftovers.app.data.creditcard.StatementWithAllocations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CreditCardViewModel(
    private val repo: CreditCardRepository,
    private val accountRepo: AccountRepository,
    private val transactionRepo: TransactionRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val cardsWithSummary: StateFlow<List<CreditCardWithSummary>> =
        repo.activeCardsWithSummary
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currencySummaries: StateFlow<List<CreditCardCurrencySummary>> =
        repo.currencySummaries
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<AccountWithBalance>> =
        accountRepo.accounts
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<Category>> =
        transactionRepo.categories
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedCardId = MutableStateFlow<Long?>(null)
    val selectedCardId: StateFlow<Long?> = _selectedCardId.asStateFlow()

    val selectedCardSummary: StateFlow<CreditCardWithSummary?> =
        combine(cardsWithSummary, _selectedCardId) { cards, selectedId ->
            if (selectedId == null) cards.firstOrNull() else cards.find { it.card.id == selectedId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedCardStatements: StateFlow<List<StatementWithAllocations>> =
        _selectedCardId.flatMapLatest { cardId ->
            if (cardId == null) flowOf(emptyList()) else repo.observeEvaluatedStatementsForCard(cardId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedCardTransactions: StateFlow<List<CreditCardTransaction>> =
        _selectedCardId.flatMapLatest { cardId ->
            if (cardId == null) flowOf(emptyList()) else repo.observeTransactionsForCard(cardId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _txFilterType = MutableStateFlow<CreditCardTxType?>(null)
    val txFilterType: StateFlow<CreditCardTxType?> = _txFilterType.asStateFlow()

    fun selectCard(cardId: Long?) {
        _selectedCardId.value = cardId
    }

    fun setTxFilterType(type: CreditCardTxType?) {
        _txFilterType.value = type
    }

    fun saveCard(card: CreditCard, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.saveCard(card)
            _selectedCardId.value = id
            onComplete()
        }
    }

    fun archiveCard(cardId: Long, archived: Boolean = true, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repo.archiveCard(cardId, archived)
            if (_selectedCardId.value == cardId) {
                _selectedCardId.value = null
            }
            onComplete()
        }
    }

    fun deleteCard(cardId: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteCard(cardId)
            if (_selectedCardId.value == cardId) {
                _selectedCardId.value = null
            }
            onComplete()
        }
    }

    fun saveStatement(statement: CreditCardStatement, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repo.saveStatement(statement)
            onComplete()
        }
    }

    fun deleteStatement(statementId: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteStatement(statementId)
            onComplete()
        }
    }

    fun recordCardTransaction(
        transaction: CreditCardTransaction,
        createFinanceExpenseTx: Boolean = false,
        financeCategoryId: Long? = null,
        onComplete: () -> Unit = {},
    ) {
        viewModelScope.launch {
            repo.recordCardTransaction(transaction, createFinanceExpenseTx, financeCategoryId)
            onComplete()
        }
    }

    fun recordPayment(
        cardId: Long,
        amountMinor: Long,
        currency: String,
        paymentEpochDay: Long,
        statementId: Long? = null,
        sourceBankAccountId: Long? = null,
        reference: String = "",
        notes: String = "",
        onComplete: () -> Unit = {},
    ) {
        viewModelScope.launch {
            repo.recordPayment(
                cardId = cardId,
                amountMinor = amountMinor,
                currency = currency,
                paymentEpochDay = paymentEpochDay,
                statementId = statementId,
                sourceBankAccountId = sourceBankAccountId,
                reference = reference,
                notes = notes,
            )
            onComplete()
        }
    }

    fun deleteTransaction(txId: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteTransaction(txId)
            onComplete()
        }
    }
}
