package org.alephium.crypto

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.TryValues

import org.alephium.util.AlephiumSpec

class AESSpec() extends AlephiumSpec with TryValues {

  val dataGen: Gen[ByteString] =
    Gen.nonEmptyListOf(Gen.posNum[Byte]).map(bytes => ByteString.fromArrayUnsafe(bytes.toArray))
  val passwordGen: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.toString)

  it should "encrypt/decrypt correct data" in {
    forAll(dataGen, passwordGen) {
      case (data, password) =>
        val encrypted = AES.encrypt(data, password)
        AES.decrypt(encrypted, password).success.value is data
    }
  }

  it should "fail to decrypt corrupted data" in {
    forAll(dataGen, passwordGen, Gen.posNum[Byte]) {
      case (data, password, byte) =>
        val encrypted = AES.encrypt(data, password)
        val index     = Random.nextInt(encrypted.encrypted.length)
        val corruptedData =
          ByteString.fromArrayUnsafe(encrypted.encrypted.updated(index, byte).toArray)
        val corrupted = encrypted.copy(encrypted = corruptedData)

        AES.decrypt(corrupted, password).failure.exception.getMessage is "Tag mismatch!"
    }
  }

  it should "fail to decrypt with wrong password " in {
    forAll(dataGen, passwordGen, passwordGen) {
      case (data, password, wrongPassword) =>
        val encrypted = AES.encrypt(data, password)
        AES.decrypt(encrypted, wrongPassword).failure.exception.getMessage is "Tag mismatch!"
    }
  }
}
