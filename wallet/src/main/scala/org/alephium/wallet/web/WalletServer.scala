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
import org.alephium.util.U64
import org.alephium.wallet.api.{WalletApiError, WalletEndpoints}
import org.alephium.wallet.api.model
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.service.WalletService._

@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
class WalletServer(walletService: WalletService, val networkType: NetworkType)(
    implicit executionContext: ExecutionContext)
    extends WalletEndpoints
    with AkkaDecodeFailureHandler {
  import WalletServer.toApiError

  private val docs: OpenAPI = List(
    createWallet,
    restoreWallet,
    lockWallet,
    unlockWallet,
    getBalances,
    transfer,
    getAddresses,
    deriveNextAddress,
    changeActiveAddress
  ).toOpenAPI("Alephium Wallet", "1.0")

  private val swaggerUIRoute = new SwaggerAkka(docs.toYaml, yamlName = "openapi.yaml").routes

  // scalastyle:off method.length
  def route: Route =
    createWallet.toRoute { walletCreation =>
      walletService
        .createWallet(walletCreation.password,
                      walletCreation.mnemonicSize.getOrElse(Mnemonic.Size.list.last),
                      walletCreation.mnemonicPassphrase)
        .map(_.map(mnemonic => model.Mnemonic(mnemonic.words)).left.map(toApiError))
    } ~
      restoreWallet.toRoute { walletRestore =>
        walletService
          .restoreWallet(walletRestore.password,
                         walletRestore.mnemonic,
                         walletRestore.mnemonicPassphrase)
          .map(_.left.map(toApiError))
      } ~
      lockWallet.toRoute { _ =>
        walletService.lockWallet().map(_.left.map(toApiError))
      } ~
      unlockWallet.toRoute { walletUnlock =>
        walletService.unlockWallet(walletUnlock.password).map(_.left.map(toApiError))
      } ~
      getBalances.toRoute { _ =>
        walletService
          .getBalances()
          .map(_.map { balances =>
            val totalBalance = balances.map { case (_, amount) => amount }.fold(U64.Zero) {
              case (acc, u64) => acc.addUnsafe(u64)
            }
            val balancesPerAddress = balances.map {
              case (address, amount) => model.Balances.AddressBalance(address, amount)
            }
            model.Balances(totalBalance, balancesPerAddress)
          }.left.map(toApiError))
      } ~
      getAddresses.toRoute { _ =>
        walletService
          .getAddresses()
          .map(_.map {
            case (active, addresses) =>
              model.Addresses(active, addresses)
          }.left.map(toApiError))
      } ~
      transfer.toRoute { tr =>
        walletService
          .transfer(tr.address, tr.amount)
          .map(_.map(model.Transfer.Result.apply).left.map(toApiError))
      } ~
      deriveNextAddress.toRoute { _ =>
        walletService
          .deriveNextAddress()
          .map(_.left.map(toApiError))
      } ~
      changeActiveAddress.toRoute { change =>
        walletService
          .changeActiveAddress(change.address)
          .map(_.left.map(toApiError))
      } ~
      swaggerUIRoute
}

object WalletServer {
  import WalletApiError._
  def toApiError(walletError: WalletError): WalletApiError = {

    val badRequest   = BadRequest(walletError.message)
    val unauthorized = Unauthorized(walletError.message)

    walletError match {
      case _: InvalidMnemonic           => badRequest
      case _: CannotCreateEncryptedFile => badRequest
      case _: BlockFlowClientError      => badRequest
      case _: UnknownAddress            => badRequest
      case NoWalletLoaded               => badRequest
      case CannotDeriveNewAddress       => badRequest

      case WalletLocked    => unauthorized
      case InvalidPassword => unauthorized
    }
  }
}
