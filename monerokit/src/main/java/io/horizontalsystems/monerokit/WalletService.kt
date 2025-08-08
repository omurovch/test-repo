package io.horizontalsystems.monerokit

import android.content.Context
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.WalletListener
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import io.horizontalsystems.monerokit.util.NetCipherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class WalletService(private val context: Context) {

    companion object {
        var running: Boolean = false
        private const val STATUS_UPDATE_INTERVAL = 120_000L // 120s
    }

    private var observer: Observer? = null
    private var listener: MyWalletListener? = null
    private var errorState = false

    private var daemonHeight: Long = 0
    private var lastDaemonStatusUpdate: Long = 0
    private var connectionStatus: Wallet.ConnectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Disconnected

    interface Observer {
        fun onRefreshed(wallet: Wallet, full: Boolean): Boolean
        fun onProgress(n: Int)
        fun onWalletStored(success: Boolean)
        fun onTransactionCreated(tag: String, pendingTransaction: PendingTransaction)
        fun onTransactionSent(txid: String)
        fun onSendTransactionFailed(error: String)
        fun onWalletStarted(walletStatus: Wallet.Status?)
        fun onWalletOpen(device: Wallet.Device)
    }

    fun setObserver(obs: Observer?) {
        observer = obs
        Timber.d("Observer set: %s", observer)
    }

    fun getWallet(): Wallet? {
        return WalletManager.getInstance().wallet
    }

    fun getDaemonHeight(): Long = daemonHeight
    fun getConnectionStatus(): Wallet.ConnectionStatus = connectionStatus

    suspend fun start(walletName: String, walletPassword: String): Wallet.Status? = withContext(Dispatchers.IO) {
        Timber.d("start()")
        showProgress(10)
        running = true

        if (listener == null) {
            Timber.d("start() loadWallet")
            val wallet = loadWallet(walletName, walletPassword) ?: return@withContext null

            Timber.d("wallet address %s, restore height: %d", wallet.address, wallet.restoreHeight)

            val walletStatus = wallet.fullStatus
            if (!walletStatus.isOk) {
                wallet.close()
                return@withContext walletStatus
            }
            listener = MyWalletListener()
            listener!!.start()
//            listener = MyWalletListener().apply { start() }
            showProgress(100)
        }
        showProgress(101)
        // if we try to refresh the history here we get occasional segfaults!
        // doesnt matter since we update as soon as we get a new block anyway
        Timber.d("start() done")

        val walletStatus = getWallet()?.getFullStatus()

        observer?.onWalletStarted(walletStatus)
        if ((walletStatus == null) || !walletStatus.isOk) {
            errorState = true
            stop()
        }
        return@withContext walletStatus
    }

    fun storeWallet() {
        getWallet()?.store()?.let {
            observer?.onWalletStored(it)
        }
    }

    fun stop() {
        Timber.d("stop()")
        setObserver(null)
        listener?.let {
            it.stop()
            getWallet()?.let { wallet ->
                wallet.close()
                Timber.d("Wallet closed")
            }
            listener = null
        }
        running = false
    }

    private fun loadWallet(walletName: String, walletPassword: String): Wallet? {
        val wallet = openWallet(walletName, walletPassword) ?: return null
        Timber.d("Using daemon %s", WalletManager.getInstance().daemonAddress)
        wallet.init(0)
        wallet.setProxy(NetCipherHelper.getProxy())
        return wallet
    }

    private fun openWallet(walletName: String, walletPassword: String): Wallet? {
        val path = Helper.getWalletFile(context, walletName).absolutePath
        val walletMgr = WalletManager.getInstance()
        Timber.d("WalletManager network=%s", walletMgr.networkType.name)

        return if (walletMgr.walletExists(path)) {
            Timber.d("open wallet %s", path)
            val device = walletMgr.queryWalletDevice("$path.keys", walletPassword)
            observer?.onWalletOpen(device)
            val wallet = walletMgr.openWallet(path, walletPassword)

            Timber.d("wallet opened")

            if (!wallet.status.isOk) {
                Timber.d("wallet status is %s", wallet.status)
                walletMgr.close(wallet)
                null
            } else wallet
        } else {
            Timber.d("service.openWallet wallet path does not exists %s", path)
            null
        }
    }

    private fun updateDaemonState(wallet: Wallet, height: Long) {
        val now = System.currentTimeMillis()
        if (height > 0) {
            daemonHeight = height
            connectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Connected
            lastDaemonStatusUpdate = now
        } else if (now - lastDaemonStatusUpdate > STATUS_UPDATE_INTERVAL) {
            lastDaemonStatusUpdate = now
            daemonHeight = wallet.daemonBlockChainHeight
            connectionStatus = if (daemonHeight > 0)
                Wallet.ConnectionStatus.ConnectionStatus_Connected
            else Wallet.ConnectionStatus.ConnectionStatus_Disconnected
        }
    }

    private fun showProgress(n: Int) {
        if (observer != null) {
            observer!!.onProgress(n)
        }
    }

    /** Wallet listener handling blockchain updates */
    private inner class MyWalletListener : WalletListener {
        var updated = true
        private var lastBlockTime = 0L
        private var lastTxCount = 0

        fun start() {
            Timber.d("WalletListener.start()")
            val wallet = getWallet() ?: throw IllegalStateException("No wallet!")
            wallet.setListener(this)
            wallet.startRefresh()
        }

        fun stop() {
            Timber.d("WalletListener.stop()")
            val wallet = getWallet() ?: throw IllegalStateException("No wallet!")
            wallet.pauseRefresh()
            wallet.setListener(null)
        }

        override fun moneySpent(txId: String, amount: Long) = Timber.d("moneySpent() $amount @ $txId")
        override fun moneyReceived(txId: String, amount: Long) = Timber.d("moneyReceived() $amount @ $txId")
        override fun unconfirmedMoneyReceived(txId: String, amount: Long) = Timber.d("unconfirmedMoneyReceived() $amount @ $txId")

        override fun newBlock(height: Long) {
            val wallet: Wallet? = getWallet()
            checkNotNull(wallet) { "No wallet!" }

            // don't flood with an update for every block ...
            if (lastBlockTime < System.currentTimeMillis() - 2000) {
                lastBlockTime = System.currentTimeMillis()
                Timber.d("newBlock() @ %d with observer %s", height, observer)
                if (observer != null) {
                    var fullRefresh = false
                    updateDaemonState(wallet, if (wallet.isSynchronized) height else 0)
                    if (!wallet.isSynchronized) {
                        updated = true
                        // we want to see our transactions as they come in
                        wallet.refreshHistory()
                        val txCount = wallet.getHistory().getCount()
                        if (txCount > lastTxCount) {
                            // update the transaction list only if we have more than before
                            lastTxCount = txCount
                            fullRefresh = true
                        }
                    }
                    if (observer != null) observer!!.onRefreshed(wallet, fullRefresh)
                }
            }
        }

        override fun updated() {
            Timber.d("updated()")
            updated = true
        }

        override fun refreshed() {
            Timber.d("refreshed()")
            val wallet = getWallet() ?: throw IllegalStateException("No wallet!")
            wallet.setSynchronized()
            if (updated) {
                updateDaemonState(wallet, wallet.blockChainHeight)
                wallet.refreshHistory()
                if (observer != null) {
                    updated = !observer!!.onRefreshed(wallet, true)
                }
//                updated = !(observer?.onRefreshed(wallet, true) ?: false)
            }
        }
    }
}
