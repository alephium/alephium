package org.alephium.api.model

import org.alephium.crypto.Sha256

final case class ApiKey private (val value: String) {
     def hash: Sha256 = Sha256.hash(value)
}
  object ApiKey {
    def unsafe(raw: String): ApiKey = new ApiKey(raw)

    def createApiKey(raw: String): Either[String, ApiKey] = {
      if (raw.length < 32) {
        Left("Api key must have at least 32 characters")
      } else {
        Right(new ApiKey(raw))
      }
    }
  }
