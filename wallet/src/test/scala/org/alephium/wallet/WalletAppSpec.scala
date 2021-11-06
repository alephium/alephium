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

package org.alephium.wallet

import java.net.InetAddress

import io.vertx.core.Vertx
import io.vertx.ext.web._
import io.vertx.ext.web.handler.BodyHandler
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import sttp.model.StatusCode

import org.alephium.api.{ApiError, ApiModelCodec}
import org.alephium.api.UtilJson.avectorReadWriter
import org.alephium.api.model._
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.http.HttpFixture._
import org.alephium.http.HttpRouteFixture
import org.alephium.json.Json._
import org.alephium.protocol.{ALPH, Hash, PrivateKey, PublicKey, SignatureSchema}
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model.{Address, CliqueId, NetworkId, TxGenerators}
import org.alephium.serde.serialize
import org.alephium.util.{discard, AlephiumFutureSpec, AVector, Duration, Hex, U256}
import org.alephium.wallet.api.model
import org.alephium.wallet.config.WalletConfigFixture
import org.alephium.wallet.json.ModelCodecs

class WalletAppSpec
    extends AlephiumFutureSpec
    with ModelCodecs
    with WalletConfigFixture
    with TxGenerators
    with HttpRouteFixture
    with IntegrationPatience {

  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  val blockFlowMock =
    new WalletAppSpec.BlockFlowServerMock(host, blockFlowPort)

  val walletApp: WalletApp =
    new WalletApp(config)

  override val port: Int   = config.port.get
  override val maybeApiKey = None

  walletApp.start().futureValue is ()

  val password                   = Hash.generate.toHexString
  val mnemonicPassphrase         = "mnemonic-passphrase"
  var mnemonic: Mnemonic         = _
  var addresses: model.Addresses = _
  var address: Address.Asset     = _
  var wallet: String             = "wallet-name"
  var minerWallet: String        = "miner-wallet-name"
  val (_, transferPublicKey)     = SignatureSchema.generatePriPub()
  val transferAddress            = Address.p2pkh(transferPublicKey).toBase58
  val transferAmount             = 10
  val balanceAmount              = Amount(ALPH.alph(42))
  val lockedAmount               = Amount(ALPH.alph(21))

  def creationJson(size: Int, name: String) =
    s"""{"password":"$password","mnemonicSize":${size},"walletName":"$name"}"""
  val minerCreationJson = s"""{"password":"$password","walletName":"$minerWallet","isMiner":true}"""
  val passwordJson      = s"""{"password":"$password"}"""
  def passwordWithPassphraseJson(mnemonicPassphrase: String) =
    s"""{"password":"$password","walletName":"$wallet","mnemonicPassphrase":"$mnemonicPassphrase"}"""
  def unlockJson(mnemonicPassphrase: Option[String]) =
    mnemonicPassphrase match {
      case None       => passwordJson
      case Some(pass) => passwordWithPassphraseJson(pass)
    }
  def transferJson(amount: Int) =
    s"""{"destinations":[{"address":"$transferAddress","amount":"$amount","tokens":[]}]}"""
  val sweepAllJson =
    s"""{"toAddress":"$transferAddress"}"""
  def changeActiveAddressJson(address: Address) = s"""{"address":"${address.toBase58}"}"""
  def restoreJson(mnemonic: Mnemonic, name: String) =
    s"""{"password":"$password","mnemonic":${writeJs(mnemonic)},"walletName":"$name"}"""

  def create(size: Int, name: String = wallet) =
    Post("/wallets", creationJson(size, name))
  def minerCreate() =
    Post("/wallets", minerCreationJson)
  def restore(mnemonic: Mnemonic, name: String = wallet) =
    Put("/wallets", restoreJson(mnemonic, name))
  def unlock(mnemonicPassphrase: Option[String] = None) =
    Post(s"/wallets/$wallet/unlock", unlockJson(mnemonicPassphrase))
  def lock()                   = Post(s"/wallets/$wallet/lock")
  def delete()                 = Delete(s"/wallets/$wallet", passwordJson)
  def getBalance()             = Get(s"/wallets/$wallet/balances")
  def getAddresses()           = Get(s"/wallets/$wallet/addresses")
  def getMinerAddresses()      = Get(s"/wallets/$minerWallet/miner-addresses")
  def revealMnemonic()         = Get(s"/wallets/$wallet/reveal-mnemonic", maybeBody = Some(passwordJson))
  def transfer(amount: Int)    = Post(s"/wallets/$wallet/transfer", transferJson(amount))
  def sweepAll()               = Post(s"/wallets/$wallet/sweep-all", sweepAllJson)
  def sign(data: String)       = Post(s"/wallets/$wallet/sign", s"""{"data":"$data"}""")
  def deriveNextAddress()      = Post(s"/wallets/$wallet/derive-next-address")
  def deriveNextMinerAddress() = Post(s"/wallets/$minerWallet/derive-next-miner-addresses")
  def getAddressInfo(address: Address) =
    Get(s"/wallets/$wallet/addresses/$address")
  def changeActiveAddress(address: Address) =
    Post(s"/wallets/$wallet/change-active-address", changeActiveAddressJson(address))
  def listWallets() = Get("/wallets")
  def getWallet()   = Get(s"/wallets/$wallet")

  it should "work" in {

    unlock() check { response =>
      response.code is StatusCode.NotFound
      response.body.leftValue is s"""{"resource":"$wallet","detail":"$wallet not found"}"""
    }

    create(2) check { response =>
      val error = response.as[ApiError.BadRequest]
      error.detail is s"""Invalid value for: body (Invalid mnemonic size: 2, expected: 12, 15, 18, 21, 24 at index 94: decoding failure)"""
      response.code is StatusCode.BadRequest
    }

    create(24) check { response =>
      val result = response.as[model.WalletCreation.Result]
      mnemonic = result.mnemonic
      wallet = result.walletName
      response.code is StatusCode.Ok
    }

    listWallets() check { response =>
      val walletStatus = response.as[AVector[model.WalletStatus]].head
      walletStatus.walletName is wallet
      walletStatus.locked is false
      response.code is StatusCode.Ok
    }

    getWallet() check { response =>
      val walletStatus = response.as[model.WalletStatus]
      walletStatus.walletName is wallet
      walletStatus.locked is false
      response.code is StatusCode.Ok
    }

    //Lock is idempotent
    (0 to 10).foreach { _ =>
      lock() check { response =>
        response.code is StatusCode.Ok
      }
    }

    getBalance() check { response =>
      response.code is StatusCode.Unauthorized
    }

    getAddresses() check { response =>
      response.code is StatusCode.Unauthorized
    }

    transfer(transferAmount) check { response =>
      response.code is StatusCode.Unauthorized
    }

    getWallet() check { response =>
      val walletStatus = response.as[model.WalletStatus]
      walletStatus.walletName is wallet
      walletStatus.locked is true
      response.code is StatusCode.Ok
    }

    unlock()

    getAddresses() check { response =>
      addresses = response.as[model.Addresses]
      address = addresses.activeAddress
      response.code is StatusCode.Ok
    }

    getBalance() check { response =>
      response.as[model.Balances] is model.Balances.from(
        balanceAmount,
        AVector(model.Balances.AddressBalance.from(address, balanceAmount, lockedAmount, None))
      )
      response.code is StatusCode.Ok
    }

    transfer(transferAmount) check { response =>
      response.as[model.Transfer.Result]
      response.code is StatusCode.Ok
    }

    val negAmount = -10
    transfer(negAmount) check { response =>
      val error = response.as[ApiError.BadRequest]
      error.detail.contains(s"""Invalid value for: body (Invalid amount: $negAmount""") is true
      response.code is StatusCode.BadRequest
    }

    val tooMuchAmount = 10000
    transfer(tooMuchAmount) check { response =>
      val error = response.as[ApiError.BadRequest]
      error.detail.contains(s"""Not enough balance""") is true
      response.code is StatusCode.BadRequest
    }

    sweepAll() check { response =>
      response.as[model.Transfer.Result]
      response.code is StatusCode.Ok
    }

    deriveNextAddress() check { response =>
      address = response.as[model.DeriveNextAddress.Result].address
      addresses = model.Addresses(
        address,
        addresses.addresses :+ model.Addresses.Info(address, address.groupIndex.value)
      )
      response.code is StatusCode.Ok
    }

    getAddresses() check { response =>
      response.as[model.Addresses] is addresses
      response.code is StatusCode.Ok
    }

    address = addresses.addresses.head.address
    addresses = addresses.copy(activeAddress = address)

    changeActiveAddress(address) check { response =>
      response.code is StatusCode.Ok
    }

    getAddresses() check { response =>
      response.as[model.Addresses] is addresses
      response.code is StatusCode.Ok
    }

    revealMnemonic() check { response =>
      response.as[model.RevealMnemonic.Result].mnemonic is mnemonic
      response.code is StatusCode.Ok
    }

    val newMnemonic = Mnemonic.generate(24).get
    restore(newMnemonic, "wallet-new-name") check { response =>
      wallet = response.as[model.WalletRestore.Result].walletName
      response.code is StatusCode.Ok
    }

    listWallets() check { response =>
      val walletStatuses = response.as[AVector[model.WalletStatus]]
      walletStatuses.length is 2
      walletStatuses.map(_.walletName).contains(wallet)
      response.code is StatusCode.Ok
    }

    Get("/docs") check { response =>
      response.code is StatusCode.Ok
    }

    Get("/docs/openapi.json") check { response =>
      response.code is StatusCode.Ok
    }

    create(24, "bad!name") check { response =>
      response.code is StatusCode.BadRequest
    }

    create(24, "correct_wallet-name") check { response =>
      response.code is StatusCode.Ok
    }

    delete() check { response =>
      response.code is StatusCode.Ok
    }

    delete() check { response =>
      response.code is StatusCode.NotFound
      write(
        response.as[ujson.Value]
      ) is s"""{"resource":"$wallet","detail":"$wallet not found"}"""
    }

    //handle passphrase
    Post("/wallets", passwordWithPassphraseJson(mnemonicPassphrase)) check { response =>
      val result = response.as[model.WalletCreation.Result]
      mnemonic = result.mnemonic
      wallet = result.walletName
      response.code is StatusCode.Ok
    }

    getAddresses() check { response =>
      addresses = response.as[model.Addresses]
      address = addresses.activeAddress
      response.code is StatusCode.Ok
    }

    lock()
    unlock()

    getAddresses() check { response =>
      response.as[model.Addresses].activeAddress isnot address
    }

    lock()
    unlock(Some(mnemonicPassphrase))

    getAddresses() check { response =>
      response.as[model.Addresses].activeAddress is address
    }

    mnemonic = Mnemonic
      .from(
        "okay teach order cycle slight angle battle enact problem ostrich wise faint office brush lava people walk arrive exit traffic thrive angle manual alley"
      )
      .get
    address = Address.asset("15L9J68punrrGAoXGQjLu9dX5k1kDKehqfG5tFVWqJbG9").get

    restore(mnemonic, "new-wallet") check { response =>
      wallet = response.as[model.WalletRestore.Result].walletName
      wallet is "new-wallet"
      response.code is StatusCode.Ok
    }

    val publicKey = PublicKey
      .from(Hex.unsafe("0362a56b41565582ec52c78f6adf76d7afdcf4b7584682011b0caa6846c3f44819"))
      .get

    val privateKey = PrivateKey
      .from(Hex.unsafe("18d3d0d2f72db3675db48cd38efd334eb10241c73b5df80b716f2905ff340d33"))
      .get

    getAddressInfo(address) check { response =>
      val addressInfo = response.as[model.AddressInfo]
      addressInfo.address is address
      addressInfo.publicKey is publicKey
      addressInfo.group is 2
      response.code is StatusCode.Ok
    }

    val unsignedTx = transactionGen().sample.get.unsigned

    sign(unsignedTx.hash.toHexString) check { response =>
      response.as[model.Sign.Result].signature is SignatureSchema.sign(
        unsignedTx.hash.bytes,
        privateKey
      )
      response.code is StatusCode.Ok
    }

    sign("non-hex-data") check { response =>
      val error = response.as[ApiError.BadRequest]
      error.detail is "Invalid hex string"
      response.code is StatusCode.BadRequest
    }

    minerCreate() check { response =>
      val result = response.as[model.WalletCreation.Result]
      mnemonic = result.mnemonic
      minerWallet = result.walletName
      response.code is StatusCode.Ok
    }

    getMinerAddresses() check { response =>
      val result = response.as[AVector[model.MinerAddressesInfo]]
      result.length is 1
      result.head.addresses.length is groupConfig.groups
      response.code is StatusCode.Ok
    }

    deriveNextMinerAddress() check { response =>
      val result = response.as[AVector[model.MinerAddressInfo]]
      result.length is groupConfig.groups
      response.code is StatusCode.Ok
    }

    getMinerAddresses() check { response =>
      val result = response.as[AVector[model.MinerAddressesInfo]]
      result.length is 2
      response.code is StatusCode.Ok
    }

    tempSecretDir.toFile.listFiles.foreach(_.deleteOnExit())
    walletApp.stop().futureValue is ()
  }
}

object WalletAppSpec extends {

  class BlockFlowServerMock(address: InetAddress, port: Int)(implicit
      val groupConfig: GroupConfig,
      val networkConfig: NetworkConfig
  ) extends TxGenerators
      with ApiModelCodec
      with ScalaFutures {

    private val cliqueId = CliqueId.generate
    private val peer     = PeerAddress(address, port, port, port)

    val blockflowFetchMaxAge = Duration.unsafe(1000)

    private val vertx  = Vertx.vertx()
    private val router = Router.router(vertx)

    def complete[A: Writer](ctx: RoutingContext, a: A, code: Int = 200): Unit = {
      discard(
        ctx.request
          .response()
          .setStatusCode(code)
          .putHeader("content-type", "application/json")
          .end(write(a))
      )
    }

    router.route().path("/transactions/build").handler(BodyHandler.create()).handler { ctx =>
      val buildTransaction = read[BuildTransaction](ctx.getBodyAsString())
      val amount = buildTransaction.destinations.fold(U256.Zero) { (acc, destination) =>
        acc.addUnsafe(destination.amount.value)
      }
      val unsignedTx = transactionGen().sample.get.unsigned

      if (amount > 100) {
        complete(
          ctx,
          ApiError.BadRequest("Not enough balance"),
          400
        )
      } else {
        complete(
          ctx,
          BuildTransactionResult(
            Hex.toHexString(serialize(unsignedTx)),
            unsignedTx.hash,
            unsignedTx.fromGroup.value,
            unsignedTx.toGroup.value
          )
        )
      }
    }

    router.route().path("/transactions/sweep-all/build").handler(BodyHandler.create()).handler {
      ctx =>
        val _          = read[BuildSweepAllTransaction](ctx.getBodyAsString())
        val unsignedTx = transactionGen().sample.get.unsigned
        complete(
          ctx,
          BuildTransactionResult(
            Hex.toHexString(serialize(unsignedTx)),
            unsignedTx.hash,
            unsignedTx.fromGroup.value,
            unsignedTx.toGroup.value
          )
        )
    }

    router.route().path("/transactions/submit").handler(BodyHandler.create()).handler { ctx =>
      val _ = read[SubmitTransaction](ctx.getBodyAsString())
      complete(ctx, TxResult(Hash.generate, 0, 0))
    }

    router.route().path("/infos/self-clique").handler { ctx =>
      complete(
        ctx,
        SelfClique(cliqueId, NetworkId.AlephiumMainNet, 18, AVector(peer, peer), true, true, 1, 2)
      )
    }

    router.route().path("/addresses/:address/balance").handler { ctx =>
      complete(ctx, Balance.from(Amount(ALPH.alph(42)), Amount(ALPH.alph(21)), 1))
    }

    private val server = vertx.createHttpServer().requestHandler(router)
    server.listen(port, address.getHostAddress)
  }
}
