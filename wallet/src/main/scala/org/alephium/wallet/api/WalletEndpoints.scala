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

import io.circe.{Decoder, Encoder}
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.EndpointIO.Example
import sttp.tapir.json.circe.{jsonBody => tapirJsonBody}

import org.alephium.api.{TapirCodecs, TapirSchemasLike}
import org.alephium.api.CirceUtils._
import org.alephium.protocol.model.Address
import org.alephium.util.AVector
import org.alephium.wallet.api.model._
import org.alephium.wallet.circe

trait WalletEndpoints
    extends circe.ModelCodecs
    with TapirSchemasLike
    with TapirCodecs
    with WalletExamples {

  private def jsonBody[T: Encoder: Decoder: Schema: Validator](
      implicit examples: List[Example[T]]) = tapirJsonBody[T].examples(examples)

  private val wallets = endpoint
    .in("wallets")
    .errorOut(
      oneOf[WalletApiError](
        statusMapping(StatusCode.BadRequest,
                      jsonBody[WalletApiError.BadRequest].description("Bad request")),
        statusMapping(StatusCode.Unauthorized,
                      jsonBody[WalletApiError.Unauthorized].description("Unauthorized"))
      )
    )
    .tag("Wallets")

  private val minerWallets = endpoint
    .in("wallets")
    .errorOut(
      oneOf[WalletApiError](
        statusMapping(StatusCode.BadRequest,
                      jsonBody[WalletApiError.BadRequest].description("Bad request")),
        statusMapping(StatusCode.Unauthorized,
                      jsonBody[WalletApiError.Unauthorized].description("Unauthorized"))
      )
    )
    .tag("Miners")
    .description(
      "This endpoint can only be called if the wallet was created with the `miner = true` flag")

  val createWallet: Endpoint[WalletCreation, WalletApiError, WalletCreation.Result, Nothing] =
    wallets.post
      .in(jsonBody[WalletCreation])
      .out(jsonBody[WalletCreation.Result])
      .summary("Create a new wallet")
      .description(s"""|A new wallet will be created and respond with a mnemonic.
         |Make sure to keep that mnemonic safely as it will allows you to recover your wallet.
         |Default mnemonic size is 24, (options: $mnemonicSizes).""".stripMargin)

  val restoreWallet: Endpoint[WalletRestore, WalletApiError, WalletRestore.Result, Nothing] =
    wallets.put
      .in(jsonBody[WalletRestore])
      .out(jsonBody[WalletRestore.Result])
      .summary("Restore a wallet from your mnemonic")

  val listWallets: Endpoint[Unit, WalletApiError, AVector[WalletStatus], Nothing] =
    wallets.get
      .out(jsonBody[AVector[WalletStatus]])
      .summary("List available wallets")

  val lockWallet: Endpoint[String, WalletApiError, Unit, Nothing] =
    wallets.post
      .in(path[String]("wallet_name"))
      .in("lock")
      .summary("Lock your wallet")

  val unlockWallet: Endpoint[(String, WalletUnlock), WalletApiError, Unit, Nothing] =
    wallets.post
      .in(path[String]("wallet_name"))
      .in("unlock")
      .in(jsonBody[WalletUnlock])
      .summary("Unlock your wallet")

  val getBalances: Endpoint[String, WalletApiError, Balances, Nothing] =
    wallets.get
      .in(path[String]("wallet_name"))
      .in("balances")
      .out(jsonBody[Balances])
      .summary("Get your total balance")

  val transfer: Endpoint[(String, Transfer), WalletApiError, Transfer.Result, Nothing] =
    wallets.post
      .in(path[String]("wallet_name"))
      .in("transfer")
      .in(jsonBody[Transfer])
      .out(jsonBody[Transfer.Result])
      .summary("Transfer ALF")

  val getAddresses: Endpoint[String, WalletApiError, Addresses, Nothing] =
    wallets.get
      .in(path[String]("wallet_name"))
      .in("addresses")
      .out(jsonBody[Addresses])
      .summary("List all your wallet's addresses")

  val deriveNextAddress: Endpoint[String, WalletApiError, Address, Nothing] =
    wallets.post
      .in(path[String]("wallet_name"))
      .in("deriveNextAddress")
      .out(jsonBody[Address])
      .summary("Derive your next address")
      .description("Cannot be called from a miner wallet")

  val changeActiveAddress: Endpoint[(String, ChangeActiveAddress), WalletApiError, Unit, Nothing] =
    wallets.post
      .in(path[String]("wallet_name"))
      .in("changeActiveAddress")
      .in(jsonBody[ChangeActiveAddress])
      .summary("Choose the active address")

  val getMinerAddresses: Endpoint[String, WalletApiError, AVector[MinerAddressesInfo], Nothing] =
    minerWallets.get
      .in(path[String]("wallet_name"))
      .in("miner-addresses")
      .out(jsonBody[AVector[MinerAddressesInfo]])
      .summary("List all miner addresses per group")

  val deriveNextMinerAddresses: Endpoint[String, WalletApiError, AVector[AddressInfo], Nothing] =
    minerWallets.post
      .in(path[String]("wallet_name"))
      .in("deriveNextMinerAddresses")
      .out(jsonBody[AVector[AddressInfo]])
      .summary("Derive your next miner addresse for each group")
      .description(s"""
        |Your wallet need to have been created with the miner flag set to true
      """.stripMargin)

}
