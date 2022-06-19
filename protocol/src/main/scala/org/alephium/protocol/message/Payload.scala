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

import akka.util.ByteString
import io.prometheus.client.Counter

import org.alephium.protocol._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp}

//scalastyle:off number.of.types

sealed trait Payload extends Product with Serializable {
  val name = productPrefix

  def measure(): Unit
}

object Payload {

  // scalastyle:off cyclomatic.complexity
  def serialize(payload: Payload): ByteString = {
    val (code, data: ByteString) = payload match {
      case x: Hello           => (Hello, Hello.serialize(x))
      case x: Ping            => (Ping, Ping.serialize(x))
      case x: Pong            => (Pong, Pong.serialize(x))
      case x: BlocksRequest   => (BlocksRequest, BlocksRequest.serialize(x))
      case x: BlocksResponse  => (BlocksResponse, BlocksResponse.serialize(x))
      case x: HeadersRequest  => (HeadersRequest, HeadersRequest.serialize(x))
      case x: HeadersResponse => (HeadersResponse, HeadersResponse.serialize(x))
      case x: InvRequest      => (InvRequest, InvRequest.serialize(x))
      case x: InvResponse     => (InvResponse, InvResponse.serialize(x))
      case x: NewBlock        => (NewBlock, NewBlock.serialize(x))
      case x: NewHeader       => (NewHeader, NewHeader.serialize(x))
      case x: NewInv          => (NewInv, NewInv.serialize(x))
      case x: NewBlockHash    => (NewBlockHash, NewBlockHash.serialize(x))
      case x: NewTxHashes     => (NewTxHashes, NewTxHashes.serialize(x))
      case x: TxsRequest      => (TxsRequest, TxsRequest.serialize(x))
      case x: TxsResponse     => (TxsResponse, TxsResponse.serialize(x))
    }
    intSerde.serialize(Code.toInt(code)) ++ data
  }
  // scalastyle:on cyclomatic.complexity

  val deserializerCode: Deserializer[Code] =
    intSerde.validateGet(Code.fromInt, c => s"Invalid code $c")

  // scalastyle:off cyclomatic.complexity
  def _deserialize(
      input: ByteString
  )(implicit config: GroupConfig): SerdeResult[Staging[Payload]] = {
    deserializerCode._deserialize(input).flatMap { case Staging(code, rest) =>
      code match {
        case Hello           => Hello._deserialize(rest)
        case Ping            => Ping._deserialize(rest)
        case Pong            => Pong._deserialize(rest)
        case BlocksRequest   => BlocksRequest._deserialize(rest)
        case BlocksResponse  => BlocksResponse._deserialize(rest)
        case HeadersRequest  => HeadersRequest._deserialize(rest)
        case HeadersResponse => HeadersResponse._deserialize(rest)
        case InvRequest      => InvRequest._deserialize(rest)
        case InvResponse     => InvResponse._deserialize(rest)
        case NewBlock        => NewBlock._deserialize(rest)
        case NewHeader       => NewHeader._deserialize(rest)
        case NewInv          => NewInv._deserialize(rest)
        case NewBlockHash    => NewBlockHash._deserialize(rest)
        case NewTxHashes     => NewTxHashes._deserialize(rest)
        case TxsRequest      => TxsRequest._deserialize(rest)
        case TxsResponse     => TxsResponse._deserialize(rest)
      }
    }
  }
  // scalastyle:on cyclomatic.complexity

  def deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[Payload] =
    _deserialize(input).flatMap { case Staging(output, rest) =>
      if (rest.isEmpty) {
        Right(output)
      } else {
        Left(SerdeError.redundant(input.size - rest.size, input.size))
      }
    }

  sealed trait Code {
    def codeName: String                   = this.getClass.getSimpleName.dropRight(1)
    lazy val payloadLabeled: Counter.Child = Payload.payloadTotal.labels(codeName)
  }

  trait FixUnused[T <: Payload] {
    def _deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[Staging[T]]
  }

  sealed trait Serding[T <: Payload] extends FixUnused[T] {
    protected def serde: Serde[T]

    def serialize(t: T): ByteString = serde.serialize(t)

    def _deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[Staging[T]] =
      serde._deserialize(input)
  }

  sealed trait ValidatedSerding[T <: Payload] extends Serding[T] {
    override def _deserialize(
        input: ByteString
    )(implicit config: GroupConfig): SerdeResult[Staging[T]] = {
      serde._deserialize(input).flatMap { case Staging(message, rest) =>
        validate(message) match {
          case Right(_)    => Right(Staging(message, rest))
          case Left(error) => Left(SerdeError.validation(error))
        }
      }
    }

    def validate(t: T)(implicit config: GroupConfig): Either[String, Unit]
  }

  object Code {
    private[message] val values: AVector[Code] =
      AVector(
        Hello,
        Ping,
        Pong,
        BlocksRequest,
        BlocksResponse,
        HeadersRequest,
        HeadersResponse,
        InvRequest,
        InvResponse,
        NewBlock,
        NewHeader,
        NewInv,
        NewBlockHash,
        NewTxHashes,
        TxsRequest,
        TxsResponse
      )

    val toInt: Map[Code, Int] = values.toIterable.zipWithIndex.toMap
    def fromInt(code: Int): Option[Code] =
      if (code >= 0 && code < values.length) Some(values(code)) else None
  }

  val payloadTotal: Counter = Counter
    .build(
      "alephium_payload_total",
      "Total number of payloads"
    )
    .labelNames("payload_type")
    .register()

  sealed trait Solicited extends Payload {
    val id: RequestId
  }

  sealed trait UnSolicited extends Payload
}

sealed trait HandShake extends Payload.UnSolicited {
  def clientId: String
  def timestamp: TimeStamp
  def brokerInfo: InterBrokerInfo
  def signature: Signature
}

sealed trait HandShakeSerding[T <: HandShake] extends Payload.ValidatedSerding[T] {
  def unsafe(
      clientId: String,
      timestamp: TimeStamp,
      brokerInfo: InterBrokerInfo,
      signature: Signature
  ): T

  def unsafe(brokerInfo: InterBrokerInfo, privateKey: PrivateKey): T = {
    val signature = SignatureSchema.sign(brokerInfo.hash.bytes, privateKey)
    unsafe(ReleaseVersion.clientId, TimeStamp.now(), brokerInfo, signature)
  }

  implicit private val stringSerde: Serde[String] = new Serde[String] {
    override def _deserialize(input: ByteString): SerdeResult[Staging[String]] = {
      intSerde._deserialize(input).flatMap { case Staging(size, rest) =>
        if (size < 0 || size > 256) {
          Left(SerdeError.validation(s"Invalid client info length: $size"))
        } else if (rest.size >= size) {
          Right(rest.splitAt(size.toInt) match {
            case (value, rest) => Staging(value.utf8String, rest)
          })
        } else {
          Left(SerdeError.incompleteData(size.toInt, rest.size))
        }
      }
    }

    override def serialize(input: String): ByteString =
      intSerde.serialize(input.length) ++ ByteString.fromString(input)
  }
  implicit private val brokerSerde: Serde[InterBrokerInfo] = InterBrokerInfo.unsafeSerde
  val serde: Serde[T] =
    Serde.forProduct4(unsafe, t => (t.clientId, t.timestamp, t.brokerInfo, t.signature))

  def validate(message: T)(implicit config: GroupConfig): Either[String, Unit] = {
    val validSignature = SignatureSchema.verify(
      message.brokerInfo.hash.bytes,
      message.signature,
      message.brokerInfo.cliqueId.publicKey
    )

    InterBrokerInfo.validate(message.brokerInfo).flatMap { _ =>
      if (
        validSignature &&
        message.timestamp > TimeStamp.zero
      ) {
        Right(())
      } else {
        Left(s"Invalid HandShake: $message")
      }
    }
  }
}

final case class Hello private (
    clientId: String,
    timestamp: TimeStamp,
    brokerInfo: InterBrokerInfo,
    signature: Signature
) extends HandShake {
  override def measure(): Unit = Hello.payloadLabeled.inc()
}

object Hello extends HandShakeSerding[Hello] with Payload.Code {
  def unsafe(
      clientId: String,
      timestamp: TimeStamp,
      brokerInfo: InterBrokerInfo,
      signature: Signature
  ): Hello = {
    new Hello(clientId, timestamp, brokerInfo, signature)
  }
}

final case class Ping(id: RequestId, timestamp: TimeStamp) extends Payload.Solicited {
  override def measure(): Unit = Ping.payloadLabeled.inc()
}

object Ping extends Payload.Serding[Ping] with Payload.Code {
  val serde: Serde[Ping] = Serde.forProduct2(apply, p => (p.id, p.timestamp))
}

final case class Pong(id: RequestId) extends Payload.Solicited {
  override def measure(): Unit = Pong.payloadLabeled.inc()
}

object Pong extends Payload.Serding[Pong] with Payload.Code {
  val serde: Serde[Pong] = Serde.forProduct1(apply, p => p.id)
}

final case class BlocksResponse(id: RequestId, blocks: AVector[Block]) extends Payload.Solicited {
  override def measure(): Unit = BlocksResponse.payloadLabeled.inc()
}

object BlocksResponse extends Payload.Serding[BlocksResponse] with Payload.Code {
  implicit val serde: Serde[BlocksResponse] = Serde.forProduct2(apply, p => (p.id, p.blocks))
}

final case class BlocksRequest(id: RequestId, locators: AVector[BlockHash])
    extends Payload.Solicited {
  override def measure(): Unit = BlocksRequest.payloadLabeled.inc()
}

object BlocksRequest extends Payload.Serding[BlocksRequest] with Payload.Code {
  implicit val serde: Serde[BlocksRequest] = Serde.forProduct2(apply, p => (p.id, p.locators))

  def apply(locators: AVector[BlockHash]): BlocksRequest = {
    BlocksRequest(RequestId.random(), locators)
  }
}

final case class HeadersResponse(id: RequestId, headers: AVector[BlockHeader])
    extends Payload.Solicited {
  override def measure(): Unit = HeadersResponse.payloadLabeled.inc()
}

object HeadersResponse extends Payload.Serding[HeadersResponse] with Payload.Code {
  implicit val serde: Serde[HeadersResponse] = Serde.forProduct2(apply, p => (p.id, p.headers))
}

final case class HeadersRequest(id: RequestId, locators: AVector[BlockHash])
    extends Payload.Solicited {
  override def measure(): Unit = HeadersRequest.payloadLabeled.inc()
}

object HeadersRequest extends Payload.Serding[HeadersRequest] with Payload.Code {
  implicit val serde: Serde[HeadersRequest] = Serde.forProduct2(apply, p => (p.id, p.locators))

  def apply(locators: AVector[BlockHash]): HeadersRequest = {
    HeadersRequest(RequestId.random(), locators)
  }
}

final case class InvRequest(id: RequestId, locators: AVector[AVector[BlockHash]])
    extends Payload.Solicited {
  override def measure(): Unit = InvRequest.payloadLabeled.inc()
}

object InvRequest extends Payload.Serding[InvRequest] with Payload.Code {
  implicit val serde: Serde[InvRequest] = Serde.forProduct2(apply, p => (p.id, p.locators))

  def apply(locators: AVector[AVector[BlockHash]]): InvRequest = {
    InvRequest(RequestId.random(), locators)
  }
}

final case class InvResponse(id: RequestId, hashes: AVector[AVector[BlockHash]])
    extends Payload.Solicited {
  override def measure(): Unit = InvResponse.payloadLabeled.inc()
}

object InvResponse extends Payload.Serding[InvResponse] with Payload.Code {
  implicit val serde: Serde[InvResponse] = Serde.forProduct2(apply, p => (p.id, p.hashes))
}

final case class NewBlock(block: Block) extends Payload.UnSolicited {
  override def measure(): Unit = NewBlock.payloadLabeled.inc()
}

object NewBlock extends Payload.Serding[NewBlock] with Payload.Code {
  implicit val serde: Serde[NewBlock] = Serde.forProduct1(apply, _.block)
}

final case class NewHeader(header: BlockHeader) extends Payload.UnSolicited {
  override def measure(): Unit = NewHeader.payloadLabeled.inc()
}

object NewHeader extends Payload.Serding[NewHeader] with Payload.Code {
  implicit val serde: Serde[NewHeader] = Serde.forProduct1(apply, _.header)
}

final case class NewInv(hashes: AVector[AVector[BlockHash]]) extends Payload.UnSolicited {
  override def measure(): Unit = NewInv.payloadLabeled.inc()
}

object NewInv extends Payload.Serding[NewInv] with Payload.Code {
  implicit val serde: Serde[NewInv] = Serde.forProduct1(apply, _.hashes)
}

final case class NewBlockHash(hash: BlockHash) extends Payload.UnSolicited {
  override def measure(): Unit = NewBlockHash.payloadLabeled.inc()
}

object NewBlockHash extends Payload.Serding[NewBlockHash] with Payload.Code {
  implicit val serde: Serde[NewBlockHash] = Serde.forProduct1(apply, _.hash)
}

trait IndexedHashes {
  def hashes: AVector[(ChainIndex, AVector[Hash])]
}

sealed trait IndexedSerding[T <: IndexedHashes with Payload] extends Payload.ValidatedSerding[T] {
  override def validate(input: T)(implicit config: GroupConfig): Either[String, Unit] = {
    if (input.hashes.forall(p => IndexedSerding.check(p._1))) {
      Right(())
    } else {
      Left("Invalid ChainIndex in Tx payload")
    }
  }
}

object IndexedSerding {
  implicit private[message] val txSerde: Serde[(ChainIndex, AVector[Hash])] = {
    implicit val indexSerde: Serde[ChainIndex] =
      Serde.forProduct2[Int, Int, ChainIndex](
        (from, to) => ChainIndex(new GroupIndex(from), new GroupIndex(to)),
        chainIndex => (chainIndex.from.value, chainIndex.to.value)
      )
    Serde.tuple2[ChainIndex, AVector[Hash]]
  }

  @inline private def check(
      chainIndex: ChainIndex
  )(implicit config: GroupConfig): Boolean = {
    ChainIndex.validate(chainIndex.from.value, chainIndex.to.value)
  }
}

final case class NewTxHashes(hashes: AVector[(ChainIndex, AVector[Hash])])
    extends Payload.UnSolicited
    with IndexedHashes {
  override def measure(): Unit = NewTxHashes.payloadLabeled.inc()
}

object NewTxHashes extends IndexedSerding[NewTxHashes] with Payload.Code {
  import IndexedSerding.txSerde
  implicit val serde: Serde[NewTxHashes] = Serde.forProduct1(NewTxHashes.apply, t => t.hashes)
}

final case class TxsRequest(id: RequestId, hashes: AVector[(ChainIndex, AVector[Hash])])
    extends Payload.Solicited
    with IndexedHashes {
  override def measure(): Unit = TxsRequest.payloadLabeled.inc()
}

object TxsRequest extends IndexedSerding[TxsRequest] with Payload.Code {
  import IndexedSerding.txSerde
  implicit val serde: Serde[TxsRequest] = Serde.forProduct2(apply, p => (p.id, p.hashes))

  def apply(hashes: AVector[(ChainIndex, AVector[Hash])]): TxsRequest =
    TxsRequest(RequestId.random(), hashes)
}

final case class TxsResponse(id: RequestId, txs: AVector[TransactionTemplate])
    extends Payload.Solicited {
  override def measure(): Unit = TxsResponse.payloadLabeled.inc()
}

object TxsResponse extends Payload.Serding[TxsResponse] with Payload.Code {
  implicit val serde: Serde[TxsResponse] =
    Serde.forProduct2(apply, p => (p.id, p.txs))
}
