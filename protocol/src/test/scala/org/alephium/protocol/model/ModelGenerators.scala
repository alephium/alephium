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
import scala.util.{Random, Sorting}

import akka.util.ByteString
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.crypto.{Byte64, ED25519PublicKey, SecP256K1PublicKey, SecP256R1PublicKey}
import org.alephium.protocol._
import org.alephium.protocol.config._
import org.alephium.protocol.model.ModelGenerators._
import org.alephium.protocol.vm.{LockupScript, PublicKeyLike, StatefulContract, UnlockScript, Val}
import org.alephium.util._

trait LockupScriptGenerators extends Generators {
  import ModelGenerators.ScriptPair

  implicit def groupConfig: GroupConfig

  lazy val dataGen: Gen[ByteString] = for {
    length <- Gen.choose(0, 20)
    bytes  <- Gen.listOfN(length, arbByte.arbitrary)
  } yield ByteString(bytes)

  def p2pkhLockupGen(groupIndex: GroupIndex): Gen[LockupScript.P2PKH] =
    for {
      publicKey <- publicKeyGen(groupIndex)
    } yield LockupScript.p2pkh(publicKey)

  def p2mpkhLockupGen(groupIndex: GroupIndex): Gen[LockupScript.Asset] =
    for {
      numKeys   <- Gen.chooseNum(1, ALPH.MaxKeysInP2MPK)
      keys      <- Gen.listOfN(numKeys, publicKeyGen(groupIndex)).map(AVector.from)
      threshold <- Gen.choose(1, keys.length)
    } yield LockupScript.p2mpkh(keys, threshold).get

  def p2mpkhLockupGen(n: Int, m: Int, groupIndex: GroupIndex): Gen[LockupScript.Asset] = {
    assume(m <= n)
    for {
      publicKey0 <- publicKeyGen(groupIndex)
      moreKeys   <- Gen.listOfN(n, publicKeyGen(groupIndex)).map(AVector.from)
    } yield LockupScript.p2mpkh(publicKey0 +: moreKeys, m).get
  }

  def p2shLockupGen(groupIndex: GroupIndex): Gen[LockupScript.Asset] = {
    hashGen
      .retryUntil { hash =>
        ScriptHint.fromHash(hash).groupIndex.equals(groupIndex)
      }
      .map(LockupScript.p2sh)
  }

  def p2pkLockupGen(groupIndex: GroupIndex): Gen[LockupScript.P2PK] = {
    Gen
      .oneOf(
        Gen.const(()).map(_ => PublicKeyLike.SecP256K1(SecP256K1PublicKey.generate)),
        Gen.const(()).map(_ => PublicKeyLike.SecP256R1(SecP256R1PublicKey.generate)),
        Gen.const(()).map(_ => PublicKeyLike.ED25519(ED25519PublicKey.generate)),
        Gen.const(()).map(_ => PublicKeyLike.WebAuthn(SecP256R1PublicKey.generate))
      )
      .map(LockupScript.p2pk(_, groupIndex))
  }

  def preDanubeLockupGen(groupIndex: GroupIndex): Gen[LockupScript.Asset] = {
    Gen.oneOf(
      p2pkhLockupGen(groupIndex),
      p2mpkhLockupGen(groupIndex),
      p2shLockupGen(groupIndex)
    )
  }

  def assetLockupGen(groupIndex: GroupIndex): Gen[LockupScript.Asset] = {
    Gen.oneOf(preDanubeLockupGen(groupIndex), p2pkLockupGen(groupIndex))
  }

  def p2cLockupGen(groupIndex: GroupIndex): Gen[LockupScript.P2C] = {
    hashGen
      .retryUntil { hash =>
        ScriptHint.fromHash(hash).groupIndex.equals(groupIndex)
      }
      .map { hash =>
        LockupScript.p2c(ContractId.unsafe(hash))
      }
  }

  def p2pkLockPairGen(groupIndex: GroupIndex): Gen[(LockupScript, UnlockScript)] =
    p2pkLockupGen(groupIndex).map((_, UnlockScript.P2PK))

  def p2pkhUnlockGen(groupIndex: GroupIndex): Gen[UnlockScript] =
    p2pkhLockPairGen(groupIndex).map(_._2)

  def p2pkhLockPairGen(groupIndex: GroupIndex): Gen[(LockupScript, UnlockScript)] =
    publicKeyGen(groupIndex).map { publicKey =>
      (LockupScript.p2pkh(publicKey), UnlockScript.p2pkh(publicKey))
    }

  def p2mpkhUnlockGen(n: Int, m: Int, groupIndex: GroupIndex): Gen[UnlockScript] =
    p2mpkhLockPairGen(n, m, groupIndex).map(_._2)

  def p2mpkhLockPairGen(
      n: Int,
      m: Int,
      groupIndex: GroupIndex
  ): Gen[(LockupScript, UnlockScript)] = {
    for {
      publicKey0 <- publicKeyGen(groupIndex)
      moreKeys   <- Gen.listOfN(n, publicKeyGen(groupIndex))
      allKeys = publicKey0 +: moreKeys
      indexedKey <- Gen.pick(m, allKeys.zipWithIndex).map(AVector.from)
    } yield {
      val lockupScript = LockupScript.p2mpkhUnsafe(AVector.from(allKeys), m)
      val unlockScript = UnlockScript.p2mpkh(indexedKey.sortBy(_._2))
      (lockupScript, unlockScript)
    }
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

  lazy val valBoolGen: Gen[Val.Bool]       = arbitrary[Boolean].map(Val.Bool.apply)
  lazy val valI256Gen: Gen[Val.I256]       = i256Gen.map(Val.I256.apply)
  lazy val valU256Gen: Gen[Val.U256]       = u256Gen.map(Val.U256.apply)
  lazy val valByteVecGen: Gen[Val.ByteVec] = dataGen.map(Val.ByteVec.apply)

  def lockupScriptGen(implicit groupConfig: GroupConfig): Gen[LockupScript] =
    for {
      groupIndex   <- groupIndexGen
      lockupScript <- lockupGen(groupIndex)
    } yield lockupScript

  def valAddressGen(implicit groupConfig: GroupConfig): Gen[Val.Address] =
    lockupScriptGen.map(Val.Address(_))

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
    } yield AssetOutputRef.unsafe(Hint.ofAsset(scriptHint), TxOutputRef.unsafeKey(hash))
  }

  def contractOutputRefGen(groupIndex: GroupIndex): Gen[ContractOutputRef] = {
    for {
      scriptHint <- scriptHintGen(groupIndex)
      hash       <- hashGen
    } yield ContractOutputRef.unsafe(Hint.ofContract(scriptHint), TxOutputRef.unsafeKey(hash))
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
      val outputRef = AssetOutputRef.from(scriptHint, TxOutputRef.unsafeKey(hash))
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
      amount <- amountGen(inputNum)
    } yield (TokenId.random, amount)

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
    val outputWithTokenNum = if (balances.tokens.isEmpty) 0 else Random.between(1, outputNum)
    val tokenSplits = balances.tokens.map { case (tokenId, amount) =>
      tokenId -> split(amount, 0, outputWithTokenNum)
    }
    val tokenBalances = AVector.tabulate(outputWithTokenNum) { index =>
      val tokens = tokenSplits.map { case (tokenId, amounts) =>
        tokenId -> amounts(index)
      }
      Balances(dustUtxoAmount, tokens)
    }
    val alphOutputNum = outputNum - outputWithTokenNum
    val alphToSplit =
      balances.attoAlphAmount.subUnsafe(dustUtxoAmount.mulUnsafe(outputWithTokenNum))
    val alphBalances = split(alphToSplit, minAmount, alphOutputNum).map { alphAmount =>
      Balances(alphAmount, Map.empty)
    }
    (tokenBalances ++ alphBalances).shuffle()
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
      dataGen: Gen[ByteString] = dataGen,
      timestampGen: Gen[TimeStamp] = Gen.const(TimeStamp.zero)
  ): Gen[AssetOutput] = {
    for {
      amount         <- _amountGen
      tokens         <- _tokensGen
      lockupScript   <- scriptGen
      timestamp      <- timestampGen
      additionalData <- dataGen
    } yield AssetOutput(amount, lockupScript, timestamp, AVector.from(tokens), additionalData)
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
//    val input =
//      s"""
//         |Contract Foo(mut x: U256) {
//         |  fn add() -> () {
//         |    x = x + 1
//         |    return
//         |  }
//         |}
//         |""".stripMargin
    // Compiled from above script
    StatefulContract(
      1,
      AVector(
        vm.Method.testDefault(
          isPublic = false,
          argsLength = 0,
          localsLength = 0,
          returnLength = 0,
          instrs =
            AVector(vm.LoadMutField(0), vm.U256Const1, vm.U256Add, vm.StoreMutField(0), vm.Return)
        )
      )
    )
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
      outputKey                              <- hashGen
    } yield {
      val assetOutput =
        AssetOutput(balances.attoAlphAmount, lockup, lockTime, AVector.from(balances.tokens), data)
      val txInput =
        TxInput(AssetOutputRef.unsafe(assetOutput.hint, TxOutputRef.unsafeKey(outputKey)), unlock)
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
      val attoAlphAmount = outputsToSpend.map(_.amount).reduce(_ + _) - nonCoinbaseMinGasPrice * gas
      val tokenTable = {
        val tokens = mutable.Map.empty[TokenId, U256]
        assets.foreach(_.referredOutput.tokens.foreach { case (tokenId, amount) =>
          val total = tokens.getOrElse(tokenId, U256.Zero)
          tokens.put(tokenId, total + amount)
        })
        tokens
      }

      val initialBalances = Balances(attoAlphAmount, tokenTable.toMap)
      val outputNum =
        min(attoAlphAmount / minAmount, inputs.length * 2, ALPH.MaxTxOutputNum).v.toInt
      val splitBalances = split(initialBalances, outputNum)
      val selectedIndex = Gen.choose(0, outputNum - 1).sample.get
      val outputs = splitBalances.mapWithIndex[AssetOutput] { case (balance, index) =>
        val lockupScript =
          if (index equals selectedIndex) {
            toLockupScript
          } else {
            Gen.oneOf(fromLockupScript, toLockupScript).sample.get
          }
        balance.toOutput(lockupScript, lockTime, dataGen.sample.get)
      }
      UnsignedTransaction(None, gas, nonCoinbaseMinGasPrice, inputs, outputs)(networkConfig)
    }

  def balancesGen(inputNum: Int, tokensNumGen: Gen[Int]): Gen[Balances] =
    for {
      attoAlphAmount <- amountGen(inputNum)
      tokens         <- tokensGen(inputNum, tokensNumGen)
    } yield Balances(attoAlphAmount, tokens)

  def assetsToSpendGen(
      inputsNumGen: Gen[Int] = Gen.choose(2, 10),
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
      inputsNumGen: Gen[Int] = Gen.choose(2, 10),
      tokensNumGen: Gen[Int] = Gen.choose(0, maxTokenPerAssetUtxo),
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
        assetInfos.map(info => SignatureSchema.sign(unsignedTx.id, info.privateKey))
    } yield {
      val tx = Transaction.from(unsignedTx, signatures.map(Byte64.from))
      tx -> assetInfos
    }

  def transactionGenWithCompressedUnlockScripts(
      unlockScriptsNumGen: Gen[Int] = Gen.choose(1, 4),
      inputsNumGen: Gen[Int] = Gen.choose(2, 4),
      tokensNumGen: Gen[Int] = Gen.const(0),
      chainIndexGen: Gen[ChainIndex] = chainIndexGen,
      scriptGen: IndexScriptPairGen = p2pkScriptGen,
      lockupGen: IndexLockupScriptGen = assetLockupGen,
      lockTimeGen: Gen[TimeStamp] = Gen.const(TimeStamp.zero)
  ): Gen[(Transaction, AVector[AssetInputInfo])] = {
    for {
      chainIndex       <- chainIndexGen
      unlockScriptsNum <- unlockScriptsNumGen
      scriptPairs      <- Gen.listOfN(unlockScriptsNum, scriptGen(chainIndex.from))
      assetInfoss <- Gen.sequence[Seq[AVector[AssetInputInfo]], AVector[AssetInputInfo]](
        (0 until unlockScriptsNum).map { index =>
          assetsToSpendGen(
            inputsNumGen,
            tokensNumGen,
            Gen.const(scriptPairs(index)),
            lockTimeGen
          )
        }
      )
      assetInfos = AVector.from(
        assetInfoss.flatMap(infos =>
          infos.head +: infos.tail.map(info =>
            info.copy(txInput = info.txInput.copy(unlockScript = UnlockScript.SameAsPrevious))
          )
        )
      )
      unsignedTx <- unsignedTxGen(chainIndex)(Gen.const(assetInfos), lockupGen)
    } yield {
      val signatures =
        AVector.from(scriptPairs.map(pair => SignatureSchema.sign(unsignedTx.id, pair.privateKey)))
      val tx = Transaction.from(unsignedTx, signatures.map(Byte64.from))
      tx -> assetInfos
    }
  }

  def transactionGen(
      numInputsGen: Gen[Int] = Gen.choose(2, 10),
      numTokensGen: Gen[Int] = Gen.choose(0, maxTokenPerAssetUtxo),
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
  implicit def consensusConfigs: ConsensusConfigs

  lazy val nonceGen = Gen.const(()).map(_ => Nonce.unsecureRandom())

  def blockGen(
      chainIndex: ChainIndex,
      blockTs: TimeStamp,
      parentHash: BlockHash,
      uncles: AVector[SelectedGhostUncle] = AVector.empty
  ): Gen[Block] = {
    val parentIndex = (groupConfig.groups * 2 - 1) / 2 + chainIndex.to.value
    for {
      depStateHash <- hashGen
      depsArray <- Gen
        .listOfN(2 * groupConfig.groups - 1, blockHashGen)
        .map(_.toArray)
      _ = depsArray(parentIndex) = parentHash
      block <- blockGenOf(
        chainIndex,
        AVector.from(depsArray),
        depStateHash,
        blockTs,
        Gen.const(0),
        uncles
      )
    } yield block
  }

  def blockGen(
      chainIndex: ChainIndex,
      txNumGen: Gen[Int],
      blockTs: TimeStamp
  ): Gen[Block] =
    for {
      depStateHash <- hashGen
      deps <- Gen
        .listOfN(2 * groupConfig.groups - 1, blockHashGen)
        .map(_.toArray)
        .map(AVector.unsafe(_))
      block <- blockGenOf(chainIndex, deps, depStateHash, blockTs, txNumGen, AVector.empty)
    } yield block

  def blockGen(chainIndex: ChainIndex): Gen[Block] = {
    blockGen(chainIndex, TimeStamp.now())
  }

  def blockGen(chainIndex: ChainIndex, blockTs: TimeStamp): Gen[Block] = {
    blockGen(chainIndex, Gen.choose(1, 5), blockTs)
  }

  def blockGenOf(broker: BrokerGroupInfo): Gen[Block] =
    chainIndexGenRelatedTo(broker).flatMap(blockGen(_))

  def blockGenNotOf(broker: BrokerGroupInfo): Gen[Block] =
    chainIndexGenNotRelatedTo(broker).flatMap(blockGen(_))

  def blockGenOf(group: GroupIndex): Gen[Block] =
    chainIndexFrom(group).flatMap(blockGen(_))

  private def gen(
      chainIndex: ChainIndex,
      deps: AVector[BlockHash],
      depStateHash: Hash,
      blockTs: TimeStamp,
      txs: AVector[Transaction],
      uncles: AVector[SelectedGhostUncle]
  ): Block = {
    val consensusConfig = consensusConfigs.getConsensusConfig(blockTs)
    val coinbase = Transaction.powCoinbaseForTest(
      chainIndex,
      txs,
      p2pkhLockupGen(chainIndex.to).sample.get,
      consensusConfig.maxMiningTarget,
      blockTs,
      uncles
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
      blockTs: TimeStamp,
      txNumGen: Gen[Int],
      uncles: AVector[SelectedGhostUncle]
  ): Gen[Block] = {
    for {
      txNum <- txNumGen
      txs   <- Gen.listOfN(txNum, transactionGen(chainIndexGen = Gen.const(chainIndex)))
    } yield gen(chainIndex, deps, depStateHash, blockTs, AVector.from(txs), uncles)
  }

  def chainGenOf(chainIndex: ChainIndex, length: Int, block: Block): Gen[AVector[Block]] =
    chainGenOf(chainIndex, length, block.hash, block.timestamp)

  def chainGenOf(chainIndex: ChainIndex, length: Int): Gen[AVector[Block]] =
    chainGenOf(chainIndex, length, BlockHash.zero, TimeStamp.now())

  def chainGenOf(
      chainIndex: ChainIndex,
      length: Int,
      initialHash: BlockHash,
      initialTs: TimeStamp
  ): Gen[AVector[Block]] =
    Gen.listOfN(length, blockGen(chainIndex)).map { blocks =>
      blocks.zipWithIndex.foldLeft(AVector.empty[Block]) { case (acc, (block, index)) =>
        val prevHash      = if (acc.isEmpty) initialHash else acc.last.hash
        val currentHeader = block.header
        val deps          = BlockDeps.build(AVector.fill(groupConfig.depsNum)(prevHash))
        val newTs         = initialTs.plusUnsafe(Duration.ofSecondsUnsafe(index.toLong + 1))
        val newHeader     = currentHeader.copy(blockDeps = deps, timestamp = newTs)
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
    chainIndexGen.flatMap(blockGen(_, txNumGen, TimeStamp.now()))

  def blockGenOf(deps: AVector[BlockHash], depStateHash: Hash): Gen[Block] =
    chainIndexGen.flatMap(
      blockGenOf(_, deps, depStateHash, TimeStamp.now(), Gen.choose(1, 5), AVector.empty)
    )

  def chainGenOf(length: Int, block: Block): Gen[AVector[Block]] =
    chainIndexGen.flatMap(chainGenOf(_, length, block))

  def chainGenOf(length: Int): Gen[AVector[Block]] =
    chainIndexGen.flatMap(chainGenOf(_, length))

  type GeneratedContract =
    (
        ContractId,
        StatefulContract.HalfDecoded,
        AVector[Val],
        AVector[Val],
        ContractOutputRef,
        ContractOutput
    )
  def generateContract(): Gen[GeneratedContract] = {
    lazy val counterStateGen: Gen[AVector[Val]] =
      Gen.choose(0L, Long.MaxValue / 1000).map(n => AVector(Val.U256(U256.unsafe(n))))
    for {
      groupIndex <- groupIndexGen
      outputRef  <- contractOutputRefGen(groupIndex)
      output     <- contractOutputGen(scriptGen = p2cLockupGen(groupIndex))
      mutState   <- counterStateGen
    } yield (
      ContractId.random,
      counterContract.toHalfDecoded(),
      AVector.empty,
      mutState,
      outputRef,
      output
    )
  }
}

trait NoIndexModelGenerators
    extends NoIndexModelGeneratorsLike
    with GroupConfigFixture.Default
    with ConsensusConfigsFixture.Default
    with NetworkConfigFixture.Default

object ModelGenerators {
  final case class ScriptPair(
      lockup: LockupScript.Asset,
      unlock: UnlockScript,
      privateKey: PrivateKey
  )

  final case class Balances(attoAlphAmount: U256, tokens: Map[TokenId, U256]) {
    def toOutput(
        lockupScript: LockupScript.Asset,
        lockTime: TimeStamp,
        data: ByteString
    ): AssetOutput = {
      val tokensVec = AVector.from(tokens)
      AssetOutput(attoAlphAmount, lockupScript, lockTime, tokensVec, data)
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
