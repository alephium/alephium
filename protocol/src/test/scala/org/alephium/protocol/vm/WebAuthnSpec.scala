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

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Arbitrary

import org.alephium.crypto.{SecP256R1, SecP256R1Signature}
import org.alephium.protocol.Hash
import org.alephium.serde.SerdeError
import org.alephium.util.AlephiumSpec

class WebAuthnSpec extends AlephiumSpec {
  it should "extract value from client data" in {
    val json0 = s"""{"type":"webauthn.get","challenge":"p5aV2uHXr0AO_qUk7HQitvi"}"""
    WebAuthn.extractValueFromClientData(json0, "type") is Right("webauthn.get")
    WebAuthn.extractValueFromClientData(json0, "challenge") is Right("p5aV2uHXr0AO_qUk7HQitvi")
    WebAuthn.extractValueFromClientData(json0, "invalidKey") is Left(
      "The invalidKey does not exist in client data"
    )

    val json1 = s"""{"type"}"""
    WebAuthn.extractValueFromClientData(json1, "type") is Left("Invalid type in client data")

    val json2 = s"""{"type":"webauthn.get}"""
    WebAuthn.extractValueFromClientData(json2, "type") is Left("Invalid type in client data")

    val json3 = s"""{"type":"webauth"n.get"}"""
    WebAuthn.extractValueFromClientData(json3, "type") is Right("webauth")
  }

  it should "validate the client data" in {
    val challenge  = Hash.generate.bytes
    val webauthn   = WebAuthn.createForTest(ByteString.empty, "webauthn.create")
    val clientData = webauthn.clientData(challenge)
    WebAuthn.validateClientData(clientData) is Left(
      "Invalid type in client data, expected webauthn.get, but got webauthn.create"
    )
  }

  def createAuthenticatorData(flag: Byte): ByteString = {
    val rpIdHash  = Hash.generate.bytes
    val signCount = bytesGen(4).sample.get
    val postfix   = bytesGen(Random.nextInt(100)).sample.get
    rpIdHash ++ ByteString(flag) ++ signCount ++ postfix
  }

  it should "validate the webauthn payload" in {
    val challenge = Hash.generate.bytes
    val authenticatorData =
      createAuthenticatorData((Arbitrary.arbByte.arbitrary.sample.get | 0x01).toByte)
    val webauthn = WebAuthn.createForTest(authenticatorData, "webauthn.get")
    WebAuthn.validate(webauthn, challenge) isE ()

    val webauthn0 = webauthn.copy(authenticatorData =
      bytesGen(Random.nextInt(WebAuthn.AuthenticatorDataMinLength)).sample.get
    )
    WebAuthn.validate(webauthn0, challenge) is Left("Invalid authenticator data length")

    val webauthn1 = webauthn.copy(clientDataPrefix = bytesGen(Random.nextInt(10)).sample.get)
    WebAuthn.validate(webauthn1, challenge) is Left("Invalid client data length")

    val webauthn2 = webauthn.copy(
      authenticatorData =
        createAuthenticatorData((Arbitrary.arbByte.arbitrary.sample.get & 0xfe).toByte)
    )
    WebAuthn.validate(webauthn2, challenge) is Left("Invalid UP bit in authenticator data")
  }

  it should "decode webauthn payload" in {
    def createIterator(bs: ByteString): () => Option[ByteString] = {
      val iterator = bs.grouped(64).iterator
      () => iterator.nextOption()
    }

    val challenge = Hash.generate.bytes
    val webauthn  = WebAuthn.createForTest(createAuthenticatorData(1), WebAuthn.GET)
    val bytes     = WebAuthn.serde.serialize(webauthn)
    WebAuthn.tryDecode(challenge, createIterator(bytes)) isE webauthn
    val validPostfix = ByteString.fromArrayUnsafe(Array.fill[Byte](10)(0))
    WebAuthn.tryDecode(challenge, createIterator(bytes ++ validPostfix)) isE webauthn

    val invalidPostfix = bytesGen(10).sample.get
    WebAuthn
      .tryDecode(challenge, createIterator(bytes ++ invalidPostfix))
      .leftValue
      .getMessage
      .startsWith("Invalid webauthn postfix") is true
    WebAuthn.tryDecode(challenge, createIterator(bytes.dropRight(1))).leftValue is
      SerdeError.WrongFormat("Incomplete webauthn payload")
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
}
