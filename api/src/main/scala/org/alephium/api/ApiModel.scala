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

import akka.util.ByteString
import io.circe._
import io.circe.generic.semiauto._

import org.alephium.api.CirceUtils._
import org.alephium.api.model._
import org.alephium.protocol.{Hash, PublicKey, Signature}
import org.alephium.protocol.model._
import org.alephium.serde.RandomBytes
import org.alephium.util._

// scalastyle:off number.of.methods
// scalastyle:off number.of.types
object ApiModel {

  final case class Error(code: Int, message: String, data: Option[String])
  object Error {
    def apply(code: Int, message: String): Error = {
      Error(code, message, None)
    }

    implicit val codec: Codec[Error] = deriveCodec[Error]

    // scalastyle:off magic.number
    val ParseError: Error        = Error(-32700, "Parse error")
    val InvalidRequest: Error    = Error(-32600, "Invalid Request")
    val MethodNotFound: Error    = Error(-32601, "Method not found")
    val InvalidParams: Error     = Error(-32602, "Invalid params")
    val InternalError: Error     = Error(-32603, "Internal error")
    val UnauthorizedError: Error = Error(-32604, "Unauthorized")

    def server(error: String): Error = Error(-32000, "Server error", Some(error))
    // scalastyle:on
  }
  trait PerChain {
    val fromGroup: Int
    val toGroup: Int
  }
}

trait ApiModelCodec {

  def blockflowFetchMaxAge: Duration
  implicit def networkType: NetworkType

  implicit val u256Encoder: Encoder[U256] = Encoder.encodeJavaBigInteger.contramap[U256](_.toBigInt)
  implicit val u256Decoder: Decoder[U256] = Decoder.decodeJavaBigInteger.emap { u256 =>
    U256.from(u256).toRight(s"Invalid U256: $u256")
  }
  implicit val u256Codec: Codec[U256] = Codec.from(u256Decoder, u256Encoder)

  implicit val publicKeyEncoder: Encoder[PublicKey] = bytesEncoder
  implicit val publicKeyDecoder: Decoder[PublicKey] = bytesDecoder(PublicKey.from)
  implicit val publicKeyCodec: Codec[PublicKey] =
    Codec.from(publicKeyDecoder, publicKeyEncoder)

  implicit val signatureEncoder: Encoder[Signature] = bytesEncoder
  implicit val signatureDecoder: Decoder[Signature] = bytesDecoder(Signature.from)

  implicit val hashEncoder: Encoder[Hash] = hash => Json.fromString(hash.toHexString)
  implicit val hashDecoder: Decoder[Hash] =
    byteStringDecoder.emap(Hash.from(_).toRight("cannot decode hash"))
  implicit val hashCodec: Codec[Hash] = Codec.from(hashDecoder, hashEncoder)

  lazy val addressEncoder: Encoder[Address] =
    Encoder.encodeString.contramap[Address](_.toBase58)
  lazy val addressDecoder: Decoder[Address] =
    Decoder.decodeString.emap { input =>
      Address
        .fromBase58(input, networkType)
        .toRight(s"Unable to decode address from $input")
    }
  implicit lazy val addressCodec: Codec[Address] = Codec.from(addressDecoder, addressEncoder)

  implicit val cliqueIdEncoder: Encoder[CliqueId] = Encoder.encodeString.contramap(_.toHexString)
  implicit val cliqueIdDecoder: Decoder[CliqueId] = Decoder.decodeString.emap(createCliqueId)
  implicit val cliqueIdCodec: Codec[CliqueId]     = Codec.from(cliqueIdDecoder, cliqueIdEncoder)

  implicit val fetchResponseCodec: Codec[FetchResponse] = deriveCodec[FetchResponse]

  implicit val outputRefCodec: Codec[OutputRef] = deriveCodec[OutputRef]

  implicit val inputCodec: Codec[Input] = deriveCodec[Input]

  implicit val outputCodec: Codec[Output] = deriveCodec[Output]

  implicit val txCodec: Codec[Tx] = deriveCodec[Tx]

  implicit val blockEntryCodec: Codec[BlockEntry] = deriveCodec[BlockEntry]

  implicit val peerAddressCodec: Codec[PeerAddress] = deriveCodec[PeerAddress]

  implicit val selfCliqueCodec: Codec[SelfClique] = deriveCodec[SelfClique]

  implicit val neighborCliquesCodec: Codec[NeighborCliques] = deriveCodec[NeighborCliques]

  implicit val getBalanceCodec: Codec[GetBalance] = deriveCodec[GetBalance]

  implicit val getGroupCodec: Codec[GetGroup] = deriveCodec[GetGroup]

  implicit val balanceCodec: Codec[Balance] = deriveCodec[Balance]

  implicit val createTransactionCodec: Codec[CreateTransaction] = deriveCodec[CreateTransaction]

  implicit val groupCodec: Codec[Group] = deriveCodec[Group]

  implicit val createTransactionResultCodec: Codec[CreateTransactionResult] =
    deriveCodec[CreateTransactionResult]

  implicit val sendTransactionCodec: Codec[SendTransaction] = deriveCodec[SendTransaction]

  implicit val createContractCodec: Codec[CreateContract] = deriveCodec[CreateContract]

  implicit val createContractResultCodec: Codec[CreateContractResult] =
    deriveCodec[CreateContractResult]

  implicit val sendContractCodec: Codec[SendContract] = deriveCodec[SendContract]

  implicit val compileResult: Codec[Compile] = deriveCodec[Compile]

  implicit val compileResultCodec: Codec[CompileResult] = deriveCodec[CompileResult]

  implicit val txResultCodec: Codec[TxResult] = deriveCodec[TxResult]

  implicit val getHashesAtHeightCodec: Codec[GetHashesAtHeight] = deriveCodec[GetHashesAtHeight]

  implicit val hashesAtHeightCodec: Codec[HashesAtHeight] = deriveCodec[HashesAtHeight]

  implicit val getChainInfoCodec: Codec[GetChainInfo] = deriveCodec[GetChainInfo]

  implicit val chainInfoCodec: Codec[ChainInfo] = deriveCodec[ChainInfo]

  implicit val getBlockCodec: Codec[GetBlock] = deriveCodec[GetBlock]

  implicit val minerActionDecoder: Decoder[MinerAction] = Decoder[String].emap {
    case "start-mining" => Right(MinerAction.StartMining)
    case "stop-mining"  => Right(MinerAction.StopMining)
    case other          => Left(s"Invalid miner action: $other")
  }
  implicit val minerActionEncoder: Encoder[MinerAction] = Encoder[String].contramap {
    case MinerAction.StartMining => "start-mining"
    case MinerAction.StopMining  => "stop-mining"
  }
  implicit val minerActionCodec: Codec[MinerAction] =
    Codec.from(minerActionDecoder, minerActionEncoder)

  implicit val cliqueEncoder: Encoder[InterCliqueInfo] =
    Encoder.forProduct3("id", "externalAddresses", "groupNumPerBroker")(info =>
      (info.id, info.externalAddresses, info.groupNumPerBroker))
  implicit val cliqueDecoder: Decoder[InterCliqueInfo] =
    Decoder.forProduct3("id", "externalAddresses", "groupNumPerBroker")(InterCliqueInfo.unsafe)

  implicit val interCliqueSyncedStatusCodec: Codec[InterCliquePeerInfo] =
    deriveCodec[InterCliquePeerInfo]

  lazy val fetchRequestDecoder: Decoder[FetchRequest] =
    deriveDecoder[FetchRequest]
      .ensure(
        fetchRequest => fetchRequest.fromTs <= fetchRequest.toTs,
        "`toTs` cannot be before `fromTs`"
      )
      .ensure(
        fetchRequest =>
          (fetchRequest.toTs -- fetchRequest.fromTs)
            .exists(_ <= blockflowFetchMaxAge),
        s"interval cannot be greater than ${blockflowFetchMaxAge}"
      )
  val fetchRequestEncoder: Encoder[FetchRequest] = deriveEncoder[FetchRequest]
  implicit lazy val fetchRequestCodec: Codec[FetchRequest] =
    Codec.from(fetchRequestDecoder, fetchRequestEncoder)

  private def bytesEncoder[T <: RandomBytes]: Encoder[T] =
    Encoder.encodeString.contramap[T](_.toHexString)
  private def bytesDecoder[T](from: ByteString => Option[T]): Decoder[T] =
    Decoder.decodeString.emap { input =>
      val keyOpt = for {
        bs  <- Hex.from(input)
        key <- from(bs)
      } yield key
      keyOpt.toRight(s"Unable to decode key from $input")
    }

  private def createCliqueId(s: String): Either[String, CliqueId] = {
    Hex.from(s).flatMap(CliqueId.from) match {
      case Some(id) => Right(id)
      case None     => Left("invalid clique id")
    }
  }
}
