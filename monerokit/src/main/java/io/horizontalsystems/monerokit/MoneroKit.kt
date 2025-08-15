package io.horizontalsystems.monerokit

import android.content.Context
import android.util.Log
import io.horizontalsystems.monerokit.data.TxData
import io.horizontalsystems.monerokit.data.UserNotes
import io.horizontalsystems.monerokit.model.NetworkType
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.Wallet.ConnectionStatus.ConnectionStatus_Connected
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import io.horizontalsystems.monerokit.util.NetCipherHelper
import io.horizontalsystems.monerokit.util.NodeHelper
import io.horizontalsystems.monerokit.util.RestoreHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar

class MoneroKit(
    private val mnemonic: String,
    private val restoreHeight: Long,
    private val walletId: String,
    private val walletService: WalletService,
    private val context: Context
) : WalletService.Observer {

    private val node = "nodex.monerujo.io:18081/mainnet/monerujo.io?rc=200?v=16&h=3458441&ts=1752868953&t=225.496692ms"
    private val node2 = "xmr-node.cakewallet.com:18081/mainnet/cakewallet.com"

    private var scope: CoroutineScope? = null

    private var started = false
    private var synced = false

    private val _syncStateFlow = MutableStateFlow<SyncState>(SyncState.NotSynced(SyncError.NotStarted))
    val syncStateFlow = _syncStateFlow.asStateFlow()

    private val _balanceFlow = MutableStateFlow<Long>(0)
    val balanceFlow = _balanceFlow.asStateFlow()

    private val _lastBlockUpdatedFlow = MutableStateFlow(Unit)
    val lastBlockUpdatedFlow: StateFlow<Unit> = _lastBlockUpdatedFlow

    private val _allTransactionsFlow = MutableStateFlow<List<TransactionInfo>>(emptyList())
    val allTransactionsFlow: StateFlow<List<TransactionInfo>> = _allTransactionsFlow

    val receiveAddress: String
        get() = try {
            walletService.getWallet()?.address ?: throw IllegalStateException("Wallet is NULL")
        } catch (_: Exception) {
            ""
        }

    val balance: Long
        get() = _balanceFlow.value

    val lastBlockHeight: Long?
        get() = if (walletService.getConnectionStatus() == ConnectionStatus_Connected)
            walletService.getDaemonHeight()
        else
            null

    fun start() {
        if (started) return
        started = true

        _syncStateFlow.update {
            SyncState.Syncing()
        }

        scope = CoroutineScope(Dispatchers.IO)

        scope?.launch {
            createWalletIfNotExists()

            val nodes = NodeHelper.getOrPopulateFavourites()
            val node = NodeHelper.autoselect(nodes)

            Log.e("eee", "selected node: ${node?.name}")

            WalletManager.getInstance().setDaemon(node)

            walletService.setObserver(this@MoneroKit)
            val status = walletService.start(walletId, "")

            Log.e("eee", "status after start: $status")
        }
    }

    fun stop() {
        if (!started) return
        started = false

        Log.e("eee", "kit.stop() before launch scope = $scope")
        scope?.launch {
            Log.e("eee", "kit.stop() before service.stop()")
            walletService.stop()
            Log.e("eee", "kit.stop() after service.stop()")
            scope?.cancel()
            Log.e("eee", "kit.stop() after cancel ")
            scope = null
        }
        Log.e("eee", "kit.stop() after launch ")
    }

    fun saveState() {
        walletService.storeWallet()
    }

    fun send(
        amount: Long,
        address: String,
        memo: String?
    ) {
        val txData = buildTxData(amount, address, memo)

        walletService.createTransaction(txData)
        walletService.sendTransaction(memo)
    }

    fun estimateFee(
        amount: Long,
        address: String,
        memo: String?
    ): Long {
        val wallet = walletService.getWallet() ?: throw IllegalStateException("Wallet is NULL")
        val txData = buildTxData(amount, address, memo)

        return wallet.estimateTransactionFee(txData)
    }

    private fun buildTxData(
        amount: Long,
        destination: String,
        memo: String?
    ) = TxData().apply {
        this.amount = amount
        this.destination = destination
        mixin = MIXIN
        priority = PendingTransaction.Priority.Priority_Medium
        if (!memo.isNullOrEmpty()) {
            userNotes = UserNotes(memo)
        }
    }


    suspend fun restoreHeightForNewWallet(): Long {
        // val currentNode: NodeInfo? = getNode() //
        // get it from the connected node if we have one

        val height: Long = -1 // if (currentNode != null) currentNode.getHeight() else -1

        val restoreHeight: Long = if (height > -1) height
        else {
            // Go back 4 days if we don't have a precise restore height
            val restoreDate = Calendar.getInstance()
            restoreDate.add(Calendar.DAY_OF_MONTH, -4)

            RestoreHeight.getInstance().getHeight(restoreDate.getTime())
        }

        return restoreHeight
    }

    private suspend fun createWalletIfNotExists() = withContext(Dispatchers.IO) {
        // check if the wallet we want to create already exists
        val walletFolder: File = Helper.getWalletRoot(context)
        if (!walletFolder.isDirectory) {
            Timber.e("Wallet dir " + walletFolder.absolutePath + "is not a directory")
            return@withContext
        }
        val cacheFile = File(walletFolder, walletId)
        val keysFile = File(walletFolder, "$walletId.keys")
        val addressFile = File(walletFolder, "$walletId.address.txt")

        if (cacheFile.exists() || keysFile.exists() || addressFile.exists()) {
            Timber.e("Some wallet files already exist for %s", cacheFile.absolutePath)
            return@withContext
        }

        val newWalletFile = File(walletFolder, walletId)
        val walletPassword = ""
        val offset = ""
        val newWallet = WalletManager.getInstance().recoveryWallet(newWalletFile, walletPassword, mnemonic, offset, restoreHeight)
        val success = checkAndCloseWallet(newWallet)

        val walletFile = File(walletFolder, walletId)
        walletFile.delete()

        if (success) {
            Timber.i("Created wallet in %s", newWalletFile.absolutePath)
            return@withContext
        } else {
            Timber.e("Could not create wallet in %s", newWalletFile.absolutePath)
            return@withContext
        }
    }

    // Observer ====================================

    private var firstBlock: Long = 0

    override fun onRefreshed(wallet: Wallet, full: Boolean): Boolean {
        Log.e("eee", "observer.onRefreshed()\n - wallet: ${wallet.fullStatus}\n - full: $full")

        val historyAll: List<TransactionInfo?>? = wallet.history.all
        Log.e("eee", "historyAll: ${historyAll?.count()}")

        if (historyAll != null) {
            _allTransactionsFlow.update {
                historyAll.mapNotNull { it }
            }
        }


        if (wallet.isSynchronized) {
            Log.e("eee", "wallet is synced, first sync = ${!synced}")
            if (!synced) { // first sync
                onProgress(-1)
                walletService.storeWallet() // save on first sync
                synced = true
            }
        }

        if (!wallet.isSynchronized) {
            val daemonHeight: Long = walletService.getDaemonHeight()
            val walletHeight = wallet.getBlockChainHeight()
            val remainingBlocks = daemonHeight - walletHeight

            if (firstBlock == 0L) {
                firstBlock = walletHeight
            }

            Timber.i(
                "firstBlock: %d, daemonHeight: %d, walletHeight: %d, remainingBlocks: %d",
                firstBlock,
                daemonHeight,
                walletHeight,
                remainingBlocks
            )
            val totalBlocks = daemonHeight - firstBlock
            val progress: Double = if (totalBlocks > 0) {
                1 - remainingBlocks.toDouble() / totalBlocks
            } else {
                1.0
            }

            _syncStateFlow.update {
                SyncState.Syncing(progress)
            }
        } else {
            _syncStateFlow.update {
                SyncState.Synced
            }
        }

        _lastBlockUpdatedFlow.update { }

        _balanceFlow.update {
            walletService.getWallet()?.balance ?: 0L
        }

        return true
    }

    override fun onProgress(n: Int) {
        Log.e("eee", "observer.onProgress()\n - n: $n")
    }

    override fun onWalletStored(success: Boolean) {
        Log.e("eee", "observer.onWalletStored()\n - success: $success")
    }

    override fun onTransactionCreated(pendingTransaction: PendingTransaction) {
        Log.e("eee", "observer.onTransactionCreated()\n - pendingTransaction.firstTxId : ${pendingTransaction.firstTxId}")
    }

    override fun onTransactionSent(txid: String) {
        Log.e("eee", "observer.onTransactionSent()\n - txid: $txid")
    }

    override fun onSendTransactionFailed(error: String) {
        Log.e("eee", "observer.onSendTransactionFailed()\n - error: $error")
    }

    override fun onWalletStarted(walletStatus: Wallet.Status?) {
        Log.e("eee", "observer.onWalletStarted()\n - walletStatus: $walletStatus")
    }

    override fun onWalletOpen(device: Wallet.Device) {
        Log.e("eee", "observer.onWalletOpen()\n - device: $device")
    }

    fun checkAndCloseWallet(aWallet: Wallet): Boolean {
        val walletStatus = aWallet.status
        if (!walletStatus.isOk) {
            Timber.tag("eee").e(walletStatus.errorString)
            throw IllegalStateException("Wallet recovery error: ${walletStatus.errorString}")
        }
        aWallet.close()
        return walletStatus.isOk()
    }

    sealed class SyncError : Error() {
        object NotStarted : SyncError() {
            override val message = "Not Started"
        }
    }

    companion object {
        const val MIXIN: Int = 0
        const val MONERO_LEGACY_MNEMONIC_COUNT = 25

        fun getInstance(
            context: Context,
            words: List<String>,
            passphrase: String,
            restoreDateOrHeight: String,
            walletId: String
        ): MoneroKit {
            val walletService = WalletService(context)
            val restoreHeight = getHeight(restoreDateOrHeight)

            Log.e("eee", "computed restoreHeight = $restoreHeight")

            val moneroMnemonic = if (words.size != MONERO_LEGACY_MNEMONIC_COUNT) {
                CakeWalletStyleConverter.getLegacySeedFromBip39(words, passphrase)
                    ?: throw IllegalArgumentException("BIP39 mnemonic can't be converted to Monero Legacy Mnemonic")
            } else {
                words.joinToString(" ")
            }

            NetCipherHelper.createInstance(context)

            return MoneroKit(moneroMnemonic, restoreHeight, walletId, walletService, context)
        }

        fun validateAddress(address: String) {
            if (!Wallet.isAddressValid(address)) {
                throw IllegalArgumentException("Invalid address")
            }
        }

        private fun getHeight(input: String): Long {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return -1

            val walletManager = WalletManager.getInstance()
            val restoreHeight = RestoreHeight.getInstance()

            var height = -1L

            if (walletManager.networkType == NetworkType.NetworkType_Mainnet) {
                // Try parsing as date (yyyy-MM-dd)
                height = runCatching {
                    SimpleDateFormat("yyyy-MM-dd").apply { isLenient = false }
                        .parse(trimmed)?.let { restoreHeight.getHeight(it) }
                }.getOrNull() ?: -1

                // Try parsing as date (yyyyMMdd) if previous failed
                if (height < 0 && trimmed.length == 8) {
                    height = runCatching {
                        SimpleDateFormat("yyyyMMdd").apply { isLenient = false }
                            .parse(trimmed)?.let { restoreHeight.getHeight(it) }
                    }.getOrNull() ?: -1
                }
            }

            // If still invalid, try numeric height
            if (height < 0) {
                height = trimmed.toLongOrNull() ?: -1
            }

            Timber.d("Using Restore Height = %d", height)
            return height
        }

    }
}

fun ByteArray?.toRawHexString(): String {
    return this?.joinToString(separator = "") {
        it.toInt().and(0xff).toString(16).padStart(2, '0')
    } ?: ""
}

fun ByteArray?.toHexString(): String {
    val rawHex = this?.toRawHexString() ?: return ""
    return "0x$rawHex"
}
