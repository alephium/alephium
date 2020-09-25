package org.alephium.wallet.api

import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe.jsonBody

import org.alephium.protocol.model.Address
import org.alephium.util.AVector
import org.alephium.wallet.api.model._
import org.alephium.wallet.circe
import org.alephium.wallet.tapir

trait WalletEndpoints extends circe.ModelCodecs with tapir.Schemas with tapir.Codecs {

  private val baseEndpoint = endpoint.errorOut(
    oneOf[WalletApiError](
      statusMapping(StatusCode.BadRequest,
                    jsonBody[WalletApiError.BadRequest].description("Bad request")),
      statusMapping(StatusCode.Unauthorized,
                    jsonBody[WalletApiError.Unauthorized].description("Unauthorized"))
    )
  )

  val createWallet: Endpoint[WalletCreation, WalletApiError, Mnemonic, Nothing] =
    baseEndpoint.post
      .in("wallets")
      .in(jsonBody[WalletCreation])
      .out(jsonBody[Mnemonic])
      .summary("Create a new wallet")

  val restoreWallet: Endpoint[WalletRestore, WalletApiError, Unit, Nothing] =
    baseEndpoint.put
      .in("wallets")
      .in(jsonBody[WalletRestore])
      .summary("Restore a wallet from your mnemonic")

  val listWallets: Endpoint[Unit, WalletApiError, AVector[String], Nothing] =
    baseEndpoint.get
      .in("wallets")
      .out(jsonBody[AVector[String]])
      .summary("List available wallets")

  val lockWallet: Endpoint[String, WalletApiError, Unit, Nothing] =
    baseEndpoint.post
      .in("wallets")
      .in(path[String]("wallet_name"))
      .in("lock")
      .summary("Lock your wallet")

  val unlockWallet: Endpoint[(String, WalletUnlock), WalletApiError, Unit, Nothing] =
    baseEndpoint.post
      .in("wallets")
      .in(path[String]("wallet_name"))
      .in("unlock")
      .in(jsonBody[WalletUnlock])
      .summary("Unlock your wallet")

  val getBalances: Endpoint[String, WalletApiError, Balances, Nothing] =
    baseEndpoint.get
      .in("wallets")
      .in(path[String]("wallet_name"))
      .in("balances")
      .out(jsonBody[Balances])
      .summary("Get your total balance")

  val transfer: Endpoint[(String, Transfer), WalletApiError, Transfer.Result, Nothing] =
    baseEndpoint.post
      .in("wallets")
      .in(path[String]("wallet_name"))
      .in("transfer")
      .in(jsonBody[Transfer])
      .out(jsonBody[Transfer.Result])
      .summary("Transfer ALF")

  val getAddresses: Endpoint[String, WalletApiError, Addresses, Nothing] =
    baseEndpoint.get
      .in("wallets")
      .in(path[String]("wallet_name"))
      .in("addresses")
      .out(jsonBody[Addresses])
      .summary("List all your wallet's addresses")

  val deriveNextAddress: Endpoint[String, WalletApiError, Address, Nothing] =
    baseEndpoint.post
      .in("wallets")
      .in(path[String]("wallet_name"))
      .in("deriveNextAddress")
      .out(jsonBody[Address])
      .summary("Derive your next address")

  val changeActiveAddress: Endpoint[(String, ChangeActiveAddress), WalletApiError, Unit, Nothing] =
    baseEndpoint.post
      .in("wallets")
      .in(path[String]("wallet_name"))
      .in("changeActiveAddress")
      .in(jsonBody[ChangeActiveAddress])
      .summary("Choose the active address")
}
