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

package org.alephium.crypto

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.TryValues

import org.alephium.util.AlephiumSpec

class AESSpec() extends AlephiumSpec with TryValues {

  val dataGen: Gen[ByteString] =
    Gen.nonEmptyListOf(arbitrary[Byte]).map(bytes => ByteString.fromArrayUnsafe(bytes.toArray))
  val passwordGen: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.toString)

  it should "encrypt/decrypt correct data" in {
    forAll(dataGen, passwordGen) { case (data, password) =>
      val encrypted = AES.encrypt(data, password)
      AES.decrypt(encrypted, password).success.value is data
    }
  }

  it should "fail to decrypt corrupted data" in {
    forAll(dataGen, passwordGen) { case (data, password) =>
      val encrypted    = AES.encrypt(data, password)
      val index        = Random.nextInt(encrypted.encrypted.length)
      val targetByte   = encrypted.encrypted(index)
      val modifiedByte = (targetByte + 1).toByte
      val corruptedData =
        ByteString.fromArrayUnsafe(encrypted.encrypted.updated(index, modifiedByte).toArray)
      val corrupted = encrypted.copy(encrypted = corruptedData)

      AES
        .decrypt(corrupted, password)
        .failure
        .exception
        .getMessage
        .startsWith("Tag mismatch") is true
    }
  }

  it should "fail to decrypt with wrong password " in {
    forAll(dataGen, passwordGen, passwordGen) { case (data, password, wrongPassword) =>
      whenever(wrongPassword != password) {
        val encrypted = AES.encrypt(data, password)
        AES
          .decrypt(encrypted, wrongPassword)
          .failure
          .exception
          .getMessage
          .startsWith("Tag mismatch") is true
      }
    }
  }
}
