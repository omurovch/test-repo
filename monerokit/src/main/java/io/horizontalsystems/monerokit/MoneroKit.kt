package io.horizontalsystems.monerokit

import android.content.Context
import android.util.Log
import io.horizontalsystems.monerokit.data.Node
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.WalletListener
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import timber.log.Timber
import java.io.File

class MoneroKit(
    private val context: Context
): WalletListener {

    private val node = "nodex.monerujo.io:18081/mainnet/monerujo.io?rc=200?v=16&h=3458441&ts=1752868953&t=225.496692ms"
    private val node2 = "xmr-node.cakewallet.com:18081/mainnet/cakewallet.com"

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

        if (wallet != null) {
            walletManager.setDaemon(Node.fromString(node))

            Timber.d("Using daemon %s", walletManager.getDaemonAddress())

            wallet.setListener(this)

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

        Log.e("eee", "wallet: ${newWallet.address}" )

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

    override fun moneySpent(txId: String?, amount: Long) {
        Timber.d("moneySpent() %d @ %s", amount, txId)
    }

    override fun moneyReceived(txId: String?, amount: Long) {
        Timber.d("moneyReceived() %d @ %s", amount, txId)
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {
        Timber.d("unconfirmedMoneyReceived() %d @ %s", amount, txId)
    }

    override fun newBlock(height: Long) {
        Timber.d("newBlock() @ %d", height)
    }

    override fun updated() {
        Timber.d("updated()")
    }

    override fun refreshed() {
        Timber.d("refreshed()")
    }
}
