package org.alephium.protocol.model

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Sorting

import akka.util.ByteString
import org.scalacheck.Arbitrary.arbByte
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.{ALF, DefaultGenerators, Generators}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.{ConsensusConfig, ConsensusConfigFixture}
import org.alephium.protocol.model.ModelGenerators.{Balances, ScriptPair}
import org.alephium.protocol.vm.{LockupScript, StatefulContract, UnlockScript, Val}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AlephiumSpec, AVector, NumericHelpers, U64}

trait LockupScriptGenerators extends Generators {
  import ModelGenerators.ScriptPair

  def p2pkhLockupGen(groupIndex: GroupIndex): Gen[LockupScript] =
    for {
      publicKey <- publicKeyGen(groupIndex)
    } yield LockupScript.p2pkh(publicKey)
  def p2pkScriptGen(groupIndex: GroupIndex): Gen[ScriptPair] =
    for {
      (privateKey, publicKey) <- keypairGen(groupIndex)
    } yield ScriptPair(LockupScript.p2pkh(publicKey), UnlockScript.p2pkh(publicKey), privateKey)
}

trait TxInputGenerators extends Generators {
  private def shortKeyGen(groupIndex: GroupIndex): Gen[Int] = {
    Gen.choose(0, Int.MaxValue).retryUntil(LockupScript.groupIndex(_) equals groupIndex)
  }

  def txInputGen(groupIndex: GroupIndex): Gen[TxInput] =
    for {
      shortKey <- shortKeyGen(groupIndex)
      hash     <- hashGen
    } yield {
      val outputRef = TxOutputRef(shortKey, hash)
      TxInput(outputRef, UnlockScript.p2pkh(ED25519PublicKey.zero))
    }
}

trait TokenGenerators extends Generators with NumericHelpers {
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
    val alfSplits = split(balances.alfAmount, minAmount, outputNum)
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
}

trait TxGenerators
    extends Generators
    with LockupScriptGenerators
    with TxInputGenerators
    with TokenGenerators {

  lazy val createdHeightGen: Gen[Int] = {
    Gen.choose(ALF.GenesisHeight, Int.MaxValue)
  }

  lazy val dataGen: Gen[ByteString] = for {
    length <- Gen.choose(0, 20)
    bytes  <- Gen.listOfN(length, arbByte.arbitrary)
  } yield ByteString(bytes)

  def assetOutputGen(groupIndex: GroupIndex)(
      amountGen: Gen[U64]                     = amountGen,
      tokensGen: Gen[AVector[(TokenId, U64)]] = tokensGen,
      heightGen: Gen[Int]                     = createdHeightGen,
      scriptGen: Gen[LockupScript]            = p2pkhLockupGen(groupIndex),
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

  def contractOutputGen(groupIndex: GroupIndex)(
      amountGen: Gen[U64]            = amountGen,
      heightGen: Gen[Int]            = createdHeightGen,
      scriptGen: Gen[LockupScript]   = p2pkhLockupGen(groupIndex),
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

  def assetInputInfoGen(groupIndex: GroupIndex)(
      amountGen: Gen[U64]                     = amountGen,
      tokensGen: Gen[AVector[(TokenId, U64)]] = tokensGen,
      heightGen: Gen[Int]                     = createdHeightGen,
      scriptGen: Gen[ScriptPair]              = p2pkScriptGen(groupIndex),
      dataGen: Gen[ByteString]                = dataGen
  ): Gen[AssetInputInfo] =
    for {
      ScriptPair(lockup, unlock, privateKey) <- scriptGen
      assetOutput <- assetOutputGen(groupIndex)(amountGen,
                                                tokensGen,
                                                heightGen,
                                                Gen.const(lockup),
                                                dataGen)
      outputHash <- hashGen
    } yield {
      val outputRef = TxOutputRef(assetOutput.scriptHint, outputHash)
      val txInput   = TxInput(outputRef, unlock)
      AssetInputInfo(txInput, assetOutput, privateKey)
    }

  def contractInfoGen(groupIndex: GroupIndex)(
      amountGen: Gen[U64]            = amountGen,
      heightGen: Gen[Int]            = createdHeightGen,
      scriptGen: Gen[ScriptPair]     = p2pkScriptGen(groupIndex),
      codeGen: Gen[StatefulContract] = Gen.const(counterContract),
      stateGen: Gen[AVector[Val]]    = counterStateGen
  ): Gen[ContractInfo] =
    for {
      ScriptPair(lockup, unlock, privateKey) <- scriptGen
      contractOutput <- contractOutputGen(groupIndex)(amountGen,
                                                      heightGen,
                                                      Gen.const(lockup),
                                                      codeGen)
      state      <- stateGen
      outputHash <- hashGen
    } yield {
      val outputRef = TxOutputRef.contract(outputHash)
      val txInput   = TxInput(outputRef, unlock)
      ContractInfo(txInput, contractOutput, state, privateKey)
    }

  private lazy val noContracts: Gen[AVector[ContractInfo]] =
    Gen.const(()).map(_ => AVector.empty)

  def unsignedTxGen(chainIndex: ChainIndex)(
      assetsToSpend: Gen[AVector[AssetInputInfo]],
      contractsToSpend: Gen[AVector[ContractInfo]] = noContracts,
      issueNewToken: Boolean                       = true,
      lockupScriptGen: Gen[LockupScript]           = p2pkhLockupGen(chainIndex.to),
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

  def assetsToSpendGen(groupIndex: GroupIndex): Gen[AVector[AssetInputInfo]] =
    for {
      inputNum <- smallPositiveInt
      inputs   <- Gen.listOfN(inputNum, assetInputInfoGen(groupIndex)())
    } yield AVector.from(inputs)

  def transactionGen(chainIndex: ChainIndex)(
      assetsToSpend: Gen[AVector[AssetInputInfo]]  = assetsToSpendGen(chainIndex.from),
      contractsToSpend: Gen[AVector[ContractInfo]] = noContracts,
      issueNewToken: Boolean                       = true,
      lockupScript: Gen[LockupScript]              = p2pkhLockupGen(chainIndex.to),
      heightGen: Gen[Int]                          = createdHeightGen,
      dataGen: Gen[ByteString]                     = dataGen
  ): Gen[Transaction] =
    for {
      assetInfos    <- assetsToSpend
      contractInfos <- contractsToSpend
      unsignedTx <- unsignedTxGen(chainIndex)(Gen.const(assetInfos),
                                              Gen.const(contractInfos),
                                              issueNewToken,
                                              lockupScript,
                                              heightGen,
                                              dataGen)
      signatures = assetInfos.map(info => ED25519.sign(unsignedTx.hash.bytes, info.privateKey)) ++
        contractInfos.map(info => ED25519.sign(unsignedTx.hash.bytes, info.privateKey))
    } yield Transaction(unsignedTx, AVector.empty, signatures)
}

trait BlockGenerators extends TxGenerators {
  implicit def config: ConsensusConfig

  def blockGen(chainIndex: ChainIndex): Gen[Block] =
    blockGenOf(chainIndex, AVector(Hash.zero))

  def blockGenOf(broker: BrokerInfo): Gen[Block] =
    chainIndexGenRelatedTo(broker).flatMap(blockGen(_))

  def blockGenNotOf(broker: BrokerInfo): Gen[Block] =
    chainIndexGenNotRelatedTo(broker).flatMap(blockGen(_))

  def blockGenOf(group: GroupIndex): Gen[Block] =
    chainIndexFrom(group).flatMap(blockGen(_))

  private def gen(chainIndex: ChainIndex, deps: AVector[Hash], txs: AVector[Transaction]): Block = {
    @tailrec
    def iter(nonce: Long): Block = {
      val block = Block.from(deps, txs, config.maxMiningTarget, nonce)
      if (block.chainIndex equals chainIndex) block else iter(nonce + 1)
    }

    iter(0L)
  }

  def blockGenOf(chainIndex: ChainIndex, deps: AVector[Hash]): Gen[Block] =
    for {
      txNum <- Gen.choose(1, 5)
      txs   <- Gen.listOfN(txNum, transactionGen(chainIndex)())
    } yield gen(chainIndex, deps, AVector.from(txs))

  def chainGenOf(chainIndex: ChainIndex, length: Int, block: Block): Gen[AVector[Block]] =
    chainGenOf(chainIndex, length, block.hash)

  def chainGenOf(chainIndex: ChainIndex, length: Int): Gen[AVector[Block]] =
    chainGenOf(chainIndex, length, Hash.zero)

  def chainGenOf(chainIndex: ChainIndex, length: Int, initialHash: Hash): Gen[AVector[Block]] =
    Gen.listOfN(length, blockGen(chainIndex)).map { blocks =>
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
}

trait ModelGenerators extends BlockGenerators

trait NoIndexModelGeneratorsLike extends ModelGenerators {
  implicit def config: ConsensusConfig

  lazy val txInputGen: Gen[TxInput] =
    for {
      groupIndex <- groupIndexGen
      txInput    <- txInputGen(groupIndex)
    } yield txInput

  lazy val transactionGen: Gen[Transaction] =
    for {
      chainIndex <- chainIndexGen
      tx         <- transactionGen(chainIndex)()
    } yield tx

  lazy val blockGen: Gen[Block] =
    for {
      chainIndex <- chainIndexGen
      block      <- blockGen(chainIndex)
    } yield block

  def blockGenOf(deps: AVector[Hash]): Gen[Block] =
    for {
      chainIndex <- chainIndexGen
      block      <- blockGenOf(chainIndex, deps)
    } yield block

  def chainGenOf(length: Int, block: Block): Gen[AVector[Block]] =
    for {
      chainIndex <- chainIndexGen
      blocks     <- chainGenOf(chainIndex, length, block)
    } yield blocks

  def chainGenOf(length: Int): Gen[AVector[Block]] =
    for {
      chainIndex <- chainIndexGen
      blocks     <- chainGenOf(chainIndex, length)
    } yield blocks
}

trait NoIndexModelGenerators extends NoIndexModelGeneratorsLike with ConsensusConfigFixture

object ModelGenerators {
  final case class ScriptPair(lockup: LockupScript,
                              unlock: UnlockScript,
                              privateKey: ED25519PrivateKey)

  final case class Balances(alfAmount: U64, tokens: Map[TokenId, U64]) {
    def toOutput(createdHeight: Int, lockupScript: LockupScript, data: ByteString): AssetOutput = {
      val tokensVec = AVector.from(tokens)
      AssetOutput(alfAmount, tokensVec, createdHeight, lockupScript, data)
    }
  }
}

class ModelGeneratorsSpec extends AlephiumSpec with TokenGenerators with DefaultGenerators {
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
