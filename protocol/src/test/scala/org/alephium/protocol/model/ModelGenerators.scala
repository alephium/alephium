package org.alephium.protocol.model

import java.net.InetSocketAddress

import scala.collection.mutable
import scala.util.Sorting

import akka.util.ByteString
import org.scalacheck.Arbitrary.arbByte
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.{ALF, Generators}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.{CliqueConfig, ConsensusConfig, GroupConfig}
import org.alephium.protocol.vm.{LockupScript, StatefulContract, UnlockScript, Val}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AlephiumSpec, AVector, NumericHelpers, U64}

trait ModelGenerators extends Generators with NumericHelpers {
  lazy val txInputGen: Gen[TxInput] = for {
    shortKey <- Gen.choose(0, 5)
  } yield {
    val outputRef = TxOutputRef(shortKey, Hash.random)
    TxInput(outputRef, UnlockScript.p2pkh(ED25519PublicKey.zero))
  }

  val minAmount = 1000L
  lazy val amountGen: Gen[U64] = {
    Gen.choose(minAmount, ALF.MaxALFValue.v / 1000L).map(U64.unsafe)
  }

  lazy val tokenGen: Gen[(TokenId, U64)] = for {
    tokenId <- hashGen
    amount  <- amountGen
  } yield (tokenId, amount)

  lazy val tokensGen: Gen[AVector[(TokenId, U64)]] = for {
    tokenNum <- Gen.choose(0, 10)
    tokens   <- Gen.listOfN(tokenNum, tokenGen)
  } yield AVector.from(tokens)

  lazy val createdHeightGen: Gen[Int] = {
    Gen.choose(ALF.GenesisHeight, Int.MaxValue)
  }

  case class ScriptPair(lockup: LockupScript, unlock: UnlockScript, privateKey: ED25519PrivateKey)

  lazy val p2pkhLockupGen: Gen[LockupScript] = for {
    publicKey <- publicKeyGen
  } yield LockupScript.p2pkh(publicKey)
  lazy val p2pkScriptGen: Gen[ScriptPair] = for {
    (privateKey, publicKey) <- keypairGen
  } yield ScriptPair(LockupScript.p2pkh(publicKey), UnlockScript.p2pkh(publicKey), privateKey)

  lazy val dataGen: Gen[ByteString] = for {
    length <- Gen.choose(0, 20)
    bytes  <- Gen.listOfN(length, arbByte.arbitrary)
  } yield ByteString(bytes)

  def assetOutputGen(
      amountGen: Gen[U64]                     = amountGen,
      tokensGen: Gen[AVector[(TokenId, U64)]] = tokensGen,
      heightGen: Gen[Int]                     = createdHeightGen,
      scriptGen: Gen[LockupScript]            = p2pkhLockupGen,
      dataGen: Gen[ByteString]                = dataGen
  ): Gen[AssetOutput] = {
    for {
      amount         <- amountGen
      tokens         <- tokensGen
      createdHeight  <- heightGen
      lockupScript   <- scriptGen
      additionalData <- dataGen
    } yield AssetOutput(amount, tokens, createdHeight, lockupScript, additionalData)
  }

  lazy val counterContract: StatefulContract = {
    val input =
      s"""
         |TxContract Foo(mut x: U64) {
         |  fn add() -> () {
         |    x = x + 1
         |    return
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(input).toOption.get
  }
  lazy val counterStateGen: Gen[AVector[Val]] =
    Gen.choose(0L, Long.MaxValue / 1000).map(n => AVector(Val.U64(U64.unsafe(n))))

  def contractOutputGen(
      amountGen: Gen[U64]            = amountGen,
      heightGen: Gen[Int]            = createdHeightGen,
      scriptGen: Gen[LockupScript]   = p2pkhLockupGen,
      codeGen: Gen[StatefulContract] = Gen.const(counterContract)
  ): Gen[ContractOutput] = {
    for {
      amount        <- amountGen
      createdHeight <- heightGen
      lockupScript  <- scriptGen
      code          <- codeGen
    } yield ContractOutput(amount, createdHeight, lockupScript, code)
  }

  lazy val txOutputGen: Gen[TxOutput] = for {
    value <- Gen.choose[Long](1, 5)
  } yield TxOutput.asset(U64.unsafe(value), 0, LockupScript.p2pkh(Hash.zero))

  sealed trait TxInputStateInfo {
    def referredOutput: TxOutput
  }
  case class AssetInputInfo(txInput: TxInput,
                            referredOutput: AssetOutput,
                            privateKey: ED25519PrivateKey)
      extends TxInputStateInfo
  case class ContractInfo(txInput: TxInput,
                          referredOutput: ContractOutput,
                          state: AVector[Val],
                          privateKey: ED25519PrivateKey)
      extends TxInputStateInfo

  def assetInputInfoGen(
      amountGen: Gen[U64]                     = amountGen,
      tokensGen: Gen[AVector[(TokenId, U64)]] = tokensGen,
      heightGen: Gen[Int]                     = createdHeightGen,
      scriptGen: Gen[ScriptPair]              = p2pkScriptGen,
      dataGen: Gen[ByteString]                = dataGen
  ): Gen[AssetInputInfo] =
    for {
      ScriptPair(lockup, unlock, privateKey) <- scriptGen
      assetOutput                            <- assetOutputGen(amountGen, tokensGen, heightGen, Gen.const(lockup), dataGen)
      outputHash                             <- hashGen
    } yield {
      val outputRef = TxOutputRef(assetOutput.scriptHint, outputHash)
      val txInput   = TxInput(outputRef, unlock)
      AssetInputInfo(txInput, assetOutput, privateKey)
    }

  def contractInfoGen(
      amountGen: Gen[U64]            = amountGen,
      heightGen: Gen[Int]            = createdHeightGen,
      scriptGen: Gen[ScriptPair]     = p2pkScriptGen,
      codeGen: Gen[StatefulContract] = Gen.const(counterContract),
      stateGen: Gen[AVector[Val]]    = counterStateGen
  ): Gen[ContractInfo] =
    for {
      ScriptPair(lockup, unlock, privateKey) <- scriptGen
      contractOutput                         <- contractOutputGen(amountGen, heightGen, Gen.const(lockup), codeGen)
      state                                  <- stateGen
      outputHash                             <- hashGen
    } yield {
      val outputRef = TxOutputRef.contract(outputHash)
      val txInput   = TxInput(outputRef, unlock)
      ContractInfo(txInput, contractOutput, state, privateKey)
    }

  private lazy val noContracts: Gen[AVector[ContractInfo]] =
    Gen.const(()).map(_ => AVector.empty)

  import ModelGenerators.Balances
  def split(amount: U64, minAmount: U64, num: Int): AVector[U64] = {
    assume(num > 0)
    val remainder = amount - (minAmount * num)
    val pivots    = Array.fill(num + 1)(nextU64(remainder))
    pivots(0) = U64.Zero
    pivots(1) = remainder
    Sorting.quickSort(pivots)
    AVector.tabulate(num)(i => pivots(i + 1) - pivots(i) + minAmount)
  }
  def split(balances: Balances, outputNum: Int): AVector[Balances] = {
    val alfSplits = split(balances.alfAmout, minAmount, outputNum)
    val tokenSplits = balances.tokens.map {
      case (tokenId, amount) =>
        tokenId -> split(amount, 0, outputNum)
    }
    AVector.tabulate(outputNum) { index =>
      val tokens = tokenSplits.map {
        case (tokenId, amounts) => tokenId -> amounts(index)
      }
      Balances(alfSplits(index), tokens)
    }
  }

  def unsignedTxGen(
      assetsToSpend: Gen[AVector[AssetInputInfo]],
      contractsToSpend: Gen[AVector[ContractInfo]] = noContracts,
      issueNewToken: Boolean                       = true,
      lockupScriptGen: Gen[LockupScript]           = p2pkhLockupGen,
      heightGen: Gen[Int]                          = createdHeightGen,
      dataGen: Gen[ByteString]                     = dataGen
  ): Gen[UnsignedTransaction] =
    for {
      assets        <- assetsToSpend
      contracts     <- contractsToSpend
      createdHeight <- heightGen
      lockupScript  <- lockupScriptGen
    } yield {
      val inputs         = assets.map(_.txInput) ++ contracts.map(_.txInput)
      val outputsToSpend = assets.map[TxOutput](_.referredOutput) ++ contracts.map(_.referredOutput)
      val alfAmount      = outputsToSpend.map(_.amount).reduce(_ + _)
      val tokenTable     = mutable.HashMap.from(assets.flatMap(_.referredOutput.tokens).toIterable)
      if (issueNewToken) {
        tokenTable(inputs.head.hash) = nextU64(1, U64.MaxValue)
      }

      val initialBalances = Balances(alfAmount, tokenTable.toMap)
      val outputNum       = min(alfAmount / minAmount, inputs.length * 2, ALF.MaxTxOutputNum)
      val splitBalances   = split(initialBalances, outputNum.v.toInt)
      val outputs =
        splitBalances.map[TxOutput](_.toOutput(createdHeight, lockupScript, dataGen.sample.get))
      UnsignedTransaction(None, inputs, outputs, AVector.empty)
    }

  lazy val assetsToSpendGen: Gen[AVector[AssetInputInfo]] = for {
    inputNum <- smallPositiveInt
    inputs   <- Gen.listOfN(inputNum, assetInputInfoGen())
  } yield AVector.from(inputs)
  def transactionGen(
      assetsToSpend: Gen[AVector[AssetInputInfo]]  = assetsToSpendGen,
      contractsToSpend: Gen[AVector[ContractInfo]] = noContracts,
      issueNewToken: Boolean                       = true,
      lockupScript: Gen[LockupScript]              = p2pkhLockupGen,
      heightGen: Gen[Int]                          = createdHeightGen,
      dataGen: Gen[ByteString]                     = dataGen
  ): Gen[Transaction] =
    for {
      assetInfos    <- assetsToSpend
      contractInfos <- contractsToSpend
      unsignedTx <- unsignedTxGen(Gen.const(assetInfos),
                                  Gen.const(contractInfos),
                                  issueNewToken,
                                  lockupScript,
                                  heightGen,
                                  dataGen)
      signatures = assetInfos.map(info => ED25519.sign(unsignedTx.hash.bytes, info.privateKey)) ++
        contractInfos.map(info => ED25519.sign(unsignedTx.hash.bytes, info.privateKey))
    } yield Transaction(unsignedTx, AVector.empty, signatures)

  def blockGen(implicit config: ConsensusConfig): Gen[Block] =
    for {
      txNum <- Gen.choose(1, 5)
      txs   <- Gen.listOfN(txNum, transactionGen())
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
      txs   <- Gen.listOfN(txNum, transactionGen())
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

object ModelGenerators {
  final case class Balances(alfAmout: U64, tokens: Map[TokenId, U64]) {
    def toOutput(createdHeight: Int, lockupScript: LockupScript, data: ByteString): AssetOutput = {
      val tokensVec = AVector.from(tokens)
      AssetOutput(alfAmout, tokensVec, createdHeight, lockupScript, data)
    }
  }
}

class ModelGeneratorsSpec extends AlephiumSpec with ModelGenerators {
  it should "split a positive number" in {
    def check(amount: Int, minAmount: Int, num: Int): Assertion = {
      val result = split(amount, minAmount, num)
      result.foreach(_ >= minAmount is true)
      result.reduce(_ + _) is amount
    }

    check(100, 0, 10)
    check(100, 5, 10)
    check(100, 10, 10)
  }
}
