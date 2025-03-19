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

import akka.util.ByteString
import org.scalacheck.Arbitrary

import org.alephium.crypto.{Byte64, SecP256R1, SecP256R1Signature}
import org.alephium.protocol.Hash
import org.alephium.serde.{intSerde, SerdeError}
import org.alephium.util.{AlephiumSpec, NumericHelpers}

class WebAuthnSpec extends AlephiumSpec with NumericHelpers {
  it should "validate the client data" in {
    val webauthn0 = WebAuthn.createForTest(ByteString.empty, "webauthn.get")
    WebAuthn.validateClientData(webauthn0) isE ()
    val webauthn1 = WebAuthn.createForTest(ByteString.empty, "webauthn.create")
    WebAuthn.validateClientData(webauthn1) is Left(
      "Invalid type in client data, expected webauthn.get"
    )
    val webauthn2 = webauthn1.copy(clientDataSuffix = WebAuthn.TypeField)
    WebAuthn.validateClientData(webauthn2) isE ()
  }

  def createAuthenticatorData(flag: Byte): ByteString = {
    val rpIdHash  = Hash.generate.bytes
    val signCount = bytesGen(4).sample.get
    val postfix   = bytesGen(nextInt(40)).sample.get
    rpIdHash ++ ByteString(flag) ++ signCount ++ postfix
  }

  it should "validate the webauthn payload" in {
    val authenticatorData =
      createAuthenticatorData((Arbitrary.arbByte.arbitrary.sample.get | 0x01).toByte)
    val webauthn = WebAuthn.createForTest(authenticatorData, WebAuthn.GET)
    WebAuthn.validate(webauthn) isE ()

    val webauthn0 =
      webauthn.copy(authenticatorData = bytesGen(nextInt(WebAuthn.FlagIndex)).sample.get)
    WebAuthn.validate(webauthn0) is Left("Invalid UP bit in authenticator data")

    val webauthn1 = webauthn.copy(
      authenticatorData =
        createAuthenticatorData((Arbitrary.arbByte.arbitrary.sample.get & 0xfe).toByte)
    )
    WebAuthn.validate(webauthn1) is Left("Invalid UP bit in authenticator data")
  }

  it should "decode webauthn payload" in {
    def createIterator(bytes: ByteString): () => Option[Byte64] = {
      val chunkSize   = (bytes.length + Byte64.length - 1) / Byte64.length
      val paddingSize = chunkSize * Byte64.length - bytes.length
      val paddedBytes = bytes ++ ByteString(Array.fill(paddingSize)(0.toByte))
      val iterator    = paddedBytes.grouped(Byte64.length).iterator
      () => iterator.nextOption().flatMap(Byte64.from)
    }

    val webauthn = WebAuthn.createForTest(createAuthenticatorData(1), WebAuthn.GET)
    val bytes    = webauthn.getLengthPrefixedPayload()
    val payload  = WebAuthn.serde.serialize(webauthn)
    bytes is (intSerde.serialize(payload.length) ++ payload)

    WebAuthn.tryDecode(() => None).leftValue is SerdeError.WrongFormat("Empty webauthn payload")
    WebAuthn.tryDecode(createIterator(ByteString(-1) ++ payload)).leftValue is SerdeError
      .WrongFormat(
        "Failed to deserialize payload length: Too few bytes: expected 68, got 64"
      )
    WebAuthn.tryDecode(createIterator(intSerde.serialize(-1) ++ payload)).leftValue is SerdeError
      .WrongFormat(
        "Invalid payload length: must be positive"
      )

    WebAuthn.tryDecode(createIterator(bytes)) isE webauthn
    val byte64Array = webauthn.encodeForTest().iterator
    WebAuthn.tryDecode(() => byte64Array.nextOption()) isE webauthn

    val validPostfix = ByteString.fromArrayUnsafe(Array.fill[Byte](10)(0))
    WebAuthn.tryDecode(createIterator(bytes ++ validPostfix)) isE webauthn

    val invalidPostfix = bytesGen(10).sample.get
    WebAuthn
      .tryDecode(createIterator(bytes ++ invalidPostfix))
      .leftValue
      .getMessage
      .startsWith("Invalid webauthn payload: unexpected trailing bytes") is true
    WebAuthn.tryDecode(createIterator(bytes.dropRight(Byte64.length))).leftValue is
      SerdeError.WrongFormat("Incomplete webauthn payload: missing chunks")
  }

  it should "verify the webauthn signature" in {
    val challenge               = Hash.generate.bytes
    val webauthn                = WebAuthn.createForTest(createAuthenticatorData(1), WebAuthn.GET)
    val (privateKey, publicKey) = SecP256R1.generatePriPub()
    val signature               = SecP256R1.sign(webauthn.messageHash(challenge), privateKey)
    webauthn.verify(challenge, signature, publicKey) is true
    webauthn.verify(Hash.generate.bytes, signature, publicKey) is false
    webauthn.verify(challenge, SecP256R1Signature.generate, publicKey) is false
  }

  it should "encode challenge as a base64 string" in {
    val challenge       = Hash.generate.bytes
    val webauthn        = WebAuthn.createForTest(ByteString.empty, WebAuthn.GET)
    val clientDataBytes = webauthn.clientData(challenge)
    val clientDataJSON  = new String(clientDataBytes.toArray, StandardCharsets.UTF_8)
    clientDataJSON is
      s"""{"type":"webauthn.get","challenge":"${WebAuthn.base64urlEncode(challenge)}"}"""
  }
}
