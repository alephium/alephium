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
import org.alephium.serde.{_deserialize => _decode, serialize => encode, _}
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
      case x: ChainState      => (ChainState, ChainState.serialize(x))
      case x: HeadersByHeightsRequest =>
        (HeadersByHeightsRequest, HeadersByHeightsRequest.serialize(x))
      case x: HeadersByHeightsResponse =>
        (HeadersByHeightsResponse, HeadersByHeightsResponse.serialize(x))
      case x: BlocksAndUnclesByHeightsRequest =>
        (BlocksAndUnclesByHeightsRequest, BlocksAndUnclesByHeightsRequest.serialize(x))
      case x: BlocksAndUnclesByHeightsResponse =>
        (BlocksAndUnclesByHeightsResponse, BlocksAndUnclesByHeightsResponse.serialize(x))
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
        case Hello                            => Hello._deserialize(rest)
        case Ping                             => Ping._deserialize(rest)
        case Pong                             => Pong._deserialize(rest)
        case BlocksRequest                    => BlocksRequest._deserialize(rest)
        case BlocksResponse                   => BlocksResponse._deserialize(rest)
        case HeadersRequest                   => HeadersRequest._deserialize(rest)
        case HeadersResponse                  => HeadersResponse._deserialize(rest)
        case InvRequest                       => InvRequest._deserialize(rest)
        case InvResponse                      => InvResponse._deserialize(rest)
        case NewBlock                         => NewBlock._deserialize(rest)
        case NewHeader                        => NewHeader._deserialize(rest)
        case NewInv                           => NewInv._deserialize(rest)
        case NewBlockHash                     => NewBlockHash._deserialize(rest)
        case NewTxHashes                      => NewTxHashes._deserialize(rest)
        case TxsRequest                       => TxsRequest._deserialize(rest)
        case TxsResponse                      => TxsResponse._deserialize(rest)
        case ChainState                       => ChainState._deserialize(rest)
        case HeadersByHeightsRequest          => HeadersByHeightsRequest._deserialize(rest)
        case HeadersByHeightsResponse         => HeadersByHeightsResponse._deserialize(rest)
        case BlocksAndUnclesByHeightsRequest  => BlocksAndUnclesByHeightsRequest._deserialize(rest)
        case BlocksAndUnclesByHeightsResponse => BlocksAndUnclesByHeightsResponse._deserialize(rest)
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
        TxsResponse,
        ChainState,
        HeadersByHeightsRequest,
        HeadersByHeightsResponse,
        BlocksAndUnclesByHeightsRequest,
        BlocksAndUnclesByHeightsResponse
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

  def unsafe(
      brokerInfo: InterBrokerInfo,
      privateKey: PrivateKey,
      p2pVersion: P2PVersion
  ): T = {
    val signature = SignatureSchema.sign(brokerInfo.hash.bytes, privateKey)
    unsafe(ReleaseVersion.clientId(p2pVersion), TimeStamp.now(), brokerInfo, signature)
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

final case class BlocksResponse(id: RequestId, blocks: Either[AVector[Block], AVector[ByteString]])
    extends Payload.Solicited {
  override def measure(): Unit = BlocksResponse.payloadLabeled.inc()
}

object BlocksResponse extends Payload.Serding[BlocksResponse] with Payload.Code {
  implicit val blockSerde: Serde[Block] = Block.serde
  implicit val serde: Serde[BlocksResponse] = new Serde[BlocksResponse] {
    def _deserialize(input: ByteString): SerdeResult[Staging[BlocksResponse]] = {
      for {
        idResult     <- _decode[RequestId](input)
        blocksResult <- _decode[AVector[Block]](idResult.rest)
      } yield Staging(BlocksResponse(idResult.value, Left(blocksResult.value)), blocksResult.rest)
    }

    def serialize(input: BlocksResponse): ByteString = {
      encode(input.id) ++ (input.blocks match {
        case Left(blocks) => encode(blocks)
        case Right(bytes) =>
          bytes.fold(CompactInteger.Signed.encode(bytes.length))(_ ++ _)
      })
    }
  }

  def fromBlocks(id: RequestId, blocks: AVector[Block]): BlocksResponse =
    BlocksResponse(id, Left(blocks))
  def fromBlockBytes(id: RequestId, blockBytes: AVector[ByteString]): BlocksResponse =
    BlocksResponse(id, Right(blockBytes))
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

final case class NewBlock(block: Either[Block, ByteString]) extends Payload.UnSolicited {
  override def measure(): Unit = NewBlock.payloadLabeled.inc()
}

object NewBlock extends Payload.Serding[NewBlock] with Payload.Code {
  implicit val blockSerde: Serde[Block] = Block.serde
  implicit val serde: Serde[NewBlock] = new Serde[NewBlock] {
    override def _deserialize(input: ByteString) = {
      blockSerde._deserialize(input).map { staging =>
        Staging(NewBlock(Left(staging.value)), staging.rest)
      }
    }

    override def serialize(input: NewBlock) = {
      input.block match {
        case Left(block)       => blockSerde.serialize(block)
        case Right(blockBytes) => blockBytes
      }
    }
  }

  def apply(block: Block): NewBlock           = NewBlock(Left(block))
  def apply(blockBytes: ByteString): NewBlock = NewBlock(Right(blockBytes))
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

trait IndexedPayload[T] {
  def data: AVector[(ChainIndex, T)]
}

sealed trait IndexedSerding[T, P <: IndexedPayload[T] with Payload]
    extends Payload.ValidatedSerding[P] {
  protected def baseSerde: Serde[T]
  implicit protected lazy val dataSerde: Serde[(ChainIndex, T)] = {
    implicit val indexSerde: Serde[ChainIndex] =
      Serde.forProduct2[Int, Int, ChainIndex](
        (from, to) => ChainIndex(new GroupIndex(from), new GroupIndex(to)),
        chainIndex => (chainIndex.from.value, chainIndex.to.value)
      )
    Serde.tuple2[ChainIndex, T](indexSerde, baseSerde)
  }

  def name: String

  def checkDataPerChain(values: T): Boolean

  override def validate(input: P)(implicit config: GroupConfig): Either[String, Unit] = {
    if (input.data.forall(p => IndexedSerding.check(p._1) && checkDataPerChain(p._2))) {
      Right(())
    } else {
      Left(s"Invalid ChainIndex or data in $name payload")
    }
  }
}

object IndexedSerding {
  @inline private def check(
      chainIndex: ChainIndex
  )(implicit config: GroupConfig): Boolean = {
    ChainIndex.validate(chainIndex.from.value, chainIndex.to.value)
  }
}

final case class NewTxHashes(hashes: AVector[(ChainIndex, AVector[TransactionId])])
    extends Payload.UnSolicited
    with IndexedPayload[AVector[TransactionId]] {
  def data: AVector[(ChainIndex, AVector[TransactionId])] = hashes
  override def measure(): Unit                            = NewTxHashes.payloadLabeled.inc()
}

object NewTxHashes extends IndexedSerding[AVector[TransactionId], NewTxHashes] with Payload.Code {
  def name: String = codeName

  def checkDataPerChain(values: AVector[TransactionId]): Boolean = true

  val baseSerde: Serde[AVector[TransactionId]] = avectorSerde[TransactionId]
  implicit val serde: Serde[NewTxHashes]       = Serde.forProduct1(NewTxHashes.apply, t => t.hashes)
}

final case class TxsRequest(id: RequestId, hashes: AVector[(ChainIndex, AVector[TransactionId])])
    extends Payload.Solicited
    with IndexedPayload[AVector[TransactionId]] {
  def data: AVector[(ChainIndex, AVector[TransactionId])] = hashes
  override def measure(): Unit                            = TxsRequest.payloadLabeled.inc()
}

object TxsRequest extends IndexedSerding[AVector[TransactionId], TxsRequest] with Payload.Code {
  def name: String = codeName

  def checkDataPerChain(values: AVector[TransactionId]): Boolean = true

  val baseSerde: Serde[AVector[TransactionId]] = avectorSerde[TransactionId]
  implicit val serde: Serde[TxsRequest]        = Serde.forProduct2(apply, p => (p.id, p.hashes))

  def apply(hashes: AVector[(ChainIndex, AVector[TransactionId])]): TxsRequest =
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

final case class ChainState(tips: AVector[ChainTip]) extends Payload.UnSolicited {
  def measure(): Unit = ChainState.payloadLabeled.inc()
}

object ChainState extends Payload.ValidatedSerding[ChainState] with Payload.Code {
  implicit val serde: Serde[ChainState] = Serde.forProduct1(ChainState.apply, c => c.tips)

  override def validate(t: ChainState)(implicit config: GroupConfig): Either[String, Unit] = {
    if (t.tips.forall(_.height >= 0)) {
      Right(())
    } else {
      Left("Invalid height in ChainState payload")
    }
  }
}

final case class HeadersByHeightsRequest(
    id: RequestId,
    data: AVector[(ChainIndex, BlockHeightRange)]
) extends Payload.Solicited
    with IndexedPayload[BlockHeightRange] {
  def measure(): Unit = HeadersByHeightsRequest.payloadLabeled.inc()
}

object HeadersByHeightsRequest
    extends IndexedSerding[BlockHeightRange, HeadersByHeightsRequest]
    with Payload.Code {
  def name: String = codeName

  val baseSerde: Serde[BlockHeightRange] = BlockHeightRange.serde
  implicit val serde: Serde[HeadersByHeightsRequest] =
    Serde.forProduct2(apply, v => (v.id, v.data))

  def checkDataPerChain(range: BlockHeightRange): Boolean = range.isValid()

  def apply(data: AVector[(ChainIndex, BlockHeightRange)]): HeadersByHeightsRequest =
    HeadersByHeightsRequest(RequestId.random(), data)
}

final case class HeadersByHeightsResponse(id: RequestId, headers: AVector[AVector[BlockHeader]])
    extends Payload.Solicited {
  def measure(): Unit = HeadersByHeightsResponse.payloadLabeled.inc()
}

object HeadersByHeightsResponse
    extends Payload.Serding[HeadersByHeightsResponse]
    with Payload.Code {
  implicit val serde: Serde[HeadersByHeightsResponse] =
    Serde.forProduct2(apply, v => (v.id, v.headers))
}

final case class BlocksAndUnclesByHeightsRequest(
    id: RequestId,
    data: AVector[(ChainIndex, BlockHeightRange)]
) extends Payload.Solicited
    with IndexedPayload[BlockHeightRange] {
  def measure(): Unit = BlocksAndUnclesByHeightsRequest.payloadLabeled.inc()
}

object BlocksAndUnclesByHeightsRequest
    extends IndexedSerding[BlockHeightRange, BlocksAndUnclesByHeightsRequest]
    with Payload.Code {
  def name: String = codeName

  val baseSerde: Serde[BlockHeightRange] = BlockHeightRange.serde
  implicit val serde: Serde[BlocksAndUnclesByHeightsRequest] =
    Serde.forProduct2(apply, v => (v.id, v.data))

  def checkDataPerChain(range: BlockHeightRange): Boolean = range.isValid()

  def apply(data: AVector[(ChainIndex, BlockHeightRange)]): BlocksAndUnclesByHeightsRequest =
    BlocksAndUnclesByHeightsRequest(RequestId.random(), data)
}

final case class BlocksAndUnclesByHeightsResponse(id: RequestId, blocks: AVector[AVector[Block]])
    extends Payload.Solicited {
  def measure(): Unit = BlocksAndUnclesByHeightsResponse.payloadLabeled.inc()
}

object BlocksAndUnclesByHeightsResponse
    extends Payload.Serding[BlocksAndUnclesByHeightsResponse]
    with Payload.Code {
  implicit val serde: Serde[BlocksAndUnclesByHeightsResponse] =
    Serde.forProduct2(apply, v => (v.id, v.blocks))
}
