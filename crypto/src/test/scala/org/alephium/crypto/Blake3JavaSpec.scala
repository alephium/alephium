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

import scala.io.Source

import org.alephium.util.AlephiumSpec

class Blake3JavaSpec extends AlephiumSpec {
  import Blake3Spec._

  it should "correctly bind the `blake3` lib" in {

    def test(builder: => Blake3Java, expected: TestCase => String)(testCase: TestCase) = {
      val hasher = builder

      val testInput = makeTestInput(testCase.input_len)
      hasher.update(testInput)

      val outputLength = expected(testCase).length / 2
      val output       = hasher.digest(outputLength)

      bytesToHex(output) is expected(testCase)
    }

    testVectors.cases.foreach(
      test(Blake3Java.newInstance(), _.hash)
    )

    testVectors.cases.foreach(
      test(Blake3Java.newKeyedHasher(testVectors.key.getBytes), _.keyed_hash)
    )

    testVectors.cases.foreach(
      test(Blake3Java.newKeyDerivationHasher(testVectors.context_string), _.derive_key)
    )
  }
}

object Blake3Spec {
  import io.circe.Decoder
  import io.circe.parser.parse
  import io.circe.generic.semiauto.deriveDecoder

  case class TestCase(input_len: Int, hash: String, keyed_hash: String, derive_key: String)
  implicit val testCaseDecoder: Decoder[TestCase] = deriveDecoder[TestCase]

  case class TestVectors(
      _comment: String,
      key: String,
      context_string: String,
      cases: Seq[TestCase]
  )
  implicit val testVectorsDecoder: Decoder[TestVectors] = deriveDecoder[TestVectors]

  private val testVectorsJsonFile: String = {
    val path = getClass.getResource("/test_vectors.json").getPath
    Source.fromFile(path.toString).getLines().mkString("")
  }

  val testVectors = parse(testVectorsJsonFile).toOption.get.as[TestVectors].toOption.get

  def makeTestInput(size: Int): Array[Byte] =
    Array.ofDim[Byte](size).zipWithIndex.map { case (_, i) =>
      (i % 251).toByte
    }

  def bytesToHex(byteArray: Array[Byte]): String = {
    val stringBuilder: StringBuilder = new StringBuilder(byteArray.length * 2)
    byteArray.foreach { b =>
      stringBuilder.append(String.format("%02x", b))
    }
    stringBuilder.toString()
  }
}
