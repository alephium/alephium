package org.alephium.wallet.api

import sttp.tapir._
import sttp.tapir.json.circe.jsonBody

import org.alephium.protocol.model.Address
import org.alephium.wallet.api.model._
import org.alephium.wallet.circe
import org.alephium.wallet.tapir

trait WalletEndpoints extends circe.ModelCodecs with tapir.Schemas with tapir.Codecs {

  val createWallet: Endpoint[WalletCreation, String, Mnemonic, Nothing] =
    endpoint.post
      .in("wallet")
      .in("create")
      .in(jsonBody[WalletCreation])
      .out(jsonBody[Mnemonic])
      .errorOut(plainBody[String])

  val restoreWallet: Endpoint[WalletRestore, String, Unit, Nothing] =
    endpoint.post
      .in("wallet")
      .in("restore")
      .in(jsonBody[WalletRestore])
      .errorOut(plainBody[String])

  val lockWallet: Endpoint[Unit, String, Unit, Nothing] =
    endpoint.post
      .in("wallet")
      .in("lock")
      .errorOut(plainBody[String])

  val unlockWallet: Endpoint[WalletUnlock, String, Unit, Nothing] =
    endpoint.post
      .in("wallet")
      .in("unlock")
      .in(jsonBody[WalletUnlock])
      .errorOut(plainBody[String])

  val getBalance: Endpoint[Unit, String, Long, Nothing] =
    endpoint.get
      .in("wallet")
      .in("balance")
      .out(jsonBody[Long])
      .errorOut(plainBody[String])

  val transfer: Endpoint[Transfer, String, Transfer.Result, Nothing] =
    endpoint.post
      .in("wallet")
      .in("transfer")
      .in(jsonBody[Transfer])
      .out(jsonBody[Transfer.Result])
      .errorOut(plainBody[String])

  val getAddress: Endpoint[Unit, String, Address, Nothing] =
    endpoint.get
      .in("wallet")
      .in("address")
      .out(jsonBody[Address])
      .errorOut(plainBody[String])
}
