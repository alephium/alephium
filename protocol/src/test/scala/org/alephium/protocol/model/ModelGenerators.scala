package org.alephium.protocol.model

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Sorting

import akka.util.ByteString
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.crypto.{ALFPrivateKey, ALFPublicKey, ALFSignatureSchema}
import org.alephium.protocol.{ALF, DefaultGenerators, Generators}
import org.alephium.protocol.Hash
import org.alephium.protocol.config._
import org.alephium.protocol.model.ModelGenerators._
import org.alephium.protocol.vm.{LockupScript, StatefulContract, UnlockScript, Val}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AlephiumSpec, AVector, NumericHelpers, U64}

trait LockupScriptGenerators extends Generators {
  import ModelGenerators.ScriptPair

  implicit def groupConfig: GroupConfig

  def p2pkhLockupGen(groupIndex: GroupIndex): Gen[LockupScript] =
    for {
      publicKey <- publicKeyGen(groupIndex)
    } yield LockupScript.p2pkh(publicKey)

  def p2pkScriptGen(groupIndex: GroupIndex): Gen[ScriptPair] =
    for {
      (privateKey, publicKey) <- keypairGen(groupIndex)
    } yield ScriptPair(LockupScript.p2pkh(publicKey), UnlockScript.p2pkh(publicKey), privateKey)

  def addressGen(groupIndex: GroupIndex): Gen[(LockupScript, ALFPublicKey, ALFPrivateKey)] =
    for {
      (privateKey, publicKey) <- keypairGen(groupIndex)
    } yield (LockupScript.p2pkh(publicKey), publicKey, privateKey)

  def addressStringGen(groupIndex: GroupIndex): Gen[(String, String, String)] =
    addressGen(groupIndex).map {
      case (script, publicKey, privateKey) =>
        (script.toBase58, publicKey.toHexString, privateKey.toHexString)
    }
}

trait TxInputGenerators extends Generators {
  implicit def groupConfig: GroupConfig

  def scriptHintGen(groupIndex: GroupIndex): Gen[ScriptHint] =
    Gen.choose(0, Int.MaxValue).map(ScriptHint.fromHash).retryUntil(_.groupIndex equals groupIndex)

  def assetOutputRefGen(groupIndex: GroupIndex): Gen[AssetOutputRef] = {
    for {
      scriptHint <- scriptHintGen(groupIndex)
      hash       <- hashGen
    } yield AssetOutputRef.unsafe(Hint.ofAsset(scriptHint), hash)
  }

  def contractOutputRefGen(groupIndex: GroupIndex): Gen[ContractOutputRef] = {
    for {
      scriptHint <- scriptHintGen(groupIndex)
      hash       <- hashGen
    } yield ContractOutputRef.unsafe(Hint.ofContract(scriptHint), hash)
  }

  def txInputGen(groupIndex: GroupIndex): Gen[TxInput] =
    for {
      scriptHint <- scriptHintGen(groupIndex)
      isAsset    <- arbBool.arbitrary
      hash       <- hashGen
    } yield {
      val hint      = if (isAsset) Hint.ofAsset(scriptHint) else Hint.ofContract(scriptHint)
      val outputRef = TxOutputRef.from(hint, hash)
      TxInput(outputRef, UnlockScript.p2pkh(ALFPublicKey.zero))
    }
}

trait TokenGenerators extends Generators with NumericHelpers {
  val minAmount = 1000L
  def amountGen(inputNum: Int): Gen[U64] = {
    Gen.choose(minAmount * inputNum, ALF.MaxALFValue.v / 1000L).map(U64.unsafe)
  }

  def tokenGen(inputNum: Int): Gen[(TokenId, U64)] =
    for {
      tokenId <- hashGen
      amount  <- amountGen(inputNum)
    } yield (tokenId, amount)

  def tokensGen(inputNum: Int, minTokens: Int, maxTokens: Int): Gen[Map[TokenId, U64]] =
    for {
      tokenNum <- Gen.choose(minTokens, maxTokens)
      tokens   <- Gen.listOfN(tokenNum, tokenGen(inputNum))
    } yield tokens.toMap

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

// scalastyle:off parameter.number
trait TxGenerators
    extends Generators
    with LockupScriptGenerators
    with TxInputGenerators
    with TokenGenerators {

  lazy val createdHeightGen: Gen[Int] = Gen.choose(ALF.GenesisHeight, Int.MaxValue)

  lazy val dataGen: Gen[ByteString] = for {
    length <- Gen.choose(0, 20)
    bytes  <- Gen.listOfN(length, arbByte.arbitrary)
  } yield ByteString(bytes)

  def assetOutputGen(groupIndex: GroupIndex)(
      _amountGen: Gen[U64]               = amountGen(1),
      _tokensGen: Gen[Map[TokenId, U64]] = tokensGen(1, 1, 5),
      heightGen: Gen[Int]                = createdHeightGen,
      scriptGen: Gen[LockupScript]       = p2pkhLockupGen(groupIndex),
      dataGen: Gen[ByteString]           = dataGen
  ): Gen[AssetOutput] = {
    for {
      amount         <- _amountGen
      tokens         <- _tokensGen
      createdHeight  <- heightGen
      lockupScript   <- scriptGen
      additionalData <- dataGen
    } yield AssetOutput(amount, createdHeight, lockupScript, AVector.from(tokens), additionalData)
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
      _amountGen: Gen[U64]           = amountGen(1),
      heightGen: Gen[Int]            = createdHeightGen,
      scriptGen: Gen[LockupScript]   = p2pkhLockupGen(groupIndex),
      codeGen: Gen[StatefulContract] = Gen.const(counterContract)
  ): Gen[ContractOutput] = {
    for {
      amount        <- _amountGen
      createdHeight <- heightGen
      lockupScript  <- scriptGen
      code          <- codeGen
    } yield ContractOutput(amount, createdHeight, lockupScript, code, ByteString.empty)
  }

  lazy val txOutputGen: Gen[TxOutput] = for {
    value <- Gen.choose[Long](1, 5)
  } yield TxOutput.asset(U64.unsafe(value), 0, LockupScript.p2pkh(Hash.zero))

  def assetInputInfoGen(balances: Balances, scriptGen: Gen[ScriptPair]): Gen[AssetInputInfo] =
    for {
      ScriptPair(lockup, unlock, privateKey) <- scriptGen
      height                                 <- createdHeightGen
      data                                   <- dataGen
      outputHash                             <- hashGen
    } yield {
      val assetOutput =
        AssetOutput(balances.alfAmount, height, lockup, AVector.from(balances.tokens), data)
      val txInput = TxInput(AssetOutputRef.from(assetOutput, outputHash), unlock)
      AssetInputInfo(txInput, assetOutput, privateKey)
    }

  private lazy val noContracts: Gen[AVector[ContractInfo]] =
    Gen.const(()).map(_ => AVector.empty)

  type IndexScriptPairGen   = GroupIndex => Gen[ScriptPair]
  type IndexLockupScriptGen = GroupIndex => Gen[LockupScript]

  def unsignedTxGen(chainIndex: ChainIndex)(
      assetsToSpend: Gen[AVector[AssetInputInfo]],
      contractsToSpend: Gen[AVector[ContractInfo]] = noContracts,
      issueNewToken: Boolean                       = true,
      lockupScriptGen: IndexLockupScriptGen        = p2pkhLockupGen,
      heightGen: Gen[Int]                          = createdHeightGen,
      dataGen: Gen[ByteString]                     = dataGen
  ): Gen[UnsignedTransaction] =
    for {
      assets           <- assetsToSpend
      contracts        <- contractsToSpend
      createdHeight    <- heightGen
      fromLockupScript <- lockupScriptGen(chainIndex.from)
      toLockupScript   <- lockupScriptGen(chainIndex.to)
    } yield {
      val inputs         = assets.map(_.txInput) ++ contracts.map(_.txInput)
      val outputsToSpend = assets.map[TxOutput](_.referredOutput) ++ contracts.map(_.referredOutput)
      val alfAmount      = outputsToSpend.map(_.amount).reduce(_ + _)
      val tokenTable = {
        val tokens = mutable.Map.empty[TokenId, U64]
        assets.foreach(_.referredOutput.tokens.foreach {
          case (tokenId, amount) =>
            val total = tokens.getOrElse(tokenId, U64.Zero)
            tokens.put(tokenId, total + amount)
        })
        tokens
      }
      if (issueNewToken) {
        tokenTable(inputs.head.hash) = nextU64(1, U64.MaxValue)
      }

      val initialBalances = Balances(alfAmount, tokenTable.toMap)
      val outputNum       = min(alfAmount / minAmount, inputs.length * 2, ALF.MaxTxOutputNum).v.toInt
      val splitBalances   = split(initialBalances, outputNum)
      val selectedIndex   = Gen.choose(0, outputNum - 1).sample.get
      val outputs = splitBalances.mapWithIndex[TxOutput] {
        case (balance, index) =>
          val lockupScript =
            if (index equals selectedIndex) toLockupScript
            else {
              Gen.oneOf(fromLockupScript, toLockupScript).sample.get
            }
          balance.toOutput(createdHeight, lockupScript, dataGen.sample.get)
      }
      UnsignedTransaction(None, inputs, outputs, AVector.empty)
    }

  def balancesGen(inputNum: Int, minTokens: Int, maxTokens: Int): Gen[Balances] =
    for {
      alfAmount <- amountGen(inputNum)
      tokens    <- tokensGen(inputNum, minTokens, maxTokens)
    } yield Balances(alfAmount, tokens)

  def assetsToSpendGen(minInputs: Int,
                       maxInputs: Int,
                       minTokens: Int,
                       maxTokens: Int,
                       scriptGen: Gen[ScriptPair]): Gen[AVector[AssetInputInfo]] =
    for {
      inputNum      <- Gen.choose(minInputs, maxInputs)
      totalBalances <- balancesGen(inputNum, minTokens, maxTokens)
      inputs <- {
        val inputBalances = split(totalBalances, inputNum)
        val gens          = inputBalances.toSeq.map(assetInputInfoGen(_, scriptGen))
        Gen.sequence[Seq[AssetInputInfo], AssetInputInfo](gens)
      }
    } yield AVector.from(inputs)

  def transactionGenWithPreOutputs(
      minInputs: Int                  = 1,
      maxInputs: Int                  = 10,
      minTokens: Int                  = 1,
      maxTokens: Int                  = 3,
      issueNewToken: Boolean          = true,
      chainIndexGen: Gen[ChainIndex]  = chainIndexGen,
      scriptGen: IndexScriptPairGen   = p2pkScriptGen,
      lockupGen: IndexLockupScriptGen = p2pkhLockupGen
  ): Gen[(Transaction, AVector[TxInputStateInfo])] =
    for {
      chainIndex <- chainIndexGen
      assetInfos <- assetsToSpendGen(minInputs,
                                     maxInputs,
                                     minTokens,
                                     maxTokens,
                                     scriptGen(chainIndex.from))
      contractInfos <- noContracts
      unsignedTx <- unsignedTxGen(chainIndex)(Gen.const(assetInfos),
                                              Gen.const(contractInfos),
                                              issueNewToken,
                                              lockupGen)
      signatures = assetInfos.map(info =>
        ALFSignatureSchema.sign(unsignedTx.hash.bytes, info.privateKey)) ++
        contractInfos.map(info => ALFSignatureSchema.sign(unsignedTx.hash.bytes, info.privateKey))
    } yield {
      val tx = Transaction(unsignedTx, AVector.empty, signatures)
      val preOutput = assetInfos.map[TxInputStateInfo](identity) ++ contractInfos
        .map[TxInputStateInfo](identity)
      tx -> preOutput
    }

  def transactionGen(
      minInputs: Int                  = 1,
      maxInputs: Int                  = 10,
      minTokens: Int                  = 1,
      maxTokens: Int                  = 3,
      issueNewToken: Boolean          = true,
      chainIndexGen: Gen[ChainIndex]  = chainIndexGen,
      scriptGen: IndexScriptPairGen   = p2pkScriptGen,
      lockupGen: IndexLockupScriptGen = p2pkhLockupGen
  ): Gen[Transaction] =
    transactionGenWithPreOutputs(minInputs,
                                 maxInputs,
                                 minTokens,
                                 maxTokens,
                                 issueNewToken,
                                 chainIndexGen,
                                 scriptGen,
                                 lockupGen).map(_._1)
}
// scalastyle:on parameter.number

trait BlockGenerators extends TxGenerators {
  implicit def groupConfig: GroupConfig
  implicit def consensusConfig: ConsensusConfig

  def blockGen(chainIndex: ChainIndex): Gen[Block] =
    blockGenOf(chainIndex, AVector(Hash.zero))

  def blockGenOf(broker: BrokerGroupInfo): Gen[Block] =
    chainIndexGenRelatedTo(broker).flatMap(blockGen)

  def blockGenNotOf(broker: BrokerGroupInfo): Gen[Block] =
    chainIndexGenNotRelatedTo(broker).flatMap(blockGen)

  def blockGenOf(group: GroupIndex): Gen[Block] =
    chainIndexFrom(group).flatMap(blockGen)

  private def gen(chainIndex: ChainIndex, deps: AVector[Hash], txs: AVector[Transaction]): Block = {
    @tailrec
    def iter(nonce: Long): Block = {
      val block = Block.from(deps, txs, consensusConfig.maxMiningTarget, nonce)
      if (block.chainIndex equals chainIndex) block else iter(nonce + 1)
    }

    iter(0L)
  }

  def blockGenOf(chainIndex: ChainIndex, deps: AVector[Hash]): Gen[Block] =
    for {
      txNum <- Gen.choose(1, 5)
      txs   <- Gen.listOfN(txNum, transactionGen(chainIndexGen = Gen.const(chainIndex)))
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
          val deps          = AVector.fill(groupConfig.depsNum)(prevHash)
          val newHeader     = currentHeader.copy(blockDeps = deps)
          val newBlock      = block.copy(header = newHeader)
          acc :+ newBlock
      }
    }
}

trait ModelGenerators extends BlockGenerators

trait NoIndexModelGeneratorsLike extends ModelGenerators {
  implicit def groupConfig: GroupConfig

  lazy val txInputGen: Gen[TxInput] = groupIndexGen.flatMap(txInputGen(_))

  lazy val blockGen: Gen[Block] =
    chainIndexGen.flatMap(blockGen(_))

  def blockGenOf(deps: AVector[Hash]): Gen[Block] =
    chainIndexGen.flatMap(blockGenOf(_, deps))

  def chainGenOf(length: Int, block: Block): Gen[AVector[Block]] =
    chainIndexGen.flatMap(chainGenOf(_, length, block))

  def chainGenOf(length: Int): Gen[AVector[Block]] =
    chainIndexGen.flatMap(chainGenOf(_, length))
}

trait NoIndexModelGenerators
    extends NoIndexModelGeneratorsLike
    with GroupConfigFixture.Default
    with ConsensusConfigFixture

object ModelGenerators {
  final case class ScriptPair(lockup: LockupScript, unlock: UnlockScript, privateKey: ALFPrivateKey)

  final case class Balances(alfAmount: U64, tokens: Map[TokenId, U64]) {
    def toOutput(createdHeight: Int, lockupScript: LockupScript, data: ByteString): AssetOutput = {
      val tokensVec = AVector.from(tokens)
      AssetOutput(alfAmount, createdHeight, lockupScript, tokensVec, data)
    }
  }

  sealed trait TxInputStateInfo {
    def referredOutput: TxOutput
  }

  case class AssetInputInfo(txInput: TxInput,
                            referredOutput: AssetOutput,
                            privateKey: ALFPrivateKey)
      extends TxInputStateInfo

  case class ContractInfo(txInput: TxInput,
                          referredOutput: ContractOutput,
                          state: AVector[Val],
                          privateKey: ALFPrivateKey)
      extends TxInputStateInfo
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
