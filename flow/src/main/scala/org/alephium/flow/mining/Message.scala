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

import org.alephium.flow.model.{BlockFlowTemplate, MiningBlob}
import org.alephium.flow.network.bootstrap.SimpleSerde
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._
import org.alephium.util.AVector

sealed trait Message

sealed trait ClientMessage
final case class SubmitBlock(blockBlob: ByteString) extends ClientMessage
object SubmitBlock {
  val serde: Serde[SubmitBlock] = Serde.forProduct1(SubmitBlock(_), t => t.blockBlob)
}

object ClientMessage extends SimpleSerde[ClientMessage] {
  override def serializeBody(message: ClientMessage): ByteString = {
    message match {
      case m: SubmitBlock => SubmitBlock.serde.serialize(m)
    }
  }

  override def deserializeBody(input: ByteString)(implicit
      groupConfig: GroupConfig
  ): SerdeResult[ClientMessage] = {
    SubmitBlock.serde.deserialize(input)
  }
}

sealed trait ServerMessage
final case class Job(
    fromGroup: Int,
    toGroup: Int,
    headerBlob: ByteString,
    txsBlob: ByteString,
    target: BigInteger
) {
  def toMiningBlob: MiningBlob = MiningBlob(headerBlob, target, txsBlob)
}
object Job {
  implicit val serde: Serde[Job] =
    Serde.forProduct5(
      Job.apply,
      t => (t.fromGroup, t.toGroup, t.headerBlob, t.txsBlob, t.target)
    )

  def from(template: BlockFlowTemplate): Job = {
    val blobs = MiningBlob.from(template)
    Job(
      template.index.from.value,
      template.index.to.value,
      blobs.headerBlob,
      blobs.txsBlob,
      blobs.target
    )
  }
}

final case class Jobs(jobs: AVector[Job]) extends ServerMessage
object Jobs {
  val serde: Serde[Jobs] = Serde.forProduct1(Jobs.apply, t => t.jobs)
}
final case class SubmitResult(fromGroup: Int, toGroup: Int, status: Boolean) extends ServerMessage
object SubmitResult {
  val serde: Serde[SubmitResult] =
    Serde.forProduct3(SubmitResult.apply, t => (t.fromGroup, t.toGroup, t.status))
}

object ServerMessage extends SimpleSerde[ServerMessage] {
  def serializeBody(message: ServerMessage): ByteString = {
    message match {
      case job: Jobs =>
        ByteString(0) ++ Jobs.serde.serialize(job)
      case result: SubmitResult =>
        ByteString(1) ++ SubmitResult.serde.serialize(result)
    }
  }

  def deserializeBody(input: ByteString)(implicit
      groupConfig: GroupConfig
  ): SerdeResult[ServerMessage] = {
    byteSerde._deserialize(input).flatMap { case Staging(byte, rest) =>
      if (byte == 0) {
        Jobs.serde.deserialize(rest)
      } else if (byte == 1) {
        SubmitResult.serde.deserialize(rest)
      } else {
        Left(SerdeError.wrongFormat(s"Invalid server message code: $byte"))
      }
    }
  }
}
