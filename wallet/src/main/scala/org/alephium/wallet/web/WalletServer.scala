// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.wallet.web

import scala.concurrent.{ExecutionContext, Future}

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import sttp.tapir.Endpoint
import sttp.tapir.docs.openapi._
import sttp.tapir.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.server.akkahttp.RichAkkaHttpEndpoint
import sttp.tapir.swagger.akkahttp.SwaggerAkka

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.NetworkType
import org.alephium.util.{Duration, U256}
import org.alephium.wallet.api.{WalletApiError, WalletEndpoints}
import org.alephium.wallet.api.model
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.service.WalletService._

@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
class WalletServer(walletService: WalletService,
                   val networkType: NetworkType,
                   val blockflowFetchMaxAge: Duration)(implicit groupConfig: GroupConfig,
                                                       executionContext: ExecutionContext)
    extends WalletEndpoints
    with AkkaDecodeFailureHandler {
  import WalletServer.toApiError

  val docs: List[Endpoint[_, _, _, _]] = List(
    createWallet,
    restoreWallet,
    listWallets,
    lockWallet,
    unlockWallet,
    getBalances,
    transfer,
    getAddresses,
    getMinerAddresses,
    deriveNextAddress,
    deriveNextMinerAddresses,
    changeActiveAddress
  )

  val docsRoute: Route = new SwaggerAkka(docs.toOpenAPI("Alephium Wallet", "1.0").toYaml,
                                         yamlName = "openapi.yaml").routes

  // scalastyle:off method.length
  def route: Route =
    createWallet.toRoute { walletCreation =>
      Future.successful(
        walletService
          .createWallet(
            walletCreation.password,
            walletCreation.mnemonicSize.getOrElse(Mnemonic.Size.list.last),
            walletCreation.isMiner.getOrElse(false),
            walletCreation.walletName,
            walletCreation.mnemonicPassphrase
          )
          .map {
            case (walletName, mnemonic) =>
              model.WalletCreation.Result(walletName, mnemonic)
          }
          .left
          .map(toApiError)
      )
    } ~
      restoreWallet.toRoute { walletRestore =>
        Future.successful(
          walletService
            .restoreWallet(walletRestore.password,
                           walletRestore.mnemonic,
                           walletRestore.isMiner.getOrElse(false),
                           walletRestore.walletName,
                           walletRestore.mnemonicPassphrase)
            .map(model.WalletRestore.Result)
            .left
            .map(toApiError)
        )
      } ~
      lockWallet.toRoute { wallet =>
        Future.successful(walletService.lockWallet(wallet).left.map(toApiError))
      } ~
      unlockWallet.toRoute {
        case (wallet, walletUnlock) =>
          Future.successful(
            walletService.unlockWallet(wallet, walletUnlock.password).left.map(toApiError))
      } ~
      getBalances.toRoute { wallet =>
        walletService
          .getBalances(wallet)
          .map(_.map { balances =>
            val totalBalance = balances.map { case (_, amount) => amount }.fold(U256.Zero) {
              case (acc, u256) => acc.addUnsafe(u256)
            }
            val balancesPerAddress = balances.map {
              case (address, amount) => model.Balances.AddressBalance(address, amount)
            }
            model.Balances(totalBalance, balancesPerAddress)
          }.left.map(toApiError))
      } ~
      getAddresses.toRoute { wallet =>
        Future.successful(
          walletService
            .getAddresses(wallet)
            .map {
              case (active, addresses) =>
                model.Addresses(active, addresses)
            }
            .left
            .map(toApiError))
      } ~
      getMinerAddresses.toRoute { wallet =>
        Future.successful(
          walletService
            .getMinerAddresses(wallet)
            .map { addresses =>
              addresses.map { p =>
                model.MinerAddressesInfo(
                  p.map {
                    case (group, ad) =>
                      model.AddressInfo(ad, group.value)
                  }
                )
              }
            }
            .left
            .map(toApiError))
      } ~
      transfer.toRoute {
        case (wallet, tr) =>
          walletService
            .transfer(wallet, tr.address, tr.amount)
            .map(_.map {
              case (txId, fromGroup, toGroup) => model.Transfer.Result(txId, fromGroup, toGroup)
            }.left.map(toApiError))
      } ~
      deriveNextAddress.toRoute { wallet =>
        Future.successful(
          walletService
            .deriveNextAddress(wallet)
            .left
            .map(toApiError))
      } ~
      deriveNextMinerAddresses.toRoute { wallet =>
        Future.successful(
          walletService
            .deriveNextMinerAddresses(wallet)
            .map(_.map(address => model.AddressInfo(address, address.groupIndex.value)))
            .left
            .map(toApiError))
      } ~
      changeActiveAddress.toRoute {
        case (wallet, change) =>
          Future.successful(
            walletService
              .changeActiveAddress(wallet, change.address)
              .left
              .map(toApiError)
          )
      } ~
      listWallets.toRoute { _ =>
        Future.successful(
          walletService
            .listWallets()
            .map(_.map { case (name, locked) => model.WalletStatus(name, locked) })
            .left
            .map(toApiError)
        )
      } ~
      docsRoute
}

object WalletServer {
  import WalletApiError._
  def toApiError(walletError: WalletError): WalletApiError = {

    val badRequest   = BadRequest(walletError.message)
    val unauthorized = Unauthorized(walletError.message)

    walletError match {
      case _: InvalidMnemonic           => badRequest
      case _: InvalidWalletName         => badRequest
      case _: CannotCreateEncryptedFile => badRequest
      case _: BlockFlowClientError      => badRequest
      case _: UnknownAddress            => badRequest
      case NoWalletLoaded               => badRequest
      case CannotDeriveNewAddress       => badRequest
      case UnexpectedError              => badRequest

      case WalletLocked        => unauthorized
      case InvalidPassword     => unauthorized
      case MinerWalletRequired => unauthorized
    }
  }
}
