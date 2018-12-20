package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.crypto._
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.util.AVector
import org.scalacheck.Gen

// TODO: rename as GenFixture
object ModelGen {
  private val (sk, pk) = ED25519.generateKeyPair()

  val txInputGen: Gen[TxInput] = for {
    index <- Gen.choose(0, 10)
  } yield TxInput(Keccak256.zero, index) // TODO: fixme: Has to use zero here to pass test on ubuntu

  val txOutputGen: Gen[TxOutput] = for {
    value <- Gen.choose(0, 100)
  } yield TxOutput(value, pk)

  val transactionGen: Gen[Transaction] = for {
    inputNum  <- Gen.choose(0, 5)
    inputs    <- Gen.listOfN(inputNum, txInputGen)
    outputNum <- Gen.choose(0, 5)
    outputs   <- Gen.listOfN(outputNum, txOutputGen)
  } yield Transaction.from(UnsignedTransaction(AVector.from(inputs), AVector.from(outputs)), sk)

  def blockGen(implicit config: ConsensusConfig): Gen[Block] =
    for {
      txNum <- Gen.choose(0, 100)
      txs   <- Gen.listOfN(txNum, transactionGen)
    } yield Block.from(AVector(Keccak256.zero), AVector.from(txs), config.maxMiningTarget, 0)

  def blockGenWith(deps: AVector[Keccak256])(implicit config: ConsensusConfig): Gen[Block] =
    for {
      txNum <- Gen.choose(0, 100)
      txs   <- Gen.listOfN(txNum, transactionGen)
    } yield Block.from(deps, AVector.from(txs), config.maxMiningTarget, 0)

  def chainGen(length: Int, block: Block)(implicit config: ConsensusConfig): Gen[AVector[Block]] =
    chainGen(length, block.hash)

  def chainGen(length: Int)(implicit config: ConsensusConfig): Gen[AVector[Block]] =
    chainGen(length, Keccak256.zero)

  def chainGen(length: Int, initialHash: Keccak256)(
      implicit config: ConsensusConfig): Gen[AVector[Block]] =
    Gen.listOfN(length, blockGen).map { blocks =>
      blocks.foldLeft(AVector.empty[Block]) {
        case (acc, block) =>
          val prevHash      = if (acc.isEmpty) initialHash else acc.last.hash
          val currentHeader = block.blockHeader
          val deps          = AVector.fill(config.depsNum)(prevHash)
          val newHeader     = currentHeader.copy(blockDeps = deps)
          val newBlock      = block.copy(blockHeader = newHeader)
          acc :+ newBlock
      }
    }

  def groupGen(implicit config: GroupConfig): Gen[GroupIndex] =
    groupGen_(config.groups)

  def groupGen_(groups: Int): Gen[GroupIndex] =
    Gen.choose(0, groups - 1).map(n => GroupIndex.unsafe(n))

  def peerId: Gen[PeerId] =
    Gen.resultOf[Unit, PeerId](_ => PeerId.generate)

  def peerId(groupIndex: GroupIndex)(implicit config: GroupConfig): Gen[PeerId] =
    Gen.resultOf[Unit, PeerId](_ => PeerId.generateFor(groupIndex))

  def socketAddress: Gen[InetSocketAddress] =
    for {
      ip0  <- Gen.choose(0, 255)
      ip1  <- Gen.choose(0, 255)
      ip2  <- Gen.choose(0, 255)
      ip3  <- Gen.choose(0, 255)
      port <- Gen.choose(0, 65535)
    } yield new InetSocketAddress(s"$ip0.$ip1.$ip2.$ip3", port)

  def peerInfo(groupIndex: GroupIndex)(implicit config: GroupConfig): Gen[PeerInfo] =
    for {
      address <- socketAddress
      id      <- peerId(groupIndex)
    } yield PeerInfo(id, address)

  def peerInfo: Gen[PeerInfo] =
    for {
      address <- socketAddress
      id      <- peerId
    } yield PeerInfo(id, address)
}
