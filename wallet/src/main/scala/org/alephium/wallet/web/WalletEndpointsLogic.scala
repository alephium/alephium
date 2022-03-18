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

import org.alephium.api.model.{Amount, ApiKey}
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.{Duration, U256}
import org.alephium.wallet.api.WalletEndpoints
import org.alephium.wallet.api.model
import org.alephium.wallet.service.WalletService

@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
trait WalletEndpointsLogic extends WalletEndpoints {

  import WalletServer.toApiError
  def walletService: WalletService
  def blockflowFetchMaxAge: Duration
  implicit def groupConfig: GroupConfig
  implicit def executionContext: ExecutionContext

  override val maybeApiKey: Option[ApiKey] = None

  val createWalletLogic = serverLogic(createWallet) { walletCreation =>
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
          model.WalletCreationResult(walletName, mnemonic)
        }
        .left
        .map(toApiError)
    )
  }
  val restoreWalletLogic = serverLogic(restoreWallet) { walletRestore =>
    Future.successful(
      walletService
        .restoreWallet(
          walletRestore.password,
          walletRestore.mnemonic,
          walletRestore.isMiner.getOrElse(false),
          walletRestore.walletName,
          walletRestore.mnemonicPassphrase
        )
        .map(model.WalletRestoreResult)
        .left
        .map(toApiError)
    )
  }
  val lockWalletLogic = serverLogic(lockWallet) { wallet =>
    Future.successful(walletService.lockWallet(wallet).left.map(toApiError))
  }
  val unlockWalletLogic = serverLogic(unlockWallet) { case (wallet, walletUnlock) =>
    Future.successful(
      walletService
        .unlockWallet(wallet, walletUnlock.password, walletUnlock.mnemonicPassphrase)
        .left
        .map(toApiError)
    )
  }
  val deleteWalletLogic = serverLogic(deleteWallet) { case (wallet, walletDeletion) =>
    Future.successful(
      walletService.deleteWallet(wallet, walletDeletion.password).left.map(toApiError)
    )
  }

  val getBalancesLogic = serverLogic(getBalances) { case (wallet, utxosLimit) =>
    walletService
      .getBalances(wallet, utxosLimit)
      .map(_.map { balances =>
        val totalBalance =
          Amount(balances.map { case (_, amount, _, _) => amount }.fold(U256.Zero) {
            case (acc, amount) =>
              acc.addUnsafe(amount.value)
          })
        val balancesPerAddress = balances.map { case (address, amount, lockedAmount, warning) =>
          model.Balances
            .AddressBalance(address, amount, amount.hint, lockedAmount, lockedAmount.hint, warning)
        }
        model.Balances(totalBalance, totalBalance.hint, balancesPerAddress)
      }.left.map(toApiError))
  }

  val getAddressesLogic = serverLogic(getAddresses) { wallet =>
    Future.successful(
      walletService
        .getAddresses(wallet)
        .left
        .map(toApiError)
    )
  }

  val getAddressInfoLogic = serverLogic(getAddressInfo) { case (wallet, address) =>
    Future.successful(
      walletService
        .getAddressInfo(wallet, address)
        .left
        .map(toApiError)
    )
  }

  val getMinerAddressesLogic = serverLogic(getMinerAddresses) { wallet =>
    Future.successful(
      walletService
        .getMinerAddresses(wallet)
        .map { addresses =>
          addresses.map(model.MinerAddressesInfo(_))
        }
        .left
        .map(toApiError)
    )
  }

  val revealMnemonicLogic = serverLogic(revealMnemonic) { case (wallet, revealMnemonicParam) =>
    Future.successful(
      walletService
        .revealMnemonic(wallet, revealMnemonicParam.password)
        .map(model.RevealMnemonicResult.apply)
        .left
        .map(toApiError)
    )
  }

  val transferLogic = serverLogic(transfer) { case (wallet, tr) =>
    walletService
      .transfer(wallet, tr.destinations, tr.gas, tr.gasPrice, tr.utxosLimit)
      .map(_.map { case (txId, fromGroup, toGroup) =>
        model.TransferResult(txId, fromGroup, toGroup)
      }.left.map(toApiError))
  }

  val sweepActiveAddressLogic = serverLogic(sweepActiveAddress) { case (wallet, sa) =>
    walletService
      .sweepActiveAddress(wallet, sa.toAddress, sa.lockTime, sa.gas, sa.gasPrice, sa.utxosLimit)
      .map(_.map(model.TransferResults.from).left.map(toApiError))
  }

  val sweepAllAddressesLogic = serverLogic(sweepAllAddresses) { case (wallet, sa) =>
    walletService
      .sweepAllAddresses(wallet, sa.toAddress, sa.lockTime, sa.gas, sa.gasPrice, sa.utxosLimit)
      .map(_.map(model.TransferResults.from).left.map(toApiError))
  }

  val signLogic = serverLogic(sign) { case (wallet, sign) =>
    Future.successful(
      walletService
        .sign(wallet, sign.data)
        .map { signature =>
          model.SignResult(signature)
        }
        .left
        .map(toApiError)
    )
  }

  val deriveNextAddressLogic = serverLogic(deriveNextAddress) { case (wallet, groupOpt) =>
    Future.successful(
      walletService
        .deriveNextAddress(wallet, groupOpt)
        .left
        .map(toApiError)
    )
  }
  val deriveNextMinerAddressesLogic = serverLogic(deriveNextMinerAddresses) { wallet =>
    Future.successful(
      walletService
        .deriveNextMinerAddresses(wallet)
        .left
        .map(toApiError)
    )
  }
  val changeActiveAddressLogic = serverLogic(changeActiveAddress) { case (wallet, change) =>
    Future.successful(
      walletService
        .changeActiveAddress(wallet, change.address)
        .left
        .map(toApiError)
    )
  }
  val listWalletsLogic = serverLogic(listWallets) { _ =>
    Future.successful(
      walletService
        .listWallets()
        .map(_.map { case (name, locked) => model.WalletStatus(name, locked) })
        .left
        .map(toApiError)
    )
  }
  val getWalletLogic = serverLogic(getWallet) { wallet =>
    Future.successful(
      walletService
        .getWallet(wallet)
        .map { case (name, locked) => model.WalletStatus(name, locked) }
        .left
        .map(toApiError)
    )
  }
}
