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

import scala.concurrent.ExecutionContext

import io.vertx.ext.web._
import sttp.model.StatusCode
import sttp.tapir.server.vertx.VertxFutureServerInterpreter

import org.alephium.api.ApiError
import org.alephium.api.OpenAPIWriters.openApiJson
import org.alephium.api.model.ApiKey
import org.alephium.http.{ServerOptions, SwaggerVertx}
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.{AVector, Duration}
import org.alephium.wallet.WalletDocumentation
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.service.WalletService._

@SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
class WalletServer(
    val walletService: WalletService,
    val blockflowFetchMaxAge: Duration,
    override val maybeApiKey: Option[ApiKey]
)(implicit val groupConfig: GroupConfig, val executionContext: ExecutionContext)
    extends WalletEndpointsLogic
    with WalletDocumentation
    with VertxFutureServerInterpreter {

  override val vertxFutureServerOptions = ServerOptions.serverOptions

  lazy val docsRoute: Router => Route = new SwaggerVertx(
    openApiJson(walletOpenAPI, maybeApiKey.isEmpty)
  ).route

  val routes: AVector[Router => Route] = AVector(
    createWalletLogic,
    restoreWalletLogic,
    lockWalletLogic,
    unlockWalletLogic,
    deleteWalletLogic,
    getBalancesLogic,
    getAddressesLogic,
    getAddressInfoLogic,
    getMinerAddressesLogic,
    transferLogic,
    sweepActiveAddressLogic,
    sweepAllAddressesLogic,
    signLogic,
    deriveNextAddressLogic,
    deriveNextMinerAddressesLogic,
    changeActiveAddressLogic,
    revealMnemonicLogic,
    listWalletsLogic,
    getWalletLogic
  ).map(route(_))
}

object WalletServer {
  import ApiError._
  def toApiError(walletError: WalletError): ApiError[_ <: StatusCode] = {

    def badRequest                 = BadRequest(walletError.message)
    def internalServerError        = InternalServerError(walletError.message)
    def unauthorized               = Unauthorized(walletError.message)
    def notFound(filename: String) = NotFound(filename)

    walletError match {
      case _: InvalidWalletName         => badRequest
      case _: CannotCreateEncryptedFile => badRequest
      case BlockFlowClientError(error)  => error
      case _: UnknownAddress            => badRequest
      case InvalidWalletFile            => badRequest
      case UnexpectedError              => internalServerError
      case WalletNotFound(file)         => notFound(file.getName())
      case _: OtherError                => badRequest

      case WalletLocked              => unauthorized
      case InvalidPassword           => unauthorized
      case InvalidMnemonicPassphrase => unauthorized
      case MinerWalletRequired       => unauthorized
    }
  }
}
