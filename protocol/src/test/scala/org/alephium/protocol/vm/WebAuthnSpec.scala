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
    val challenge   = Hash.generate.bytes
    val clientData0 = WebAuthn.createClientData("webauthn.create", challenge)
    WebAuthn.validateClientData(clientData0, challenge) is Left(
      "Invalid type in client data, expected webauthn.get, but got webauthn.create"
    )

    val clientData1 = WebAuthn.createClientData("webauthn.get", challenge)
    WebAuthn.validateClientData(clientData1, challenge) isE ()
    val index                 = Random.nextInt(challenge.length)
    val byte                  = (challenge(index).toInt + 1).toByte
    val invalidChallengeBytes = challenge.toArray
    invalidChallengeBytes.update(index, byte)
    WebAuthn
      .validateClientData(clientData1, ByteString.fromArrayUnsafe(invalidChallengeBytes))
      .isLeft is true
    WebAuthn.validateClientData(clientData1, Hash.generate.bytes).isLeft is true
  }

  def createAuthenticatorData(flag: Byte): ByteString = {
    val rpIdHash  = Hash.generate.bytes
    val signCount = bytesGen(4).sample.get
    val postfix   = bytesGen(Random.nextInt(100)).sample.get
    rpIdHash ++ ByteString(flag) ++ signCount ++ postfix
  }

  it should "validate the webauthn payload" in {
    val challenge  = Hash.generate.bytes
    val clientData = WebAuthn.createClientData("webauthn.get", challenge)
    val authenticatorData =
      createAuthenticatorData((Arbitrary.arbByte.arbitrary.sample.get | 0x01).toByte)

    val webauthn0 = WebAuthn(
      bytesGen(Random.nextInt(WebAuthn.AuthenticatorDataMinLength)).sample.get,
      clientData
    )
    WebAuthn.validate(webauthn0, challenge) is Left("Invalid authenticator data length")

    val webauthn1 = WebAuthn(
      authenticatorData,
      bytesGen(Random.nextInt(WebAuthn.ClientDataMinLength)).sample.get
    )
    WebAuthn.validate(webauthn1, challenge) is Left("Invalid client data length")

    val webauthn2 = WebAuthn(
      createAuthenticatorData((Arbitrary.arbByte.arbitrary.sample.get & 0xfe).toByte),
      clientData
    )
    WebAuthn.validate(webauthn2, challenge) is Left("Invalid UP bit in authenticator data")

    val webauthn3 = WebAuthn(authenticatorData, clientData)
    WebAuthn.validate(webauthn3, challenge) isE ()
  }

  it should "decode webauthn payload" in {
    def createIterator(bs: ByteString): () => Option[ByteString] = {
      val iterator = bs.grouped(64).iterator
      () => iterator.nextOption()
    }

    val challenge = Hash.generate.bytes
    val webauthn =
      WebAuthn(createAuthenticatorData(1), WebAuthn.createClientData("webauthn.get", challenge))
    val bytes = WebAuthn.serde.serialize(webauthn)
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
    val challenge = Hash.generate.bytes
    val webauthn =
      WebAuthn(createAuthenticatorData(1), WebAuthn.createClientData("webauthn.get", challenge))
    val (privateKey, publicKey) = SecP256R1.generatePriPub()
    val signature               = SecP256R1.sign(webauthn.messageHash, privateKey)
    webauthn.verify(signature, publicKey) is true
    webauthn.verify(SecP256R1Signature.generate, publicKey) is false
  }
}
