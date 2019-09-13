package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.scalacheck.Gen

import org.alephium.crypto._
import org.alephium.protocol.config.{CliqueConfig, ConsensusConfig, GroupConfig}
import org.alephium.util.AVector

// TODO: rename as GenFixture
object ModelGen {
  private val (sk, pk) = ED25519.generatePriPub()

  val txInputGen: Gen[TxOutputPoint] = for {
    index <- Gen.choose(0, 10)
  } yield {
    val publicKey = ED25519PublicKey.generate
    TxOutputPoint(publicKey, Keccak256.random, index)
  }

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
          val currentHeader = block.header
          val deps          = AVector.fill(config.depsNum)(prevHash)
          val newHeader     = currentHeader.copy(blockDeps = deps)
          val newBlock      = block.copy(header = newHeader)
          acc :+ newBlock
      }
    }

  def groupIndexGen(implicit config: GroupConfig): Gen[GroupIndex] =
    groupIndexGen(config.groups)

  def groupIndexGen(groups: Int): Gen[GroupIndex] =
    Gen.choose(0, groups - 1).map(n => GroupIndex.unsafe(n))

  def cliqueId: Gen[CliqueId] =
    Gen.resultOf[Unit, CliqueId](_ => CliqueId.generate)

  def groupNumPerBrokerGen(implicit config: GroupConfig): Gen[Int] = {
    Gen.oneOf((1 to config.groups).filter(i => (config.groups % i) equals 0))
  }

  def brokerInfo(implicit config: CliqueConfig): Gen[BrokerInfo] = {
    for {
      id      <- Gen.choose(0, config.brokerNum - 1)
      address <- socketAddress
    } yield BrokerInfo.unsafe(id, config.groupNumPerBroker, address)
  }

  def cliqueInfo(implicit config: GroupConfig): Gen[CliqueInfo] = {
    for {
      groupNumPerBroker <- groupNumPerBrokerGen
      peers             <- Gen.listOfN(config.groups / groupNumPerBroker, socketAddress)
      cid               <- cliqueId
    } yield CliqueInfo.unsafe(cid, AVector.from(peers), groupNumPerBroker)
  }

  val socketAddress: Gen[InetSocketAddress] =
    for {
      ip0  <- Gen.choose(0, 255)
      ip1  <- Gen.choose(0, 255)
      ip2  <- Gen.choose(0, 255)
      ip3  <- Gen.choose(0, 255)
      port <- Gen.choose(0, 65535)
    } yield new InetSocketAddress(s"$ip0.$ip1.$ip2.$ip3", port)
}
