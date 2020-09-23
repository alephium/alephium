package org.alephium.wallet.api

import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe.jsonBody

import org.alephium.protocol.model.Address
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
      .in("wallet")
      .in("create")
      .in(jsonBody[WalletCreation])
      .out(jsonBody[Mnemonic])

  val restoreWallet: Endpoint[WalletRestore, WalletApiError, Unit, Nothing] =
    baseEndpoint.post
      .in("wallet")
      .in("restore")
      .in(jsonBody[WalletRestore])

  val lockWallet: Endpoint[Unit, WalletApiError, Unit, Nothing] =
    baseEndpoint.post
      .in("wallet")
      .in("lock")

  val unlockWallet: Endpoint[WalletUnlock, WalletApiError, Unit, Nothing] =
    baseEndpoint.post
      .in("wallet")
      .in("unlock")
      .in(jsonBody[WalletUnlock])

  val getBalance: Endpoint[Unit, WalletApiError, Long, Nothing] =
    baseEndpoint.get
      .in("wallet")
      .in("balance")
      .out(jsonBody[Long])

  val transfer: Endpoint[Transfer, WalletApiError, Transfer.Result, Nothing] =
    baseEndpoint.post
      .in("wallet")
      .in("transfer")
      .in(jsonBody[Transfer])
      .out(jsonBody[Transfer.Result])

  val getAddress: Endpoint[Unit, WalletApiError, Address, Nothing] =
    baseEndpoint.get
      .in("wallet")
      .in("address")
      .out(jsonBody[Address])
}
