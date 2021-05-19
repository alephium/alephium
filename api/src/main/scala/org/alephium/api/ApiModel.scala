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

import java.net.InetSocketAddress

import akka.util.ByteString
import upickle.core.Abort

import org.alephium.api.UtilJson._
import org.alephium.api.model._
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.json.Json._
import org.alephium.json.Json.{ReadWriter => RW}
import org.alephium.protocol.{BlockHash, Hash, PublicKey, Signature}
import org.alephium.protocol.model._
import org.alephium.serde.RandomBytes
import org.alephium.util._

// scalastyle:off number.of.methods
// scalastyle:off number.of.types
object ApiModel {
  trait PerChain {
    val fromGroup: Int
    val toGroup: Int
  }
}

@SuppressWarnings(Array("org.wartremover.warts.ToString"))
trait ApiModelCodec {

  def blockflowFetchMaxAge: Duration
  implicit def networkType: NetworkType

  implicit val peerStatusBannedRW: RW[PeerStatus.Banned]   = macroRW
  implicit val peerStatusPenaltyRW: RW[PeerStatus.Penalty] = macroRW

  implicit val peerStatusRW: RW[PeerStatus] = RW.merge(peerStatusBannedRW, peerStatusPenaltyRW)

  implicit val peerMisbehaviorRW: RW[PeerMisbehavior] = macroRW

  implicit val u256Writer: Writer[U256] = javaBigIntegerWriter.comap[U256](_.toBigInt)
  implicit val u256Reader: Reader[U256] = javaBigIntegerReader.map { u256 =>
    U256.from(u256).getOrElse(throw new Abort(s"Invalid U256: $u256"))
  }

  implicit val publicKeyWriter: Writer[PublicKey] = bytesWriter
  implicit val publicKeyReader: Reader[PublicKey] = bytesReader(PublicKey.from)

  implicit val signatureWriter: Writer[Signature] = bytesWriter
  implicit val signatureReader: Reader[Signature] = bytesReader(Signature.from)

  implicit val hashWriter: Writer[Hash] = StringWriter.comap[Hash](
    _.toHexString
  )
  implicit val hashReader: Reader[Hash] =
    byteStringReader.map(Hash.from(_).getOrElse(throw new Abort("cannot decode hash")))

  implicit val blockHashWriter: Writer[BlockHash] = StringWriter.comap[BlockHash](
    _.toHexString
  )
  implicit val blockHashReader: Reader[BlockHash] =
    byteStringReader.map(BlockHash.from(_).getOrElse(throw new Abort("cannot decode block hash")))

  implicit lazy val addressWriter: Writer[Address] = StringWriter.comap[Address](_.toBase58)
  implicit lazy val addressReader: Reader[Address] = StringReader.map { input =>
    Address
      .fromBase58(input, networkType)
      .getOrElse(
        throw new Abort(s"Unable to decode address from $input")
      )
  }

  implicit val cliqueIdWriter: Writer[CliqueId] = StringWriter.comap[CliqueId](_.toHexString)
  implicit val cliqueIdReader: Reader[CliqueId] = StringReader.map { s =>
    Hex.from(s).flatMap(CliqueId.from).getOrElse(throw new Abort("invalid clique id"))
  }

  implicit val networkTypeWriter: Writer[NetworkType] = StringWriter.comap(_.name)
  implicit val networkTypeReader: Reader[NetworkType] = StringReader.map { s =>
    NetworkType.fromName(s).getOrElse(throw new Abort("Invalid network type."))
  }

  implicit val fetchResponseRW: RW[FetchResponse] = macroRW

  implicit val outputRefRW: RW[OutputRef] = macroRW

  implicit val outputRW: RW[Output] = macroRW

  //macro failed on Input for unknwown reason
  implicit val inputRW: ReadWriter[Input] = readwriter[ujson.Value].bimap[Input](
    { input =>
      input.unlockScript match {
        case Some(unlockScript) =>
          ujson
            .Obj("outputRef" -> writeJs(input.outputRef), "unlockScript" -> writeJs(unlockScript))
        case None => ujson.Obj("outputRef" -> writeJs(input.outputRef))
      }
    },
    json => Input(read[OutputRef](json("outputRef")), readOpt[ByteString](json("unlockScript")))
  )

  implicit val txRW: RW[Tx] = macroRW

  implicit val exportFileRW: RW[ExportFile] = macroRW

  implicit val blockEntryRW: RW[BlockEntry] = macroRW

  implicit val blockCandidateRW: RW[BlockCandidate] = macroRW

  implicit val blockSolutionRW: RW[BlockSolution] = macroRW

  implicit val peerAddressRW: RW[PeerAddress] = macroRW

  implicit val nodeInfoRW: RW[NodeInfo] = macroRW

  implicit val selfCliqueRW: RW[SelfClique] = macroRW

  implicit val neighborPeersRW: RW[NeighborPeers] = macroRW

  implicit val getBalanceRW: RW[GetBalance] = macroRW

  implicit val getGroupRW: RW[GetGroup] = macroRW

  implicit val balanceRW: RW[Balance] = macroRW

  implicit val buildTransactionRW: RW[BuildTransaction] = macroRW

  implicit val groupRW: RW[Group] = macroRW

  implicit val buildTransactionResultRW: RW[BuildTransactionResult] = macroRW

  implicit val sendTransactionRW: RW[SendTransaction] = macroRW

  implicit val txStatusRW: RW[TxStatus] =
    RW.merge(macroRW[Confirmed], macroRW[MemPooled.type], macroRW[NotFound.type])

  implicit val buildContractRW: RW[BuildContract] = macroRW

  implicit val buildContractResultRW: RW[BuildContractResult] = macroRW

  implicit val sendContractRW: RW[SendContract] = macroRW

  implicit val compileRW: RW[Compile] = macroRW

  implicit val compileResultRW: RW[CompileResult] = macroRW

  implicit val txResultRW: RW[TxResult] = macroRW

  implicit val getHashesAtHeightRW: RW[GetHashesAtHeight] = macroRW

  implicit val hashesAtHeightRW: RW[HashesAtHeight] = macroRW

  implicit val getChainInfoRW: RW[GetChainInfo] = macroRW

  implicit val chainInfoRW: RW[ChainInfo] = macroRW

  implicit val getBlockRW: RW[GetBlock] = macroRW

  implicit val minerActionRW: RW[MinerAction] = readwriter[String].bimap(
    {
      case MinerAction.StartMining => "start-mining"
      case MinerAction.StopMining  => "stop-mining"
    },
    {
      case "start-mining" => MinerAction.StartMining
      case "stop-mining"  => MinerAction.StopMining
      case other          => throw new Abort(s"Invalid miner action: $other")
    }
  )

  implicit val misbehaviorActionBanRW: RW[MisbehaviorAction.Unban] = macroRW

  implicit val misbehaviorActionRW: RW[MisbehaviorAction] = macroRW

  implicit val minerAddressesRW: RW[MinerAddresses] = macroRW

  implicit val peerInfoRW: ReadWriter[BrokerInfo] = {
    readwriter[ujson.Value].bimap[BrokerInfo](
      peer =>
        ujson.Obj(
          "cliqueId"          -> writeJs(peer.cliqueId),
          "brokerId"          -> writeJs(peer.brokerId),
          "groupNumPerBroker" -> writeJs(peer.groupNumPerBroker),
          "address"           -> writeJs(peer.address)
        ),
      json =>
        BrokerInfo.unsafe(
          read[CliqueId](json("cliqueId")),
          read[Int](json("brokerId")),
          read[Int](json("groupNumPerBroker")),
          read[InetSocketAddress](json("address"))
        )
    )
  }

  implicit val interCliqueSyncedStatusRW: RW[InterCliquePeerInfo] = macroRW

  implicit val fetchRequestRW: RW[FetchRequest] = macroRW[FetchRequest].bimap[FetchRequest](
    identity,
    { fetchRequest =>
      if (fetchRequest.toTs < fetchRequest.fromTs) {
        throw Abort("`toTs` cannot be before `fromTs`")
      } else if ((fetchRequest.toTs -- fetchRequest.fromTs).exists(_ > blockflowFetchMaxAge)) {
        throw Abort(s"interval cannot be greater than ${blockflowFetchMaxAge}")
      } else {
        fetchRequest
      }
    }
  )

  implicit val mnemonicSizeRW: RW[Mnemonic.Size] = readwriter[Int].bimap(
    _.value,
    { size =>
      Mnemonic
        .Size(size)
        .getOrElse(
          throw Abort(
            s"Invalid mnemonic size: $size, expected: ${Mnemonic.Size.list.map(_.value).mkString(", ")}"
          )
        )
    }
  )

  private def bytesWriter[T <: RandomBytes]: Writer[T] =
    StringWriter.comap[T](_.toHexString)

  private def bytesReader[T <: RandomBytes](from: ByteString => Option[T]): Reader[T] =
    StringReader.map { input =>
      val keyOpt = for {
        bs  <- Hex.from(input)
        key <- from(bs)
      } yield key
      keyOpt.getOrElse(throw Abort(s"Unable to decode key from $input"))
    }
}
