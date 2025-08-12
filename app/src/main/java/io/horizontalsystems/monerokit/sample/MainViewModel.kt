package io.horizontalsystems.monerokit.sample

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.monerokit.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.math.BigDecimal

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val kit = App.kit
    private var syncState = kit.syncStateFlow.value

    private var totalBalance: BigDecimal? = null

    var address: String = ""

    private val decimal = 12

    var uiState by mutableStateOf(
        MainUiState(
            syncState = syncState,
            totalBalance = totalBalance,
        )
    )
        private set

    init {
        viewModelScope.launch(Dispatchers.Default) {
            kit.syncStateFlow.collect(::updateSyncState)
        }
        viewModelScope.launch(Dispatchers.Default) {
            kit.allTransactionsFlow.collect {
                Log.e("eee", "txs: ${it.joinToString(separator = "\n")}")
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            kit.balanceFlow.collect {
                updateBalance(it)
            }
        }
    }

    private fun updateBalance(balance: Long?) {
        totalBalance = balance?.let {
            scaleDown(it.toBigDecimal())
        } ?: BigDecimal.ZERO

        emitState()
    }

    private fun updateSyncState(syncState: SyncState) {
        this.syncState = syncState

        emitState()
    }

    private fun scaleDown(amount: BigDecimal): BigDecimal {
        return amount.movePointLeft(decimal).stripTrailingZeros()
    }

    override fun onCleared() {
        kit.stop()
    }

    private fun emitState() {
        viewModelScope.launch {
            uiState = MainUiState(
                syncState = syncState,
                totalBalance = totalBalance,
            )
        }
    }

    fun start() {
        kit.start()
    }

    fun stop() {
        kit.stop()
    }
}

data class MainUiState(
    val syncState: SyncState,
    val totalBalance: BigDecimal?,
)
