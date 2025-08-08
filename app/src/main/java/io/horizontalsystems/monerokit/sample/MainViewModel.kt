package io.horizontalsystems.monerokit.sample

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.monerokit.SyncState
import io.horizontalsystems.monerokit.util.NetCipherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.math.BigDecimal

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val kit = App.kit

    val address = ""//kit.receiveAddress

    //
    private var syncState = SyncState.NotSynced(IllegalStateException("Not Implemented")) //  kit.syncStateFlow.value
    private var operationsSyncState = SyncState.NotSynced(IllegalStateException("Not Implemented")) //kit.operationsSyncStateFlow.value

    private var totalBalance: BigDecimal? = null
    private var minimumBalance = BigDecimal.ZERO

    var uiState by mutableStateOf(
        MainUiState(
            syncState = syncState,
            operationsSyncState = operationsSyncState,
            totalBalance = totalBalance,
            minimumBalance = minimumBalance,
        )
    )
        private set

    init {
        NetCipherHelper.createInstance(App.instance)
    }

    //
//    init {
//        viewModelScope.launch(Dispatchers.Default) {
//            kit.syncStateFlow.collect(::updateSyncState)
//        }
//        viewModelScope.launch(Dispatchers.Default) {
//            kit.operationsSyncStateFlow.collect(::updateOperationsSyncState)
//        }
//        viewModelScope.launch(Dispatchers.Default) {
//            kit.getBalanceFlow(StellarAsset.Native).collect {
//                updateBalance(it)
//            }
//        }
//        viewModelScope.launch(Dispatchers.Default) {
//            kit.assetBalanceMapFlow.collect {
//                updateAssetBalanceMap(it)
//            }
//        }
//    }
//
//    private fun updateAssetBalanceMap(assetBalanceMap: Map<StellarAsset, AssetBalance>) {
//        this.assetBalanceMap = assetBalanceMap
//
//        emitState()
//    }
//
//    private fun updateBalance(balance: AssetBalance?) {
//        totalBalance = balance?.balance
//        minimumBalance = balance?.minBalance ?: BigDecimal.ZERO
//
//        emitState()
//    }
//
//    private fun updateSyncState(syncState: SyncState) {
//        this.syncState = syncState
//
//        emitState()
//    }
//
//    private fun updateOperationsSyncState(syncState: SyncState) {
//        this.operationsSyncState = syncState
//
//        emitState()
//    }
//
//    override fun onCleared() {
////        kit.stop()
//    }
//
//    private fun emitState() {
//        viewModelScope.launch {
//            uiState = MainUiState(
//                syncState = syncState,
//                operationsSyncState = operationsSyncState,
//                totalBalance = totalBalance,
//                minimumBalance = minimumBalance,
//                assetBalanceMap = assetBalanceMap,
//            )
//        }
//    }
//
    fun start() {
        viewModelScope.launch(Dispatchers.Default) {
//            kit.restoreWallet()
//            kit.openWallet()
            kit.start()

        }
    }

    fun stop() {
//        kit.stop()
    }
}

data class MainUiState(
    val syncState: SyncState,
    val operationsSyncState: SyncState,
    val totalBalance: BigDecimal?,
    val minimumBalance: BigDecimal,
)
