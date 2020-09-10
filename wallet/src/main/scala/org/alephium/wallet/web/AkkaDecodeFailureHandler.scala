package org.alephium.wallet.web

import sttp.tapir.server.akkahttp.AkkaHttpServerOptions

import org.alephium.wallet.api.DecodeFailureHandler

trait AkkaDecodeFailureHandler extends DecodeFailureHandler {
  implicit val akkaHttpServerOptions: AkkaHttpServerOptions = AkkaHttpServerOptions.default.copy(
    decodeFailureHandler = myDecodeFailureHandler
  )
}
