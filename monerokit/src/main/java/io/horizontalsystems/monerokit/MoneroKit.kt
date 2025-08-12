package io.horizontalsystems.monerokit

import android.content.Context
import android.util.Log
import io.horizontalsystems.monerokit.data.Node
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
    val allTransactions: StateFlow<List<TransactionInfo>> = _allTransactionsFlow

    val receiveAddress: String
        get() = try {
            walletService.getWallet()?.address ?: throw IllegalStateException("Wallet is NULL")
        } catch (e: Exception) {
            Log.e("MoneroKit", "getAddress", e)
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

        scope?.launch {
            walletService.stop()
        }
        scope?.cancel()
        scope = null
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
        val walletPassword = "" // TODO
        val offset = "" // TODO mnemonic passphrase?
        val newWallet = WalletManager.getInstance().recoveryWallet(newWalletFile, walletPassword, mnemonic, offset, restoreHeight)
        val success = checkAndCloseWallet(newWallet)

        val walletFile = File(walletFolder, walletId)
        Timber.d("New Wallet delete %s", walletFile.absolutePath)
        // NEXT line is VERY important for correct update
        walletFile.delete() // when recovering wallets, the cache seems corrupt - so remove it


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

    override fun onTransactionCreated(tag: String, pendingTransaction: PendingTransaction) {
        Log.e("eee", "observer.onTransactionCreated()\n - tag: $tag\n pendingTransaction.firstTxId : ${pendingTransaction.firstTxId}")
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
    // ==============================================


    fun openWallet() {
        Timber.i("called openWallet")
        val walletName = "uw-test-wallet"
        val rootStorage = Helper.getWalletRoot(context)
        val walletFile = File(rootStorage, walletName)
        val password = "123"

        val walletManager = WalletManager.getInstance()

        val wallet = walletManager.openWallet(walletFile.absolutePath, password)

        Timber.i("restoreHeight: ${wallet.restoreHeight} ")
        Timber.i("status: ${wallet.status} ")
        Timber.tag("eee").e("address: ${wallet.address}")

        if (wallet != null) {
            walletManager.setDaemon(Node.fromString(node))

            Timber.d("Using daemon %s", walletManager.getDaemonAddress())

//            wallet.setListener(this)

            wallet.init(0)
            Timber.i("fullStatus: ${wallet.fullStatus} ")


            wallet.startRefresh()

//            Thread.sleep(10_000)
//
//            wallet.refresh()
//            wallet.setProxy(NetCipherHelper.getProxy())
        }


    }

    fun restoreWallet(seed: String) {
        Log.e("eee", "called restoreWallet")

        val password = "123"
        val offset = ""
        val restoreHeight = 3409492L
        val walletName = "uw-test-wallet"
        val rootStorage = Helper.getWalletRoot(context)
        val newWalletFile = File(rootStorage, walletName)

        Log.e("eee", "wallet path: ${newWalletFile.absolutePath}")

        val newWallet: Wallet = WalletManager.getInstance()
            .recoveryWallet(newWalletFile, password, seed, offset, restoreHeight)

        Log.e("eee", "wallet: ${newWallet.address}")

        val result = checkAndCloseWallet(newWallet)

        Log.e("eee", "check result: $result")
    }

    fun checkAndCloseWallet(aWallet: Wallet): Boolean {
        val walletStatus = aWallet.getStatus()
        if (!walletStatus.isOk()) {
            Timber.tag("eee").e(walletStatus.errorString)
//            toast(walletStatus.getErrorString())
        }
        aWallet.close()
        return walletStatus.isOk()
    }

//    override fun moneySpent(txId: String?, amount: Long) {
//        Timber.d("moneySpent() %d @ %s", amount, txId)
//    }

//    override fun moneyReceived(txId: String?, amount: Long) {
//        Timber.d("moneyReceived() %d @ %s", amount, txId)
//    }

//    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {
//        Timber.d("unconfirmedMoneyReceived() %d @ %s", amount, txId)
//    }
//
//    override fun newBlock(height: Long) {
//        Timber.d("newBlock() @ %d", height)
//    }
//
//    override fun updated() {
//        Timber.d("updated()")
//    }
//
//    override fun refreshed() {
//        Timber.d("WalletListener::refreshed()")
//    }

    sealed class SyncError : Error() {
        object NotStarted : SyncError() {
            override val message = "Not Started"
        }
    }

    companion object {
        fun getInstance(
            context: Context,
            words: List<String>,
            restoreDateOrHeight: String,
            walletId: String
        ): MoneroKit {
            val walletService = WalletService(context)
            val restoreHeight = getHeight(restoreDateOrHeight)

            Log.e("eee", "computed restoreHeight = $restoreHeight")

            NetCipherHelper.createInstance(context)

            return MoneroKit(words.joinToString(" "), restoreHeight, walletId, walletService, context)
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
