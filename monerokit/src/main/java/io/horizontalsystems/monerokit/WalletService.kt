package io.horizontalsystems.monerokit

import android.content.Context
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.WalletListener
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
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

    fun getWallet(): Wallet? = WalletManager.getInstance().wallet
    fun getDaemonHeight(): Long = daemonHeight
    fun getConnectionStatus(): Wallet.ConnectionStatus = connectionStatus

    suspend fun start(walletName: String, walletPassword: String): Wallet.Status? {
        Timber.d("start()")
        showProgress(10)
        running = true

        if (listener == null) {
            val wallet = loadWallet(walletName, walletPassword) ?: return null
            val walletStatus = wallet.fullStatus
            if (!walletStatus.isOk) {
                wallet.close()
                return walletStatus
            }
            listener = MyWalletListener().apply { start() }
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
        return walletStatus
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
        return wallet
    }

    private fun openWallet(walletName: String, walletPassword: String): Wallet? {
        val path = Helper.getWalletFile(context, walletName).absolutePath
        val walletMgr = WalletManager.getInstance()

        return if (walletMgr.walletExists(path)) {
            Timber.d("open wallet %s", path)
            val device = walletMgr.queryWalletDevice("$path.keys", walletPassword)
            observer?.onWalletOpen(device)
            val wallet = walletMgr.openWallet(path, walletPassword)
            if (!wallet.status.isOk) {
                Timber.d("wallet status is %s", wallet.status)
                walletMgr.close(wallet)
                null
            } else wallet
        } else null
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
            Timber.d("newBlock() @ %d", height)
            val wallet = getWallet() ?: throw IllegalStateException("No wallet!")

            Timber.d("newBlock() @ %d -> wallet %s", height, wallet.name)

            if (System.currentTimeMillis() - lastBlockTime > 2000) {
                Timber.d("newBlock() 1")
                lastBlockTime = System.currentTimeMillis()
                updateDaemonState(wallet, if (wallet.isSynchronized) height else 0)

                Timber.d("newBlock() 2")

                if (!wallet.isSynchronized) {
                    Timber.d("newBlock() 3")
                    updated = true
                    wallet.refreshHistory()
                    val txCount = wallet.history.count

                    Timber.d("txCount %d", txCount)
                    Timber.d("balance %d", wallet.balance)
                    Timber.d("txCount > lastTxCount %b", txCount > lastTxCount)

                    if (txCount > lastTxCount) {
                        lastTxCount = txCount
                        observer?.onRefreshed(wallet, true)
                    }
                } else {
                    Timber.d("newBlock() 4")
                    observer?.onRefreshed(wallet, false)
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
                updated = !(observer?.onRefreshed(wallet, true) ?: false)
            }
        }
    }
}
