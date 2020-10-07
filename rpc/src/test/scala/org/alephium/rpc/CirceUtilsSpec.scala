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

package org.alephium.rpc

import java.net.InetSocketAddress

import akka.util.ByteString
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.Assertion

import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

case class Foo(bar: ByteString)
object Foo {
  import CirceUtils._
  implicit val decoder: Decoder[Foo] = deriveDecoder[Foo]
  implicit val encoder: Encoder[Foo] = deriveEncoder[Foo]
}

class CirceUtilsSpec extends AlephiumSpec {
  import CirceUtils._

  def check[T: Codec](input: T, rawJson: String): Assertion = {
    val json = input.asJson
    print(json) is rawJson
    json.as[T] isE input
  }

  it should "encode/decode vectors" in {
    forAll { ys: List[Int] =>
      check(AVector.from(ys), ys.mkString("[", ",", "]"))
    }
  }

  def addressJson(addr: String): String = s"""{"addr":"$addr","port":9000}"""

  it should "encode/decode socket addresses" in {
    val addr0    = "127.0.0.1"
    val address0 = new InetSocketAddress(addr0, 9000)
    check(address0, addressJson(addr0))

    val addr1    = "2001:db8:85a3:0:0:8a2e:370:7334"
    val address1 = new InetSocketAddress(addr1, 9000)
    check(address1, addressJson(addr1))
  }

  it should "fail for address based on host name" in {
    val rawJson = addressJson("foobar")
    parse(rawJson).toOption.get.as[InetSocketAddress].isLeft is true
  }

  it should "encode/decode hexstring" in {
    val jsonRaw = """{"bar": "48656c6c6f20776f726c642021"}"""
    val json    = parse(jsonRaw).toOption.get
    val foo     = json.as[Foo].toOption.get
    foo.bar.utf8String is "Hello world !"
  }

  it should "encode/decode TimeStamp" in {
    val rawTimestamp: Long = 1234589
    val rawJson            = s"$rawTimestamp"
    val timestamp          = TimeStamp.unsafe(rawTimestamp)
    check(timestamp, rawJson)
  }

  it should "fail to decode negative TimeStamp" in {
    parse("-12345").toOption.get.as[TimeStamp].isLeft is true
  }
}
