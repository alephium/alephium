package org.alephium.wallet.web

import scala.concurrent.ExecutionContext

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import sttp.tapir.docs.openapi._
import sttp.tapir.openapi.OpenAPI
import sttp.tapir.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.server.akkahttp.RichAkkaHttpEndpoint
import sttp.tapir.swagger.akkahttp.SwaggerAkka

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.model.NetworkType
import org.alephium.wallet.api.WalletEndpoints
import org.alephium.wallet.api.model
import org.alephium.wallet.service.WalletService

@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
class WalletServer(walletService: WalletService, val networkType: NetworkType)(
    implicit executionContext: ExecutionContext)
    extends WalletEndpoints {

  private val docs: OpenAPI = List(
    createWallet,
    restoreWallet,
    lockWallet,
    unlockWallet,
    getBalance,
    transfer,
    getAddress
  ).toOpenAPI("Alephium Wallet", "1.0")

  private val swaggerUIRoute = new SwaggerAkka(docs.toYaml, yamlName = "openapi.yaml").routes

  def route: Route =
    createWallet.toRoute(
      walletCreation =>
        walletService
          .createWallet(walletCreation.password,
                        walletCreation.mnemonicSize.getOrElse(Mnemonic.worldListSizes.last),
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
        walletService.transfer(tr.address, tr.amount).map(_.map(model.Transfer.Result.apply))) ~
      swaggerUIRoute
}
