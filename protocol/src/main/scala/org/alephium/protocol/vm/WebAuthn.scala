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

import scala.annotation.tailrec

import akka.util.ByteString

import org.alephium.crypto.{Byte64, SecP256R1, SecP256R1PublicKey, SecP256R1Signature, Sha256}
import org.alephium.protocol.model.TransactionId
import org.alephium.serde.{
  bytestringSerde,
  intSerde,
  serdeImpl,
  Serde,
  SerdeError,
  SerdeResult,
  Staging
}
import org.alephium.util.{AVector, Hex}

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
    verify(txId.bytes, signature, publicKey)
  }

  def getLengthPrefixedPayload(): ByteString = {
    val payload = WebAuthn.serde.serialize(this)
    val length  = serdeImpl[Int].serialize(payload.length)
    length ++ payload
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def encodeForTest(): AVector[Byte64] = {
    val bytes       = getLengthPrefixedPayload()
    val chunkSize   = (bytes.length + Byte64.length - 1) / Byte64.length
    val paddingSize = chunkSize * Byte64.length - bytes.length
    val paddedBytes = bytes ++ ByteString(Array.fill(paddingSize)(0.toByte))
    AVector.from(paddedBytes.grouped(Byte64.length).map(Byte64.from(_).get))
  }
}

object WebAuthn {
  val GET: String = "webauthn.get"
  val TypeField: ByteString =
    ByteString.fromArrayUnsafe(s""""type":"$GET"""".getBytes(StandardCharsets.UTF_8))
  val FlagIndex: Int = 32

  implicit val serde: Serde[WebAuthn] =
    Serde.forProduct3(
      WebAuthn.apply,
      v => (v.authenticatorData, v.clientDataPrefix, v.clientDataSuffix)
    )

  private def extractChunks(
      nextBytes: () => Option[Byte64],
      chunkSize: Int
  ): SerdeResult[ByteString] = {
    @tailrec
    def iter(acc: ByteString, remaining: Int): SerdeResult[ByteString] = {
      if (remaining == 0) {
        Right(acc)
      } else {
        nextBytes() match {
          case Some(bytes) =>
            iter(acc ++ bytes.bytes, remaining - 1)
          case None => Left(SerdeError.WrongFormat("Incomplete webauthn payload: missing chunks"))
        }
      }
    }
    iter(ByteString.empty, chunkSize)
  }

  def decode(payload: ByteString): SerdeResult[WebAuthn] = {
    serde._deserialize(payload).flatMap { case Staging(webauthn, rest) =>
      if (rest.exists(_ != 0)) {
        Left(
          SerdeError.validation(
            s"Invalid webauthn payload: unexpected trailing bytes ${Hex.toHexString(rest)}"
          )
        )
      } else {
        validate(webauthn)
          .map(_ => webauthn)
          .left
          .map(SerdeError.validation)
      }
    }
  }

  def tryDecodePayload(nextBytes: () => Option[Byte64]): SerdeResult[ByteString] = {
    for {
      firstChunk <- nextBytes().toRight(SerdeError.WrongFormat("Empty webauthn payload"))
      deserialized0 <- serdeImpl[Int]
        ._deserialize(firstChunk.bytes)
        .left
        .map(e => SerdeError.WrongFormat(s"Failed to deserialize payload length: ${e.getMessage}"))
      Staging(payloadLength, payloadFirstChunk) = deserialized0
      _ <- Either.cond(
        payloadLength > 0,
        (),
        SerdeError.WrongFormat("Invalid payload length: must be positive")
      )
      chunkSize = (payloadLength - payloadFirstChunk.length + Byte64.length - 1) / Byte64.length
      restPayload <- extractChunks(nextBytes, chunkSize)
    } yield payloadFirstChunk ++ restPayload
  }

  def tryDecode(nextBytes: () => Option[Byte64]): SerdeResult[WebAuthn] = {
    tryDecodePayload(nextBytes).flatMap(decode)
  }

  @inline private[vm] def base64urlEncode(bs: ByteString): String = {
    Base64.getUrlEncoder.withoutPadding().encodeToString(bs.toArray)
  }

  def encodeChallenge(rawChallenge: ByteString): ByteString = {
    val base64Encoded = WebAuthn.base64urlEncode(rawChallenge)
    ByteString.fromArrayUnsafe(base64Encoded.getBytes(StandardCharsets.UTF_8))
  }

  private[vm] def validateClientData(webauthn: WebAuthn): Either[String, Unit] = {
    if (
      webauthn.clientDataPrefix.containsSlice(TypeField) ||
      webauthn.clientDataSuffix.containsSlice(TypeField)
    ) {
      Right(())
    } else {
      Left(s"Invalid type in client data, expected $GET")
    }
  }

  private[vm] def validate(webauthn: WebAuthn): Either[String, Unit] = {
    if (
      webauthn.authenticatorData.length > FlagIndex &&
      (webauthn.authenticatorData(FlagIndex) & 0x01) == 0x01
    ) {
      validateClientData(webauthn)
    } else {
      Left("Invalid UP bit in authenticator data")
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
