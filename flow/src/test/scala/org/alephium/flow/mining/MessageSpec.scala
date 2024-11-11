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

package org.alephium.flow.mining

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.flow.FlowFixture
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.protocol.model.{BlockHash, ChainIndex, Transaction}
import org.alephium.serde.{avectorSerde, serialize, Staging}
import org.alephium.util.{AlephiumSpec, AVector}
import org.alephium.util.Hex.HexStringSyntax

class MessageSpec extends AlephiumSpec with GroupConfigFixture.Default {
  "ClientMessage" should "serde properly" in {
    val message    = ClientMessage.from(SubmitBlock(hex"bbbb"))
    val serialized = ClientMessage.serialize(message)
    ClientMessage.tryDeserialize(serialized).rightValue.get is
      Staging(message, ByteString.empty)
    ClientMessage.tryDeserialize(serialized.init).rightValue is None
    ClientMessage.tryDeserialize(serialized ++ serialized.init).rightValue.get is
      Staging(message, serialized.init)

    val invalidMessage = message.copy(version = MiningProtocolVersion(2))
    ClientMessage.tryDeserialize(ClientMessage.serialize(invalidMessage)).leftValue.getMessage is
      "Invalid mining protocol version: got 2, expect 1"
  }

  it should "pass explicit hex string serialization examples" in {
    val message    = ClientMessage.from(SubmitBlock(hex"bbbb"))
    val serialized = ClientMessage.serialize(message)
    serialized is
      // message.length (4 bytes)
      hex"00000008" ++
      // version (1 byte)
      hex"01" ++
      // message type (1 byte)
      hex"00" ++
      // blockBlob.length (4 bytes) ++ blockBlob
      hex"00000002" ++ hex"bbbb"
  }

  "ServerMessage" should "serde properly" in {
    val payloads = Seq(
      Jobs(AVector(Job(0, 1, hex"aa", hex"bb", BigInteger.ZERO, 1))),
      SubmitResult(
        0,
        1,
        BlockHash.unsafe(hex"109b05391a240a0d21671720f62fe39138aaca562676053900b348a51e11ba25"),
        true
      ),
      SubmitResult(
        0,
        1,
        BlockHash.unsafe(hex"798e9e137aec7c2d59d9655b4ffa640f301f628bf7c365083bb255f6aa5f89ef"),
        false
      )
    )
    val messages    = payloads.map(ServerMessage.from)
    val serializeds = messages.map(ServerMessage.serialize)
    messages.zip(serializeds).foreach { case (message, serialized) =>
      ServerMessage.tryDeserialize(serialized).rightValue.get is
        Staging(message, ByteString.empty)
      ServerMessage.tryDeserialize(serialized.init).rightValue is None
      ServerMessage.tryDeserialize(serialized ++ serialized.init).rightValue.get is
        Staging(message, serialized.init)
    }

    messages.foreach { message =>
      val invalidMessage = message.copy(version = MiningProtocolVersion(2))
      ServerMessage.tryDeserialize(ServerMessage.serialize(invalidMessage)).leftValue.getMessage is
        "Invalid mining protocol version: got 2, expect 1"
    }
  }

  it should "pass explicit hex string serialization examples" in {
    {
      val message: ServerMessage =
        ServerMessage.from(Jobs(AVector(Job(0, 1, hex"aa", hex"bb", BigInteger.ONE, 17))))
      val serializedJobs = ServerMessage.serialize(message)
      serializedJobs is
        // message.length (4 bytes)
        hex"00000021" ++
        // version (1 byte)
        hex"01" ++
        // message type (1 byte)
        hex"00" ++
        // jobs.length (4 bytes)
        hex"00000001" ++
        // fromGroup (4 bytes)
        hex"00000000" ++
        // toGroup (4 bytes)
        hex"00000001" ++
        // headerBlob.length (4 bytes) ++ blob bytes
        hex"00000001" ++ hex"aa" ++
        // txsBlob.length (4 bytes) ++ blob bytes
        hex"00000001" ++ hex"bb" ++
        // target.length (4 bytes) ++ target bytes
        hex"00000001" ++ hex"01" ++
        // height (4 bytes)
        hex"00000011"
    }

    {
      val message: ServerMessage = ServerMessage.from(
        SubmitResult(
          0,
          1,
          BlockHash.unsafe(hex"bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5"),
          true
        )
      )
      val serialized = ServerMessage.serialize(message)
      serialized is
        // message length (4 bytes)
        hex"0000002b" ++
        // version (1 byte)
        hex"01" ++
        // message type (1 byte)
        hex"01" ++
        // fromGroup (4 bytes)
        hex"00000000" ++
        // toGroup (4 bytes)
        hex"00000001" ++
        // blockHash (32 bytes)
        hex"bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5" ++
        // bool type (1 byte) indicating if the block submission succeeded
        hex"01"
    }
  }

  "Job" should "use empty transaction list for efficiency" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = emptyBlock(blockFlow, chainIndex)
    val parentHash = block.blockDeps.parentHash(chainIndex)
    val blockFlowTemplate = BlockFlowTemplate(
      chainIndex,
      block.blockDeps.deps,
      block.header.depStateHash,
      block.target,
      block.timestamp,
      block.transactions,
      blockFlow.getHeightUnsafe(parentHash) + 1
    )
    Job.fromWithoutTxs(blockFlowTemplate).txsBlob is serialize(AVector.empty[Transaction])
  }
}
