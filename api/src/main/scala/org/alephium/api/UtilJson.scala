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
import java.net.{InetAddress, InetSocketAddress}

import scala.reflect.ClassTag

import akka.util.ByteString
import upickle.core.Util

import org.alephium.json.Json._
import org.alephium.util.{AVector, Hex, TimeStamp}

object UtilJson {

  implicit def avectorWriter[A: Writer]: Writer[AVector[A]] =
    ArrayWriter[A].comap(_.toArray)
  implicit def avectorReader[A: Reader: ClassTag]: Reader[AVector[A]] =
    ArrayReader[A].map(AVector.unsafe(_))

  implicit def avectorReadWriter[A: ReadWriter: ClassTag]: ReadWriter[AVector[A]] =
    ReadWriter.join(avectorReader, avectorWriter)

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  implicit val javaBigIntegerReader: Reader[BigInteger] = new SimpleReader[BigInteger] {
    override def expectedMsg                              = "expected bigint"
    override def visitString(s: CharSequence, index: Int) = new BigInteger(s.toString)
    override def visitFloat64StringParts(
        s: CharSequence,
        decIndex: Int,
        expIndex: Int,
        index: Int
    ) = {
      if (decIndex == -1 && expIndex == -1) {
        BigInteger.valueOf(Util.parseIntegralNum(s, decIndex, expIndex, index))
      } else {
        throw upickle.core.Abort(expectedMsg + " but got float")
      }
    }
  }

  implicit val javaBigIntegerWriter: Writer[BigInteger] = BigIntWriter.comap[BigInteger] { jbi =>
    BigInt(jbi)
  }

  implicit val byteStringWriter: Writer[ByteString] =
    StringWriter.comap[ByteString]((bs: ByteString) => Hex.toHexString(bs))

  implicit val byteStringReader: Reader[ByteString] = StringReader.map { bs =>
    Hex
      .from(bs)
      .getOrElse(
        throw new upickle.core.Abort(s"Invalid hex string: $bs")
      )
  }

  implicit val inetAddressRW: ReadWriter[InetAddress] = readwriter[String].bimap[InetAddress](
    _.getHostAddress,
    InetAddress.getByName(_)
  )

  implicit val socketAddressRW: ReadWriter[InetSocketAddress] = {
    readwriter[ujson.Value].bimap[InetSocketAddress](
      i => ujson.Obj("addr" -> (writeJs(i.getAddress)), "port" -> writeJs(i.getPort().toDouble)),
      json => new InetSocketAddress(read[InetAddress](json("addr")), json("port").num.toInt)
    )
  }

  implicit val timestampWriter: Writer[TimeStamp] = LongWriter.comap[TimeStamp](_.millis)

  implicit val timestampReader: Reader[TimeStamp] = LongReader.map { millis =>
    if (millis < 0) {
      throw new upickle.core.Abort("expect positive timestamp")
    } else {
      TimeStamp.unsafe(millis)
    }
  }
}
