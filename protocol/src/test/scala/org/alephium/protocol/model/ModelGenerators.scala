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

package org.alephium.protocol.model

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Sorting

import akka.util.ByteString
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.protocol._
import org.alephium.protocol.config._
import org.alephium.protocol.model.ModelGenerators._
import org.alephium.protocol.vm.{LockupScript, StatefulContract, UnlockScript, Val}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AlephiumSpec, AVector, I256, Number, NumericHelpers, TimeStamp, U256}

trait LockupScriptGenerators extends Generators {
  import ModelGenerators.ScriptPair

  implicit def groupConfig: GroupConfig

  lazy val dataGen: Gen[ByteString] = for {
    length <- Gen.choose(0, 20)
    bytes  <- Gen.listOfN(length, arbByte.arbitrary)
  } yield ByteString(bytes)

  def p2pkhLockupGen(groupIndex: GroupIndex): Gen[LockupScript.Asset] =
    for {
      publicKey <- publicKeyGen(groupIndex)
    } yield LockupScript.p2pkh(publicKey)

  def p2mpkhLockupGen(groupIndex: GroupIndex): Gen[LockupScript.Asset] =
    for {
      publicKey0 <- publicKeyGen(groupIndex)
      moreKeys   <- Gen.nonEmptyListOf(publicKeyGen(groupIndex)).map(AVector.from)
      threshold  <- Gen.choose(1, moreKeys.length + 1)
    } yield LockupScript.p2mpkh(publicKey0 +: moreKeys, threshold).get

  def p2shLockupGen(groupIndex: GroupIndex): Gen[LockupScript.Asset] = {
    hashGen
      .retryUntil { hash =>
        ScriptHint.fromHash(hash).groupIndex.equals(groupIndex)
      }
      .map(LockupScript.p2sh)
  }

  def assetLockupGen(groupIndex: GroupIndex): Gen[LockupScript.Asset] = {
    Gen.oneOf(
      p2pkhLockupGen(groupIndex),
      p2mpkhLockupGen(groupIndex),
      p2shLockupGen(groupIndex)
    )
  }

  def p2cLockupGen(groupIndex: GroupIndex): Gen[LockupScript.P2C] = {
    hashGen
      .retryUntil { hash =>
        ScriptHint.fromHash(hash).groupIndex.equals(groupIndex)
      }
      .map(LockupScript.p2c)
  }

  def lockupGen(groupIndex: GroupIndex): Gen[LockupScript] = {
    Gen.oneOf(
      p2pkhLockupGen(groupIndex),
      p2mpkhLockupGen(groupIndex),
      p2shLockupGen(groupIndex),
      p2cLockupGen(groupIndex)
    )
  }

  def p2pkScriptGen(groupIndex: GroupIndex): Gen[ScriptPair] =
    for {
      (privateKey, publicKey) <- keypairGen(groupIndex)
    } yield ScriptPair(LockupScript.p2pkh(publicKey), UnlockScript.p2pkh(publicKey), privateKey)

  def addressGen(groupIndex: GroupIndex): Gen[(LockupScript.Asset, PublicKey, PrivateKey)] =
    for {
      (privateKey, publicKey) <- keypairGen(groupIndex)
    } yield (LockupScript.p2pkh(publicKey), publicKey, privateKey)

  def addressStringGen(groupIndex: GroupIndex): Gen[(String, PublicKey, PrivateKey)] =
    addressGen(groupIndex).map { case (script, publicKey, privateKey) =>
      (
        Address.from(script).toBase58,
        publicKey,
        privateKey
      )
    }

  def addressStringGen(implicit groupConfig: GroupConfig): Gen[(String, String, String)] =
    for {
      groupIndex                      <- groupIndexGen
      (script, publicKey, privateKey) <- addressGen(groupIndex)
    } yield {
      (
        Address.from(script).toBase58,
        publicKey.toHexString,
        privateKey.toHexString
      )
    }

  private val i256Gen: Gen[I256] =
    Gen.choose[java.math.BigInteger](I256.MinValue.v, I256.MaxValue.v).map(I256.unsafe)
  private val u256Gen: Gen[U256] =
    Gen.choose[java.math.BigInteger](U256.MinValue.v, U256.MaxValue.v).map(U256.unsafe)

  lazy val valBoolGen: Gen[Val.Bool]       = arbitrary[Boolean].map(Val.Bool.apply)
  lazy val valI256Gen: Gen[Val.I256]       = i256Gen.map(Val.I256.apply)
  lazy val valU256Gen: Gen[Val.U256]       = u256Gen.map(Val.U256.apply)
  lazy val valByteVecGen: Gen[Val.ByteVec] = dataGen.map(Val.ByteVec.apply)

  def valAddressGen(implicit groupConfig: GroupConfig): Gen[Val.Address] =
    for {
      groupIndex   <- groupIndexGen
      lockupScript <- lockupGen(groupIndex)
    } yield Val.Address(lockupScript)

  def vmValGen(implicit groupConfig: GroupConfig): Gen[Val] = {
    Gen.oneOf(
      valBoolGen,
      valI256Gen,
      valU256Gen,
      valByteVecGen,
      valAddressGen
    )
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

  lazy val txInputGen: Gen[TxInput] =
    for {
      index   <- groupIndexGen
      txInput <- txInputGen(index)
    } yield txInput

  def txInputGen(groupIndex: GroupIndex): Gen[TxInput] =
    for {
      scriptHint <- scriptHintGen(groupIndex)
      hash       <- hashGen
    } yield {
      val outputRef = AssetOutputRef.unsafeWithScriptHint(scriptHint, hash)
      TxInput(outputRef, UnlockScript.p2pkh(PublicKey.generate))
    }
}

trait TokenGenerators extends Generators with NumericHelpers {
  val minAmountInNanoAlph = dustUtxoAmount.divUnsafe(ALPH.oneNanoAlph).toBigInt.longValue()
  val minAmount           = ALPH.nanoAlph(minAmountInNanoAlph)
  def amountGen(inputNum: Int): Gen[U256] = {
    Gen.choose(minAmountInNanoAlph * inputNum, Number.quadrillion).map(ALPH.nanoAlph)
  }

  def tokenGen(inputNum: Int): Gen[(TokenId, U256)] =
    for {
      tokenId <- hashGen
      amount  <- amountGen(inputNum)
    } yield (tokenId, amount)

  def tokensGen(inputNum: Int, tokensNumGen: Gen[Int]): Gen[Map[TokenId, U256]] =
    for {
      tokenNum <- tokensNumGen
      tokens   <- Gen.listOfN(tokenNum, tokenGen(inputNum))
    } yield tokens.toMap

  def split(amount: U256, minAmount: U256, num: Int): AVector[U256] = {
    assume(num > 0)
    val remainder = amount - (minAmount * num)
    val pivots    = Array.fill(num + 1)(nextU256(remainder))
    pivots(0) = U256.Zero
    pivots(1) = remainder
    Sorting.quickSort(pivots)
    AVector.tabulate(num)(i => pivots(i + 1) - pivots(i) + minAmount)
  }
  def split(balances: Balances, outputNum: Int): AVector[Balances] = {
    val alphSplits = split(balances.alphAmount, minAmount, outputNum)
    val tokenSplits = balances.tokens.map { case (tokenId, amount) =>
      tokenId -> split(amount, 0, outputNum)
    }
    AVector.tabulate(outputNum) { index =>
      val tokens = tokenSplits.map { case (tokenId, amounts) =>
        tokenId -> amounts(index)
      }
      Balances(alphSplits(index), tokens)
    }
  }
}

// scalastyle:off parameter.number
trait TxGenerators
    extends Generators
    with LockupScriptGenerators
    with TxInputGenerators
    with TokenGenerators {
  implicit def networkConfig: NetworkConfig

  lazy val createdHeightGen: Gen[Int] = Gen.choose(ALPH.GenesisHeight, Int.MaxValue)

  def assetOutputGen(groupIndex: GroupIndex)(
      _amountGen: Gen[U256] = amountGen(1),
      _tokensGen: Gen[Map[TokenId, U256]] = tokensGen(1, Gen.choose(1, 5)),
      scriptGen: Gen[LockupScript.Asset] = assetLockupGen(groupIndex),
      dataGen: Gen[ByteString] = dataGen
  ): Gen[AssetOutput] = {
    for {
      amount         <- _amountGen
      tokens         <- _tokensGen
      lockupScript   <- scriptGen
      additionalData <- dataGen
    } yield AssetOutput(amount, lockupScript, TimeStamp.zero, AVector.from(tokens), additionalData)
  }

  def contractOutputGen(
      _amountGen: Gen[U256] = amountGen(1),
      _tokensGen: Gen[Map[TokenId, U256]] = tokensGen(1, Gen.choose(1, 5)),
      scriptGen: Gen[LockupScript.P2C]
  ): Gen[ContractOutput] = {
    for {
      amount       <- _amountGen
      tokens       <- _tokensGen
      lockupScript <- scriptGen
    } yield ContractOutput(amount, lockupScript, AVector.from(tokens))
  }

  lazy val counterContract: StatefulContract = {
    val input =
      s"""
         |TxContract Foo(mut x: U256) {
         |  fn add() -> () {
         |    x = x + 1
         |    return
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(input).toOption.get
  }

  lazy val assetOutputGen: Gen[AssetOutput] = for {
    value <- Gen.choose[Long](1, 5)
  } yield TxOutput.asset(U256.unsafe(value), LockupScript.p2pkh(Hash.zero))

  def assetInputInfoGen(
      balances: Balances,
      scriptGen: Gen[ScriptPair],
      lockTimeGen: Gen[TimeStamp]
  ): Gen[AssetInputInfo] =
    for {
      ScriptPair(lockup, unlock, privateKey) <- scriptGen
      lockTime                               <- lockTimeGen
      data                                   <- dataGen
      outputHash                             <- hashGen
    } yield {
      val assetOutput =
        AssetOutput(balances.alphAmount, lockup, lockTime, AVector.from(balances.tokens), data)
      val txInput = TxInput(AssetOutputRef.unsafe(assetOutput.hint, outputHash), unlock)
      AssetInputInfo(txInput, assetOutput, privateKey)
    }

  type IndexScriptPairGen   = GroupIndex => Gen[ScriptPair]
  type IndexLockupScriptGen = GroupIndex => Gen[LockupScript.Asset]

  def unsignedTxGen(chainIndex: ChainIndex)(
      assetsToSpend: Gen[AVector[AssetInputInfo]],
      lockupScriptGen: IndexLockupScriptGen = assetLockupGen,
      lockTimeGen: Gen[TimeStamp] = Gen.const(TimeStamp.zero),
      dataGen: Gen[ByteString] = dataGen
  ): Gen[UnsignedTransaction] =
    for {
      assets           <- assetsToSpend
      fromLockupScript <- lockupScriptGen(chainIndex.from)
      toLockupScript   <- lockupScriptGen(chainIndex.to)
      lockTime         <- lockTimeGen
    } yield {
      val inputs         = assets.map(_.txInput)
      val outputsToSpend = assets.map[TxOutput](_.referredOutput)
      val gas            = math.max(minimalGas.value, inputs.length * 20000)
      val alphAmount     = outputsToSpend.map(_.amount).reduce(_ + _) - defaultGasPrice * gas
      val tokenTable = {
        val tokens = mutable.Map.empty[TokenId, U256]
        assets.foreach(_.referredOutput.tokens.foreach { case (tokenId, amount) =>
          val total = tokens.getOrElse(tokenId, U256.Zero)
          tokens.put(tokenId, total + amount)
        })
        tokens
      }

      val initialBalances = Balances(alphAmount, tokenTable.toMap)
      val outputNum       = min(alphAmount / minAmount, inputs.length * 2, ALPH.MaxTxOutputNum).v.toInt
      val splitBalances   = split(initialBalances, outputNum)
      val selectedIndex   = Gen.choose(0, outputNum - 1).sample.get
      val outputs = splitBalances.mapWithIndex[AssetOutput] { case (balance, index) =>
        val lockupScript =
          if (index equals selectedIndex) {
            toLockupScript
          } else {
            Gen.oneOf(fromLockupScript, toLockupScript).sample.get
          }
        balance.toOutput(lockupScript, lockTime, dataGen.sample.get)
      }
      UnsignedTransaction(None, gas, defaultGasPrice, inputs, outputs)(networkConfig)
    }

  def balancesGen(inputNum: Int, tokensNumGen: Gen[Int]): Gen[Balances] =
    for {
      alphAmount <- amountGen(inputNum)
      tokens     <- tokensGen(inputNum, tokensNumGen)
    } yield Balances(alphAmount, tokens)

  def assetsToSpendGen(
      inputsNumGen: Gen[Int] = Gen.choose(1, 10),
      tokensNumGen: Gen[Int] = Gen.choose(0, 10),
      scriptGen: Gen[ScriptPair],
      lockTimeGen: Gen[TimeStamp] = Gen.const(TimeStamp.zero)
  ): Gen[AVector[AssetInputInfo]] =
    for {
      inputNum      <- inputsNumGen
      totalBalances <- balancesGen(inputNum, tokensNumGen)
      inputs <- {
        val inputBalances = split(totalBalances, inputNum)
        val gens          = inputBalances.toSeq.map(assetInputInfoGen(_, scriptGen, lockTimeGen))
        Gen.sequence[Seq[AssetInputInfo], AssetInputInfo](gens)
      }
    } yield AVector.from(inputs)

  def transactionGenWithPreOutputs(
      inputsNumGen: Gen[Int] = Gen.choose(1, 10),
      tokensNumGen: Gen[Int] = Gen.choose(0, 10),
      chainIndexGen: Gen[ChainIndex] = chainIndexGen,
      scriptGen: IndexScriptPairGen = p2pkScriptGen,
      lockupGen: IndexLockupScriptGen = assetLockupGen,
      lockTimeGen: Gen[TimeStamp] = Gen.const(TimeStamp.zero)
  ): Gen[(Transaction, AVector[AssetInputInfo])] =
    for {
      chainIndex <- chainIndexGen
      assetInfos <- assetsToSpendGen(
        inputsNumGen,
        tokensNumGen,
        scriptGen(chainIndex.from),
        lockTimeGen
      )
      unsignedTx <- unsignedTxGen(chainIndex)(Gen.const(assetInfos), lockupGen)
      signatures =
        assetInfos.map(info => SignatureSchema.sign(unsignedTx.hash.bytes, info.privateKey))
    } yield {
      val tx = Transaction.from(unsignedTx, signatures)
      tx -> assetInfos
    }

  def transactionGen(
      numInputsGen: Gen[Int] = Gen.choose(1, 10),
      numTokensGen: Gen[Int] = Gen.choose(0, 10),
      chainIndexGen: Gen[ChainIndex] = chainIndexGen,
      scriptGen: IndexScriptPairGen = p2pkScriptGen,
      lockupGen: IndexLockupScriptGen = assetLockupGen
  ): Gen[Transaction] =
    transactionGenWithPreOutputs(
      numInputsGen,
      numTokensGen,
      chainIndexGen,
      scriptGen,
      lockupGen
    ).map(_._1)
}
// scalastyle:on parameter.number

trait BlockGenerators extends TxGenerators {
  implicit def groupConfig: GroupConfig
  implicit def consensusConfig: ConsensusConfig

  lazy val nonceGen = Gen.const(()).map(_ => Nonce.unsecureRandom())

  def blockGen(chainIndex: ChainIndex, txNumGen: Gen[Int]): Gen[Block] =
    for {
      depStateHash <- hashGen
      deps <- Gen
        .listOfN(2 * groupConfig.groups - 1, blockHashGen)
        .map(_.toArray)
        .map(AVector.unsafe(_))
      block <- blockGenOf(chainIndex, deps, depStateHash, txNumGen)
    } yield block

  def blockGen(chainIndex: ChainIndex): Gen[Block] = {
    blockGen(chainIndex, Gen.choose(1, 5))
  }

  def blockGenOf(broker: BrokerGroupInfo): Gen[Block] =
    chainIndexGenRelatedTo(broker).flatMap(blockGen)

  def blockGenNotOf(broker: BrokerGroupInfo): Gen[Block] =
    chainIndexGenNotRelatedTo(broker).flatMap(blockGen)

  def blockGenOf(group: GroupIndex): Gen[Block] =
    chainIndexFrom(group).flatMap(blockGen)

  private def gen(
      chainIndex: ChainIndex,
      deps: AVector[BlockHash],
      depStateHash: Hash,
      txs: AVector[Transaction]
  ): Block = {
    val blockTs = TimeStamp.now()
    val coinbase = Transaction.coinbase(
      chainIndex,
      txs,
      p2pkhLockupGen(chainIndex.to).sample.get,
      consensusConfig.maxMiningTarget,
      blockTs
    )
    val txsWithCoinbase = txs :+ coinbase
    @tailrec
    def iter(nonce: Long): Block = {
      val block = Block.from(
        deps,
        depStateHash,
        txsWithCoinbase,
        consensusConfig.maxMiningTarget,
        blockTs,
        Nonce.unsecureRandom()
      )
      if (block.chainIndex equals chainIndex) block else iter(nonce + 1)
    }

    iter(0L)
  }

  def blockGenOf(
      chainIndex: ChainIndex,
      deps: AVector[BlockHash],
      depStateHash: Hash,
      txNumGen: Gen[Int]
  ): Gen[Block] =
    for {
      txNum <- txNumGen
      txs   <- Gen.listOfN(txNum, transactionGen(chainIndexGen = Gen.const(chainIndex)))
    } yield gen(chainIndex, deps, depStateHash, AVector.from(txs))

  def chainGenOf(chainIndex: ChainIndex, length: Int, block: Block): Gen[AVector[Block]] =
    chainGenOf(chainIndex, length, block.hash)

  def chainGenOf(chainIndex: ChainIndex, length: Int): Gen[AVector[Block]] =
    chainGenOf(chainIndex, length, BlockHash.zero)

  def chainGenOf(chainIndex: ChainIndex, length: Int, initialHash: BlockHash): Gen[AVector[Block]] =
    Gen.listOfN(length, blockGen(chainIndex)).map { blocks =>
      blocks.foldLeft(AVector.empty[Block]) { case (acc, block) =>
        val prevHash      = if (acc.isEmpty) initialHash else acc.last.hash
        val currentHeader = block.header
        val deps          = BlockDeps.build(AVector.fill(groupConfig.depsNum)(prevHash))
        val newHeader     = currentHeader.copy(blockDeps = deps)
        val newBlock      = block.copy(header = newHeader)
        acc :+ newBlock
      }
    }
}

trait ModelGenerators extends BlockGenerators

trait NoIndexModelGeneratorsLike extends ModelGenerators {
  implicit def groupConfig: GroupConfig

  lazy val blockGen: Gen[Block] =
    chainIndexGen.flatMap(blockGen(_))

  def blockGenOf(txNumGen: Gen[Int]): Gen[Block] =
    chainIndexGen.flatMap(blockGen(_, txNumGen))

  def blockGenOf(deps: AVector[BlockHash], depStateHash: Hash): Gen[Block] =
    chainIndexGen.flatMap(blockGenOf(_, deps, depStateHash, Gen.choose(1, 5)))

  def chainGenOf(length: Int, block: Block): Gen[AVector[Block]] =
    chainIndexGen.flatMap(chainGenOf(_, length, block))

  def chainGenOf(length: Int): Gen[AVector[Block]] =
    chainIndexGen.flatMap(chainGenOf(_, length))
}

trait NoIndexModelGenerators
    extends NoIndexModelGeneratorsLike
    with GroupConfigFixture.Default
    with ConsensusConfigFixture.Default
    with NetworkConfigFixture.Default

object ModelGenerators {
  final case class ScriptPair(
      lockup: LockupScript.Asset,
      unlock: UnlockScript,
      privateKey: PrivateKey
  )

  final case class Balances(alphAmount: U256, tokens: Map[TokenId, U256]) {
    def toOutput(
        lockupScript: LockupScript.Asset,
        lockTime: TimeStamp,
        data: ByteString
    ): AssetOutput = {
      val tokensVec = AVector.from(tokens)
      AssetOutput(alphAmount, lockupScript, lockTime, tokensVec, data)
    }
  }

  case class AssetInputInfo(txInput: TxInput, referredOutput: AssetOutput, privateKey: PrivateKey)
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
