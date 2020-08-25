package org.alephium.wallet.web

import scala.concurrent.ExecutionContext

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import sttp.tapir.server.akkahttp.RichAkkaHttpEndpoint

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.wallet.api.WalletEndpoints
import org.alephium.wallet.api.model
import org.alephium.wallet.service.WalletService

class WalletServer(walletService: WalletService)(implicit executionContext: ExecutionContext)
    extends WalletEndpoints {
  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def route: Route =
    createWallet.toRoute(
      walletCreation =>
        walletService
          .createWallet(walletCreation.password,
                        walletCreation.mnemonicSize.getOrElse(Mnemonic.worldListSizes.head),
                        walletCreation.mnemonicPassphrase)
          .map(_.map(mnemonic => model.Mnemonic(mnemonic.words)))) ~
      restoreWallet.toRoute(
        walletRestore =>
          walletService
            .restoreWallet(walletRestore.password,
                           walletRestore.mnemonic,
                           walletRestore.mnemonicPassphrase)
      ) ~
      lockWallet.toRoute(_              => walletService.lockWallet()) ~
      unlockWallet.toRoute(walletUnlock => walletService.unlockWallet(walletUnlock.password)) ~
      getBalance.toRoute(_              => walletService.getBalance()) ~
      getAddress.toRoute(_              => walletService.getAddress()) ~
      transfer.toRoute(tr =>
        walletService.transfer(tr.address, tr.amount).map(_.map(model.Transfer.Result.apply)))
}
