package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.scalacheck.Gen

import org.alephium.crypto.{ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.{CliqueConfig, ConsensusConfig, GroupConfig}
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.util.{AVector, U64}

trait ModelGenerators {
  lazy val txInputGen: Gen[TxInput] = for {
    shortKey <- Gen.choose(0, 5)
  } yield {
    val outputRef = TxOutputRef(shortKey, Hash.random)
    TxInput(outputRef, UnlockScript.p2pkh(ED25519PublicKey.zero))
  }

  lazy val txOutputGen: Gen[TxOutput] = for {
    value <- Gen.choose[Long](1, 5)
  } yield TxOutput.asset(U64.unsafe(value), 0, LockupScript.p2pkh(Hash.zero))

  lazy val transactionGen: Gen[Transaction] = for {
    inputNum  <- Gen.choose(1, 5)
    inputs    <- Gen.listOfN(inputNum, txInputGen)
    outputNum <- Gen.choose(1, 5)
    outputs   <- Gen.listOfN(outputNum, txOutputGen)
  } yield {
    Transaction.from(AVector.from(inputs), AVector.from(outputs), AVector.empty[ED25519Signature])
  }

  def blockGen(implicit config: ConsensusConfig): Gen[Block] =
    for {
      txNum <- Gen.choose(1, 5)
      txs   <- Gen.listOfN(txNum, transactionGen)
    } yield Block.from(AVector(Hash.zero), AVector.from(txs), config.maxMiningTarget, 0)

  def blockGenNonEmpty(implicit config: ConsensusConfig): Gen[Block] =
    blockGen.retryUntil(_.transactions.nonEmpty)

  def blockGenOf(broker: BrokerInfo)(implicit config: ConsensusConfig): Gen[Block] =
    blockGenNonEmpty.retryUntil(_.chainIndex.relateTo(broker))

  def blockGenNotOf(broker: BrokerInfo)(implicit config: ConsensusConfig): Gen[Block] = {
    blockGenNonEmpty.retryUntil(!_.chainIndex.relateTo(broker))
  }

  def blockGenOf(group: GroupIndex)(implicit config: ConsensusConfig): Gen[Block] = {
    blockGenNonEmpty.retryUntil(_.chainIndex.from equals group)
  }

  def blockGenOf(deps: AVector[Hash])(implicit config: ConsensusConfig): Gen[Block] =
    for {
      txNum <- Gen.choose(0, 5)
      txs   <- Gen.listOfN(txNum, transactionGen)
    } yield Block.from(deps, AVector.from(txs), config.maxMiningTarget, 0)

  def chainGenOf(length: Int, block: Block)(implicit config: ConsensusConfig): Gen[AVector[Block]] =
    chainGenOf(length, block.hash)

  def chainGenOf(length: Int)(implicit config: ConsensusConfig): Gen[AVector[Block]] =
    chainGenOf(length, Hash.zero)

  def chainGenOf(length: Int, initialHash: Hash)(
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
    Gen.choose(0, config.groups - 1).map(n => GroupIndex.unsafe(n))

  def cliqueIdGen: Gen[CliqueId] =
    Gen.resultOf[Unit, CliqueId](_ => CliqueId.generate)

  def groupNumPerBrokerGen(implicit config: GroupConfig): Gen[Int] = {
    Gen.oneOf((1 to config.groups).filter(i => (config.groups % i) equals 0))
  }

  def brokerInfoGen(implicit config: CliqueConfig): Gen[BrokerInfo] = {
    for {
      id      <- Gen.choose(0, config.brokerNum - 1)
      address <- socketAddressGen
    } yield BrokerInfo.unsafe(id, config.groupNumPerBroker, address)
  }

  def cliqueInfoGen(implicit config: GroupConfig): Gen[CliqueInfo] = {
    for {
      groupNumPerBroker <- groupNumPerBrokerGen
      peers             <- Gen.listOfN(config.groups / groupNumPerBroker, socketAddressGen)
      cid               <- cliqueIdGen
    } yield CliqueInfo.unsafe(cid, AVector.from(peers), groupNumPerBroker)
  }

  lazy val socketAddressGen: Gen[InetSocketAddress] =
    for {
      ip0  <- Gen.choose(0, 255)
      ip1  <- Gen.choose(0, 255)
      ip2  <- Gen.choose(0, 255)
      ip3  <- Gen.choose(0, 255)
      port <- Gen.choose(0x401, 65535)
    } yield new InetSocketAddress(s"$ip0.$ip1.$ip2.$ip3", port)
}
