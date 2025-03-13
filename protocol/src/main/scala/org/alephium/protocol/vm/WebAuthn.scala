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

package org.alephium.protocol.vm

import java.nio.charset.StandardCharsets
import java.util.Base64

import akka.util.ByteString

import org.alephium.crypto.{SecP256R1, SecP256R1PublicKey, SecP256R1Signature, Sha256}
import org.alephium.protocol.model.TransactionId
import org.alephium.serde.{bytestringSerde, Serde, SerdeError, SerdeResult}
import org.alephium.util.Hex

final case class WebAuthn(
    authenticatorData: ByteString,
    clientDataPrefix: ByteString,
    clientDataSuffix: ByteString
) {
  def bytesLength: Int =
    authenticatorData.length + clientDataPrefix.length + clientDataSuffix.length

  def clientData(rawChallenge: ByteString): ByteString = {
    val challengeBytes = WebAuthn.encodeChallenge(rawChallenge)
    clientDataPrefix ++ challengeBytes ++ clientDataSuffix
  }

  def messageHash(rawChallenge: ByteString): Sha256 = {
    val clientDataHash = Sha256.hash(clientData(rawChallenge))
    Sha256.hash(authenticatorData ++ clientDataHash.bytes)
  }

  def messageHash(txId: TransactionId): Sha256 = messageHash(txId.bytes)

  def verify(
      rawChallenge: ByteString,
      signature: SecP256R1Signature,
      publicKey: SecP256R1PublicKey
  ): Boolean = {
    SecP256R1.verify(messageHash(rawChallenge).bytes, signature, publicKey)
  }

  def verify(
      txId: TransactionId,
      signature: SecP256R1Signature,
      publicKey: SecP256R1PublicKey
  ): Boolean = {
    SecP256R1.verify(messageHash(txId).bytes, signature, publicKey)
  }
}

object WebAuthn {
  val AuthenticatorDataMinLength: Int = 37
  val ClientDataMinLength: Int        = 81
  val GET: String                     = "webauthn.get"
  val FlagIndex: Int                  = 32

  implicit val serde: Serde[WebAuthn] =
    Serde.forProduct3(
      WebAuthn.apply,
      v => (v.authenticatorData, v.clientDataPrefix, v.clientDataSuffix)
    )

  def tryDecode(
      rawChallenge: ByteString,
      nextBytes: () => Option[ByteString]
  ): SerdeResult[WebAuthn] = {
    val decoder = new Decoder(rawChallenge)

    @scala.annotation.tailrec
    def decode(): SerdeResult[WebAuthn] = {
      nextBytes() match {
        case Some(bytes) =>
          decoder.tryDecode(bytes) match {
            case Right(None)        => decode()
            case Right(Some(value)) => Right(value)
            case Left(error)        => Left(error)
          }
        case None => Left(SerdeError.WrongFormat("Incomplete webauthn payload"))
      }
    }

    decode()
  }

  @inline private[vm] def base64urlEncode(bs: ByteString): String = {
    Base64.getUrlEncoder.withoutPadding().encodeToString(bs.toArray)
  }

  def encodeChallenge(rawChallenge: ByteString): ByteString = {
    val base64Encoded = WebAuthn.base64urlEncode(rawChallenge)
    ByteString.fromArrayUnsafe(base64Encoded.getBytes(StandardCharsets.UTF_8))
  }

  private[vm] def extractValueFromClientData(
      jsonStr: String,
      key: String
  ): Either[String, String] = {
    val keyIndex      = jsonStr.indexOf(key)
    val valStartIndex = keyIndex + key.length + 3
    if (keyIndex == -1) {
      Left(s"The $key does not exist in client data")
    } else if (valStartIndex >= jsonStr.length) {
      Left(s"Invalid $key in client data")
    } else {
      val valEndIndex = jsonStr.indexOf('"', valStartIndex)
      if (valEndIndex == -1) {
        Left(s"Invalid $key in client data")
      } else {
        Right(jsonStr.slice(valStartIndex, valEndIndex))
      }
    }
  }

  private[vm] def validateClientData(clientData: ByteString): Either[String, Unit] = {
    if (clientData.length < ClientDataMinLength) {
      Left("Invalid client data length")
    } else {
      val clientDataStr = new String(clientData.toArray, StandardCharsets.UTF_8)
      for {
        tpe <- extractValueFromClientData(clientDataStr, "type")
        _ <-
          if (tpe == GET) {
            Right(())
          } else {
            Left(s"Invalid type in client data, expected $GET, but got $tpe")
          }
      } yield ()
    }
  }

  private[vm] def validate(webauthn: WebAuthn, rawChallenge: ByteString): Either[String, Unit] = {
    if (webauthn.authenticatorData.length < AuthenticatorDataMinLength) {
      Left("Invalid authenticator data length")
    } else if ((webauthn.authenticatorData(FlagIndex) & 0x01) != 0x01) {
      Left("Invalid UP bit in authenticator data")
    } else {
      validateClientData(webauthn.clientData(rawChallenge))
    }
  }

  private[vm] class Decoder(rawChallenge: ByteString) {
    private var _bytes: ByteString = ByteString.empty

    def tryDecode(bytes: ByteString): SerdeResult[Option[WebAuthn]] = {
      _bytes = _bytes ++ bytes
      serde._deserialize(_bytes) match {
        case Right(result) =>
          if (result.rest.exists(_ != 0)) {
            Left(
              SerdeError.validation(s"Invalid webauthn postfix: ${Hex.toHexString(result.rest)}")
            )
          } else {
            validate(result.value, rawChallenge)
              .map(_ => Some(result.value))
              .left
              .map(SerdeError.validation)
          }
        case Left(_: SerdeError.WrongFormat) => Right(None)
        case Left(error)                     => Left(error)
      }
    }
  }

  def createForTest(authenticatorData: ByteString, tpe: String): WebAuthn = {
    val clientDataPrefixStr = s"""{"type":"$tpe","challenge":""""
    val clientDataSuffixStr = s""""}"""
    WebAuthn(
      authenticatorData,
      ByteString.fromArrayUnsafe(clientDataPrefixStr.getBytes(StandardCharsets.UTF_8)),
      ByteString.fromArrayUnsafe(clientDataSuffixStr.getBytes(StandardCharsets.UTF_8))
    )
  }
}
