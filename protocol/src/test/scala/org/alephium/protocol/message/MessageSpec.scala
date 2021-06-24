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

package org.alephium.protocol.message

import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.NetworkType
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, Bytes, Hex}

class MessageSpec extends AlephiumSpec {

  implicit val groupConfig: GroupConfig = new GroupConfig {
    override def groups: Int = 4
  }

  val networkType: NetworkType = NetworkType.Testnet

  val magicLength    = 4
  val lengthField    = 4
  val checksumLength = 4
  val magic          = networkType.magicBytes
  val pong           = Pong(1)
  val message        = Message(pong)
  val serialized     = Message.serialize(message, networkType)
  val header         = serdeImpl[Header].serialize(message.header)
  val payload        = Payload.serialize(pong)
  val data           = header ++ payload
  val messageLength  = Bytes.from(data.length)
  val checksum       = Hash.hash(data).bytes.take(checksumLength)

  val additionalLength = magicLength + lengthField + checksumLength

  it should "serde message" in {

    payload.length is 2
    header.length is 2

    serialized.length is (payload.length + header.length + additionalLength)

    Message.deserialize(serialized, networkType) isE message

    val Staging(deserialized, rest) =
      Message._deserialize(serialized ++ serialized, networkType).toOption.get

    deserialized is message
    Message.deserialize(rest, networkType) isE message
  }

  it should "fail to deserialize if length isn't correct" in {

    Seq(-1, 0, 1, data.length - 1, data.length + 1).foreach { newDataLength =>
      val newData     = data.take(newDataLength)
      val newCheckSum = Hash.hash(newData).bytes.take(checksumLength)
      val message     = magic ++ newCheckSum ++ Bytes.from(newDataLength) ++ newData
      val result      = Message.deserialize(message, networkType).swap
      if (newDataLength < 0) {
        result isE SerdeError.wrongFormat(s"Negative length: $newDataLength")
      } else if (newDataLength < data.length) {
        result.rightValue
          .asInstanceOf[SerdeError.WrongFormat]
          .message
          .startsWith("Too few bytes") is true
      } else {
        result isE a[SerdeError.NotEnoughBytes]
      }
    }
  }

  it should "fail to deserialize if checksum doesn't match" in {
    val wrongChecksum = Hash.generate.bytes.take(checksumLength)

    Message
      .deserialize(magic ++ wrongChecksum ++ messageLength ++ header ++ payload, networkType)
      .swap
      .map(_.getMessage) isE s"Wrong checksum: expected ${Hex.toHexString(checksum)}, got ${Hex.toHexString(wrongChecksum)}"
  }

  it should "fail to deserialize if magic number doesn't match" in {
    Message
      .deserialize(Message.serialize(message, NetworkType.Mainnet), networkType)
      .swap isE SerdeError.wrongFormat("Wrong magic bytes")
  }

  it should "fail to deserialize if not enough bytes" in {
    serialized.init.indices.foreach { n =>
      Message
        .deserialize(serialized.take(n + 1), networkType)
        .swap isE a[SerdeError.NotEnoughBytes]
    }
  }

  it should "produce the same code name" in {
    pong.name is Pong.codeName
  }
}
