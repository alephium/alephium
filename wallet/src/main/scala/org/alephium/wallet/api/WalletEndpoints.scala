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

package org.alephium.wallet.api

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.{BaseEndpoint, TapirCodecs, TapirSchemasLike}
import org.alephium.api.Endpoints.{jsonBody, jsonBodyWithAlph}
import org.alephium.api.UtilJson._
import org.alephium.protocol.model.{Address, GroupIndex}
import org.alephium.util.AVector
import org.alephium.wallet.api.model._
import org.alephium.wallet.json

trait WalletEndpoints
    extends json.ModelCodecs
    with BaseEndpoint
    with TapirSchemasLike
    with TapirCodecs
    with WalletExamples {

  private val wallets = baseEndpoint
    .in("wallets")
    .tag("Wallets")

  private val wallet =
    wallets
      .in(path[String]("wallet_name"))

  private val minerWallet = baseEndpoint
    .in("wallets")
    .in(path[String]("wallet_name"))
    .tag("Miners")
    .description(
      "This endpoint can only be called if the wallet was created with the `isMiner = true` flag"
    )

  val createWallet: BaseEndpoint[WalletCreation, WalletCreationResult] =
    wallets.post
      .in(jsonBody[WalletCreation])
      .out(jsonBody[WalletCreationResult])
      .summary("Create a new wallet")
      .description(
        "A new wallet will be created and respond with a mnemonic. " +
          "Make sure to keep that mnemonic safely as it will allows you to recover your wallet. " +
          s"Default mnemonic size is 24, (options: $mnemonicSizes)."
      )

  val restoreWallet: BaseEndpoint[WalletRestore, WalletRestoreResult] =
    wallets.put
      .in(jsonBody[WalletRestore])
      .out(jsonBody[WalletRestoreResult])
      .summary("Restore a wallet from your mnemonic")

  val listWallets: BaseEndpoint[Unit, AVector[WalletStatus]] =
    wallets.get
      .out(jsonBody[AVector[WalletStatus]])
      .summary("List available wallets")

  val getWallet: BaseEndpoint[String, WalletStatus] =
    wallet.get
      .out(jsonBody[WalletStatus])
      .summary("Get wallet's status")

  val lockWallet: BaseEndpoint[String, Unit] =
    wallet.post
      .in("lock")
      .summary("Lock your wallet")

  val unlockWallet: BaseEndpoint[(String, WalletUnlock), Unit] =
    wallet.post
      .in("unlock")
      .in(jsonBody[WalletUnlock])
      .summary("Unlock your wallet")

  val deleteWallet: BaseEndpoint[(String, WalletDeletion), Unit] =
    wallet.delete
      .in(jsonBody[WalletDeletion])
      .summary("Delete your wallet file (can be recovered with your mnemonic)")

  val getBalances: BaseEndpoint[String, Balances] =
    wallet.get
      .in("balances")
      .out(jsonBodyWithAlph[Balances])
      .summary("Get your total balance")

  val transfer: BaseEndpoint[(String, Transfer), TransferResult] =
    wallet.post
      .in("transfer")
      .in(jsonBodyWithAlph[Transfer])
      .out(jsonBody[TransferResult])
      .summary("Transfer ALPH from the active address")

  val sign: BaseEndpoint[(String, Sign), SignResult] =
    wallet.post
      .in("sign")
      .in(jsonBody[Sign])
      .out(jsonBody[SignResult])
      .summary("Sign the given data and return back the signature")

  val sweepActiveAddress: BaseEndpoint[(String, Sweep), TransferResults] =
    wallet.post
      .in("sweep-active-address")
      .in(jsonBody[Sweep])
      .out(jsonBody[TransferResults])
      .summary("Transfer all unlocked ALPH from the active address to another address")

  val sweepAllAddresses: BaseEndpoint[(String, Sweep), TransferResults] =
    wallet.post
      .in("sweep-all-addresses")
      .in(jsonBody[Sweep])
      .out(jsonBody[TransferResults])
      .summary(
        "Transfer unlocked ALPH from all addresses (including all mining addresses if applicable) to another address"
      )

  val getAddresses: BaseEndpoint[String, Addresses] =
    wallet.get
      .in("addresses")
      .out(jsonBody[Addresses])
      .summary("List all your wallet's addresses")

  val getAddressInfo: BaseEndpoint[(String, Address.Asset), AddressInfo] =
    wallet.get
      .in("addresses")
      .in(path[Address.Asset]("address"))
      .out(jsonBody[AddressInfo])
      .summary("Get address' info")

  val deriveNextAddress: BaseEndpoint[(String, Option[GroupIndex]), AddressInfo] =
    wallet.post
      .in("derive-next-address")
      .in(query[Option[GroupIndex]]("group"))
      .out(jsonBody[AddressInfo])
      .summary("Derive your next address")
      .description("Cannot be called from a miner wallet")

  val changeActiveAddress: BaseEndpoint[(String, ChangeActiveAddress), Unit] =
    wallet.post
      .in("change-active-address")
      .in(jsonBody[ChangeActiveAddress])
      .summary("Choose the active address")

  val revealMnemonic: BaseEndpoint[(String, RevealMnemonic), RevealMnemonicResult] =
    wallet.post
      .in("reveal-mnemonic")
      .in(jsonBody[RevealMnemonic])
      .out(jsonBody[RevealMnemonicResult])
      .summary("Reveal your mnemonic. !!! use it with caution !!!")

  val getMinerAddresses: BaseEndpoint[String, AVector[MinerAddressesInfo]] =
    minerWallet.get
      .in("miner-addresses")
      .out(jsonBody[AVector[MinerAddressesInfo]])
      .summary("List all miner addresses per group")

  val deriveNextMinerAddresses: BaseEndpoint[String, AVector[AddressInfo]] =
    minerWallet.post
      .in("derive-next-miner-addresses")
      .out(jsonBody[AVector[AddressInfo]])
      .summary("Derive your next miner addresses for each group")
      .description(s"Your wallet need to have been created with the miner flag set to true")

}
