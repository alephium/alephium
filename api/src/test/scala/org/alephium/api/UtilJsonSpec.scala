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

package org.alephium.api

import java.math.BigInteger
import java.net.InetSocketAddress

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.json.Json._
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

case class Foo(bar: ByteString)
object Foo {
  import UtilJson._
  implicit val rw: ReadWriter[Foo] = macroRW
}

class UtilJsonSpec extends AlephiumSpec {
  import UtilJson._

  def check[T: Reader: Writer](input: T, rawJson: String): Assertion = {
    val json = write(input)
    json is rawJson
    read[T](json) is input
  }

  it should "write/read vectors" in {
    forAll { ys: List[Int] => check(AVector.from(ys), ys.mkString("[", ",", "]")) }
  }

  def addressJson(addr: String): String = s"""{"addr":"$addr","port":9000}"""

  it should "write/read socket addresses" in {
    val addr0    = "127.0.0.1"
    val address0 = new InetSocketAddress(addr0, 9000)
    check(address0, addressJson(addr0))

    val addr1    = "2001:db8:85a3:0:0:8a2e:370:7334"
    val address1 = new InetSocketAddress(addr1, 9000)
    check(address1, addressJson(addr1))
  }

  it should "fail for address based on host name" in {
    val rawJson = addressJson("foobar")
    assertThrows[java.net.UnknownHostException](read[InetSocketAddress](rawJson))
  }

  it should "read hexstring" in {
    val jsonRaw = """{"bar": "48656c6c6f20776f726c642021"}"""
    val foo     = read[Foo](jsonRaw)
    foo.bar.utf8String is "Hello world !"
  }

  it should "write/read TimeStamp" in {
    val rawTimestamp: Long = 1234589
    val rawJson            = s"$rawTimestamp"
    val timestamp          = TimeStamp.unsafe(rawTimestamp)
    check(timestamp, rawJson)
  }

  it should "fail to read negative TimeStamp" in {
    assertThrows[upickle.core.AbortException](read[TimeStamp]("-12345"))
  }

  it should "fail to read a Double as a BigInteger" in {
    forAll(Gen.chooseNum(Double.MinValue, Double.MaxValue)) { double =>
      assertThrows[upickle.core.AbortException](read[BigInteger](s"$double"))
    }
  }
}
