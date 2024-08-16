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

import scala.reflect.ClassTag

import akka.util.ByteString

import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.network.bootstrap.SimpleSerde
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHash, Nonce}
import org.alephium.serde.{intSerde => _, _}
import org.alephium.util.{AVector, Bytes}

sealed trait Message
object Message {
  implicit val simpleIntSerde: Serde[Int] =
    Serde.bytesSerde(4).xmap(Bytes.toIntUnsafe, Bytes.from)
  implicit val bytestringSerde: Serde[ByteString] = new Serde[ByteString] {
    def serialize(input: ByteString): ByteString =
      simpleIntSerde.serialize(input.length) ++ input

    def _deserialize(input: ByteString): SerdeResult[Staging[ByteString]] = {
      simpleIntSerde._deserialize(input).flatMap { case Staging(length, rest) =>
        Serde.bytesSerde(length)._deserialize(rest)
      }
    }
  }
  implicit def avectorSerde[T: ClassTag](implicit serde: Serde[T]): Serde[AVector[T]] =
    new Serde[AVector[T]] {
      def serialize(input: AVector[T]): ByteString = {
        val lengthBytes = simpleIntSerde.serialize(input.length)
        input.map(serde.serialize).fold(lengthBytes)(_ ++ _)
      }

      def _deserialize(input: ByteString): SerdeResult[Staging[AVector[T]]] = {
        simpleIntSerde._deserialize(input).flatMap { case Staging(length, rest) =>
          (new Serde.BatchDeserializer(serde))._deserializeAVector(length, rest)
        }
      }
    }
}

final case class MiningProtocolVersion(value: Byte) extends AnyVal
object MiningProtocolVersion {
  implicit val serde: Serde[MiningProtocolVersion] =
    Serde.forProduct1(MiningProtocolVersion.apply, _.value)

  val Default: MiningProtocolVersion = MiningProtocolVersion(1)

  def deserialize(input: ByteString): SerdeResult[Staging[MiningProtocolVersion]] = {
    for {
      version <- MiningProtocolVersion.serde._deserialize(input)
      _ <-
        if (version.value == MiningProtocolVersion.Default) {
          Right(())
        } else {
          Left(
            SerdeError.Other(
              s"Invalid mining protocol version: got ${version.value.value}, expect ${MiningProtocolVersion.Default.value}"
            )
          )
        }
    } yield version
  }
}

sealed trait ClientMessagePayload
final case class SubmitBlock(blockBlob: ByteString) extends ClientMessagePayload
object SubmitBlock {
  import Message.bytestringSerde
  val serde: Serde[SubmitBlock] = Serde.forProduct1(SubmitBlock(_), t => t.blockBlob)
}

final case class ClientMessage(version: MiningProtocolVersion, payload: ClientMessagePayload)
object ClientMessage extends SimpleSerde[ClientMessage] {
  def from(payload: ClientMessagePayload): ClientMessage = {
    ClientMessage(MiningProtocolVersion.Default, payload)
  }

  override def serializeBody(message: ClientMessage): ByteString = {
    message.payload match {
      case m: SubmitBlock =>
        MiningProtocolVersion.serde.serialize(message.version) ++
          ByteString(0) ++ SubmitBlock.serde.serialize(m)
    }
  }

  override def deserializeBody(input: ByteString)(implicit
      groupConfig: GroupConfig
  ): SerdeResult[ClientMessage] = {
    for {
      version <- MiningProtocolVersion.deserialize(input)
      payload <- byteSerde._deserialize(version.rest).flatMap { case Staging(byte, rest) =>
        if (byte == 0) {
          SubmitBlock.serde.deserialize(rest)
        } else {
          Left(SerdeError.wrongFormat(s"Invalid client message code: $byte"))
        }
      }
    } yield ClientMessage(version.value, payload)
  }
}

sealed trait ServerMessagePayload extends Product with Serializable
final case class Job(
    fromGroup: Int,
    toGroup: Int,
    headerBlob: ByteString,
    txsBlob: ByteString,
    target: BigInteger,
    height: Int
) {
  def toBlockBlob(nonce: Nonce): ByteString = {
    nonce.value ++ headerBlob ++ txsBlob
  }
}
object Job {
  implicit val serde: Serde[Job] = {
    import Message.{bytestringSerde, simpleIntSerde}
    implicit val bigIntegerSerde: Serde[BigInteger] = new Serde[BigInteger] {
      def serialize(input: BigInteger): ByteString = {
        val bytes = input.toByteArray
        simpleIntSerde.serialize(bytes.length) ++ ByteString.fromArrayUnsafe(bytes)
      }
      def _deserialize(input: ByteString): SerdeResult[Staging[BigInteger]] = {
        simpleIntSerde._deserialize(input).flatMap { case Staging(length, rest) =>
          Serde
            .bytesSerde(length)
            ._deserialize(rest)
            .map(_.mapValue(bytes => new BigInteger(bytes.toArray)))
        }
      }
    }
    Serde.forProduct6(
      Job.apply,
      t => (t.fromGroup, t.toGroup, t.headerBlob, t.txsBlob, t.target, t.height)
    )
  }

  private val emptyTxsBlob = ByteString(0)
  def fromWithoutTxs(template: BlockFlowTemplate): Job = {
    from(template, emptyTxsBlob)
  }

  def from(template: BlockFlowTemplate): Job = {
    from(template, serialize(template.transactions))
  }

  private def from(template: BlockFlowTemplate, txsBlob: ByteString): Job = {
    val dummyHeader = template.dummyHeader()
    val headerBlob  = serialize(dummyHeader)
    Job(
      template.index.from.value,
      template.index.to.value,
      headerBlob.drop(Nonce.byteLength),
      txsBlob,
      template.target.value,
      template.height
    )
  }
}

final case class Jobs(jobs: AVector[Job]) extends ServerMessagePayload
object Jobs {
  implicit private val jobsSerde: Serde[AVector[Job]] = Message.avectorSerde
  val serde: Serde[Jobs]                              = Serde.forProduct1(Jobs.apply, t => t.jobs)
}
final case class SubmitResult(
    fromGroup: Int,
    toGroup: Int,
    blockHash: BlockHash,
    status: Boolean
) extends ServerMessagePayload
object SubmitResult {
  import Message.simpleIntSerde
  val serde: Serde[SubmitResult] =
    Serde.forProduct4(
      SubmitResult.apply,
      t => (t.fromGroup, t.toGroup, t.blockHash, t.status)
    )
}

final case class ServerMessage(version: MiningProtocolVersion, payload: ServerMessagePayload)
object ServerMessage extends SimpleSerde[ServerMessage] {
  def from(payload: ServerMessagePayload): ServerMessage = {
    ServerMessage(MiningProtocolVersion.Default, payload)
  }

  def serializeBody(message: ServerMessage): ByteString = {
    val payload = message.payload match {
      case job: Jobs =>
        ByteString(0) ++ Jobs.serde.serialize(job)
      case result: SubmitResult =>
        ByteString(1) ++ SubmitResult.serde.serialize(result)
    }
    MiningProtocolVersion.serde.serialize(message.version) ++ payload
  }

  def deserializeBody(input: ByteString)(implicit
      groupConfig: GroupConfig
  ): SerdeResult[ServerMessage] = {
    for {
      version <- MiningProtocolVersion.deserialize(input)
      payload <- byteSerde._deserialize(version.rest).flatMap { case Staging(byte, rest) =>
        if (byte == 0) {
          Jobs.serde.deserialize(rest)
        } else if (byte == 1) {
          SubmitResult.serde.deserialize(rest)
        } else {
          Left(SerdeError.wrongFormat(s"Invalid server message code: $byte"))
        }
      }
    } yield ServerMessage(version.value, payload)
  }
}
