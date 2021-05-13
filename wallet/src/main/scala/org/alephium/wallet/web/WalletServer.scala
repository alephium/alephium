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

import io.vertx.ext.web._
import sttp.tapir.server.vertx.VertxFutureServerInterpreter.{route => toRoute}
import sttp.tapir.swagger.vertx.SwaggerVertx

import org.alephium.api.ApiError
import org.alephium.api.OpenAPIWriters.openApiJson
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.http.ServerOptions
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.NetworkType
import org.alephium.util.{AVector, Duration, U256}
import org.alephium.wallet.WalletDocumentation
import org.alephium.wallet.api.WalletEndpoints
import org.alephium.wallet.api.model
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.service.WalletService._

@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
class WalletServer(
    walletService: WalletService,
    val networkType: NetworkType,
    val blockflowFetchMaxAge: Duration
)(implicit groupConfig: GroupConfig, val executionContext: ExecutionContext)
    extends WalletEndpoints
    with WalletDocumentation
    with ServerOptions {
  import WalletServer.toApiError

  lazy val docsRoute: Router => Route = new SwaggerVertx(
    openApiJson(walletOpenAPI),
    yamlName = "openapi.json"
  ).route

  // scalastyle:off method.length
  lazy val routes: AVector[Router => Route] = AVector(
    toRoute(createWallet) { walletCreation =>
      Future.successful(
        walletService
          .createWallet(
            walletCreation.password,
            walletCreation.mnemonicSize.getOrElse(Mnemonic.Size.list.last),
            walletCreation.isMiner.getOrElse(false),
            walletCreation.walletName,
            walletCreation.mnemonicPassphrase
          )
          .map { case (walletName, mnemonic) =>
            model.WalletCreation.Result(walletName, mnemonic)
          }
          .left
          .map(toApiError)
      )
    },
    toRoute(restoreWallet) { walletRestore =>
      Future.successful(
        walletService
          .restoreWallet(
            walletRestore.password,
            walletRestore.mnemonic,
            walletRestore.isMiner.getOrElse(false),
            walletRestore.walletName,
            walletRestore.mnemonicPassphrase
          )
          .map(model.WalletRestore.Result)
          .left
          .map(toApiError)
      )
    },
    toRoute(lockWallet) { wallet =>
      Future.successful(walletService.lockWallet(wallet).left.map(toApiError))
    },
    toRoute(unlockWallet) { case (wallet, walletUnlock) =>
      Future.successful(
        walletService.unlockWallet(wallet, walletUnlock.password).left.map(toApiError)
      )
    },
    toRoute(deleteWallet) { case (wallet, walletDeletion) =>
      Future.successful(
        walletService.deleteWallet(wallet, walletDeletion.password).left.map(toApiError)
      )
    },
    toRoute(getBalances) { wallet =>
      walletService
        .getBalances(wallet)
        .map(_.map { balances =>
          val totalBalance =
            balances.map { case (_, amount) => amount }.fold(U256.Zero) { case (acc, u256) =>
              acc.addUnsafe(u256)
            }
          val balancesPerAddress = balances.map { case (address, amount) =>
            model.Balances.AddressBalance(address, amount)
          }
          model.Balances(totalBalance, balancesPerAddress)
        }.left.map(toApiError))
    },
    toRoute(getAddresses) { wallet =>
      Future.successful(
        walletService
          .getAddresses(wallet)
          .map { case (active, addresses) =>
            model.Addresses(active, addresses)
          }
          .left
          .map(toApiError)
      )
    },
    toRoute(getMinerAddresses) { wallet =>
      Future.successful(
        walletService
          .getMinerAddresses(wallet)
          .map { addresses =>
            addresses.map { p =>
              model.MinerAddressesInfo(
                p.map { case (group, ad) =>
                  model.AddressInfo(ad, group.value)
                }
              )
            }
          }
          .left
          .map(toApiError)
      )
    },
    toRoute(transfer) { case (wallet, tr) =>
      walletService
        .transfer(wallet, tr.address, tr.amount)
        .map(_.map { case (txId, fromGroup, toGroup) =>
          model.Transfer.Result(txId, fromGroup, toGroup)
        }.left.map(toApiError))
    },
    toRoute(deriveNextAddress) { wallet =>
      Future.successful(
        walletService
          .deriveNextAddress(wallet)
          .map(model.DeriveNextAddress.Result(_))
          .left
          .map(toApiError)
      )
    },
    toRoute(deriveNextMinerAddresses) { wallet =>
      Future.successful(
        walletService
          .deriveNextMinerAddresses(wallet)
          .map(_.map(address => model.AddressInfo(address, address.groupIndex.value)))
          .left
          .map(toApiError)
      )
    },
    toRoute(changeActiveAddress) { case (wallet, change) =>
      Future.successful(
        walletService
          .changeActiveAddress(wallet, change.address)
          .left
          .map(toApiError)
      )
    },
    toRoute(listWallets) { _ =>
      Future.successful(
        walletService
          .listWallets()
          .map(_.map { case (name, locked) => model.WalletStatus(name, locked) })
          .left
          .map(toApiError)
      )
    }
  )
}

object WalletServer {
  import ApiError._
  def toApiError(walletError: WalletError): ApiError[_] = {

    def badRequest                 = BadRequest(walletError.message)
    def internalServerError        = InternalServerError(walletError.message)
    def unauthorized               = Unauthorized(walletError.message)
    def notFound(filename: String) = NotFound(filename)

    walletError match {
      case _: InvalidWalletName         => badRequest
      case _: CannotCreateEncryptedFile => badRequest
      case _: BlockFlowClientError      => internalServerError
      case _: UnknownAddress            => badRequest
      case InvalidWalletFile            => badRequest
      case UnexpectedError              => internalServerError
      case WalletNotFound(file)         => notFound(file.getName())

      case WalletLocked        => unauthorized
      case InvalidPassword     => unauthorized
      case MinerWalletRequired => unauthorized
    }
  }
}
