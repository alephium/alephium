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

package org.alephium.protocol.vm

import java.math.BigInteger
import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import akka.util.ByteString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import org.alephium.crypto
import org.alephium.crypto.Byte64
import org.alephium.protocol._
import org.alephium.protocol.config.{NetworkConfig, NetworkConfigFixture}
import org.alephium.protocol.config.NetworkConfigFixture.{Danube, Genesis, Leman}
import org.alephium.protocol.model.{NetworkId => _, _}
import org.alephium.protocol.model.NetworkId.AlephiumMainNet
import org.alephium.serde._
import org.alephium.util._

// scalastyle:off file.size.limit no.equal number.of.methods number.of.types
class InstrSpec extends AlephiumSpec with NumericHelpers {
  import Instr._

  it should "initialize proper bytecodes" in {
    toCode.size is (statelessInstrs0.length + statefulInstrs0.length)
    toCode(CallLocal) is 0
    toCode(CallExternal) is 1
    toCode(Return) is 2
    statelessInstrs0.foreach { instr =>
      toCode(instr) < 160 is true
    }
    statefulInstrs0.foreach {
      case CallExternal => ()
      case instr        => toCode(instr) >= 160 is true
    }
    toCode
  }

  it should "serde properly" in new AllInstrsFixture {
    statelessInstrs.toSet.size is Instr.statelessInstrs0.length
    statefulInstrs.toSet.size is Instr.statefulInstrs0.length
    statelessInstrs.foreach { instr =>
      statelessSerde.deserialize(statelessSerde.serialize(instr)) isE instr
      statefulSerde.deserialize(statefulSerde.serialize(instr)) isE instr
    }
    statefulInstrs.foreach { instr =>
      statefulSerde.deserialize(statefulSerde.serialize(instr)) isE instr
    }
  }

  trait LemanForkFixture extends AllInstrsFixture {
    // format: off
    val lemanStatelessInstrs = AVector[LemanInstr[StatelessContext]](
      ByteVecSlice, ByteVecToAddress, Encode, Zeros,
      U256To1Byte, U256To2Byte, U256To4Byte, U256To8Byte, U256To16Byte, U256To32Byte,
      U256From1Byte, U256From2Byte, U256From4Byte, U256From8Byte, U256From16Byte, U256From32Byte,
      EthEcRecover,
      Log6, Log7, Log8, Log9,
      ContractIdToAddress,
      LoadLocalByIndex, StoreLocalByIndex, Dup, AssertWithErrorCode, Swap,
      vm.BlockHash, DEBUG(AVector.empty), TxGasPrice, TxGasAmount, TxGasFee,
      I256Exp, U256Exp, U256ModExp, VerifyBIP340Schnorr, GetSegregatedSignature, MulModN, AddModN,
      U256ToString, I256ToString, BoolToString
    )
    val lemanStatefulInstrs = AVector[LemanInstr[StatefulContext]](
      MigrateSimple, MigrateWithFields, CopyCreateContractWithToken, BurnToken, LockApprovedAssets,
      CreateSubContract, CreateSubContractWithToken, CopyCreateSubContract, CopyCreateSubContractWithToken,
      LoadMutFieldByIndex, StoreMutFieldByIndex, ContractExists, CreateContractAndTransferToken, CopyCreateContractAndTransferToken,
      CreateSubContractAndTransferToken, CopyCreateSubContractAndTransferToken,
      NullContractAddress, SubContractId, SubContractIdOf, ALPHTokenId,
      LoadImmField(0.toByte), LoadImmFieldByIndex
    )
    // format: on
  }

  trait RhoneForkFixture extends AllInstrsFixture {
    val rhoneStatelessInstrs = AVector[RhoneInstr[StatelessContext]](GroupOfAddress)
    val rhoneStatefulInstrs =
      AVector[RhoneInstr[StatefulContext]](
        PayGasFee,
        MinimalContractDeposit,
        CreateMapEntry(twoBytes),
        MethodSelector(Method.Selector(0)),
        CallExternalBySelector(Method.Selector(0))
      )
  }

  trait DanubeForkFixture extends AllInstrsFixture {
    val danubeStatelessInstrs =
      AVector[DanubeInstr[StatelessContext]](VerifySignature, GetSegregatedWebAuthnSignature)
    val danubeStatefulInstrs = AVector[DanubeInstr[StatefulContext]](
      ExternalCallerContractId,
      ExternalCallerAddress
    )
  }

  it should "check all LemanInstr" in new LemanForkFixture {
    lemanStatelessInstrs.foreach(_.isInstanceOf[LemanInstr[_]] is true)
    lemanStatefulInstrs.foreach(_.isInstanceOf[LemanInstr[_]] is true)
    (statelessInstrs.toSet -- lemanStatelessInstrs.toSet)
      .map(_.isInstanceOf[LemanInstr[_]] is false)
    (statefulInstrs.toSet -- lemanStatefulInstrs.toSet)
      .map(_.isInstanceOf[LemanInstr[_]] is false)
  }

  it should "check all RhoneInstr" in new RhoneForkFixture {
    rhoneStatelessInstrs.foreach(_.isInstanceOf[RhoneInstr[_]] is true)
    rhoneStatefulInstrs.foreach(_.isInstanceOf[RhoneInstr[_]] is true)
    (statelessInstrs.toSet -- rhoneStatelessInstrs.toSet)
      .map(_.isInstanceOf[RhoneInstr[_]] is false)
    (statefulInstrs.toSet -- rhoneStatefulInstrs.toSet)
      .map(_.isInstanceOf[RhoneInstr[_]] is false)
  }

  it should "check all DanubeInstr" in new DanubeForkFixture {
    danubeStatelessInstrs.foreach(_.isInstanceOf[DanubeInstr[_]] is true)
    danubeStatefulInstrs.foreach(_.isInstanceOf[DanubeInstr[_]] is true)
    (statelessInstrs.toSet -- danubeStatelessInstrs.toSet)
      .map(_.isInstanceOf[DanubeInstr[_]] is false)
    (statefulInstrs.toSet -- danubeStatefulInstrs.toSet)
      .map(_.isInstanceOf[DanubeInstr[_]] is false)
  }

  it should "fail if the fork is not activated yet for stateless instrs" in new LemanForkFixture
    with StatelessFixture {
    val frame0 = prepareFrame(AVector.empty)(NetworkConfigFixture.Leman) // Leman is activated
    lemanStatelessInstrs.foreach { instr =>
      val result = instr.runWith(frame0)
      if (result.isLeft) {
        result.leftValue isnotE InactiveInstr(instr)
      }
    }
    val frame1 =
      prepareFrame(AVector.empty)(NetworkConfigFixture.Genesis) // Leman is not activated yet
    lemanStatelessInstrs.foreach(instr => instr.runWith(frame1).leftValue isE InactiveInstr(instr))
  }

  it should "fail if the fork is not activated yet for stateful instrs" in new LemanForkFixture
    with StatefulFixture {
    val frame0 = prepareFrame()(NetworkConfigFixture.Leman) // Leman is activated
    lemanStatefulInstrs.foreach { instr =>
      val result = instr.runWith(frame0)
      if (result.isLeft) {
        result.leftValue isnotE InactiveInstr(instr)
      }
    }
    val frame1 = preparePreLemanFrame()
    lemanStatefulInstrs.foreach(instr => instr.runWith(frame1).leftValue isE InactiveInstr(instr))
  }

  it should "fail if the rhone hardfork is not activated yet for stateful instrs" in new RhoneForkFixture
    with StatefulFixture {
    val frame0 = prepareFrame()(NetworkConfigFixture.Rhone) // Rhone is activated
    rhoneStatefulInstrs.init.foreach { instr =>
      val result = instr.runWith(frame0)
      if (result.isLeft) {
        result.leftValue isnotE InactiveInstr(instr)
      }
    }
    val frame1 = prepareFrame()(NetworkConfigFixture.Leman)
    rhoneStatefulInstrs.foreach(instr => instr.runWith(frame1).leftValue isE InactiveInstr(instr))
    val frame2 = preparePreLemanFrame()
    rhoneStatefulInstrs.foreach(instr => instr.runWith(frame2).leftValue isE InactiveInstr(instr))
  }

  it should "fail if the danube hardfork is not activated yet for stateless instrs" in new DanubeForkFixture
    with StatelessFixture {
    val frame0 = prepareFrame(AVector.empty)(NetworkConfigFixture.Danube) // Danube is activated
    danubeStatelessInstrs.foreach { instr =>
      val result = instr.runWith(frame0)
      if (result.isLeft) {
        result.leftValue isnotE InactiveInstr(instr)
      }
    }
    val frame1 = prepareFrame(AVector.empty)(NetworkConfigFixture.PreDanube)
    danubeStatelessInstrs.foreach(instr => instr.runWith(frame1).leftValue isE InactiveInstr(instr))
  }

  trait GenFixture extends ContextGenerators {
    val lockupScriptGen: Gen[LockupScript] = for {
      group        <- groupIndexGen
      lockupScript <- lockupGen(group)
    } yield lockupScript

    val contractLockupScriptGen: Gen[LockupScript.P2C] = for {
      group <- groupIndexGen
      p2c   <- p2cLockupGen(group)
    } yield p2c

    val assetLockupScriptGen: Gen[LockupScript.Asset] = for {
      group <- groupIndexGen
      asset <- assetLockupGen(group)
    } yield asset
  }

  trait StatelessFixture extends GenFixture {
    lazy val localsLength = 0
    def prepareFrame(
        instrs: AVector[Instr[StatelessContext]],
        blockEnv: Option[BlockEnv] = None,
        txEnv: Option[TxEnv] = None
    )(implicit networkConfig: NetworkConfig): Frame[StatelessContext] = {
      val baseMethod = Method.testDefault(
        isPublic = true,
        argsLength = 0,
        localsLength = localsLength,
        returnLength = 0,
        instrs
      )
      val ctx = genStatelessContext(
        blockEnv = blockEnv,
        txEnv = txEnv
      )
      val obj = StatelessScript.unsafe(AVector(baseMethod)).toObject
      Frame
        .stateless(ctx, obj, obj.getMethod(0).rightValue, Stack.ofCapacity(10), VM.noReturnTo)
        .rightValue
    }
    val addressValGen: Gen[Val.Address] = for {
      group        <- groupIndexGen
      lockupScript <- lockupGen(group)
    } yield Val.Address(lockupScript)
  }

  trait StatelessInstrFixture extends StatelessFixture {
    lazy val frame   = prepareFrame(AVector.empty)
    lazy val stack   = frame.opStack
    lazy val context = frame.ctx
    lazy val locals  = frame.locals

    lazy val mockBlockEnv =
      BlockEnv(
        ChainIndex.randomIntraGroup,
        AlephiumMainNet,
        TimeStamp.now(),
        Target.Max,
        Some(BlockHash.generate)
      )

    def runAndCheckGas[I <: Instr[StatelessContext] with GasSimple](
        instr: I,
        frame: Frame[StatelessContext] = frame
    ) = {
      val context    = frame.ctx
      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      initialGas.subUnsafe(context.gasRemaining) is instr.gas()
    }
  }

  it should "VerifyAbsoluteLocktime" in new StatelessFixture {
    def prepare(timeLock: TimeStamp, blockTs: TimeStamp): Frame[StatelessContext] = {
      val frame = prepareFrame(
        AVector.empty,
        blockEnv = Some(
          BlockEnv(
            ChainIndex.randomIntraGroup,
            AlephiumMainNet,
            blockTs,
            Target.Max,
            Some(BlockHash.generate)
          )
        )
      )
      frame.pushOpStack(Val.U256(timeLock.millis))
      frame
    }

    {
      info("time lock is still locked")
      val lockUntil = TimeStamp.now()
      val blockTime = lockUntil.minusUnsafe(Duration.ofSecondsUnsafe(1))
      val frame     = prepare(lockUntil, blockTime)
      VerifyAbsoluteLocktime.runWith(frame) is failed(
        AbsoluteLockTimeVerificationFailed(lockUntil, blockTime)
      )
    }

    {
      info("time lock is unlocked (1)")
      val now   = TimeStamp.now()
      val frame = prepare(now, now)
      VerifyAbsoluteLocktime.runWith(frame) isE ()
    }

    {
      info("time lock is unlocked (2)")
      val now   = TimeStamp.now()
      val frame = prepare(now, now.plus(Duration.ofSecondsUnsafe(1)).get)
      VerifyAbsoluteLocktime.runWith(frame) isE ()
    }
  }

  it should "VerifyRelativeLocktime" in new StatelessFixture {
    def prepare(
        timeLock: Duration,
        blockTs: TimeStamp,
        txLockTime: TimeStamp,
        txIndex: Int = 0
    ): Frame[StatelessContext] = {
      val (tx, prevOutputs) = transactionGenWithPreOutputs().sample.get
      val frame = prepareFrame(
        AVector.empty,
        blockEnv = Some(
          BlockEnv(
            ChainIndex.randomIntraGroup,
            AlephiumMainNet,
            blockTs,
            Target.Max,
            Some(BlockHash.generate)
          )
        ),
        txEnv = Some(
          TxEnv.dryrun(
            tx,
            prevOutputs.map(_.referredOutput.copy(lockTime = txLockTime)),
            Stack.ofCapacity[Byte64](0)
          )
        )
      )
      frame.pushOpStack(Val.U256(txIndex))
      frame.pushOpStack(Val.U256(timeLock.millis))
      frame
    }

    {
      info("tx inputs are not from worldstate, and the locktime of the UTXOs is zero")
      val frame = prepare(
        timeLock = Duration.ofSecondsUnsafe(1),
        blockTs = TimeStamp.now(),
        txLockTime = TimeStamp.zero
      )
      VerifyRelativeLocktime.runWith(frame) is failed(RelativeLockTimeExpectPersistedUtxo)
    }

    {
      info("the relative lock is still locked")
      val blockTs    = TimeStamp.now()
      val txLockTime = TimeStamp.now()
      val timeLock   = Duration.ofSecondsUnsafe(1)
      val frame      = prepare(timeLock, blockTs, txLockTime)
      VerifyRelativeLocktime.runWith(frame) is failed(
        RelativeLockTimeVerificationFailed(txLockTime.plus(timeLock).value, blockTs)
      )
    }

    {
      info("the relative lock is unlocked (1)")
      val now = TimeStamp.now()
      val frame = prepare(
        timeLock = Duration.ofSecondsUnsafe(1),
        blockTs = now,
        txLockTime = now.minusUnsafe(Duration.ofSecondsUnsafe(1))
      )
      VerifyRelativeLocktime.runWith(frame) isE ()
    }

    {
      info("the relative lock is unlocked (2)")
      val now = TimeStamp.now()
      val frame = prepare(
        timeLock = Duration.ofSecondsUnsafe(1),
        blockTs = now.plus(Duration.ofSecondsUnsafe(2)).get,
        txLockTime = now
      )
      VerifyRelativeLocktime.runWith(frame) isE ()
    }
  }

  trait ConstInstrFixture extends StatelessInstrFixture {
    def test[C <: ConstInstr](constInstr: C, value: Val) = {
      val initialGas = context.gasRemaining
      constInstr.runWith(frame) isE ()
      stack.size is 1
      stack.top.get is value
      initialGas.subUnsafe(context.gasRemaining) is constInstr.gas()
    }
  }

  it should "ConstTrue" in new ConstInstrFixture {
    test(ConstTrue, Val.True)
  }

  it should "ConstFalse" in new ConstInstrFixture {
    test(ConstFalse, Val.False)
  }

  it should "I256Const0" in new ConstInstrFixture {
    test(I256Const0, Val.I256(I256.Zero))
  }

  it should "I256Const1" in new ConstInstrFixture {
    test(I256Const1, Val.I256(I256.One))
  }

  it should "I256Const2" in new ConstInstrFixture {
    test(I256Const2, Val.I256(I256.Two))
  }

  it should "I256Const3" in new ConstInstrFixture {
    test(I256Const3, Val.I256(I256.from(3L)))
  }

  it should "I256Const4" in new ConstInstrFixture {
    test(I256Const4, Val.I256(I256.from(4L)))
  }

  it should "I256Const5" in new ConstInstrFixture {
    test(I256Const5, Val.I256(I256.from(5L)))
  }

  it should "I256ConstN1" in new ConstInstrFixture {
    test(I256ConstN1, Val.I256(I256.NegOne))
  }

  it should "U256Const0" in new ConstInstrFixture {
    test(U256Const0, Val.U256(U256.Zero))
  }

  it should "U256Const1" in new ConstInstrFixture {
    test(U256Const1, Val.U256(U256.One))
  }

  it should "U256Const2" in new ConstInstrFixture {
    test(U256Const2, Val.U256(U256.Two))
  }

  it should "U256Const3" in new ConstInstrFixture {
    test(U256Const3, Val.U256(U256.unsafe(3L)))
  }

  it should "U256Const4" in new ConstInstrFixture {
    test(U256Const4, Val.U256(U256.unsafe(4L)))
  }

  it should "U256Const5" in new ConstInstrFixture {
    test(U256Const5, Val.U256(U256.unsafe(5L)))
  }

  it should "I256Const" in new ConstInstrFixture {
    forAll(arbitrary[Long]) { long =>
      val value = Val.I256(I256.from(long))
      test(I256Const(value), value)
      stack.pop()
    }
  }

  it should "U256Const" in new ConstInstrFixture {
    forAll(posLongGen) { long =>
      val value = Val.U256(U256.unsafe(long))
      test(U256Const(value), value)
      stack.pop()
    }
  }

  it should "BytesConst" in new ConstInstrFixture {
    forAll(dataGen) { data =>
      val value = Val.ByteVec(data)
      test(BytesConst(value), value)
      stack.pop()
    }
  }

  it should "AddressConst" in new ConstInstrFixture {
    forAll(addressValGen) { address =>
      test(AddressConst(address), address)
      stack.pop()
    }
  }

  it should "LoadLocal" in new StatelessInstrFixture {
    override lazy val localsLength = 1

    val bool: Val = Val.Bool(true)
    locals.set(0, bool)

    val instr = LoadLocal(0.toByte)
    runAndCheckGas(instr)
    stack.size is 1
    stack.top.get is bool

    LoadLocal(1.toByte).runWith(frame).leftValue isE InvalidVarIndex(1, 0)
    LoadLocal(-1.toByte).runWith(frame).leftValue isE InvalidVarIndex(255, 0)
  }

  it should "StoreLocal" in new StatelessInstrFixture {
    override lazy val localsLength = 1

    val bool: Val = Val.Bool(true)
    stack.push(bool)

    val instr = StoreLocal(0.toByte)
    runAndCheckGas(instr)
    locals.getUnsafe(0) is bool

    StoreLocal(1.toByte).runWith(frame).leftValue isE StackUnderflow

    stack.push(bool)
    StoreLocal(1.toByte).runWith(frame).leftValue isE InvalidVarIndex(1, 0)
    stack.push(bool)
    StoreLocal(-1.toByte).runWith(frame).leftValue isE InvalidVarIndex(255, 0)
  }

  it should "LoadLocalByIndex" in new StatelessInstrFixture {
    override lazy val localsLength = 1

    val bool: Val = Val.Bool(true)
    locals.set(0, bool)
    locals.getUnsafe(0) is bool

    stack.push(Val.U256(0))
    runAndCheckGas(LoadLocalByIndex)
    stack.size is 1
    stack.top.get is bool

    stack.push(Val.U256(1)) isE ()
    LoadLocalByIndex.runWith(frame).leftValue isE InvalidVarIndex(1, 0)
    stack.push(Val.U256(0xff)) isE ()
    LoadLocalByIndex.popIndex(frame, InvalidVarIndex.apply) isE 0xff
    stack.push(Val.U256(0xff + 1)) isE ()
    LoadLocalByIndex.popIndex(frame, InvalidVarIndex.apply).leftValue isE InvalidVarIndex(
      0xff + 1,
      0xff
    )
  }

  it should "StoreLocalByIndex" in new StatelessInstrFixture {
    override lazy val localsLength: Int = 1

    val bool: Val = Val.Bool(true)
    stack.push(bool)
    stack.push(Val.U256(0))
    runAndCheckGas(StoreLocalByIndex)
    stack.size is 0

    stack.push(bool)
    stack.push(Val.U256(1))
    StoreLocalByIndex.runWith(frame).leftValue isE InvalidVarIndex(1, 0)
    stack.push(bool)
    stack.push(Val.U256(0xff))
    StoreLocalByIndex.popIndex(frame, InvalidVarIndex.apply) isE 0xff
    stack.push(bool)
    stack.push(Val.U256(0xff + 1))
    StoreLocalByIndex.popIndex(frame, InvalidVarIndex.apply).leftValue isE InvalidVarIndex(
      0xff + 1,
      0xff
    )
  }

  it should "Pop" in new StatelessInstrFixture {
    val bool: Val = Val.Bool(true)
    stack.push(bool)

    runAndCheckGas(Pop)
    stack.size is 0

    Pop.runWith(frame).leftValue isE StackUnderflow
  }

  it should "Dup" in new StatelessInstrFixture {
    stack.size is 0
    stack.top is None
    Dup.runWith(frame).leftValue isE StackUnderflow

    val bool: Val = Val.True
    stack.push(bool)
    stack.top is Some(bool)
    stack.size is 1

    runAndCheckGas(Dup)
    stack.top is Some(bool)
    stack.size is 2
  }

  it should "Swap" in new StatelessInstrFixture {
    stack.size is 0
    Swap.runWith(frame).leftValue isE StackUnderflow

    stack.push(Val.True)
    stack.size is 1
    Swap.runWith(frame).leftValue isE StackUnderflow

    stack.push(Val.False)
    stack.size is 2
    stack.underlying.toSeq.take(2) is Seq(Val.True, Val.False)
    runAndCheckGas(Swap)
    stack.size is 2
    stack.underlying.toSeq.take(2) is Seq(Val.False, Val.True)
  }

  it should "BoolNot" in new StatelessInstrFixture {
    val bool: Val = Val.Bool(true)
    stack.push(bool)

    val initialGas = context.gasRemaining
    BoolNot.runWith(frame) isE ()
    stack.top.get is Val.Bool(false)
    initialGas.subUnsafe(context.gasRemaining) is BoolNot.gas()

    val zero = Val.I256(I256.Zero)
    stack.push(zero)
    BoolNot.runWith(frame).leftValue isE InvalidType(Val.Bool, zero)
  }

  trait BinaryBoolFixture extends StatelessInstrFixture {
    def test(binaryBoll: BinaryBool, op: (Boolean, Boolean) => Boolean) = {

      forAll(arbitrary[Boolean], arbitrary[Boolean]) { case (b1, b2) =>
        val bool1: Val = Val.Bool(b1)
        val bool2: Val = Val.Bool(b2)
        stack.push(bool1)
        stack.push(bool2)

        val initialGas = context.gasRemaining

        binaryBoll.runWith(frame) isE ()

        stack.size is 1
        stack.top.get is Val.Bool(op(b1, b2))
        initialGas.subUnsafe(context.gasRemaining) is BoolNot.gas()
        stack.pop()

        stack.push(bool1)
        binaryBoll.runWith(frame).leftValue isE StackUnderflow
      }
    }
  }

  it should "BoolAnd" in new BinaryBoolFixture {
    test(BoolAnd, _ && _)
  }

  it should "BoolOr" in new BinaryBoolFixture {
    test(BoolOr, _ || _)
  }

  it should "BoolEq" in new BinaryBoolFixture {
    test(BoolEq, _ == _)
  }

  it should "BoolNeq" in new BinaryBoolFixture {
    test(BoolNeq, _ != _)
  }

  it should "BoolToByteVec" in new StatelessInstrFixture {
    forAll(arbitrary[Boolean]) { boolean =>
      val bool       = Val.Bool(boolean)
      val initialGas = context.gasRemaining
      val bytes      = serialize(bool)

      stack.push(bool)
      BoolToByteVec.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is Val.ByteVec(bytes)
      initialGas.subUnsafe(context.gasRemaining) is BoolToByteVec.gas(bytes.length)

      stack.pop()
    }
  }

  trait BinaryArithmeticInstrFixture extends StatelessInstrFixture {
    def binaryArithmeticGenTest[A <: Val, B, R](
        instr: BinaryArithmeticInstr[A],
        buildArg: B => A,
        buildRes: R => Val,
        op: (B, B) => R,
        genB: Gen[B]
    ) = {
      binaryArithmeticGenTest[A, B, B, R](instr, buildArg, buildArg, buildRes, op, genB, genB)
    }

    def binaryArithmeticGenTest[T <: Val, B1, B2, R](
        instr: BinaryArithmeticInstr[T],
        buildArg1: B1 => Val,
        buildArg2: B2 => Val,
        buildRes: R => Val,
        op: (B1, B2) => R,
        genB1: Gen[B1],
        genB2: Gen[B2]
    ) = {
      forAll(genB1, genB2) { case (b1, b2) =>
        binaryArithmeticTest[T, B1, B2, R](instr, buildArg1, buildArg2, buildRes, op, b1, b2)
      }
    }

    def binaryArithmeticTest[A <: Val, B, R](
        instr: BinaryArithmeticInstr[A],
        buildArg: B => A,
        buildRes: R => Val,
        op: (B, B) => R,
        b1: B,
        b2: B
    ) = {
      binaryArithmeticTest[A, B, B, R](instr, buildArg, buildArg, buildRes, op, b1, b2)
    }

    def binaryArithmeticTest[T <: Val, B1, B2, R](
        instr: BinaryArithmeticInstr[T],
        buildArg1: B1 => Val,
        buildArg2: B2 => Val,
        buildRes: R => Val,
        op: (B1, B2) => R,
        b1: B1,
        b2: B2
    ) = {
      val a1 = buildArg1(b1)
      val a2 = buildArg2(b2)
      stack.push(a1)
      stack.push(a2)

      val initialGas = context.gasRemaining

      instr.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is buildRes(op(b1, b2))
      initialGas.subUnsafe(context.gasRemaining) is instr.gas()
      stack.pop()

      stack.push(a1)
      instr.runWith(frame).leftValue isE StackUnderflow

      instr match {
        case _: DanubeBitwiseInstr | _: DanubeShiftInstr =>
          stack.push(a1)
          stack.push(Val.False)
          instr.runWith(frame).leftValue.rightValue is a[ArithmeticError]
        case _ => ()
      }
    }

    def binaryArithmeticfail[A <: Val, B](
        instr: BinaryArithmeticInstr[A],
        b1: B,
        b2: B,
        buildArg: B => A
    ) = {
      binaryArithmeticfail[A, B, B](instr, b1, b2, buildArg, buildArg)
    }

    def binaryArithmeticfail[T <: Val, B1, B2](
        instr: BinaryArithmeticInstr[T],
        b1: B1,
        b2: B2,
        buildArg1: B1 => Val,
        buildArg2: B2 => Val
    ) = {
      val a1 = buildArg1(b1)
      val a2 = buildArg2(b2)
      stack.push(a1)
      stack.push(a2)

      instr.runWith(frame).leftValue isE a[ArithmeticError]
    }
  }

  trait I256BinaryArithmeticInstrFixture extends BinaryArithmeticInstrFixture {
    override val i256Gen: Gen[I256] = arbitrary[Long].map(I256.from)

    def testOp(instr: BinaryArithmeticInstr[Val.I256], op: (I256, I256) => I256) = {
      binaryArithmeticGenTest(instr, Val.I256.apply, Val.I256.apply, op, i256Gen)
    }

    def testBitwiseOp(instr: DanubeBitwiseInstr, op: (I256, I256) => I256) = {
      binaryArithmeticGenTest(instr, Val.I256.apply, Val.I256.apply, op, i256Gen)
    }

    def testShiftOp(instr: DanubeShiftInstr, op: (I256, U256) => I256) = {
      binaryArithmeticGenTest(
        instr,
        Val.I256.apply,
        Val.U256.apply,
        Val.I256.apply,
        op,
        i256Gen,
        u256Gen
      )
    }

    def testOp(
        instr: BinaryArithmeticInstr[Val.I256],
        op: (I256, I256) => I256,
        b1: I256,
        b2: I256
    ) = {
      binaryArithmeticTest(instr, Val.I256.apply, Val.I256.apply, op, b1, b2)
    }

    def testShiftOp(
        instr: DanubeShiftInstr,
        op: (I256, U256) => I256,
        b1: I256,
        b2: U256
    ) = {
      binaryArithmeticTest(instr, Val.I256.apply, Val.U256.apply, Val.I256.apply, op, b1, b2)
    }

    def testComp(instr: BinaryArithmeticInstr[Val.I256], comp: (I256, I256) => Boolean) = {
      binaryArithmeticGenTest(instr, Val.I256.apply, Val.Bool.apply, comp, i256Gen)
    }

    def fail(instr: BinaryArithmeticInstr[Val.I256], b1: I256, b2: I256) = {
      binaryArithmeticfail(instr, b1, b2, Val.I256.apply)
    }

    def fail(instr: DanubeShiftInstr, b1: I256, b2: U256) = {
      binaryArithmeticfail(instr, b1, b2, Val.I256.apply, Val.U256.apply)
    }
  }

  it should "I256Add" in new I256BinaryArithmeticInstrFixture {
    testOp(I256Add, _ addUnsafe _)
    fail(I256Add, I256.MaxValue, I256.One)
    fail(I256Add, I256.MinValue, I256.NegOne)
  }

  it should "I256Sub" in new I256BinaryArithmeticInstrFixture {
    testOp(I256Sub, _ subUnsafe _)
    fail(I256Sub, I256.MinValue, I256.One)
    fail(I256Sub, I256.MaxValue, I256.NegOne)
  }

  it should "I256Mul" in new I256BinaryArithmeticInstrFixture {
    testOp(I256Mul, _ mulUnsafe _)
    fail(I256Mul, I256.MaxValue, I256.Two)
    fail(I256Mul, I256.MinValue, I256.Two)
  }

  it should "I256Div" in new I256BinaryArithmeticInstrFixture {
    override val i256Gen: Gen[I256] = arbitrary[Long].retryUntil(_ != 0).map(I256.from)
    testOp(I256Div, _ divUnsafe _)
    testOp(I256Div, _ divUnsafe _, I256.Zero, I256.One)
    fail(I256Div, I256.One, I256.Zero)
    fail(I256Div, I256.MinValue, I256.NegOne)
    testOp(I256Div, _ divUnsafe _, I256.NegOne, I256.MinValue)
  }

  it should "I256Mod" in new I256BinaryArithmeticInstrFixture {
    override val i256Gen: Gen[I256] = arbitrary[Long].retryUntil(_ != 0).map(I256.from)
    testOp(I256Mod, _ modUnsafe _)
    testOp(I256Mod, _ modUnsafe _, I256.Zero, I256.One)
    fail(I256Mod, I256.One, I256.Zero)
  }

  it should "I256Eq" in new I256BinaryArithmeticInstrFixture {
    testComp(I256Eq, _ == _)
  }

  it should "I256Neq" in new I256BinaryArithmeticInstrFixture {
    testComp(I256Neq, _ != _)
  }

  it should "I256Lt" in new I256BinaryArithmeticInstrFixture {
    testComp(I256Lt, _ < _)
  }

  it should "I256Le" in new I256BinaryArithmeticInstrFixture {
    testComp(I256Le, _ <= _)
  }

  it should "I256Gt" in new I256BinaryArithmeticInstrFixture {
    testComp(I256Gt, _ > _)
  }

  it should "I256Ge" in new I256BinaryArithmeticInstrFixture {
    testComp(I256Ge, _ >= _)
  }

  it should "I256BitAnd" in new I256BinaryArithmeticInstrFixture {
    testBitwiseOp(NumericBitAnd, _ bitAnd _)
  }

  it should "I256BitOr" in new I256BinaryArithmeticInstrFixture {
    testBitwiseOp(NumericBitOr, _ bitOr _)
  }

  it should "I256Xor" in new I256BinaryArithmeticInstrFixture {
    testBitwiseOp(NumericXor, _ xor _)
  }

  it should "I256SHL" in new I256BinaryArithmeticInstrFixture {
    testShiftOp(NumericSHL, (x, y) => x.shl(y).get, I256.HalfMaxValue, U256.One)
    fail(NumericSHL, I256.MaxValue, U256.One)
  }

  it should "I256SHR" in new I256BinaryArithmeticInstrFixture {
    testShiftOp(NumericSHR, _ shr _)
  }

  it should "test I256 instrs before Danube" in new I256BinaryArithmeticInstrFixture {
    override lazy val frame = prepareFrame(AVector.empty)(NetworkConfigFixture.PreDanube)
    Seq(NumericBitAnd, NumericBitOr, NumericXor).foreach { instr =>
      stack.push(Val.I256(i256Gen.sample.get))
      stack.push(Val.I256(i256Gen.sample.get))
      instr
        .runWith(frame)
        .leftValue
        .rightValue
        .toString
        .contains("is not enabled before Danube") is true
    }

    Seq(NumericSHL, NumericSHR).foreach { instr =>
      stack.push(Val.I256(i256Gen.sample.get))
      stack.push(Val.U256(u256Gen.sample.get))
      instr
        .runWith(frame)
        .leftValue
        .rightValue
        .toString
        .contains("is not enabled before Danube") is true
    }
  }

  trait U256BinaryArithmeticInstrFixture extends BinaryArithmeticInstrFixture {
    override val u256Gen: Gen[U256] = posLongGen.map(U256.unsafe)

    def testOp(instr: BinaryArithmeticInstr[Val.U256], op: (U256, U256) => U256) = {
      binaryArithmeticGenTest(instr, Val.U256.apply, Val.U256.apply, op, u256Gen)
    }

    def testOp(instr: DanubeBinaryArithmeticInstr, op: (U256, U256) => U256) = {
      binaryArithmeticGenTest(instr, Val.U256.apply, Val.U256.apply, op, u256Gen)
    }

    def testOp(
        instr: BinaryArithmeticInstr[Val.U256],
        op: (U256, U256) => U256,
        b1: U256,
        b2: U256
    ) = {
      binaryArithmeticTest(instr, Val.U256.apply, Val.U256.apply, op, b1, b2)
    }

    def testOp(
        instr: DanubeBinaryArithmeticInstr,
        op: (U256, U256) => U256,
        b1: U256,
        b2: U256
    ) = {
      binaryArithmeticTest(instr, Val.U256.apply, Val.U256.apply, op, b1, b2)
    }

    def testComp(instr: BinaryArithmeticInstr[Val.U256], comp: (U256, U256) => Boolean) = {
      binaryArithmeticGenTest(instr, Val.U256.apply, Val.Bool.apply, comp, u256Gen)
    }

    def fail(instr: BinaryArithmeticInstr[Val.U256], b1: U256, b2: U256) = {
      binaryArithmeticfail(instr, b1, b2, Val.U256.apply)
    }

    def fail(instr: DanubeBinaryArithmeticInstr, b1: U256, b2: U256) = {
      binaryArithmeticfail(instr, b1, b2, Val.U256.apply)
    }
  }

  it should "U256Add" in new U256BinaryArithmeticInstrFixture {
    testOp(U256Add, _ addUnsafe _)
    fail(U256Add, U256.MaxValue, U256.One)
  }

  it should "U256Sub" in new U256BinaryArithmeticInstrFixture {
    testOp(U256Sub, _ subUnsafe _, U256.Ten, U256.One)
    testOp(U256Sub, _ subUnsafe _, U256.One, U256.One)
    testOp(U256Sub, _ subUnsafe _, U256.MaxValue, U256.MaxValue)
    fail(U256Sub, U256.MinValue, U256.One)
    fail(U256Sub, U256.Zero, U256.One)
    fail(U256Sub, U256.One, U256.Two)
  }

  it should "U256Mul" in new U256BinaryArithmeticInstrFixture {
    testOp(U256Mul, _ mulUnsafe _)
    fail(U256Mul, U256.MaxValue, U256.Two)
  }

  it should "U256Div" in new U256BinaryArithmeticInstrFixture {
    override val u256Gen: Gen[U256] = posLongGen.retryUntil(_ != 0).map(U256.unsafe)
    testOp(U256Div, _ divUnsafe _)
    testOp(U256Div, _ divUnsafe _, U256.Zero, U256.One)
    fail(U256Div, U256.One, U256.Zero)
  }

  it should "U256Mod" in new U256BinaryArithmeticInstrFixture {
    override val u256Gen: Gen[U256] = posLongGen.retryUntil(_ != 0).map(U256.unsafe)
    testOp(U256Mod, _ modUnsafe _)
    testOp(U256Mod, _ modUnsafe _, U256.Zero, U256.One)
    fail(U256Mod, U256.One, U256.Zero)
  }

  it should "U256Eq" in new U256BinaryArithmeticInstrFixture {
    testComp(U256Eq, _ == _)
  }

  it should "U256Neq" in new U256BinaryArithmeticInstrFixture {
    testComp(U256Neq, _ != _)
  }

  it should "U256Lt" in new U256BinaryArithmeticInstrFixture {
    testComp(U256Lt, _ < _)
  }

  it should "U256Le" in new U256BinaryArithmeticInstrFixture {
    testComp(U256Le, _ <= _)
  }

  it should "U256Gt" in new U256BinaryArithmeticInstrFixture {
    testComp(U256Gt, _ > _)
  }

  it should "U256Ge" in new U256BinaryArithmeticInstrFixture {
    testComp(U256Ge, _ >= _)
  }

  it should "U256ModAdd" in new U256BinaryArithmeticInstrFixture {
    testOp(U256ModAdd, _ modAdd _)
  }

  it should "U256ModSub" in new U256BinaryArithmeticInstrFixture {
    testOp(U256ModSub, _ modSub _)
  }

  it should "U256ModMul" in new U256BinaryArithmeticInstrFixture {
    testOp(U256ModMul, _ modMul _)
  }

  it should "U256BitAnd" in new U256BinaryArithmeticInstrFixture {
    testOp(NumericBitAnd, _ bitAnd _)
  }

  it should "U256BitOr" in new U256BinaryArithmeticInstrFixture {
    testOp(NumericBitOr, _ bitOr _)
  }

  it should "U256Xor" in new U256BinaryArithmeticInstrFixture {
    testOp(NumericXor, _ xor _)
  }

  it should "U256SHL Pre-Danube" in new U256BinaryArithmeticInstrFixture {
    override lazy val frame = prepareFrame(AVector.empty)(NetworkConfigFixture.PreDanube)
    testOp(NumericSHL, _ shlDeprecated _)
  }

  it should "U256SHL Danube" in new U256BinaryArithmeticInstrFixture {
    override lazy val frame = prepareFrame(AVector.empty)(NetworkConfigFixture.Danube)
    testOp(NumericSHL, (x, y) => x.shl(y).get, U256.HalfMaxValue, U256.One)
    fail(NumericSHL, U256.MaxValue, U256.One)
  }

  it should "U256SHR" in new U256BinaryArithmeticInstrFixture {
    testOp(NumericSHR, _ shr _)
  }

  trait ExpArithmeticInstrFixture extends StatelessInstrFixture {
    val expGen: Gen[U256] = Gen.choose(0, 200).map(U256.unsafe)

    def test[T <: Val](
        instr: ExpInstr[T],
        baseGen: Gen[T],
        expGen: Gen[U256],
        check: (T, U256) => Either[ExeFailure, T]
    ) = {
      forAll(baseGen, expGen) { case (base, exp) =>
        stack.push(base)
        stack.push(Val.U256(exp))

        check(base, exp) match {
          case Left(error) =>
            instr.runWith(frame).leftValue is Right(error)
            stack.pop(2)
          case Right(result) =>
            val initialGas = context.gasRemaining
            instr.runWith(frame) isE ()

            stack.size is 1
            stack.top.get is result
            initialGas.subUnsafe(context.gasRemaining) is instr.gas(exp.byteLength())

            stack.pop()
        }
      }
    }
  }

  it should "I256Exp" in new ExpArithmeticInstrFixture {
    val baseGen: Gen[Val.I256] = Gen.choose(-200, 200).map(v => Val.I256(I256.from(v)))
    test(
      I256Exp,
      baseGen,
      expGen,
      (base: Val.I256, e: U256) => {
        val exp = e.toIntUnsafe
        base.v
          .pow(exp)
          .map(v => Val.I256(v))
          .toRight(ArithmeticError(s"Exp overflow: $base ** $exp"))
      }
    )
  }

  it should "U256Exp" in new ExpArithmeticInstrFixture {
    val baseGen: Gen[Val.U256] = Gen.choose(0, 200).map(v => Val.U256(U256.unsafe(v)))
    test(
      U256Exp,
      baseGen,
      expGen,
      (base: Val.U256, e: U256) => {
        val exp = e.toIntUnsafe
        base.v
          .pow(exp)
          .map(v => Val.U256(v))
          .toRight(ArithmeticError(s"Exp overflow: $base ** $exp"))
      }
    )
  }

  it should "U256ModExp" in new ExpArithmeticInstrFixture {
    val baseGen: Gen[Val.U256] = Gen.choose(0, 200).map(v => Val.U256(U256.unsafe(v)))
    test(
      U256ModExp,
      baseGen,
      expGen,
      (base: Val.U256, e: U256) => Right(Val.U256(base.v.modPow(e.toIntUnsafe)))
    )
  }

  trait ModNInstrFixture extends StatelessInstrFixture {
    def test(instr: ModNInstr, op: (Val.U256, Val.U256, Val.U256) => Val.U256) = {
      val nonZeroU256 = valU256Gen.retryUntil(_.v.nonZero)
      forAll(valU256Gen, valU256Gen, nonZeroU256) { case (x, y, n) =>
        stack.push(x)
        stack.push(y)
        stack.push(n)

        val initialGas = context.gasRemaining
        instr.runWith(frame) isE ()

        stack.size is 1
        stack.top.get is op(x, y, n)
        initialGas.subUnsafe(context.gasRemaining) is instr.gas()

        stack.pop()
      }

      stack.push(Val.U256(U256.Two))
      stack.push(Val.U256(U256.Two))
      stack.push(Val.U256(U256.Zero))

      instr.runWith(frame).leftValue isE a[ArithmeticError]
    }
  }

  it should "MulModN" in new ModNInstrFixture {
    test(MulModN, (x, y, n) => Val.U256(x.v.mulModN(y.v, n.v).get))
  }

  it should "AddModN" in new ModNInstrFixture {
    test(AddModN, (x, y, n) => Val.U256(x.v.addModN(y.v, n.v).get))
  }

  it should "I256ToU256" in new StatelessInstrFixture {
    override val i256Gen: Gen[I256] = posLongGen.map(I256.from)

    forAll(i256Gen) { i256 =>
      val value = Val.I256(i256)
      stack.push(value)

      val initialGas = context.gasRemaining
      I256ToU256.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is Val.U256(U256.fromI256(i256).get)
      initialGas.subUnsafe(context.gasRemaining) is I256ToU256.gas()

      stack.pop()
    }

    val negI256Gen: Gen[I256] = negLongGen.map(I256.from)

    forAll(negI256Gen) { i256 =>
      val value = Val.I256(i256)
      stack.push(value)
      I256ToU256.runWith(frame).leftValue isE a[InvalidConversion]
      stack.pop()
    }
  }

  it should "I256ToByteVec" in new StatelessInstrFixture {
    forAll(i256Gen) { i256 =>
      val value = Val.I256(i256)
      stack.push(value)

      val initialGas = context.gasRemaining
      I256ToByteVec.runWith(frame) isE ()

      val bytes = serialize(i256)
      stack.size is 1
      stack.top.get is Val.ByteVec(bytes)
      initialGas.subUnsafe(context.gasRemaining) is I256ToByteVec.gas(bytes.length)

      stack.pop()
    }
  }

  it should "U256ToI256" in new StatelessInstrFixture {
    override val u256Gen: Gen[U256] = posLongGen.map(U256.unsafe)

    forAll(u256Gen) { u256 =>
      val value = Val.U256(u256)
      stack.push(value)

      val initialGas = context.gasRemaining
      U256ToI256.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is Val.I256(I256.fromU256(u256).get)
      initialGas.subUnsafe(context.gasRemaining) is U256ToI256.gas()

      stack.pop()

      stack.push(Val.U256(U256.MaxValue))
      U256ToI256.runWith(frame).leftValue isE a[InvalidConversion]
    }
  }

  it should "U256ToByteVec" in new StatelessInstrFixture {
    forAll(u256Gen) { u256 =>
      val value = Val.U256(u256)
      stack.push(value)

      val initialGas = context.gasRemaining
      U256ToByteVec.runWith(frame) isE ()

      val bytes = serialize(u256)
      stack.size is 1
      stack.top.get is Val.ByteVec(bytes)
      initialGas.subUnsafe(context.gasRemaining) is U256ToByteVec.gas(bytes.length)

      stack.pop()
    }
  }

  trait ByteVecCompFixture extends StatelessInstrFixture {
    def test(
        instr: ByteVecComparison,
        op: (ByteString, ByteString) => Boolean,
        sameByteVecComp: Boolean
    ) = {
      forAll(dataGen) { data =>
        val value = Val.ByteVec(data)

        stack.push(value)
        stack.push(value)

        val initialGas = context.gasRemaining
        instr.runWith(frame) isE ()

        stack.size is 1
        stack.top.get is Val.Bool(sameByteVecComp)
        initialGas.subUnsafe(context.gasRemaining) is instr.gas(data.length)

        stack.pop()
      }

      forAll(dataGen, dataGen) { case (data1, data2) =>
        val value1 = Val.ByteVec(data1)
        val value2 = Val.ByteVec(data2)

        stack.push(value1)
        stack.push(value2)

        val initialGas = context.gasRemaining
        instr.runWith(frame) isE ()

        stack.size is 1
        stack.top.get is Val.Bool(op(data1, data2))
        initialGas.subUnsafe(context.gasRemaining) is instr.gas(data2.length)

        stack.pop()
      }

      stack.push(Val.ByteVec(dataGen.sample.get))
      instr.runWith(frame).leftValue isE StackUnderflow
    }
  }

  it should "ByteVecEq" in new ByteVecCompFixture {
    test(ByteVecEq, _ == _, true)
  }

  it should "ByteVecNeq" in new ByteVecCompFixture {
    test(ByteVecNeq, _ != _, false)
  }

  it should "ByteVecSize" in new StatelessInstrFixture {
    forAll(dataGen) { data =>
      val value = Val.ByteVec(data)

      stack.push(value)

      val initialGas = context.gasRemaining
      ByteVecSize.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is Val.U256(U256.unsafe(data.length))
      initialGas.subUnsafe(context.gasRemaining) is ByteVecSize.gas()

      stack.pop()
    }
  }

  it should "ByteVecConcat" in new StatelessInstrFixture {
    forAll(dataGen, dataGen) { case (data1, data2) =>
      val value1 = Val.ByteVec(data1)
      val value2 = Val.ByteVec(data2)
      val concat = data1 ++ data2

      stack.push(value1)
      stack.push(value2)

      val initialGas = context.gasRemaining
      ByteVecConcat.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is Val.ByteVec(concat)
      initialGas.subUnsafe(context.gasRemaining) is ByteVecConcat.gas(concat.length)

      stack.pop()
    }

    stack.push(Val.ByteVec(dataGen.sample.get))
    ByteVecNeq.runWith(frame).leftValue isE StackUnderflow
  }

  it should "ByteVecSlice" in new StatelessInstrFixture {
    def prepare(bytes: ByteString, begin: Int, end: Int) = {
      stack.push(Val.ByteVec(bytes))
      stack.push(Val.U256(begin))
      stack.push(Val.U256(end))
    }

    val bytes = ByteString(Array[Byte](1, 2, 3, 4))
    // The type is U256 and cannot be less than 0
    val invalidArgs = Seq((0, 5), (3, 2))
    invalidArgs.foreach { case (begin, end) =>
      prepare(bytes, begin, end)
      ByteVecSlice.runWith(frame).leftValue isE InvalidBytesSliceArg
      stack.pop(3)
    }
    val validArgs = Seq((0, 4), (1, 3), (3, 3))
    validArgs.foreach { case (begin, end) =>
      prepare(bytes, begin, end)

      val slice      = bytes.slice(begin, end)
      val initialGas = context.gasRemaining
      ByteVecSlice.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is Val.ByteVec(slice)
      initialGas.subUnsafe(context.gasRemaining) is ByteVecSlice.gas(slice.length)
      stack.pop()
    }
  }

  it should "Encode" in new StatelessInstrFixture {
    stack.push(Val.True)
    stack.push(Val.U256(U256.One))
    stack.push(Val.False)
    stack.push(Val.U256(U256.MaxValue))
    Encode.runWith(frame).leftValue isE InvalidLengthForEncodeInstr

    stack.push(Val.U256(U256.unsafe(3)))
    Encode.runWith(frame) isE ()
    stack.pop() isE Val.ByteVec(Hex.unsafe("03000102010000"))
  }

  it should "Zeros" in new StatelessInstrFixture {
    stack.push(Val.U256(U256.unsafe(4097)))
    Zeros.runWith(frame).leftValue isE InvalidSizeForZeros
    stack.push(Val.U256(U256.unsafe(4096)))
    Zeros.runWith(frame) isE ()
    stack.pop() isE Val.ByteVec(ByteString.fromArrayUnsafe(Array.fill(4096)(0)))
  }

  it should "ByteVecToAddress" in new StatelessInstrFixture {
    forAll(lockupScriptGen) { lockupScript =>
      val address    = Val.Address(lockupScript)
      val bytes      = serialize(address)
      val initialGas = context.gasRemaining

      stack.push(Val.ByteVec(bytes))
      ByteVecToAddress.runWith(frame) isE ()
      stack.size is 1
      stack.top.get is address
      initialGas.subUnsafe(context.gasRemaining) is ByteVecToAddress.gas(bytes.length)
      stack.pop()
    }

    Seq(32, 34).foreach { n =>
      val byteVec = Val.ByteVec(ByteString(Gen.listOfN(n, arbitrary[Byte]).sample.get))
      stack.push(byteVec)
      ByteVecToAddress.runWith(frame).leftValue isE a[SerdeErrorByteVecToAddress]
    }
  }

  it should "ContractIdToAddress" in new StatelessInstrFixture {
    val p2cLockupScriptGen: Gen[LockupScript.P2C] = for {
      group        <- groupIndexGen
      lockupScript <- p2cLockupGen(group)
    } yield lockupScript

    forAll(p2cLockupScriptGen) { lockupScript =>
      val bytes      = lockupScript.contractId.bytes
      val address    = Val.Address(lockupScript)
      val initialGas = context.gasRemaining

      stack.push(Val.ByteVec(bytes))
      ContractIdToAddress.runWith(frame) isE ()
      stack.size is 1
      stack.top.get is address
      initialGas.subUnsafe(context.gasRemaining) is ContractIdToAddress.gas()
      stack.pop()
    }

    Seq(31, 33).foreach { n =>
      val byteVec = Val.ByteVec(ByteString(Gen.listOfN(n, arbitrary[Byte]).sample.get))
      stack.push(byteVec)
      ContractIdToAddress.runWith(frame).leftValue isE InvalidContractId.from(byteVec)
    }
  }

  trait AddressCompFixture extends StatelessInstrFixture {
    def test(
        instr: ComparisonInstr[Val.Address],
        op: (LockupScript, LockupScript) => Boolean,
        sameAddressComp: Boolean
    ) = {
      forAll(addressValGen) { address =>
        stack.push(address)
        stack.push(address)

        val initialGas = context.gasRemaining
        instr.runWith(frame) isE ()
        initialGas.subUnsafe(context.gasRemaining) is instr.gas()

        stack.size is 1
        stack.top.get is Val.Bool(sameAddressComp)
        stack.pop()
      }

      forAll(addressValGen, addressValGen) { case (address1, address2) =>
        stack.push(address1)
        stack.push(address2)

        val initialGas = context.gasRemaining
        instr.runWith(frame) isE ()
        initialGas.subUnsafe(context.gasRemaining) is instr.gas()

        stack.size is 1
        stack.top.get is Val.Bool(op(address1.lockupScript, address2.lockupScript))
        stack.pop()
      }

      stack.push(addressValGen.sample.get)
      instr.runWith(frame).leftValue isE StackUnderflow
    }
  }

  it should "AddressEq" in new AddressCompFixture {
    test(AddressEq, _ == _, true)
  }

  it should "AddressNeq" in new AddressCompFixture {
    test(AddressNeq, _ != _, false)
  }

  it should "AddressToByteVec" in new StatelessInstrFixture {
    forAll(addressValGen) { address =>
      stack.push(address)

      val initialGas = context.gasRemaining
      AddressToByteVec.runWith(frame) isE ()

      val bytes = serialize(address)
      stack.size is 1
      stack.top.get is Val.ByteVec(bytes)
      initialGas.subUnsafe(context.gasRemaining) is AddressToByteVec.gas(bytes.length)

      stack.pop()
    }
  }

  it should "IsAssetAddress" in new StatelessInstrFixture {
    forAll(addressValGen) { address =>
      stack.push(address)

      val initialGas = context.gasRemaining
      IsAssetAddress.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is Val.Bool(address.lockupScript.isAssetType)
      initialGas.subUnsafe(context.gasRemaining) is IsAssetAddress.gas()

      stack.pop()
    }
  }

  it should "IsContractAddress" in new StatelessInstrFixture {
    forAll(addressValGen) { address =>
      stack.push(address)

      val initialGas = context.gasRemaining
      IsContractAddress.runWith(frame) isE ()

      stack.size is 1
      stack.top.get is Val.Bool(!address.lockupScript.isAssetType)
      initialGas.subUnsafe(context.gasRemaining) is IsContractAddress.gas()

      stack.pop()
    }
  }

  it should "Jump" in new StatelessInstrFixture {
    Jump(0).runWith(frame).leftValue isE InvalidInstrOffset

    val newFrame = prepareFrame(AVector(ConstTrue, ConstTrue, ConstTrue))

    Jump(-1).runWith(newFrame).leftValue isE InvalidInstrOffset

    Jump(0).runWith(newFrame) isE ()
    newFrame.pc is 0

    Jump(2).runWith(newFrame) isE ()
    newFrame.pc is 2

    Jump(-1).runWith(newFrame) isE ()
    newFrame.pc is 1

    Jump(2).runWith(newFrame).leftValue isE InvalidInstrOffset
  }

  it should "IfTrue" in new StatelessInstrFixture {
    override lazy val frame = prepareFrame(AVector(ConstTrue, ConstTrue))

    stack.push(Val.Bool(false))
    stack.push(Val.Bool(true))

    val initialGas = context.gasRemaining
    IfTrue(1).runWith(frame) isE ()
    frame.pc is 1
    stack.size is 1
    initialGas.subUnsafe(context.gasRemaining) is IfTrue(1).gas()

    IfTrue(-1).runWith(frame) isE ()
    frame.pc is 1
    stack.size is 0

    IfTrue(0).runWith(frame).leftValue isE StackUnderflow
  }

  it should "IfFalse" in new StatelessInstrFixture {
    override lazy val frame = prepareFrame(AVector(ConstTrue, ConstTrue))

    stack.push(Val.Bool(true))
    stack.push(Val.Bool(false))

    val initialGas = context.gasRemaining
    IfFalse(1).runWith(frame) isE ()
    frame.pc is 1
    stack.size is 1
    initialGas.subUnsafe(context.gasRemaining) is IfFalse(1).gas()

    IfFalse(-1).runWith(frame) isE ()
    frame.pc is 1
    stack.size is 0

    IfFalse(0).runWith(frame).leftValue isE StackUnderflow
  }

  it should "CallLocal" in new StatelessInstrFixture {
    intercept[NotImplementedError] {
      CallLocal(0.toByte).runWith(frame)
    }
  }

  // TODO Not sure how to test this one
  it should "Return" in new StatelessInstrFixture {
    Return.runWith(frame) isE ()
  }

  it should "Assert" in new StatelessInstrFixture {
    forAll(arbitrary[Boolean]) { boolean =>
      val bool       = Val.Bool(boolean)
      val initialGas = context.gasRemaining

      stack.push(bool)

      if (boolean) {
        Assert.runWith(frame) isE ()
      } else {
        Assert.runWith(frame).leftValue isE AssertionFailed
      }
      initialGas.subUnsafe(context.gasRemaining) is Assert.gas()
    }
  }

  it should "AssertWithErrorCode" in {
    new StatelessInstrFixture {
      stack.push(Val.Bool(true))
      stack.push(Val.U256(U256.Zero))
      runAndCheckGas(AssertWithErrorCode)
    }

    new StatelessInstrFixture {
      stack.push(Val.Bool(false))
      stack.push(Val.U256(U256.MaxValue))
      AssertWithErrorCode.runWith(frame).leftValue isE InvalidErrorCode(U256.MaxValue)
    }

    new StatelessInstrFixture {
      stack.push(Val.Bool(false))
      stack.push(Val.U256(U256.Zero))
      AssertWithErrorCode.runWith(frame).leftValue isE AssertionFailedWithErrorCode(None, 0)
    }

    new StatefulInstrFixture {
      stack.push(Val.Bool(false))
      stack.push(Val.U256(U256.Zero))
      AssertWithErrorCode.runWith(frame).leftValue isE AssertionFailedWithErrorCode(
        frame.obj.contractIdOpt,
        0
      )
    }
  }

  trait HashFixture extends StatelessInstrFixture {
    def test[H <: RandomBytes](instr: HashAlg[H], hashSchema: crypto.HashSchema[H]) = {
      forAll(dataGen) { data =>
        val value = Val.ByteVec(data)
        stack.push(value)

        val initialGas = context.gasRemaining
        instr.runWith(frame)
        stack.size is 1
        stack.top.get is Val.ByteVec.from(hashSchema.hash(data))

        initialGas.subUnsafe(context.gasRemaining) is instr.gas(data.length)

        stack.pop()
      }
      instr.runWith(frame).leftValue isE StackUnderflow
    }
  }
  it should "Blake2b" in new HashFixture {
    test(Blake2b, crypto.Blake2b)
  }

  it should "Keccak256" in new HashFixture {
    test(Keccak256, crypto.Keccak256)
  }

  it should "Sha256" in new HashFixture {
    test(Sha256, crypto.Sha256)
  }

  it should "Sha3" in new HashFixture {
    test(Sha3, crypto.Sha3)
  }

  trait SignatureFixture extends StatelessInstrFixture {
    val keysGen = for {
      group         <- groupIndexGen
      (_, pub, pri) <- addressGen(group)
    } yield ((pub, pri))

    val tx               = transactionGen().sample.get
    val (pubKey, priKey) = keysGen.sample.get

    val signature      = Byte64.from(SignatureSchema.sign(tx.id.bytes, priKey))
    val signatureStack = Stack.ofCapacity[Byte64](1)
    signatureStack.push(signature)

    override lazy val frame = prepareFrame(
      AVector.empty,
      txEnv = Some(TxEnv.dryrun(tx, AVector.empty, signatureStack))
    )
  }

  it should "VerifyTxSignature" in new SignatureFixture {
    val initialGas = context.gasRemaining
    stack.push(Val.ByteVec(pubKey.bytes))
    VerifyTxSignature.runWith(frame) isE ()
    initialGas.subUnsafe(context.gasRemaining) is VerifyTxSignature.gas()

    val (wrongKey, _) = keysGen.sample.get

    signatureStack.push(signature)
    stack.push(Val.ByteVec(wrongKey.bytes))
    VerifyTxSignature.runWith(frame).leftValue isE InvalidSignature(
      wrongKey.bytes,
      tx.id.bytes,
      signature.bytes
    )

    signatureStack.push(signature)
    stack.push(Val.ByteVec(wrongKey.bytes))
    VerifyTxSignature.mockup().runWith(frame) isE ()
    stack.isEmpty is true

    val wrongData = dataGen.sample.get
    stack.push(Val.ByteVec(wrongData))
    VerifyTxSignature
      .runWith(frame)
      .leftValue
      .rightValue
      .toString is s"Invalid public key: ${Hex.toHexString(wrongData)}"
  }

  it should "GetSegregatedSignature" in new SignatureFixture {
    GetSegregatedSignature.runWith(frame) isE ()
    GetSegregatedSignature.runWith(frame).leftValue isE StackUnderflow
  }

  trait GenericSignatureFixture extends StatelessInstrFixture {
    val data32Gen: Gen[ByteString] = for {
      bytes <- Gen.listOfN(32, arbitrary[Byte])
    } yield ByteString(bytes)

    private def pushPublicKeyType(tpe: Option[ByteString]) = {
      tpe.foreach(v => stack.push(Val.ByteVec(v)))
    }

    // scalastyle:off method.length
    def test0[PriKey <: RandomBytes, PubKey <: RandomBytes](
        instr: SignatureInstr,
        generatePriPub: => (PriKey, PubKey),
        sign: (ByteString, PriKey) => ByteString,
        publicKeyType: Option[ByteString] = None
    ) = {
      val keysGen = for {
        (pri, pub) <- Gen.const(()).map(_ => generatePriPub)
      } yield ((pri, pub))

      val (priKey, pubKey) = keysGen.sample.get
      val data             = data32Gen.sample.get

      val signature = sign(data, priKey)

      stack.push(Val.ByteVec(data))
      stack.push(Val.ByteVec(pubKey.bytes))
      stack.push(Val.ByteVec(signature))
      pushPublicKeyType(publicKeyType)

      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      val extraGas = if (publicKeyType == Some(ByteString(3))) { // webauthn
        GasBox.unsafe(84)
      } else {
        GasBox.zero
      }
      val usedGas = GasBox.unsafe(instr.gas().value + extraGas.value)
      initialGas.subUnsafe(context.gasRemaining) is usedGas

      stack.push(Val.ByteVec(data))
      stack.push(Val.ByteVec(pubKey.bytes))
      stack.push(Val.ByteVec(ByteString("zzz")))
      pushPublicKeyType(publicKeyType)
      instr.runWith(frame).leftValue isE InvalidSignatureFormat(ByteString("zzz"))
      if (publicKeyType.isEmpty) stack.pop(2)
      stack.isEmpty is true

      val wrongData = dataGen.sample.get
      stack.push(Val.ByteVec(wrongData))
      stack.push(Val.ByteVec(signature))
      pushPublicKeyType(publicKeyType)
      val invalidPublicKeyBytes = publicKeyType.getOrElse(ByteString.empty) ++ wrongData
      instr
        .runWith(frame)
        .leftValue
        .rightValue
        .toString is s"Invalid public key: ${Hex.toHexString(invalidPublicKeyBytes)}"
      stack.isEmpty is true

      if (publicKeyType.isDefined) {
        val invalidPublicKeyType0 = ByteString(nextInt(4, 255).toByte)
        stack.push(Val.ByteVec(pubKey.bytes))
        stack.push(Val.ByteVec(signature))
        stack.push(Val.ByteVec(invalidPublicKeyType0))
        instr
          .runWith(frame)
          .leftValue
          .rightValue
          .toString is s"Invalid public key: ${Hex.toHexString(invalidPublicKeyType0 ++ pubKey.bytes)}"

        val invalidPublicKeyType1 =
          ByteString(nextInt(4, 255).toByte) ++ bytesGen(nextInt(1, 5)).sample.get
        stack.push(Val.ByteVec(invalidPublicKeyType1))
        instr.runWith(frame).leftValue isE InvalidPublicKeyType(invalidPublicKeyType1)
      }

      val signedData = dataGen.sample.get
      stack.push(Val.ByteVec(signedData))
      stack.push(Val.ByteVec(pubKey.bytes))
      stack.push(Val.ByteVec(signature))
      pushPublicKeyType(publicKeyType)
      instr
        .runWith(frame)
        .leftValue
        .rightValue
        .toString is s"Signed data bytes should have 32 bytes, get ${signedData.length} instead"

      stack.push(Val.ByteVec(data))
      stack.push(Val.ByteVec(pubKey.bytes))
      stack.push(Val.ByteVec(sign(data32Gen.sample.get, priKey)))
      pushPublicKeyType(publicKeyType)
      instr.runWith(frame).leftValue isE a[InvalidSignature]
      stack.isEmpty is true

      stack.push(Val.ByteVec(data))
      stack.push(Val.ByteVec(pubKey.bytes))
      stack.push(Val.ByteVec(sign(data32Gen.sample.get, priKey)))
      pushPublicKeyType(publicKeyType)
      instr.mockup().runWith(frame) isE ()
      stack.isEmpty is true
    }
    // scalastyle:on method.length

    // scalastyle:off method.length
    def test[PriKey <: RandomBytes, PubKey <: RandomBytes, Sig <: RandomBytes](
        instr: SignatureInstr,
        generatePriPub: => (PriKey, PubKey),
        sign: (ByteString, PriKey) => Sig,
        publicKeyType: Option[ByteString] = None
    ) = {
      val signFunc = (data: ByteString, pk: PriKey) => sign(data, pk).bytes
      test0(instr, generatePriPub, signFunc, publicKeyType)
    }
    // scalastyle:on method.length
  }

  it should "VerifySecP256K1" in new GenericSignatureFixture {
    test(VerifySecP256K1, crypto.SecP256K1.generatePriPub(), crypto.SecP256K1.sign)
  }

  it should "VerifyED25519" in new GenericSignatureFixture {
    test(VerifyED25519, crypto.ED25519.generatePriPub(), crypto.ED25519.sign)
  }

  it should "VerifyBIP340Schnorr" in new GenericSignatureFixture {
    test(VerifyBIP340Schnorr, crypto.BIP340Schnorr.generatePriPub(), crypto.BIP340Schnorr.sign)
  }

  it should "VerifySignature:SecP256K1" in new GenericSignatureFixture {
    test(
      VerifySignature,
      crypto.SecP256K1.generatePriPub(),
      crypto.SecP256K1.sign,
      Some(ByteString(0))
    )
  }

  it should "VerifySignature:SecP256R1" in new GenericSignatureFixture {
    test(
      VerifySignature,
      crypto.SecP256R1.generatePriPub(),
      crypto.SecP256R1.sign,
      Some(ByteString(1))
    )
  }

  it should "VerifySignature:ED25519" in new GenericSignatureFixture {
    test(VerifySignature, crypto.ED25519.generatePriPub(), crypto.ED25519.sign, Some(ByteString(2)))
  }

  trait WebAuthnFixture {
    val authenticatorData = Hash.generate.bytes ++ ByteString(1)
    val webauthn          = WebAuthn.createForTest(authenticatorData, WebAuthn.GET)

    def signRaw(challenge: ByteString, pk: crypto.SecP256R1PrivateKey) = {
      val messageHash = webauthn.messageHash(challenge)
      val signature   = Byte64.from(crypto.SecP256R1.sign(messageHash, pk))
      webauthn.encodeForTest() :+ signature
    }

    def sign(challenge: ByteString, pk: crypto.SecP256R1PrivateKey) = {
      signRaw(challenge, pk).map(_.bytes).reduce(_ ++ _).drop(2)
    }
  }

  it should "VerifySignature:WebAuthn" in new GenericSignatureFixture with WebAuthnFixture {
    test0(VerifySignature, crypto.SecP256R1.generatePriPub(), sign, Some(ByteString(3)))

    val (priKey, pubKey) = crypto.SecP256R1.generatePriPub()
    val challenge        = Hash.generate.bytes
    stack.push(Val.ByteVec(challenge))
    stack.push(Val.ByteVec(pubKey.bytes))
    val payload = sign(challenge, priKey)
    stack.push(Val.ByteVec(payload.drop(1)))
    stack.push(Val.ByteVec(ByteString(3)))
    VerifySignature.runWith(frame).leftValue isE a[InvalidWebAuthnPayload]
  }

  it should "GetSegregatedWebAuthnSignature" in new StatelessInstrFixture with WebAuthnFixture {
    val priKey     = crypto.SecP256R1.generatePriPub()._1
    val tx         = transactionGen().sample.get
    val signatures = signRaw(tx.id.bytes, priKey)

    val signatureStack = Stack.ofCapacity[Byte64](signatures.length + 1)
    val signature0     = Byte64.from(ByteString(-1) ++ bytesGen(63).sample.get).get
    signatureStack.push(signature0)
    signatureStack.push(signatures.reverse)

    override lazy val frame = prepareFrame(
      AVector.empty,
      txEnv = Some(TxEnv.dryrun(tx, AVector.empty, signatureStack))
    )

    val initialGas = context.gasRemaining
    GetSegregatedWebAuthnSignature.runWith(frame) isE ()
    initialGas.subUnsafe(context.gasRemaining) is GasMid.gas
    stack.size is 1
    stack.top.get is Val.ByteVec(signatures.map(_.bytes).reduce(_ ++ _).drop(2))
    frame.ctx.signatures.size is 1
    frame.ctx.signatures.top.get is signature0
    GetSegregatedWebAuthnSignature.runWith(frame).leftValue isE a[InvalidWebAuthnPayload]

    signatureStack.push(signature0)
    GetSegregatedWebAuthnSignatureMockup.runWith(frame) isE ()
    stack.size is 2
    stack.top.get is Val.ByteVec(signature0.bytes)
    frame.ctx.signatures.isEmpty is true
  }

  it should "test EthEcRecover: succeed in execution" in new StatelessInstrFixture
    with crypto.EthEcRecoverFixture {
    val initialGas = context.gasRemaining
    stack.push(Val.ByteVec(messageHash.bytes))
    stack.push(Val.ByteVec(signature))
    EthEcRecover.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.ByteVec(address)
    initialGas.subUnsafe(context.gasRemaining) is EthEcRecover.gas()
  }

  it should "test EthEcRecover: fail in execution" in new StatelessInstrFixture
    with crypto.EthEcRecoverFixture {
    stack.push(Val.ByteVec(signature))
    stack.push(Val.ByteVec(messageHash.bytes))
    EthEcRecover.runWith(frame).leftValue isE FailedInRecoverEthAddress
  }

  it should "NetworkId" in new StatelessInstrFixture {
    override lazy val frame = prepareFrame(
      AVector.empty,
      blockEnv = Some(mockBlockEnv)
    )

    val initialGas = context.gasRemaining
    NetworkId.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.ByteVec(ByteString(AlephiumMainNet.id))
    initialGas.subUnsafe(context.gasRemaining) is NetworkId.gas()
  }

  it should "BlockTimeStamp" in {
    new StatelessInstrFixture {
      private val timestamp = TimeStamp.now()
      override lazy val frame = prepareFrame(
        AVector.empty,
        blockEnv = Some(mockBlockEnv.copy(timeStamp = timestamp))
      )

      private val initialGas = context.gasRemaining
      BlockTimeStamp.runWith(frame) isE ()
      stack.size is 1
      stack.top.get is Val.U256(timestamp.millis)
      initialGas.subUnsafe(context.gasRemaining) is BlockTimeStamp.gas()
    }

    new StatelessInstrFixture {
      val timestamp = new TimeStamp(-1)
      override lazy val frame = prepareFrame(
        AVector.empty,
        blockEnv = Some(mockBlockEnv.copy(timeStamp = timestamp))
      )

      BlockTimeStamp.runWith(frame).leftValue isE NegativeTimeStamp(-1)
    }
  }

  it should "BlockTarget" in new StatelessInstrFixture {
    override lazy val frame = prepareFrame(
      AVector.empty,
      blockEnv = Some(mockBlockEnv)
    )

    private val initialGas = context.gasRemaining
    BlockTarget.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.U256(U256.unsafe(Target.Max.value))
    initialGas.subUnsafe(context.gasRemaining) is BlockTarget.gas()
  }

  it should "TxId" in new StatelessInstrFixture {
    val tx = transactionGen().sample.get

    override lazy val frame = prepareFrame(
      AVector.empty,
      txEnv = Some(TxEnv.dryrun(tx, AVector.empty, Stack.ofCapacity[Byte64](0)))
    )

    val initialGas = context.gasRemaining
    TxId.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.ByteVec(tx.id.bytes)
    initialGas.subUnsafe(context.gasRemaining) is TxId.gas()
  }

  trait GasInstrFixture extends StatelessInstrFixture {
    val gasPriceGen = Gen
      .choose[BigInteger](coinbaseGasPrice.value.v, ALPH.oneAlph.v)
      .map(v => GasPrice(U256.unsafe(v)))
    val gasAmountGen = Gen.choose[Int](minimalGas.value, maximalGasPerTx.value).map(GasBox.unsafe)
    val txEnv = TxEnv.mockup(
      TransactionId.random,
      Stack.ofCapacity(0),
      AVector.empty,
      AVector.empty,
      gasPriceGen.sample.get,
      gasAmountGen.sample.get,
      isEntryMethodPayable = false
    )

    override lazy val frame = prepareFrame(AVector.empty, txEnv = Some(txEnv))
  }

  it should "TxGasPrice" in new GasInstrFixture {
    runAndCheckGas(TxGasPrice, frame)
    stack.size is 1
    stack.top.get is Val.U256(txEnv.gasPrice.value)
  }

  it should "TxGasAmount" in new GasInstrFixture {
    runAndCheckGas(TxGasAmount, frame)
    stack.size is 1
    stack.top.get is Val.U256(txEnv.gasAmount.toU256)
  }

  it should "TxGasFee" in new GasInstrFixture {
    runAndCheckGas(TxGasFee, frame)
    stack.size is 1
    stack.top.get is Val.U256(txEnv.gasFeeUnsafe)
  }

  trait TxEnvFixture extends StatefulInstrFixture {
    val (script, _)   = prepareStatefulScript(StatefulScript.alwaysFail)
    val (tx, prevOut) = transactionGenWithPreOutputs(inputsNumGen = Gen.const(3)).sample.get
    val prevOutputs0  = prevOut.map(_.referredOutput)
    val prevOutputs1  = AVector.fill(3)(prevOutputs0.head)

    val txEnvWithRandomAddresses =
      TxEnv.dryrun(tx, prevOutputs0, Stack.ofCapacity(0))
    val txEnvWithUniqueAddress =
      TxEnv.dryrun(tx, prevOutputs1, Stack.ofCapacity(0))
    val uniqueAddress = Val.Address(prevOutputs0.head.lockupScript)
  }

  it should "TxInputAddressAt" in new TxEnvFixture {
    override lazy val frame: Frame[StatefulContext] =
      prepareFrame(txEnvOpt = Some(txEnvWithRandomAddresses))
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)

    val index      = prevOutputs0.length - 1
    val initialGas = context.gasRemaining
    stack.push(Val.U256(U256.unsafe(index)))
    TxInputAddressAt.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.Address(prevOutputs0.get(index).get.lockupScript)
    initialGas.subUnsafe(context.gasRemaining) is TxInputAddressAt.gas()

    val contractFrame = prepareFrame(txEnvOpt = Some(txEnvWithRandomAddresses))
    TxInputAddressAt.runWith(contractFrame).leftValue isE AccessTxInputAddressInContract
  }

  it should "TxInputsSize" in new TxEnvFixture {
    override lazy val frame: Frame[StatefulContext] =
      prepareFrame(txEnvOpt = Some(txEnvWithRandomAddresses))
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)

    val initialGas = context.gasRemaining
    TxInputsSize.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.U256(U256.unsafe(prevOutputs0.length))
    initialGas.subUnsafe(context.gasRemaining) is TxInputsSize.gas()

    val contractFrame = prepareFrame(txEnvOpt = Some(txEnvWithRandomAddresses))
    TxInputsSize.runWith(contractFrame).leftValue isE AccessTxInputAddressInContract
  }

  it should "test TxInstr.checkScriptFrameForLeman" in new TxEnvFixture {
    import NetworkConfigFixture.Leman
    val lemanContractFrame    = prepareFrame()(Leman)
    val lemanScriptFrame      = lemanContractFrame.asInstanceOf[StatefulFrame].copy(obj = script)
    val preLemanContractFrame = preparePreLemanFrame()
    val preLemanScriptFrame   = preLemanContractFrame.asInstanceOf[StatefulFrame].copy(obj = script)

    TxInstr
      .checkScriptFrameForLeman(lemanContractFrame)
      .leftValue isE AccessTxInputAddressInContract
    TxInstr.checkScriptFrameForLeman(lemanScriptFrame) isE ()
    TxInstr.checkScriptFrameForLeman(preLemanContractFrame) isE ()
    TxInstr.checkScriptFrameForLeman(preLemanScriptFrame) isE ()
  }

  trait LogFixture extends StatefulInstrFixture {
    def test(instr: LogInstr, n: Int) = {
      stack.pop(stack.size).isRight is true
      (0 until n).foreach { _ =>
        stack.push(Val.True)
      }

      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      stack.size is 0
      initialGas.subUnsafe(context.gasRemaining) is instr.gas(n)

      (0 until (n - 1)).foreach { _ =>
        stack.push(Val.True)
      }

      instr.runWith(frame).leftValue isE StackUnderflow
    }
  }

  it should "test Log" in new LogFixture {
    Instr.allLogInstrs.zipWithIndex.foreach { case (log, index) =>
      test(log, index + 1)
    }
    statelessInstrs0.filter(_.isInstanceOf[LogInstr]).length is Instr.allLogInstrs.length
  }

  it should "Log1" in new LogFixture {
    test(Log1, 1)
  }

  it should "Log2" in new LogFixture {
    test(Log2, 2)
  }

  it should "Log3" in new LogFixture {
    test(Log3, 3)
  }

  it should "Log4" in new LogFixture {
    test(Log4, 4)
  }

  it should "Log5" in new LogFixture {
    test(Log5, 5)
  }

  trait U256ToBytesFixture extends StatelessInstrFixture {
    def check(instr: U256ToBytesInstr, value: U256, bytes: ByteString) = {
      stack.push(Val.U256(value))
      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      initialGas.subUnsafe(context.gasRemaining) is instr.gas(instr.size)
      stack.size is 1
      stack.top.get is Val.ByteVec(bytes)
      stack.pop()
    }

    def test(instr: U256ToBytesInstr, size: Int) = {
      val gen = Gen
        .choose[BigInteger](
          BigInteger.ZERO,
          BigInteger.ONE.shiftLeft(size * 8).subtract(BigInteger.ONE)
        )
        .map(U256.unsafe)

      forAll(gen) { value =>
        val expected = value.toFixedSizeBytes(size).get
        check(instr, value, expected)
      }

      check(instr, U256.Zero, ByteString(Array.fill[Byte](size)(0)))
      check(
        instr,
        U256.unsafe(BigInteger.ONE.shiftLeft(size * 8).subtract(BigInteger.ONE)),
        ByteString(Array.fill[Byte](size)(-1))
      )
      if (size != 32) {
        val value = Val.U256(U256.unsafe(BigInteger.ONE.shiftLeft(size * 8)))
        stack.push(value)
        instr.runWith(frame).leftValue isE InvalidConversion(value, Val.ByteVec)
      }
    }
  }

  it should "U256To1Byte" in new U256ToBytesFixture {
    test(U256To1Byte, 1)
  }

  it should "U256To2Byte" in new U256ToBytesFixture {
    test(U256To2Byte, 2)
  }

  it should "U256To4Byte" in new U256ToBytesFixture {
    test(U256To4Byte, 4)
  }

  it should "U256To8Byte" in new U256ToBytesFixture {
    test(U256To8Byte, 8)
  }

  it should "U256To16Byte" in new U256ToBytesFixture {
    test(U256To16Byte, 16)
  }

  it should "U256To32Byte" in new U256ToBytesFixture {
    test(U256To32Byte, 32)
  }

  trait U256FromBytesFixture extends StatelessInstrFixture {
    def test(instr: U256FromBytesInstr, size: Int) = {
      forAll(Gen.listOfN(size, arbitrary[Byte])) { bytes =>
        val byteString = ByteString(bytes)
        val value      = U256.from(byteString).get
        stack.push(Val.ByteVec(byteString))
        val initialGas = context.gasRemaining
        instr.runWith(frame) isE ()
        initialGas.subUnsafe(context.gasRemaining) is instr.gas(size)
        stack.top.get is Val.U256(value)
        stack.pop()
      }

      Seq(size - 1, size + 1).foreach { n =>
        val bytes = ByteString(Gen.listOfN(n, arbitrary[Byte]).sample.get)
        stack.push(Val.ByteVec(bytes))
        instr.runWith(frame).leftValue isE InvalidBytesSize
      }
    }
  }

  it should "U256From1Byte" in new U256FromBytesFixture {
    test(U256From1Byte, 1)
  }

  it should "U256From2Byte" in new U256FromBytesFixture {
    test(U256From2Byte, 2)
  }

  it should "U256From4Byte" in new U256FromBytesFixture {
    test(U256From4Byte, 4)
  }

  it should "U256From8Byte" in new U256FromBytesFixture {
    test(U256From8Byte, 8)
  }

  it should "U256From16Byte" in new U256FromBytesFixture {
    test(U256From16Byte, 16)
  }

  it should "U256From32Byte" in new U256FromBytesFixture {
    test(U256From32Byte, 32)
  }

  it should "GroupOfAddress" in new StatelessInstrFixture {
    def lemanP2CLockupGen(groupIndex: GroupIndex): Gen[LockupScript.P2C] = {
      txIdGen.map(txId => LockupScript.p2c(ContractId.from(txId, 0, groupIndex)))
    }
    def lockupScriptGen(groupIndex: GroupIndex): Gen[LockupScript] = {
      Gen.oneOf(assetLockupGen(groupIndex), lemanP2CLockupGen(groupIndex))
    }
    forAll(groupIndexGen) { groupIndex =>
      val lockupScript = lockupScriptGen(groupIndex).sample.get

      stack.push(Val.Address(lockupScript))
      runAndCheckGas(GroupOfAddress)
      stack.size is 1
      stack.top.get is Val.U256(U256.unsafe(groupIndex.value))
      stack.pop()
    }
  }

  trait StatefulFixture extends GenFixture {
    lazy val baseMethod = Method.testDefault[StatefulContext](
      isPublic = true,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector()
    )

    lazy val contract = StatefulContract(2, methods = AVector(baseMethod))

    lazy val tokenId = TokenId.generate

    def alphBalance(lockupScript: LockupScript, amount: U256): MutBalances = {
      MutBalances(ArrayBuffer((lockupScript, MutBalancesPerLockup.alph(amount))))
    }

    def tokenBalance(lockupScript: LockupScript, tokenId: TokenId, amount: U256): MutBalances = {
      MutBalances(ArrayBuffer((lockupScript, MutBalancesPerLockup.token(tokenId, amount))))
    }

    def balances(
        lockupScript: LockupScript,
        alphAmount: Option[U256],
        tokens: Map[TokenId, U256]
    ): MutBalances = {
      MutBalances(
        ArrayBuffer(
          (
            lockupScript,
            MutBalancesPerLockup(
              alphAmount.getOrElse(U256.Zero),
              mutable.Map.from(tokens),
              0
            )
          )
        )
      )
    }

    def prepareFrame(
        balanceState: Option[MutBalanceState] = None,
        contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)] = None,
        txEnvOpt: Option[TxEnv] = None,
        callerFrameOpt: Option[StatefulFrame] = None,
        immFields: AVector[Val] = AVector(Val.False),
        mutFields: AVector[Val] = AVector(Val.True),
        contractIdOpt: Option[ContractId] = None
    )(implicit networkConfig: NetworkConfig) = {
      val (obj, ctx) =
        prepareContract(
          contract,
          immFields,
          mutFields,
          contractOutputOpt = contractOutputOpt,
          txEnvOpt = txEnvOpt,
          contractIdOpt = contractIdOpt
        )
      Frame
        .stateful(
          ctx,
          callerFrameOpt,
          balanceState,
          obj,
          baseMethod,
          AVector.empty,
          Stack.ofCapacity(10),
          _ => okay
        )
        .rightValue
    }

    def preparePreDanubeFrame(
        balanceState: Option[MutBalanceState] = None,
        contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)] = None,
        txEnvOpt: Option[TxEnv] = None,
        callerFrameOpt: Option[StatefulFrame] = None
    ) = {
      val config = NetworkConfigFixture.PreDanube
      if (config.getHardFork(TimeStamp.now()).isLemanEnabled()) {
        prepareFrame(balanceState, contractOutputOpt, txEnvOpt, callerFrameOpt)(config)
      } else {
        preparePreLemanFrame(balanceState, contractOutputOpt, txEnvOpt, callerFrameOpt)
      }
    }

    def preparePreRhoneFrame(
        balanceState: Option[MutBalanceState] = None,
        contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)] = None,
        txEnvOpt: Option[TxEnv] = None,
        callerFrameOpt: Option[StatefulFrame] = None
    ) = {
      val config = NetworkConfigFixture.PreRhone
      if (config.getHardFork(TimeStamp.now()).isLemanEnabled()) {
        prepareFrame(balanceState, contractOutputOpt, txEnvOpt, callerFrameOpt)(config)
      } else {
        preparePreLemanFrame(balanceState, contractOutputOpt, txEnvOpt, callerFrameOpt)
      }
    }

    def preparePreLemanFrame(
        balanceState: Option[MutBalanceState] = None,
        contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)] = None,
        txEnvOpt: Option[TxEnv] = None,
        callerFrameOpt: Option[StatefulFrame] = None,
        immFields: AVector[Val] = AVector.empty,
        mutFields: AVector[Val] = AVector(Val.True, Val.False)
    ) = {
      prepareFrame(
        balanceState,
        contractOutputOpt,
        txEnvOpt,
        callerFrameOpt,
        immFields,
        mutFields
      )(NetworkConfigFixture.Genesis)
    }
  }

  trait StatefulInstrFixture extends StatefulFixture {
    lazy val frame   = prepareFrame()
    lazy val stack   = frame.opStack
    lazy val context = frame.ctx

    lazy val contractAddress = LockupScript.p2c(ContractId.random)

    def runAndCheckGas[I <: Instr[StatefulContext] with GasSimple](
        instr: I,
        extraGasOpt: Option[GasBox] = None,
        frame: Frame[StatefulContext] = frame
    ) = {
      val initialGas = frame.ctx.gasRemaining
      instr.runWith(frame) isE ()
      initialGas.subUnsafe(frame.ctx.gasRemaining) is
        instr.gas().addUnsafe(extraGasOpt.getOrElse(GasBox.zero))
    }
  }

  it should "LoadImmField(byte)" in new StatefulInstrFixture {
    runAndCheckGas(LoadImmField(0.toByte))
    stack.size is 1
    stack.top.get is Val.False

    LoadImmField(1.toByte).runWith(frame).leftValue isE InvalidImmFieldIndex(1, 1)
    LoadImmField(-1.toByte).runWith(frame).leftValue isE InvalidImmFieldIndex(255, 1)
  }

  it should "LoadMutField(byte)" in new StatefulInstrFixture {
    runAndCheckGas(LoadMutField(0.toByte))
    stack.size is 1
    stack.top.get is Val.True

    LoadMutField(1.toByte).runWith(frame).leftValue isE InvalidMutFieldIndex(1, 1)
    LoadMutField(-1.toByte).runWith(frame).leftValue isE InvalidMutFieldIndex(255, 1)
  }

  it should "StoreMutField(byte)" in new StatefulInstrFixture {
    stack.push(Val.False)
    runAndCheckGas(StoreMutField(0.toByte))
    stack.size is 0
    frame.obj.getMutField(0) isE Val.False

    stack.push(Val.True)
    StoreMutField(1.toByte).runWith(frame).leftValue isE InvalidMutFieldIndex(1, 1)
    stack.push(Val.True)
    StoreMutField(-1.toByte).runWith(frame).leftValue isE InvalidMutFieldIndex(255, 1)
  }

  it should "LoadImmFieldByIndex" in new StatefulInstrFixture {
    stack.push(Val.U256(0))
    runAndCheckGas(LoadImmFieldByIndex)
    stack.size is 1
    stack.top.get is Val.False

    stack.push(Val.U256(1))
    LoadImmFieldByIndex.runWith(frame).leftValue isE InvalidImmFieldIndex(1, 1)
    stack.push(Val.U256(0xff))
    LoadImmFieldByIndex.popIndex(frame, InvalidMutFieldIndex.apply) isE 0xff
    stack.push(Val.U256(0xff + 1))
    LoadImmFieldByIndex
      .popIndex(frame, InvalidMutFieldIndex.apply)
      .leftValue isE InvalidMutFieldIndex(
      0xff + 1,
      0xff
    )
  }

  it should "LoadMutFieldByIndex" in new StatefulInstrFixture {
    stack.push(Val.U256(0))
    runAndCheckGas(LoadMutFieldByIndex)
    stack.size is 1
    stack.top.get is Val.True

    stack.push(Val.U256(1))
    LoadMutFieldByIndex.runWith(frame).leftValue isE InvalidMutFieldIndex(1, 1)
    stack.push(Val.U256(0xff))
    LoadMutFieldByIndex.popIndex(frame, InvalidMutFieldIndex.apply) isE 0xff
    stack.push(Val.U256(0xff + 1))
    LoadMutFieldByIndex
      .popIndex(frame, InvalidMutFieldIndex.apply)
      .leftValue isE InvalidMutFieldIndex(
      0xff + 1,
      0xff
    )
  }

  it should "StoreMutFieldByIndex" in new StatefulInstrFixture {
    stack.push(Val.False)
    stack.push(Val.U256(0))
    runAndCheckGas(StoreMutFieldByIndex)
    stack.size is 0
    frame.obj.getMutField(0) isE Val.False

    stack.push(Val.False)
    stack.push(Val.U256(1))
    StoreMutFieldByIndex.runWith(frame).leftValue isE InvalidMutFieldIndex(1, 1)
    stack.push(Val.False)
    stack.push(Val.U256(0xff))
    StoreMutFieldByIndex.popIndex(frame, InvalidMutFieldIndex.apply) isE 0xff
    stack.push(Val.False)
    stack.push(Val.U256(0xff + 1))
    StoreMutFieldByIndex
      .popIndex(frame, InvalidMutFieldIndex.apply)
      .leftValue isE InvalidMutFieldIndex(
      0xff + 1,
      0xff
    )
  }

  it should "CallExternal(byte)" in new StatefulInstrFixture {
    intercept[NotImplementedError] {
      CallExternal(0.toByte).runWith(frame)
    }
  }

  "MethodSelector" should "run successfully with empty stack" in new StatefulInstrFixture {
    MethodSelector(Method.Selector(0xffffffff)).runWith(frame).isRight is true
  }

  "CallExternalBySelector(bytes)" should "not run" in new StatefulInstrFixture {
    intercept[NotImplementedError] {
      CallExternalBySelector(Method.Selector(0)).runWith(frame)
    }
  }

  it should "serde" in new StatefulInstrFixture {
    def test(selector: Int, encoded: ByteString) = {
      val instr = CallExternalBySelector(Method.Selector(selector))
      val bytes = ByteString(CallExternalBySelector.code) ++ encoded
      instr.serialize() is bytes
      deserialize[Instr[StatefulContext]](bytes).rightValue is instr
    }
    CallExternalBySelector.code is 212.toByte
    CallExternalBySelector.code.toInt is -44
    test(0, ByteString(0, 0, 0, 0))
    test(0xffffffff, ByteString(0xff, 0xff, 0xff, 0xff))
  }

  it should "ApproveAlph" in new StatefulInstrFixture {
    val lockupScript       = lockupScriptGen.sample.get
    val randomLockupScript = lockupScriptGen.sample.get

    def test(
        frame: Frame[StatefulContext],
        amount: U256,
        balanceState: MutBalanceState,
        lockupScriptOpt: Option[LockupScript] = None
    ) = {
      frame.opStack.push(Val.Address(lockupScriptOpt.getOrElse(lockupScript)))
      frame.opStack.push(Val.U256(amount))
      runAndCheckGas(ApproveAlph, None, frame)

      frame.opStack.size is 0
      frame.getBalanceState() isE balanceState
    }
    def fail(
        frame: Frame[StatefulContext],
        amount: U256,
        remain: U256,
        lockupScriptOpt: Option[LockupScript] = None
    ) = {
      val from = lockupScriptOpt.getOrElse(lockupScript)
      intercept[AssertionError](
        test(frame, amount, MutBalanceState.empty, lockupScriptOpt)
      ).getMessage is Right(
        NotEnoughApprovedBalance(from, TokenId.alph, amount, remain)
      ).toString
    }

    val balanceState0 = MutBalanceState.from(alphBalance(lockupScript, ALPH.oneAlph))
    val preRhoneFrame = preparePreRhoneFrame(Some(balanceState0))
    test(
      preRhoneFrame,
      U256.Zero,
      MutBalanceState(
        alphBalance(lockupScript, ALPH.oneAlph),
        alphBalance(lockupScript, U256.Zero)
      )
    )
    test(
      preRhoneFrame,
      ALPH.oneNanoAlph,
      MutBalanceState(
        alphBalance(lockupScript, ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph)),
        alphBalance(lockupScript, ALPH.oneNanoAlph)
      )
    )
    fail(preRhoneFrame, U256.Zero, U256.Zero, Some(randomLockupScript))
    fail(preRhoneFrame, U256.One, U256.Zero, Some(randomLockupScript))
    fail(preRhoneFrame, ALPH.alph(2), ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))

    val balanceState1      = MutBalanceState.from(alphBalance(lockupScript, ALPH.oneAlph))
    val remainBalanceState = MutBalanceState.from(alphBalance(lockupScript, ALPH.oneAlph))
    val rhoneFrame         = prepareFrame(Some(balanceState1))(NetworkConfigFixture.Rhone)
    test(rhoneFrame, U256.Zero, remainBalanceState)
    test(rhoneFrame, U256.Zero, remainBalanceState, Some(randomLockupScript))
    fail(rhoneFrame, U256.One, U256.Zero, Some(randomLockupScript))
    test(
      rhoneFrame,
      ALPH.oneNanoAlph,
      MutBalanceState(
        alphBalance(lockupScript, ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph)),
        alphBalance(lockupScript, ALPH.oneNanoAlph)
      )
    )
    fail(rhoneFrame, ALPH.alph(2), ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))

    val frameWithEmptyBalanceState = prepareFrame(Some(MutBalanceState.empty))
    test(frameWithEmptyBalanceState, U256.Zero, MutBalanceState.empty)
    fail(frameWithEmptyBalanceState, U256.One, U256.Zero)
  }

  it should "ApproveToken" in new StatefulInstrFixture {
    val lockupScript       = lockupScriptGen.sample.get
    val randomLockupScript = lockupScriptGen.sample.get
    val randomTokenId      = TokenId.generate

    def test(
        frame: Frame[StatefulContext],
        tokenId: TokenId,
        amount: U256,
        balanceState: MutBalanceState,
        lockupScriptOpt: Option[LockupScript] = None
    ) = {
      frame.opStack.push(Val.Address(lockupScriptOpt.getOrElse(lockupScript)))
      frame.opStack.push(Val.ByteVec(tokenId.bytes))
      frame.opStack.push(Val.U256(amount))
      runAndCheckGas(ApproveToken, None, frame)

      frame.opStack.size is 0
      frame.getBalanceState() isE balanceState
    }
    def fail(
        frame: Frame[StatefulContext],
        tokenId: TokenId,
        amount: U256,
        remain: U256,
        lockupScriptOpt: Option[LockupScript] = None
    ) = {
      val from = lockupScriptOpt.getOrElse(lockupScript)
      intercept[AssertionError](
        test(frame, tokenId, amount, MutBalanceState.empty, lockupScriptOpt)
      ).getMessage is Right(
        NotEnoughApprovedBalance(from, tokenId, amount, remain)
      ).toString
    }

    val initBalanceState0 =
      MutBalanceState.from(
        balances(lockupScript, None, Map(tokenId -> ALPH.oneAlph, TokenId.alph -> ALPH.oneAlph))
      )
    val genesisFrame = preparePreLemanFrame(Some(initBalanceState0))
    fail(genesisFrame, tokenId, ALPH.alph(2), ALPH.oneAlph)
    fail(genesisFrame, tokenId, U256.Zero, U256.Zero, Some(randomLockupScript))
    fail(genesisFrame, TokenId.alph, U256.Zero, U256.Zero, Some(randomLockupScript))
    fail(genesisFrame, randomTokenId, U256.Zero, U256.Zero, Some(randomLockupScript))
    test(
      genesisFrame,
      tokenId,
      U256.Zero,
      MutBalanceState(
        balances(lockupScript, None, Map(tokenId -> ALPH.oneAlph, TokenId.alph -> ALPH.oneAlph)),
        balances(lockupScript, None, Map(tokenId -> U256.Zero))
      )
    )
    test(
      genesisFrame,
      TokenId.alph,
      U256.Zero,
      MutBalanceState(
        balances(lockupScript, None, Map(tokenId -> ALPH.oneAlph, TokenId.alph -> ALPH.oneAlph)),
        balances(lockupScript, None, Map(tokenId -> U256.Zero, TokenId.alph -> U256.Zero))
      )
    )
    test(
      genesisFrame,
      tokenId,
      ALPH.oneNanoAlph,
      MutBalanceState(
        balances(
          lockupScript,
          None,
          Map(tokenId -> ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph), TokenId.alph -> ALPH.oneAlph)
        ),
        balances(lockupScript, None, Map(tokenId -> ALPH.oneNanoAlph, TokenId.alph -> U256.Zero))
      )
    )
    test(
      genesisFrame,
      TokenId.alph,
      ALPH.oneNanoAlph,
      MutBalanceState(
        balances(
          lockupScript,
          None,
          Map(
            tokenId      -> ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph),
            TokenId.alph -> ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph)
          )
        ),
        balances(
          lockupScript,
          None,
          Map(tokenId -> ALPH.oneNanoAlph, TokenId.alph -> ALPH.oneNanoAlph)
        )
      )
    )

    val initBalanceState1 =
      MutBalanceState.from(balances(lockupScript, Some(ALPH.oneAlph), Map(tokenId -> ALPH.oneAlph)))
    val lemanFrame = prepareFrame(Some(initBalanceState1))(NetworkConfigFixture.Leman)
    fail(lemanFrame, tokenId, ALPH.alph(2), ALPH.oneAlph)
    fail(lemanFrame, tokenId, U256.Zero, U256.Zero, Some(randomLockupScript))
    fail(lemanFrame, TokenId.alph, U256.Zero, U256.Zero, Some(randomLockupScript))
    fail(lemanFrame, randomTokenId, U256.One, U256.Zero, Some(randomLockupScript))
    test(
      lemanFrame,
      tokenId,
      U256.Zero,
      MutBalanceState(
        balances(lockupScript, Some(ALPH.oneAlph), Map(tokenId -> ALPH.oneAlph)),
        balances(lockupScript, None, Map(tokenId -> U256.Zero))
      )
    )
    test(
      lemanFrame,
      TokenId.alph,
      U256.Zero,
      MutBalanceState(
        balances(lockupScript, Some(ALPH.oneAlph), Map(tokenId -> ALPH.oneAlph)),
        balances(lockupScript, Some(U256.Zero), Map(tokenId -> U256.Zero))
      )
    )
    test(
      lemanFrame,
      tokenId,
      ALPH.oneNanoAlph,
      MutBalanceState(
        balances(
          lockupScript,
          Some(ALPH.oneAlph),
          Map(tokenId -> ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))
        ),
        balances(lockupScript, Some(U256.Zero), Map(tokenId -> ALPH.oneNanoAlph))
      )
    )
    test(
      lemanFrame,
      TokenId.alph,
      ALPH.oneNanoAlph,
      MutBalanceState(
        balances(
          lockupScript,
          Some(ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph)),
          Map(tokenId -> ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))
        ),
        balances(lockupScript, Some(ALPH.oneNanoAlph), Map(tokenId -> ALPH.oneNanoAlph))
      )
    )

    val initBalanceState2 =
      MutBalanceState.from(balances(lockupScript, Some(ALPH.oneAlph), Map(tokenId -> ALPH.oneAlph)))
    val remainBalanceState =
      MutBalanceState.from(balances(lockupScript, Some(ALPH.oneAlph), Map(tokenId -> ALPH.oneAlph)))
    val rhoneFrame = prepareFrame(Some(initBalanceState2))(NetworkConfigFixture.Rhone)
    fail(rhoneFrame, tokenId, ALPH.alph(2), ALPH.oneAlph)
    test(rhoneFrame, tokenId, U256.Zero, remainBalanceState, Some(randomLockupScript))
    fail(rhoneFrame, tokenId, U256.One, U256.Zero, Some(randomLockupScript))
    test(rhoneFrame, TokenId.alph, U256.Zero, remainBalanceState, Some(randomLockupScript))
    fail(rhoneFrame, TokenId.alph, U256.One, U256.Zero, Some(randomLockupScript))
    test(rhoneFrame, randomTokenId, U256.Zero, remainBalanceState, Some(randomLockupScript))
    fail(rhoneFrame, randomTokenId, U256.One, U256.Zero, Some(randomLockupScript))
    test(rhoneFrame, tokenId, U256.Zero, remainBalanceState)
    test(rhoneFrame, TokenId.alph, U256.Zero, remainBalanceState)
    test(
      rhoneFrame,
      tokenId,
      ALPH.oneNanoAlph,
      MutBalanceState(
        balances(
          lockupScript,
          Some(ALPH.oneAlph),
          Map(tokenId -> ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))
        ),
        balances(lockupScript, None, Map(tokenId -> ALPH.oneNanoAlph))
      )
    )
    test(
      rhoneFrame,
      TokenId.alph,
      ALPH.oneNanoAlph,
      MutBalanceState(
        balances(
          lockupScript,
          Some(ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph)),
          Map(tokenId -> ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))
        ),
        balances(lockupScript, Some(ALPH.oneNanoAlph), Map(tokenId -> ALPH.oneNanoAlph))
      )
    )
  }

  it should "AlphRemaining" in new StatefulInstrFixture {
    val lockupScript       = lockupScriptGen.sample.get
    val randomLockupScript = lockupScriptGen.sample.get

    def test(
        frame: Frame[StatefulContext],
        amount: U256,
        lockupScriptOpt: Option[LockupScript] = None
    ) = {
      frame.opStack.push(Val.Address(lockupScriptOpt.getOrElse(lockupScript)))
      runAndCheckGas(AlphRemaining, None, frame)

      frame.opStack.size is 1
      frame.opStack.top.get is Val.U256(amount)
      frame.opStack.pop()
    }
    def fail(
        frame: Frame[StatefulContext],
        amount: U256,
        lockupScriptTest: LockupScript
    ) = {
      intercept[AssertionError](
        test(frame, amount, Option(lockupScriptTest))
      ).getMessage is Right(
        NoAlphBalanceForTheAddress(Address.from(lockupScriptTest))
      ).toString
    }

    val balanceState =
      MutBalanceState.from(alphBalance(lockupScript, ALPH.oneAlph))

    val preRhoneFrame = preparePreRhoneFrame(Option(balanceState))
    test(preRhoneFrame, ALPH.oneAlph)
    fail(preRhoneFrame, U256.Zero, randomLockupScript)

    val rhoneFrame = prepareFrame(Option(balanceState))(NetworkConfigFixture.SinceRhone)
    test(rhoneFrame, ALPH.oneAlph)
    test(rhoneFrame, U256.Zero, Option(randomLockupScript))
  }

  it should "TokenRemaining" in new StatefulInstrFixture {
    val lockupScript       = lockupScriptGen.sample.get
    val randomLockupScript = lockupScriptGen.sample.get

    def test(
        frame: Frame[StatefulContext],
        tokenId: TokenId,
        amount: U256,
        lockupScriptOpt: Option[LockupScript] = None
    ) = {
      frame.opStack.push(Val.Address(lockupScriptOpt.getOrElse(lockupScript)))
      frame.opStack.push(Val.ByteVec(tokenId.bytes))

      runAndCheckGas(TokenRemaining, None, frame)

      frame.opStack.size is 1
      frame.opStack.top.get is Val.U256(amount)
      frame.opStack.pop()
    }

    def fail(
        frame: Frame[StatefulContext],
        tokenId: TokenId,
        amount: U256,
        lockupScriptOpt: Option[LockupScript] = None
    ) = {
      val address = Address.from(lockupScriptOpt.getOrElse(lockupScript))
      intercept[AssertionError](
        test(frame, tokenId, amount, lockupScriptOpt)
      ).getMessage is Right(
        if (tokenId == TokenId.alph) {
          NoAlphBalanceForTheAddress(address)
        } else {
          NoTokenBalanceForTheAddress(tokenId, address)
        }
      ).toString
    }

    val balanceState = MutBalanceState.from(
      balances(lockupScript, Option(ALPH.oneAlph), Map(tokenId -> ALPH.oneAlph))
    )

    val genesisFrame = preparePreLemanFrame(Option(balanceState))
    test(genesisFrame, tokenId, ALPH.oneAlph)
    fail(genesisFrame, tokenId, U256.Zero, Option(randomLockupScript))
    fail(genesisFrame, TokenId.alph, ALPH.oneAlph)
    fail(genesisFrame, TokenId.alph, U256.Zero, Option(randomLockupScript))

    val lemanFrame = prepareFrame(Option(balanceState))(NetworkConfigFixture.Leman)
    test(lemanFrame, tokenId, ALPH.oneAlph)
    fail(lemanFrame, tokenId, U256.Zero, Option(randomLockupScript))
    test(lemanFrame, TokenId.alph, ALPH.oneAlph)
    fail(lemanFrame, TokenId.alph, U256.Zero, Option(randomLockupScript))

    val rhoneFrame = prepareFrame(Option(balanceState))(NetworkConfigFixture.SinceRhone)
    test(rhoneFrame, tokenId, ALPH.oneAlph)
    test(rhoneFrame, tokenId, U256.Zero, Option(randomLockupScript))
    test(rhoneFrame, TokenId.alph, ALPH.oneAlph)
    test(rhoneFrame, TokenId.alph, U256.Zero, Option(randomLockupScript))
  }

  it should "IsPaying" in new StatefulFixture {
    {
      info("Alph")
      val lockupScript = lockupScriptGen.sample.get
      val balanceState =
        MutBalanceState.from(alphBalance(lockupScript, ALPH.oneAlph))
      val frame = prepareFrame(Some(balanceState))
      val stack = frame.opStack

      stack.push(Val.Address(lockupScript))

      val initialGas = frame.ctx.gasRemaining
      IsPaying.runWith(frame) isE ()
      initialGas.subUnsafe(frame.ctx.gasRemaining) is IsPaying.gas()

      stack.size is 1
      stack.top.get is Val.Bool(true)
      stack.pop()

      stack.push(Val.Address(lockupScriptGen.sample.get))
      IsPaying.runWith(frame) isE ()
      stack.size is 1
      stack.top.get is Val.Bool(false)
    }
    {
      info("Token")
      val lockupScript = lockupScriptGen.sample.get
      val balanceState =
        MutBalanceState.from(
          tokenBalance(lockupScript, tokenId, ALPH.oneAlph)
        )
      val frame = prepareFrame(Some(balanceState))
      val stack = frame.opStack

      stack.push(Val.Address(lockupScript))

      val initialGas = frame.ctx.gasRemaining
      IsPaying.runWith(frame) isE ()
      initialGas.subUnsafe(frame.ctx.gasRemaining) is IsPaying.gas()

      stack.size is 1
      stack.top.get is Val.Bool(true)
      stack.pop()

      stack.push(Val.Address(lockupScriptGen.sample.get))
      IsPaying.runWith(frame) isE ()
      stack.size is 1
      stack.top.get is Val.Bool(false)
    }
  }

  it should "BurnToken" in new StatefulInstrFixture {
    val from = lockupScriptGen.sample.get
    val balanceState = MutBalanceState.from(
      tokenBalance(
        from,
        tokenId,
        ALPH.alph(2)
      )
    )
    override lazy val frame = prepareFrame(Some(balanceState))

    stack.push(Val.Address(from))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.oneAlph))

    runAndCheckGas(BurnToken)

    frame.balanceStateOpt is Some(
      MutBalanceState.from(
        tokenBalance(from, tokenId, ALPH.oneAlph)
      )
    )

    stack.push(Val.Address(from))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.alph(2)))

    BurnToken.runWith(frame).leftValue isE NotEnoughApprovedBalance(
      from,
      tokenId,
      ALPH.alph(2),
      ALPH.oneAlph
    )

    stack.push(Val.Address(from))
    stack.push(Val.ByteVec(TokenId.alph.bytes))
    stack.push(Val.U256(ALPH.alph(2)))

    BurnToken.runWith(frame).leftValue isE BurningAlphNotAllowed
  }

  trait LockApprovedAssetsFixture extends StatefulInstrFixture {
    val assetAddress = assetLockupScriptGen.sample.get
    val balanceState = MutBalanceState.from {
      val balance = tokenBalance(assetAddress, tokenId, ALPH.alph(2))
      balance.merge(alphBalance(assetAddress, ALPH.alph(2)))
      balance
    }

    def prepareStack(
        attoAlphAmount: U256,
        tokenAmount: U256,
        timestamp: U256,
        recipient: LockupScript.Asset = assetAddress
    ): ExeResult[Unit] = {
      balanceState.approveALPH(assetAddress, attoAlphAmount)
      balanceState.approveToken(assetAddress, tokenId, tokenAmount)
      stack.push(Val.Address(recipient))
      stack.push(Val.U256(timestamp))
    }

    val validTimestamp = TimeStamp.now().plusHoursUnsafe(1)

    prepareStack(ALPH.oneAlph, ALPH.cent(1), validTimestamp.millis)
    runAndCheckGas(LockApprovedAssets, Some(GasSchedule.txOutputBaseGas.mulUnsafe(2)))
    frame.balanceStateOpt is Some(
      MutBalanceState.from {
        val balance = tokenBalance(assetAddress, tokenId, ALPH.cent(199))
        balance.merge(alphBalance(assetAddress, ALPH.oneAlph))
        balance
      }
    )
    frame.ctx.outputBalances.all.isEmpty is true
    frame.ctx.generatedOutputs.head is
      TxOutput.asset(
        dustUtxoAmount,
        assetAddress,
        AVector(tokenId -> ALPH.cent(1)),
        validTimestamp
      )
    frame.ctx.generatedOutputs(1) is
      TxOutput.asset(
        ALPH.oneAlph - dustUtxoAmount,
        assetAddress,
        AVector.empty,
        validTimestamp
      )

    prepareStack(ALPH.oneAlph, ALPH.oneNanoAlph, U256.MaxValue)
    LockApprovedAssets.runWith(frame).leftValue isE LockTimeOverflow

    prepareStack(ALPH.oneAlph, ALPH.alph(2), 0)
    LockApprovedAssets.runWith(frame).leftValue isE a[InvalidLockTime]
  }

  it should "LockApprovedAssets before Danube" in new LockApprovedAssetsFixture {
    override lazy val frame = prepareFrame(Some(balanceState))(NetworkConfigFixture.Leman)

    val recipient = assetLockupScriptGen.sample.get
    prepareStack(ALPH.oneAlph, ALPH.oneNanoAlph, validTimestamp.millis, recipient)
    LockApprovedAssets.runWith(frame).leftValue isE a[NoAssetsApproved]

    prepareStack(ALPH.oneAlph, ALPH.oneNanoAlph, validTimestamp.millis)
    LockApprovedAssets.runWith(frame) isE ()

    prepareStack(ALPH.oneAlph, ALPH.alph(2), validTimestamp.millis)
    LockApprovedAssets.runWith(frame).leftValue isE a[NoAssetsApproved]
  }

  it should "LockApprovedAssets after Danube" in new LockApprovedAssetsFixture {
    override lazy val frame = prepareFrame(Some(balanceState))(NetworkConfigFixture.SinceDanube)

    val recipient = assetLockupScriptGen.sample.get
    prepareStack(ALPH.oneAlph, ALPH.oneNanoAlph, validTimestamp.millis, recipient)
    LockApprovedAssets.runWith(frame) isE ()

    prepareStack(ALPH.oneAlph, ALPH.alph(2), validTimestamp.millis)
    LockApprovedAssets.runWith(frame).leftValue isE a[NoAssetsApproved]
  }

  it should "TransferAlph" in new StatefulInstrFixture {
    val from               = lockupScriptGen.sample.get
    val to                 = assetLockupScriptGen.sample.get
    val randomLockupScript = lockupScriptGen.sample.get

    def test(
        frame: Frame[StatefulContext],
        amount: U256,
        received: U256,
        fromLockupScriptOpt: Option[LockupScript] = None,
        toLockupScriptOpt: Option[LockupScript] = None
    ) = {
      val toLockupScript = toLockupScriptOpt.getOrElse(to)
      frame.opStack.push(Val.Address(fromLockupScriptOpt.getOrElse(from)))
      frame.opStack.push(Val.Address(toLockupScript))
      frame.opStack.push(Val.U256(amount))
      runAndCheckGas(TransferAlph, None, frame)
      frame.ctx.outputBalances
        .getBalances(toLockupScript)
        .map(_.attoAlphAmount)
        .getOrElse(U256.Zero) is received
    }
    def fail(
        frame: Frame[StatefulContext],
        amount: U256,
        remain: U256,
        fromLockupScriptOpt: Option[LockupScript] = None,
        toLockupScriptOpt: Option[LockupScript] = None
    ) = {
      val error =
        intercept[AssertionError](
          test(frame, amount, U256.Zero, fromLockupScriptOpt, toLockupScriptOpt)
        )
      if (toLockupScriptOpt.exists(_.isInstanceOf[LockupScript.P2C])) {
        val address = Address.contract(contractAddress.contractId)
        error.getMessage is Right(PayToContractAddressNotInCallerTrace(address)).toString
      } else {
        val f = fromLockupScriptOpt.getOrElse(from)
        error.getMessage is Right(
          NotEnoughApprovedBalance(f, TokenId.alph, amount, remain)
        ).toString
      }
    }

    val balanceState0 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
    val genesisFrame  = preparePreLemanFrame(Some(balanceState0))
    test(genesisFrame, ALPH.oneNanoAlph, ALPH.oneNanoAlph)
    fail(genesisFrame, ALPH.alph(10), ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))
    fail(genesisFrame, U256.Zero, U256.Zero, Some(randomLockupScript))

    val balanceState1 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
    val lemanFrame    = prepareFrame(Some(balanceState1))(NetworkConfigFixture.Leman)
    test(lemanFrame, ALPH.oneNanoAlph, ALPH.oneNanoAlph)
    fail(lemanFrame, ALPH.alph(10), ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))
    fail(lemanFrame, ALPH.oneNanoAlph, U256.Zero, None, Some(contractAddress))
    fail(lemanFrame, U256.Zero, U256.Zero, Some(randomLockupScript))

    val balanceState2 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
    val rhoneFrame    = prepareFrame(Some(balanceState2))(NetworkConfigFixture.Rhone)
    test(rhoneFrame, U256.Zero, U256.Zero, Some(randomLockupScript))
    test(rhoneFrame, ALPH.oneNanoAlph, ALPH.oneNanoAlph)
    fail(rhoneFrame, ALPH.alph(10), ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph))
    fail(rhoneFrame, ALPH.oneNanoAlph, U256.Zero, None, Some(contractAddress))
    test(rhoneFrame, U256.Zero, ALPH.oneNanoAlph, Some(randomLockupScript))
    fail(rhoneFrame, U256.One, U256.Zero, Some(randomLockupScript))
  }

  trait ContractOutputFixture extends StatefulInstrFixture {
    val contractOutput =
      ContractOutput(ALPH.alph(0), contractLockupScriptGen.sample.get, AVector.empty)
    val txId              = TransactionId.generate
    val contractOutputRef = ContractOutputRef.from(txId, contractOutput, 0)
    val contractId        = ContractId.random
  }

  it should "TransferAlphFromSelf" in new ContractOutputFixture {
    val from = LockupScript.P2C(contractId)
    val to   = assetLockupScriptGen.sample.get

    def test(
        frame: Frame[StatefulContext],
        amount: U256,
        received: U256,
        toLockupScriptOpt: Option[LockupScript] = None
    ) = {
      val toLockupScript = toLockupScriptOpt.getOrElse(to)
      frame.opStack.push(Val.Address(toLockupScript))
      frame.opStack.push(Val.U256(amount))
      runAndCheckGas(TransferAlphFromSelf, None, frame)
      frame.ctx.outputBalances
        .getBalances(toLockupScript)
        .map(_.attoAlphAmount)
        .getOrElse(U256.Zero) is received
    }
    def fail(
        frame: Frame[StatefulContext],
        amount: U256,
        remain: U256,
        toLockupScriptOpt: Option[LockupScript] = None
    ) = {
      val error =
        intercept[AssertionError](test(frame, amount, U256.Zero, toLockupScriptOpt))
      if (toLockupScriptOpt.exists(_.isInstanceOf[LockupScript.P2C])) {
        val address = Address.contract(contractAddress.contractId)
        error.getMessage is Right(PayToContractAddressNotInCallerTrace(address)).toString
      } else {
        error.getMessage is Right(
          NotEnoughApprovedBalance(from, TokenId.alph, amount, remain)
        ).toString
      }
    }

    val balanceState0 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
    val genesisFrame =
      preparePreLemanFrame(
        Some(balanceState0),
        Some((contractId, contractOutput, contractOutputRef))
      )
    test(genesisFrame, U256.Zero, U256.Zero)
    fail(genesisFrame, ALPH.alph(2), ALPH.oneAlph)
    test(genesisFrame, ALPH.oneNanoAlph, ALPH.oneNanoAlph)

    val balanceState1 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
    val sinceLemanFrame =
      prepareFrame(Some(balanceState1), Some((contractId, contractOutput, contractOutputRef)))(
        NetworkConfigFixture.SinceLeman
      )
    test(sinceLemanFrame, U256.Zero, U256.Zero)
    fail(sinceLemanFrame, ALPH.alph(2), ALPH.oneAlph)
    test(sinceLemanFrame, ALPH.oneNanoAlph, ALPH.oneNanoAlph)
    fail(sinceLemanFrame, ALPH.oneNanoAlph, U256.Zero, Some(contractAddress))

    val frameWithEmptyBalanceState =
      prepareFrame(
        Some(MutBalanceState.empty),
        Some((contractId, contractOutput, contractOutputRef))
      )
    test(frameWithEmptyBalanceState, U256.Zero, U256.Zero)
    fail(frameWithEmptyBalanceState, U256.One, U256.Zero)
  }

  it should "TransferAlphToSelf" in new ContractOutputFixture {
    val from               = lockupScriptGen.sample.get
    val to                 = LockupScript.P2C(contractId)
    val randomLockupScript = lockupScriptGen.sample.get

    def test(
        frame: Frame[StatefulContext],
        amount: U256,
        received: U256,
        fromLockupScriptOpt: Option[LockupScript] = None
    ) = {
      frame.opStack.push(Val.Address(fromLockupScriptOpt.getOrElse(from)))
      frame.opStack.push(Val.U256(amount))
      runAndCheckGas(TransferAlphToSelf, None, frame)
      frame.ctx.outputBalances
        .getBalances(to)
        .map(_.attoAlphAmount)
        .getOrElse(U256.Zero) is received
    }
    def fail(
        frame: Frame[StatefulContext],
        amount: U256,
        remain: U256,
        fromLockupScriptOpt: Option[LockupScript]
    ) = {
      val error =
        intercept[AssertionError](test(frame, amount, U256.Zero, fromLockupScriptOpt))
      val f = fromLockupScriptOpt.getOrElse(from)
      error.getMessage is Right(NotEnoughApprovedBalance(f, TokenId.alph, amount, remain)).toString
    }

    val balanceState0 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
    val preRhoneFrame =
      preparePreRhoneFrame(
        Some(balanceState0),
        Some((contractId, contractOutput, contractOutputRef))
      )
    test(preRhoneFrame, U256.Zero, U256.Zero)
    test(preRhoneFrame, ALPH.oneNanoAlph, ALPH.oneNanoAlph)
    fail(preRhoneFrame, U256.Zero, U256.Zero, Some(randomLockupScript))

    val balanceState1 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
    val rhoneFrame =
      prepareFrame(Some(balanceState1), Some((contractId, contractOutput, contractOutputRef)))(
        NetworkConfigFixture.Rhone
      )
    test(rhoneFrame, U256.Zero, U256.Zero)
    test(rhoneFrame, U256.Zero, U256.Zero, Some(randomLockupScript))
    test(rhoneFrame, ALPH.oneNanoAlph, ALPH.oneNanoAlph)
    test(rhoneFrame, U256.Zero, ALPH.oneNanoAlph, Some(randomLockupScript))
    fail(rhoneFrame, U256.One, U256.Zero, Some(randomLockupScript))
  }

  trait PayGasFeeFixture extends ContractOutputFixture {
    def contractBalance: U256
    def gasFeePaid: U256
    def gasFeePaying: U256 = gasFeePaid

    val from  = LockupScript.P2C(contractId)
    val txEnv = genTxEnv(None, AVector.empty)
    def balanceState =
      MutBalanceState(remaining = alphBalance(from, contractBalance), approved = MutBalances.empty)

    override lazy val frame = {
      val frame = prepareFrame(
        Some(balanceState),
        Some((contractId, contractOutput, contractOutputRef)),
        Some(txEnv)
      )
      frame.opStack.push(Val.Address(from))
      frame.opStack.push(Val.U256(gasFeePaying))
      frame
    }

    def success() = {
      runAndCheckGas(PayGasFee)

      frame.ctx.gasFeePaid is gasFeePaid
      frame.getBalanceState().rightValue.remaining.getAttoAlphAmount(from).value is contractBalance
        .sub(
          gasFeePaid
        )
        .getOrElse(U256.Zero)
      stack.size is 0
    }
  }

  it should "not pay when contract has no available fund [PayGasFee]" in new PayGasFeeFixture {
    override def contractBalance: U256 = U256.Zero
    override def gasFeePaid: U256      = U256.Zero

    success()
  }

  it should "pay partial gas that contract has available fund for [PayGasFee]" in new PayGasFeeFixture {
    lazy val halfGas                   = txEnv.gasFeeUnsafe.div(2).get
    override def contractBalance: U256 = halfGas
    override def gasFeePaid: U256      = halfGas

    success()
  }

  it should "pay all gas if contract has enough fund [PayGasFee]" in new PayGasFeeFixture {
    lazy val twiceGas                  = txEnv.gasFeeUnsafe.mul(2).get
    override def contractBalance: U256 = minimalAlphInContract.addUnsafe(twiceGas)
    override def gasFeePaid: U256      = txEnv.gasFeeUnsafe

    success()
  }

  it should "pay gas from ALPH, not enough approved balance [PayGasFee]" in new PayGasFeeFixture {
    lazy val halfGas                   = txEnv.gasFeeUnsafe.div(2).get
    override def contractBalance: U256 = halfGas.subUnsafe(1)
    override def gasFeePaid: U256      = halfGas

    PayGasFee.runWith(frame).leftValue isE a[NotEnoughApprovedBalance]
  }

  it should "pay gas from ALPH, paying too much gas [PayGasFee]" in new PayGasFeeFixture {
    override def contractBalance: U256 = txEnv.gasFeeUnsafe.addUnsafe(1)
    override def gasFeePaid: U256      = txEnv.gasFeeUnsafe.addUnsafe(1)

    PayGasFee.runWith(frame).leftValue isE GasOverPaid
  }

  trait TransferTokenFixture extends ContractOutputFixture {
    def instr: Instr[StatefulContext] with GasSimple
    def from: LockupScript
    def to: LockupScript
    def contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)]
    def prepareOpStack(frame: Frame[StatefulContext]): ExeResult[Unit]

    def createBalanceState(tokenId: TokenId, address: LockupScript, amount: U256) = {
      MutBalanceState.from(tokenBalance(address, tokenId, amount))
    }

    private def test(
        frame: Frame[StatefulContext],
        tokenId: TokenId,
        amount: U256,
        outputBalances: MutBalances
    ) = {
      prepareOpStack(frame)
      frame.opStack.push(Val.ByteVec(tokenId.bytes))
      frame.opStack.push(Val.U256(amount))

      runAndCheckGas(instr, None, frame)

      frame.ctx.outputBalances is outputBalances
    }

    private def fail(
        frame: Frame[StatefulContext],
        tokenId: TokenId,
        amount: U256
    ) = {
      intercept[AssertionError](
        test(frame, tokenId, amount, MutBalances.empty)
      ).getMessage is Right(NotEnoughApprovedBalance(from, tokenId, amount, U256.Zero)).toString
    }

    // scalastyle:off method.length
    def testTransferToken() = {
      val randomTokenId = TokenId.generate
      val balanceState0 = createBalanceState(tokenId, from, ALPH.oneAlph)
      val genesisFrame0 =
        preparePreLemanFrame(Some(balanceState0), contractOutputOpt)
      val outputBalances0 = MutBalances(
        ArrayBuffer((to, MutBalancesPerLockup.token(tokenId, ALPH.oneNanoAlph)))
      )
      test(genesisFrame0, tokenId, ALPH.oneNanoAlph, outputBalances0)
      fail(genesisFrame0, randomTokenId, U256.Zero)

      val balanceState1 = createBalanceState(TokenId.alph, from, ALPH.oneAlph)
      val genesisFrame1 =
        preparePreLemanFrame(Some(balanceState1), contractOutputOpt)
      val outputBalances1 = MutBalances(
        ArrayBuffer((to, MutBalancesPerLockup.token(TokenId.alph, ALPH.oneNanoAlph)))
      )
      test(genesisFrame1, TokenId.alph, ALPH.oneNanoAlph, outputBalances1)

      val balanceState2 = createBalanceState(tokenId, from, ALPH.oneAlph)
      val lemanFrame0 =
        prepareFrame(Some(balanceState2), contractOutputOpt)(NetworkConfigFixture.Leman)
      val outputBalances2 = MutBalances(
        ArrayBuffer((to, MutBalancesPerLockup.token(tokenId, ALPH.oneNanoAlph)))
      )
      test(lemanFrame0, tokenId, ALPH.oneNanoAlph, outputBalances2)
      fail(lemanFrame0, TokenId.generate, U256.Zero)

      val balanceState3 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
      val lemanFrame1 =
        prepareFrame(Some(balanceState3), contractOutputOpt)(NetworkConfigFixture.Leman)
      val outputBalances3 = MutBalances(
        ArrayBuffer((to, MutBalancesPerLockup.alph(ALPH.oneNanoAlph)))
      )
      test(lemanFrame1, TokenId.alph, ALPH.oneNanoAlph, outputBalances3)

      val balanceState4 = createBalanceState(tokenId, from, ALPH.oneAlph)
      val rhoneFrame0 =
        prepareFrame(Some(balanceState4), contractOutputOpt)(NetworkConfigFixture.Rhone)
      val outputBalances4 = MutBalances(
        ArrayBuffer((to, MutBalancesPerLockup.token(tokenId, ALPH.oneNanoAlph)))
      )
      test(rhoneFrame0, tokenId, ALPH.oneNanoAlph, outputBalances4)
      test(rhoneFrame0, randomTokenId, U256.Zero, outputBalances4)
      fail(rhoneFrame0, randomTokenId, U256.One)

      val balanceState5 = MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
      val rhoneFrame1 =
        prepareFrame(Some(balanceState5), contractOutputOpt)(NetworkConfigFixture.Rhone)
      val outputBalances5 = MutBalances(
        ArrayBuffer((to, MutBalancesPerLockup.alph(ALPH.oneNanoAlph)))
      )
      test(rhoneFrame1, TokenId.alph, ALPH.oneNanoAlph, outputBalances5)

      val rhoneFrame2 =
        prepareFrame(Some(MutBalanceState.empty), contractOutputOpt)(NetworkConfigFixture.Rhone)
      test(rhoneFrame2, tokenId, U256.Zero, MutBalances.empty)
      fail(rhoneFrame2, tokenId, U256.One)
      test(rhoneFrame2, TokenId.alph, U256.Zero, MutBalances.empty)
      fail(rhoneFrame2, TokenId.alph, U256.One)
      test(rhoneFrame2, randomTokenId, U256.Zero, MutBalances.empty)
      fail(rhoneFrame2, randomTokenId, U256.One)
    }
    // scalastyle:on method.length
  }

  it should "TransferToken" in new TransferTokenFixture {
    val instr: Instr[StatefulContext] with GasSimple = TransferToken
    val from                                         = lockupScriptGen.sample.get
    val to: LockupScript                             = assetLockupScriptGen.sample.get
    val contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)] = None

    def prepareOpStack(frame: Frame[StatefulContext]): ExeResult[Unit] = {
      frame.opStack.push(Val.Address(from))
      frame.opStack.push(Val.Address(to))
    }

    testTransferToken()

    override lazy val frame = prepareFrame(Some(createBalanceState(tokenId, from, ALPH.oneAlph)))(
      NetworkConfigFixture.SinceLeman
    )

    stack.push(Val.Address(from))
    stack.push(Val.Address(contractAddress))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.oneNanoAlph))
    TransferToken.runWith(frame).leftValue isE a[PayToContractAddressNotInCallerTrace]

    stack.push(Val.Address(from))
    stack.push(Val.Address(to))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.alph(2)))
    TransferToken.runWith(frame).leftValue isE NotEnoughApprovedBalance(
      from,
      tokenId,
      ALPH.alph(2),
      ALPH.oneAlph
    )
  }

  it should "TransferTokenFromSelf" in new TransferTokenFixture {
    val instr: Instr[StatefulContext] with GasSimple = TransferTokenFromSelf
    val from: LockupScript                           = LockupScript.P2C(contractId)
    val to: LockupScript                             = assetLockupScriptGen.sample.get
    val contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)] =
      Some((contractId, contractOutput, contractOutputRef))

    def prepareOpStack(frame: Frame[StatefulContext]): ExeResult[Unit] = {
      frame.opStack.push(Val.Address(to))
    }

    testTransferToken()

    override lazy val frame =
      prepareFrame(Some(createBalanceState(tokenId, from, ALPH.oneAlph)), contractOutputOpt)(
        NetworkConfigFixture.SinceLeman
      )

    stack.push(Val.Address(contractAddress))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.oneNanoAlph))
    TransferTokenFromSelf.runWith(frame).leftValue isE a[PayToContractAddressNotInCallerTrace]
  }

  it should "TransferTokenToSelf" in new TransferTokenFixture {
    val instr: Instr[StatefulContext] with GasSimple = TransferTokenToSelf
    val from: LockupScript                           = lockupScriptGen.sample.get
    val to: LockupScript                             = LockupScript.P2C(contractId)
    val contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)] =
      Some((contractId, contractOutput, contractOutputRef))

    def prepareOpStack(frame: Frame[StatefulContext]): ExeResult[Unit] = {
      frame.opStack.push(Val.Address(from))
    }

    testTransferToken()
  }

  it should "generate contract id for different network" in new StatefulInstrFixture {
    val groupIndex   = GroupIndex.random
    val path         = Hash.random.bytes
    val genesisFrame = preparePreLemanFrame()
    val lemanFrame   = prepareFrame()(NetworkConfigFixture.Leman)

    CreateContractAbstract.getContractId(genesisFrame, false, groupIndex).rightValue is
      ContractId.deprecatedFrom(genesisFrame.ctx.txId, 0)

    CreateContractAbstract.getContractId(lemanFrame, false, groupIndex).rightValue is
      ContractId.from(lemanFrame.ctx.txId, 0, groupIndex)

    genesisFrame.pushOpStack(Val.ByteVec(path))
    intercept[RuntimeException](
      CreateContractAbstract.getContractId(genesisFrame, true, groupIndex)
    ).getMessage is "Dead branch while creating a new contract"

    lemanFrame.pushOpStack(Val.ByteVec(path))
    CreateContractAbstract.getContractId(lemanFrame, true, groupIndex).rightValue is
      ContractId.subContract(lemanFrame.obj.getContractId().rightValue.bytes ++ path, groupIndex)
  }

  trait CreateContractAbstractFixture extends StatefulInstrFixture {
    val from              = lockupScriptGen.sample.get
    val (tx, prevOutputs) = transactionGenWithPreOutputs().sample.get
    val immFields         = AVector[Val](Val.False)
    val mutFields         = AVector[Val](Val.True)
    val contractBytes     = serialize(contract)

    val immState = Val.ByteVec(serialize(immFields))
    val mutState = Val.ByteVec(serialize(mutFields))

    val balanceState = MutBalanceState(
      MutBalances.empty,
      alphBalance(from, ALPH.oneAlph)
    )

    val callerFrame = prepareFrame().asInstanceOf[StatefulFrame]
    override lazy val frame = prepareFrame(
      Some(balanceState),
      txEnvOpt = Some(
        TxEnv.dryrun(
          tx,
          prevOutputs.map(_.referredOutput),
          Stack.ofCapacity[Byte64](0)
        )
      ),
      callerFrameOpt = Some(callerFrame)
    )
    lazy val fromContractId = frame.obj.contractIdOpt.get

    def getSubContractId(path: String): ContractId = {
      fromContractId.subContractId(serialize(path), frame.ctx.blockEnv.chainIndex.from)
    }

    def createContract(instr: ContractFactory) = {
      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      val extraGas = instr match {
        case CreateContract | CreateContractWithToken | CreateContractAndTransferToken =>
          contractBytes.length + 200 // 200 from GasSchedule.callGas
        case CopyCreateContract | CopyCreateContractWithToken |
            CopyCreateContractAndTransferToken =>
          801 // 801 from contractLoadGas
        case CreateSubContract | CreateSubContractWithToken | CreateSubContractAndTransferToken |
            CreateMapEntry(_, _) =>
          contractBytes.length + 314
        case CopyCreateSubContract | CopyCreateSubContractWithToken |
            CopyCreateSubContractAndTransferToken =>
          915
      }
      initialGas.subUnsafe(frame.ctx.gasRemaining) is GasBox.unsafe(
        instr.gas().value + immFields.length + mutFields.length + extraGas
      )
    }

    def checkContractState(
        instr: ContractFactory,
        contractId: ContractId,
        attoAlphAmount: U256,
        tokens: AVector[(TokenId, U256)],
        tokenAmount: Option[U256]
    ) = {
      val contractState = frame.ctx.worldState.getContractState(contractId).rightValue
      contractState.immFields is immFields
      contractState.mutFields is mutFields
      val contractOutput =
        frame.ctx.worldState.getContractAsset(contractState.contractOutputRef).rightValue
      val tokenId = TokenId.from(contractId)
      val allTokens = tokenAmount match {
        case Some(amount) => tokens :+ (tokenId -> amount)
        case None         => tokens
      }
      contractOutput.tokens.toSet is allTokens.toSet
      contractOutput.amount is attoAlphAmount
      val code = frame.ctx.worldState.getContractCode(contractState).rightValue
      code.toContract() isE contract

      val event = frame.ctx.worldState.nodeIndexesState.logState.getNewLogs().last.states.last
      event.index is createContractEventIndexInt.toByte
      event.fields(0) is Val.Address(Address.contract(contractId).lockupScript)
      if (instr.subContract) {
        event.fields(1) is Val.Address(Address.contract(frame.obj.contractIdOpt.value).lockupScript)
      } else {
        event.fields(1) is Val.ByteVec(ByteString.empty)
      }
    }

    def test(
        instr: ContractFactory,
        attoAlphAmount: U256,
        tokens: AVector[(TokenId, U256)],
        tokenAmount: Option[U256],
        expectedContractId: Option[ContractId] = None
    ) = {
      createContract(instr)
      frame.opStack.size is 1
      val contractId = ContractId.from(frame.popOpStackByteVec().rightValue.bytes).get
      expectedContractId.foreach { _ is contractId }
      checkContractState(instr, contractId, attoAlphAmount, tokens, tokenAmount)
    }

    val rhoneInstrs: AVector[Instr[StatefulContext]] = AVector(
      GroupOfAddress,
      PayGasFee,
      MinimalContractDeposit,
      CreateMapEntry(0, 0),
      MethodSelector(Method.Selector(0)),
      CallExternalBySelector(Method.Selector(0))
    )
    val invalidMethod        = baseMethod.copy(instrs = AVector(rhoneInstrs.shuffle().head))
    val invalidContract      = contract.copy(methods = AVector(invalidMethod))
    val invalidContractBytes = serialize(invalidContract)

    def testInactiveInstrs(instr: ContractFactory, values: AVector[Val]) = {
      val preRhoneFrame = prepareFrame(
        Some(balanceState),
        txEnvOpt = Some(
          TxEnv.dryrun(
            tx,
            prevOutputs.map(_.referredOutput),
            Stack.ofCapacity[Byte64](0)
          )
        ),
        callerFrameOpt = Some(callerFrame)
      )(NetworkConfigFixture.Leman)
      values.foreach(preRhoneFrame.opStack.push)
      // Inactive instrs check will be enabled in future upgrades
      instr.runWith(preRhoneFrame).leftValue isnotE a[InactiveInstr[_]]
    }
  }

  it should "CreateContract" in new CreateContractAbstractFixture {
    val values: AVector[Val] = AVector(Val.ByteVec(contractBytes), immState, mutState)
    values.foreach(stack.push)
    test(CreateContract, ALPH.oneAlph, AVector.empty, None)

    testInactiveInstrs(CreateContract, values.replace(0, Val.ByteVec(invalidContractBytes)))
  }

  it should "CreateContractWithToken" in new CreateContractAbstractFixture {
    val values: AVector[Val] =
      AVector(Val.ByteVec(contractBytes), immState, mutState, Val.U256(ALPH.oneNanoAlph))
    values.foreach(stack.push)
    test(
      CreateContractWithToken,
      ALPH.oneAlph,
      AVector.empty,
      Some(ALPH.oneNanoAlph)
    )

    testInactiveInstrs(
      CreateContractWithToken,
      values.replace(0, Val.ByteVec(invalidContractBytes))
    )
  }

  it should "CreateContractAndTransferToken" in new CreateContractAbstractFixture {
    {
      info("create contract and transfer token")

      val values: AVector[Val] = AVector(
        Val.ByteVec(contractBytes),
        immState,
        mutState,
        Val.U256(ALPH.oneNanoAlph),
        Val.Address(assetLockupScriptGen.sample.get)
      )
      values.foreach(stack.push)
      test(
        CreateContractAndTransferToken,
        ALPH.oneAlph,
        AVector.empty,
        tokenAmount = None
      )

      testInactiveInstrs(
        CreateContractAndTransferToken,
        values.replace(0, Val.ByteVec(invalidContractBytes))
      )
    }

    {
      info("can only transfer to asset address")

      stack.push(Val.ByteVec(contractBytes))
      stack.push(immState)
      stack.push(mutState)
      stack.push(Val.U256(ALPH.oneNanoAlph))
      stack.push(Val.Address(contractLockupScriptGen.sample.get))

      CreateContractAndTransferToken.runWith(frame).leftValue isE a[InvalidAssetAddress]
    }
  }

  it should "CreateSubContract" in new CreateContractAbstractFixture {
    val values: AVector[Val] =
      AVector(Val.ByteVec(serialize("nft-01")), Val.ByteVec(contractBytes), immState, mutState)
    values.foreach(stack.push)
    val subContractId = getSubContractId("nft-01")
    test(CreateSubContract, ALPH.oneAlph, AVector.empty, None, Some(subContractId))

    testInactiveInstrs(CreateSubContract, values.replace(1, Val.ByteVec(invalidContractBytes)))
  }

  it should "CreateSubContractWithToken" in new CreateContractAbstractFixture {
    val values: AVector[Val] = AVector(
      Val.ByteVec(serialize("nft-01")),
      Val.ByteVec(contractBytes),
      immState,
      mutState,
      Val.U256(ALPH.oneNanoAlph)
    )
    values.foreach(stack.push)
    val subContractId = getSubContractId("nft-01")
    test(
      CreateSubContractWithToken,
      ALPH.oneAlph,
      AVector.empty,
      Some(ALPH.oneNanoAlph),
      Some(subContractId)
    )

    testInactiveInstrs(
      CreateSubContractWithToken,
      values.replace(1, Val.ByteVec(invalidContractBytes))
    )
  }

  it should "CreateSubContractAndTransferToken" in new CreateContractAbstractFixture {
    {
      info("create sub contract and transfer token")

      val values: AVector[Val] = AVector(
        Val.ByteVec(serialize("nft-01")),
        Val.ByteVec(contractBytes),
        immState,
        mutState,
        Val.U256(ALPH.oneNanoAlph),
        Val.Address(assetLockupScriptGen.sample.get)
      )
      values.foreach(stack.push)
      val subContractId = getSubContractId("nft-01")
      test(
        CreateSubContractAndTransferToken,
        ALPH.oneAlph,
        AVector.empty,
        tokenAmount = None,
        Some(subContractId)
      )

      testInactiveInstrs(
        CreateSubContractAndTransferToken,
        values.replace(1, Val.ByteVec(invalidContractBytes))
      )
    }

    {
      info("can only transfer to asset address")

      stack.push(Val.ByteVec(serialize("nft-01")))
      stack.push(Val.ByteVec(contractBytes))
      stack.push(immState)
      stack.push(mutState)
      stack.push(Val.U256(ALPH.oneNanoAlph))
      stack.push(Val.Address(contractLockupScriptGen.sample.get))

      CreateSubContractAndTransferToken.runWith(frame).leftValue isE a[InvalidAssetAddress]
    }
  }

  it should "CreateMapEntry" in new CreateContractAbstractFixture {
    override lazy val contract = CreateMapEntry.genContract(immFields.length, mutFields.length)

    stack.push(Val.ByteVec(serialize("entity")))
    immFields.foreach(stack.push)
    mutFields.foreach(stack.push)

    val subContractId = getSubContractId("entity")
    val instr         = CreateMapEntry(immFields.length.toByte, mutFields.length.toByte)
    createContract(instr)
    frame.opStack.size is 0
    checkContractState(instr, subContractId, ALPH.oneAlph, AVector.empty, None)
  }

  it should "check external method arg and return length" in new ContextGenerators {
    // scalastyle:off method.length
    def prepareFrame(lengthOpt: Option[(U256, U256)])(implicit
        networkConfig: NetworkConfig
    ): Frame[StatefulContext] = {
      val contractMethod = Method.testDefault[StatefulContext](
        isPublic = true,
        argsLength = 1,
        localsLength = 1,
        returnLength = 1,
        instrs = AVector(LoadLocal(0), Return)
      )
      val contract   = StatefulContract(0, AVector(contractMethod))
      val (obj, ctx) = prepareContract(contract, AVector.empty[Val], AVector.empty[Val])
      val instrs = AVector[Instr[StatefulContext]](
        BytesConst(Val.ByteVec(obj.contractId.bytes)),
        CallExternal(0)
      )
      val scriptMethod = Method.testDefault[StatefulContext](
        isPublic = true,
        argsLength = 0,
        localsLength = 0,
        returnLength = 0,
        instrs = lengthOpt match {
          case Some((argLength, retLength)) =>
            AVector[Instr[StatefulContext]](
              U256Const0,
              ConstInstr.u256(Val.U256(argLength)),
              ConstInstr.u256(Val.U256(retLength))
            ) ++ instrs
          case _ => U256Const0 +: instrs
        }
      )
      val script         = StatefulScript.from(AVector(scriptMethod)).get
      val (scriptObj, _) = prepareStatefulScript(script)
      Frame
        .stateful(
          ctx,
          None,
          None,
          scriptObj,
          script.methods(0),
          AVector.empty,
          Stack.ofCapacity(10),
          _ => okay
        )
        .rightValue
    }

    prepareFrame(None)(NetworkConfigFixture.Genesis).execute().isRight is true
    prepareFrame(Some((U256.One, U256.One)))(NetworkConfigFixture.Genesis)
      .execute()
      .isRight is true

    prepareFrame(None)(NetworkConfigFixture.Leman)
      .execute()
      .leftValue isE a[InvalidExternalMethodReturnLength]

    prepareFrame(Some((U256.One, U256.One)))(NetworkConfigFixture.Leman).execute().isRight is true
    prepareFrame(Some((U256.One, U256.Zero)))(NetworkConfigFixture.Leman)
      .execute()
      .leftValue isE a[InvalidExternalMethodReturnLength]

    prepareFrame(Some((U256.One, U256.Two)))(NetworkConfigFixture.Leman)
      .execute()
      .leftValue isE a[InvalidExternalMethodReturnLength]

    prepareFrame(Some((U256.One, U256.MaxValue)))(NetworkConfigFixture.Leman)
      .execute()
      .leftValue is Right(
      InvalidReturnLength
    )
    prepareFrame(Some((U256.Zero, U256.One)))(NetworkConfigFixture.Leman)
      .execute()
      .leftValue isE a[InvalidExternalMethodArgLength]

    prepareFrame(Some((U256.Two, U256.One)))(NetworkConfigFixture.Leman)
      .execute()
      .leftValue isE a[InvalidExternalMethodArgLength]

    prepareFrame(Some((U256.MaxValue, U256.One)))(NetworkConfigFixture.Leman)
      .execute()
      .leftValue is Right(
      InvalidArgLength
    )
  }

  it should "check method modifier when creating contract" in new StatefulFixture {
    val from = lockupScriptGen.sample.get

    val preLemanFrame = (balanceState: MutBalanceState) =>
      prepareFrame(
        Some(balanceState),
        immFields = AVector.empty,
        mutFields = AVector(Val.True, Val.False)
      )(NetworkConfigFixture.Genesis)
    val lemanFrame =
      (balanceState: MutBalanceState) =>
        prepareFrame(Some(balanceState))(NetworkConfigFixture.Leman)
    val rhoneFrame =
      (balanceState: MutBalanceState) =>
        prepareFrame(Some(balanceState))(NetworkConfigFixture.Rhone)

    val contract0 =
      StatefulContract(0, AVector(Method(true, true, true, false, false, 0, 0, 0, AVector.empty)))
    val contract1 =
      StatefulContract(0, AVector(Method(true, false, false, false, false, 0, 0, 0, AVector.empty)))
    val contract2 =
      StatefulContract(0, AVector(Method(true, true, false, false, false, 0, 0, 0, AVector.empty)))
    val contract3 =
      StatefulContract(0, AVector(Method(true, false, true, false, false, 0, 0, 0, AVector.empty)))
    val contract4 =
      StatefulContract(0, AVector(Method(true, true, true, true, false, 0, 0, 0, AVector.empty)))
    val contract5 =
      StatefulContract(0, AVector(Method(true, false, false, true, false, 0, 0, 0, AVector.empty)))
    val contract6 =
      StatefulContract(0, AVector(Method(true, true, false, true, false, 0, 0, 0, AVector.empty)))
    val contract7 =
      StatefulContract(0, AVector(Method(true, false, true, true, false, 0, 0, 0, AVector.empty)))

    def testModifier(
        instr: Instr[StatefulContext],
        frameBuilder: MutBalanceState => Frame[StatefulContext],
        contract: StatefulContract,
        succeeded: Boolean
    ) = {
      val balanceState =
        MutBalanceState(MutBalances.empty, tokenBalance(from, tokenId, ALPH.oneAlph))
      val frame = frameBuilder(balanceState)
      frame.opStack.push(Val.ByteVec(serialize(contract)))
      if (frame.ctx.getHardFork().isLemanEnabled()) {
        // push immutable fields
        frame.opStack.push(Val.ByteVec(serialize(AVector.empty[Val])))
      }
      frame.opStack.push(Val.ByteVec(serialize(AVector.empty[Val])))
      if (instr.isInstanceOf[CreateContractWithToken.type]) {
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      }
      if (succeeded) {
        instr.runWith(frame) isE ()
      } else {
        instr.runWith(frame).leftValue.toString.contains("InvalidMethodModifier") is true
      }
    }

    testModifier(CreateContract, rhoneFrame, contract0, true)
    testModifier(CreateContract, rhoneFrame, contract1, true)
    testModifier(CreateContract, rhoneFrame, contract2, true)
    testModifier(CreateContract, rhoneFrame, contract3, true)
    testModifier(CreateContract, rhoneFrame, contract4, false)
    testModifier(CreateContract, rhoneFrame, contract5, true)
    testModifier(CreateContract, rhoneFrame, contract6, true)
    testModifier(CreateContract, rhoneFrame, contract7, false)
    testModifier(CreateContract, lemanFrame, contract0, true)
    testModifier(CreateContract, lemanFrame, contract1, true)
    testModifier(CreateContract, lemanFrame, contract2, true)
    testModifier(CreateContract, lemanFrame, contract3, true)
    testModifier(CreateContract, lemanFrame, contract4, false)
    testModifier(CreateContract, lemanFrame, contract5, false)
    testModifier(CreateContract, lemanFrame, contract6, false)
    testModifier(CreateContract, lemanFrame, contract7, false)
    testModifier(CreateContract, preLemanFrame, contract0, true)
    testModifier(CreateContract, preLemanFrame, contract1, true)
    testModifier(CreateContract, preLemanFrame, contract2, false)
    testModifier(CreateContract, preLemanFrame, contract3, false)
    testModifier(CreateContract, preLemanFrame, contract4, false)
    testModifier(CreateContract, preLemanFrame, contract5, false)
    testModifier(CreateContract, preLemanFrame, contract6, false)
    testModifier(CreateContract, preLemanFrame, contract7, false)

    testModifier(CreateContractWithToken, rhoneFrame, contract0, true)
    testModifier(CreateContractWithToken, rhoneFrame, contract1, true)
    testModifier(CreateContractWithToken, rhoneFrame, contract2, true)
    testModifier(CreateContractWithToken, rhoneFrame, contract3, true)
    testModifier(CreateContractWithToken, rhoneFrame, contract4, false)
    testModifier(CreateContractWithToken, rhoneFrame, contract5, true)
    testModifier(CreateContractWithToken, rhoneFrame, contract6, true)
    testModifier(CreateContractWithToken, rhoneFrame, contract7, false)
    testModifier(CreateContractWithToken, lemanFrame, contract0, true)
    testModifier(CreateContractWithToken, lemanFrame, contract1, true)
    testModifier(CreateContractWithToken, lemanFrame, contract2, true)
    testModifier(CreateContractWithToken, lemanFrame, contract3, true)
    testModifier(CreateContractWithToken, lemanFrame, contract4, false)
    testModifier(CreateContractWithToken, lemanFrame, contract5, false)
    testModifier(CreateContractWithToken, lemanFrame, contract6, false)
    testModifier(CreateContractWithToken, lemanFrame, contract7, false)
    testModifier(CreateContractWithToken, preLemanFrame, contract0, true)
    testModifier(CreateContractWithToken, preLemanFrame, contract1, true)
    testModifier(CreateContractWithToken, preLemanFrame, contract2, false)
    testModifier(CreateContractWithToken, preLemanFrame, contract3, false)
    testModifier(CreateContractWithToken, preLemanFrame, contract4, false)
    testModifier(CreateContractWithToken, preLemanFrame, contract5, false)
    testModifier(CreateContractWithToken, preLemanFrame, contract6, false)
    testModifier(CreateContractWithToken, preLemanFrame, contract7, false)
  }

  it should "CopyCreateContract" in new CreateContractAbstractFixture {
    stack.push(Val.ByteVec(serialize(Hash.generate)))
    stack.push(immState)
    stack.push(mutState)
    CopyCreateContract.runWith(frame).leftValue isE a[NonExistContract]

    stack.push(Val.ByteVec(fromContractId.bytes))
    stack.push(immState)
    stack.push(mutState)
    test(CopyCreateContract, ALPH.oneAlph, AVector.empty, None)
  }

  it should "CopyCreateContractWithToken" in new CreateContractAbstractFixture {
    stack.push(Val.ByteVec(serialize(Hash.generate)))
    stack.push(immState)
    stack.push(mutState)
    stack.push(Val.U256(ALPH.oneNanoAlph))
    CopyCreateContractWithToken.runWith(frame).leftValue isE a[NonExistContract]

    stack.push(Val.ByteVec(fromContractId.bytes))
    stack.push(immState)
    stack.push(mutState)
    stack.push(Val.U256(ALPH.oneNanoAlph))
    test(
      CopyCreateContractWithToken,
      ALPH.oneAlph,
      AVector.empty,
      Some(ALPH.oneNanoAlph)
    )
  }

  it should "CopyCreateContractAndTransferToken" in new CreateContractAbstractFixture {
    val assetAddress = Val.Address(assetLockupScriptGen.sample.get)

    {
      info("create contract and transfer token")

      stack.push(Val.ByteVec(fromContractId.bytes))
      stack.push(immState)
      stack.push(mutState)
      stack.push(Val.U256(ALPH.oneNanoAlph))
      stack.push(assetAddress)
      test(
        CopyCreateContractAndTransferToken,
        ALPH.oneAlph,
        AVector.empty,
        tokenAmount = None
      )
    }

    {
      info("non existent contract")

      stack.push(Val.ByteVec(serialize(Hash.generate)))
      stack.push(immState)
      stack.push(mutState)
      stack.push(Val.U256(ALPH.oneNanoAlph))
      stack.push(Val.Address(assetLockupScriptGen.sample.get))
      CopyCreateContractAndTransferToken.runWith(frame).leftValue isE a[NonExistContract]
    }

    {
      info("can only transfer to asset address")

      stack.push(Val.ByteVec(serialize(Hash.generate)))
      stack.push(immState)
      stack.push(mutState)
      stack.push(Val.U256(ALPH.oneNanoAlph))
      stack.push(Val.Address(contractLockupScriptGen.sample.get))
      CopyCreateContractAndTransferToken.runWith(frame).leftValue isE a[InvalidAssetAddress]
    }
  }

  it should "CopyCreateSubContract" in new CreateContractAbstractFixture {
    stack.push(Val.ByteVec(serialize(Hash.generate)))
    stack.push(immState)
    stack.push(mutState)
    CopyCreateSubContract.runWith(frame).leftValue isE a[NonExistContract]

    stack.push(Val.ByteVec(serialize("nft-01")))
    stack.push(Val.ByteVec(fromContractId.bytes))
    stack.push(immState)
    stack.push(mutState)

    val subContractId = getSubContractId("nft-01")
    test(CopyCreateSubContract, ALPH.oneAlph, AVector.empty, None, Some(subContractId))
  }

  it should "CopyCreateSubContractWithToken" in new CreateContractAbstractFixture {
    stack.push(Val.ByteVec(serialize(Hash.generate)))
    stack.push(immState)
    stack.push(mutState)
    stack.push(Val.U256(ALPH.oneNanoAlph))
    CopyCreateSubContractWithToken.runWith(frame).leftValue isE a[NonExistContract]

    stack.push(Val.ByteVec(serialize("nft-01")))
    stack.push(Val.ByteVec(fromContractId.bytes))
    stack.push(immState)
    stack.push(mutState)
    stack.push(Val.U256(ALPH.oneNanoAlph))

    val subContractId = getSubContractId("nft-01")
    test(
      CopyCreateSubContractWithToken,
      ALPH.oneAlph,
      AVector.empty,
      Some(ALPH.oneNanoAlph),
      Some(subContractId)
    )
  }

  it should "CopyCreateSubContractAndTransferToken" in new CreateContractAbstractFixture {
    val assetAddress = Val.Address(assetLockupScriptGen.sample.get)

    {
      info("copy create sub contract and transfer token")

      stack.push(Val.ByteVec(serialize("nft-01")))
      stack.push(Val.ByteVec(fromContractId.bytes))
      stack.push(immState)
      stack.push(mutState)
      stack.push(Val.U256(ALPH.oneNanoAlph))
      stack.push(assetAddress)

      val subContractId = getSubContractId("nft-01")
      test(
        CopyCreateSubContractAndTransferToken,
        ALPH.oneAlph,
        AVector.empty,
        tokenAmount = None,
        Some(subContractId)
      )
    }

    {
      info("non existent contract")

      stack.push(Val.ByteVec(serialize("nft-01")))
      stack.push(Val.ByteVec(serialize(Hash.generate)))
      stack.push(immState)
      stack.push(mutState)
      stack.push(Val.U256(ALPH.oneNanoAlph))
      stack.push(assetAddress)

      CopyCreateSubContractAndTransferToken.runWith(frame).leftValue isE a[NonExistContract]
    }

    {
      info("can only transfer to asset address")

      stack.push(Val.ByteVec(serialize("nft-01")))
      stack.push(Val.ByteVec(serialize(Hash.generate)))
      stack.push(immState)
      stack.push(mutState)
      stack.push(Val.U256(ALPH.oneNanoAlph))
      stack.push(Val.Address(contractLockupScriptGen.sample.get))

      CopyCreateContractAndTransferToken.runWith(frame).leftValue isE a[InvalidAssetAddress]
    }
  }

  it should "ContractExists" in new StatefulInstrFixture {
    val contractOutput =
      ContractOutput(ALPH.alph(1), contractLockupScriptGen.sample.get, AVector.empty)
    val contractOutputRef = ContractOutputRef.from(TransactionId.generate, contractOutput, 0)
    val contractId        = ContractId.random
    override lazy val frame =
      prepareFrame(contractOutputOpt = Some((contractId, contractOutput, contractOutputRef)))

    stack.push(Val.ByteVec(contractId.bytes))
    runAndCheckGas(ContractExists)
    frame.opStack.top.get is Val.True

    stack.push(Val.ByteVec(Hash.generate.bytes))
    runAndCheckGas(ContractExists)
    frame.opStack.top.get is Val.False
  }

  it should "not DestroySelf if contract asset is not used" in new StatefulInstrFixture {
    val contractOutput =
      ContractOutput(ALPH.alph(0), contractLockupScriptGen.sample.get, AVector.empty)
    val txId = TransactionId.generate

    val contractOutputRef = ContractOutputRef.from(txId, contractOutput, 0)
    val contractId        = ContractId.random

    val callerFrame = prepareFrame().asInstanceOf[StatefulFrame]

    val from = LockupScript.P2C(contractId)

    val balanceState =
      MutBalanceState.from(alphBalance(from, ALPH.oneAlph))
    override lazy val frame =
      prepareFrame(
        Some(balanceState),
        Some((contractId, contractOutput, contractOutputRef)),
        callerFrameOpt = Some(callerFrame)
      )

    stack.push(Val.Address(assetLockupScriptGen.sample.get))

    DestroySelf
      .runWith(frame)
      .leftValue
      .rightValue
      .toString is s"Assets for contract ${Address.contract(contractId).toBase58} is not loaded, please annotate the function with `@using(assetsInContract = true)`"
  }

  trait DestroySelfFixture extends GenFixture {
    // scalastyle:off method.length
    def prepareFrame()(implicit networkConfig: NetworkConfig): Frame[StatefulContext] = {
      val destroyMethod = Method[StatefulContext](
        isPublic = true,
        usePreapprovedAssets = true,
        useContractAssets = true,
        usePayToContractOnly = false,
        useRoutePattern = false,
        argsLength = 0,
        localsLength = 0,
        returnLength = 0,
        instrs = AVector(DestroySelf)
      )

      val destroyContract = StatefulContract(0, AVector(destroyMethod))
      val (destroyContractObj, ctx) =
        prepareContract(destroyContract, AVector.empty[Val], AVector.empty[Val])

      val callingMethod =
        Method.testDefault[StatefulContext](
          isPublic = true,
          argsLength = 0,
          localsLength = 0,
          returnLength = 0,
          instrs = AVector(
            BytesConst(Val.ByteVec(destroyContractObj.contractId.bytes)),
            CallExternal(0)
          )
        )
      val callingContract = StatefulContract(0, AVector(callingMethod))
      val (callingContractObj, _) =
        prepareContract(callingContract, AVector.empty[Val], AVector.empty[Val])

      val balanceState = MutBalanceState.from(
        MutBalances(
          ArrayBuffer(
            (
              LockupScript.P2C(destroyContractObj.contractId),
              MutBalancesPerLockup.alph(ALPH.oneAlph)
            )
          )
        )
      )

      Frame
        .stateful(
          ctx,
          None,
          Some(balanceState),
          callingContractObj,
          callingMethod,
          AVector.empty,
          Stack.ofCapacity(10),
          _ => okay
        )
        .rightValue
    }
    // scalastyle:on method.length
  }

  it should "test DestroySelf and transfer fund to non-calling contract" in new DestroySelfFixture {
    {
      info("Before Leman hardfork")

      val frame        = prepareFrame()(Genesis)
      val destroyFrame = frame.execute().rightValue.value

      destroyFrame.opStack.push(Val.Address(contractLockupScriptGen.sample.get))
      destroyFrame.execute().leftValue.rightValue is InvalidAddressTypeInContractDestroy
    }

    {
      info("After Leman hardfork")

      val frame = prepareFrame()(Leman)
      frame.opStack.push(Val.U256(0))
      frame.opStack.push(Val.U256(0))

      val destroyFrame = frame.execute().rightValue.value

      destroyFrame.opStack.push(Val.Address(contractLockupScriptGen.sample.get))
      destroyFrame.execute().leftValue.rightValue is a[PayToContractAddressNotInCallerTrace]
    }
  }

  it should "test DestroySelf and transfer fund to calling contract" in new DestroySelfFixture {
    {
      info("Should fail before Leman hardfork")

      val frame               = prepareFrame()(Genesis)
      val callingLockupScript = LockupScript.p2c(frame.obj.contractIdOpt.value)

      val destroyFrame = frame.execute().rightValue.value

      destroyFrame.opStack.push(Val.Address(callingLockupScript))
      destroyFrame.execute().leftValue.rightValue is InvalidAddressTypeInContractDestroy
    }

    {
      info("Should succeed after Leman hardfork")

      val frame = prepareFrame()(Leman)
      frame.opStack.push(Val.U256(0))
      frame.opStack.push(Val.U256(0))

      checkDestroyRefundBalance(frame) { destroyFrame =>
        val callingLockupScript = LockupScript.p2c(frame.obj.contractIdOpt.value)
        destroyFrame.opStack.push(Val.Address(callingLockupScript))
        destroyFrame.execute().isRight is true

        callingLockupScript
      }
    }
  }

  it should "test DestroySelf and transfer fund to asset address" in new DestroySelfFixture {
    {
      info("Before Leman hardfork")

      val frame = prepareFrame()(Genesis)

      checkDestroyRefundBalance(frame) { destroyFrame =>
        val assetLockupScript = assetLockupScriptGen.sample.get
        destroyFrame.opStack.push(Val.Address(assetLockupScript))
        destroyFrame.execute().isRight is true

        assetLockupScript
      }
    }

    {
      info("After Leman hardfork")

      val frame = prepareFrame()(Leman)
      frame.opStack.push(Val.U256(0))
      frame.opStack.push(Val.U256(0))

      checkDestroyRefundBalance(frame) { destroyFrame =>
        val assetLockupScript = assetLockupScriptGen.sample.get
        destroyFrame.opStack.push(Val.Address(assetLockupScript))
        destroyFrame.execute().isRight is true

        assetLockupScript
      }
    }
  }

  private def checkDestroyRefundBalance(
      frame: Frame[StatefulContext]
  )(runTest: (Frame[StatefulContext]) => LockupScript) = {
    val destroyFrame         = frame.execute().rightValue.value
    val remainingBalance     = destroyFrame.getBalanceState().rightValue.remaining
    val contractId           = destroyFrame.obj.contractIdOpt.value
    val contractLockupScript = LockupScript.p2c(contractId)
    val contractBalance      = remainingBalance.getBalances(contractLockupScript).value

    val lockupScript = runTest(destroyFrame)

    val refundBalance = destroyFrame.ctx.outputBalances.getBalances(lockupScript).value
    refundBalance is contractBalance
  }

  trait ContractInstrFixture extends StatefulInstrFixture {
    override lazy val frame = prepareFrame()

    def test(
        instr: ContractInstr,
        value: Val,
        frame: Frame[StatefulContext] = frame,
        extraGas: GasBox = GasBox.zero
    ) = {
      val initialGas = frame.ctx.gasRemaining
      instr.runWith(frame) isE ()
      frame.opStack.size is 1
      frame.opStack.top.get is value
      initialGas.subUnsafe(frame.ctx.gasRemaining) is instr.gas().addUnsafe(extraGas)
    }
  }

  it should "SelfContractId" in new ContractInstrFixture {
    test(SelfContractId, Val.ByteVec(frame.obj.contractIdOpt.get.bytes))
  }

  trait SubContractIdBaseFixture extends StatefulInstrFixture {
    def test(instr: SubContractIdBase, expected: ContractId, initialVals: Val*) = {
      initialVals.foreach(frame.pushOpStack)
      frame.opStack.size is initialVals.length
      instr.runWithLeman(frame) isE ()
      frame.popContractId().rightValue is expected
    }
  }

  it should "SubContractId" in new SubContractIdBaseFixture {
    val path = Hash.random.bytes
    val expectedId =
      frame.obj.getContractId().rightValue.subContractId(path, frame.ctx.blockEnv.chainIndex.from)
    test(SubContractId, expectedId, Val.ByteVec(path))
  }

  it should "SubContractIdOf" in new SubContractIdBaseFixture {
    val parentId   = ContractId.random
    val path       = Hash.random.bytes
    val expectedId = parentId.subContractId(path, frame.ctx.blockEnv.chainIndex.from)
    test(SubContractIdOf, expectedId, Val.ByteVec(parentId.bytes), Val.ByteVec(path))
  }

  it should "ALPHTokenId" in new StatefulInstrFixture {
    stack.size is 0
    runAndCheckGas(ALPHTokenId)
    stack.size is 1
    stack.top.get is Val.ByteVec(TokenId.alph.bytes)
  }

  it should "SelfAddress" in new ContractInstrFixture {
    test(SelfAddress, Val.Address(LockupScript.p2c(frame.obj.contractIdOpt.get)))
  }

  trait CallerFrameFixture extends ContractInstrFixture {
    val callerFrame         = prepareFrame().asInstanceOf[StatefulFrame]
    override lazy val frame = prepareFrame(callerFrameOpt = Some(callerFrame))
  }

  it should "CallerContractId" in new CallerFrameFixture {
    test(CallerContractId, Val.ByteVec(callerFrame.obj.contractIdOpt.get.bytes))
  }

  it should "CallerAddress" in new CallerFrameFixture with TxEnvFixture {
    {
      info("PreLeman: Caller is a contract frame")
      val callerFrame = preparePreLemanFrame().asInstanceOf[StatefulFrame]
      val frame       = preparePreLemanFrame(callerFrameOpt = Some(callerFrame))
      test(CallerAddress, Val.Address(LockupScript.p2c(callerFrame.obj.contractIdOpt.get)), frame)
    }

    {
      info("Leman: Caller is a contract frame")
      val callerFrame = prepareFrame()(Leman).asInstanceOf[StatefulFrame]
      val frame       = prepareFrame(callerFrameOpt = Some(callerFrame))(Leman)
      test(CallerAddress, Val.Address(LockupScript.p2c(callerFrame.obj.contractIdOpt.get)), frame)
    }

    {
      info("PreLeman: Caller is a script frame with unique address in tx env")
      val callerFrame = preparePreLemanFrame(txEnvOpt = Some(txEnvWithUniqueAddress))
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)
      val frame = preparePreLemanFrame(callerFrameOpt = Some(callerFrame))
      CallerAddress.runWith(frame).leftValue isE PartiallyActiveInstr(CallerAddress)
    }

    {
      info("Leman: Caller is a script frame with unique address in tx env")
      val callerFrame = prepareFrame(txEnvOpt = Some(txEnvWithUniqueAddress))
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)
      val frame = prepareFrame(callerFrameOpt = Some(callerFrame))
      test(CallerAddress, uniqueAddress, frame)
    }

    {
      info("Leman: Caller is a script frame with random addresses in tx env")
      val callerFrame = prepareFrame(txEnvOpt = Some(txEnvWithRandomAddresses))
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)
      val frame = prepareFrame(callerFrameOpt = Some(callerFrame))
      CallerAddress.runWith(frame).leftValue isE a[TxInputAddressesAreNotIdentical]
    }

    {
      info("PreLeman: The current frame is a script frame")
      val frame = preparePreLemanFrame(txEnvOpt = Some(txEnvWithUniqueAddress))
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)
      CallerAddress.runWith(frame).leftValue isE PartiallyActiveInstr(CallerAddress)
    }

    {
      info("Leman: The current frame is a script frame with unique address in tx env")
      val frame = prepareFrame(txEnvOpt = Some(txEnvWithUniqueAddress))
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)
      test(CallerAddress, uniqueAddress, frame, extraGas = GasBox.unsafe(6))
    }

    {
      info("Leman: The current frame is a script frame with random addresses in tx env")
      val frame = prepareFrame(txEnvOpt = Some(txEnvWithRandomAddresses))
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)
      val randomAddresses = prevOutputs0.map { v =>
        Address.Asset(v.lockupScript).toBase58
      }.toSet
      CallerAddress
        .runWith(frame)
        .leftValue
        .rightValue
        .toString is s"Tx input addresses are not identical for `callerAddress` function: ${randomAddresses
          .mkString(", ")}"
    }
  }

  trait ExternalCallerFixture extends ContractInstrFixture with TxEnvFixture {
    def prepareScriptFrame(config: NetworkConfig = Danube) = {
      prepareFrame(txEnvOpt = Some(txEnvWithUniqueAddress))(config)
        .asInstanceOf[StatefulFrame]
        .copy(obj = script)
    }

    def prepareContractFrame(
        callerFrameOpt: Option[StatefulFrame] = None,
        contractIdOpt: Option[ContractId] = None,
        config: NetworkConfig = Danube
    ) = {
      prepareFrame(
        txEnvOpt = callerFrameOpt.map(_.ctx.txEnv),
        callerFrameOpt = callerFrameOpt,
        contractIdOpt = contractIdOpt
      )(config)
        .asInstanceOf[StatefulFrame]
    }
  }

  it should "ExternalCallerAddress" in new ExternalCallerFixture {
    {
      info("Not activated in PreDanube")
      ExternalCallerAddress.runWith(preparePreDanubeFrame()).leftValue isE
        InactiveInstr(ExternalCallerAddress)
    }

    {
      info("Current frame is a script frame")
      val frame = prepareScriptFrame()
      test(
        ExternalCallerAddress,
        uniqueAddress,
        frame,
        extraGas = GasUniqueAddress.gas(frame.ctx.txEnv.prevOutputs.length)
      )
    }

    {
      info("Current frame is a contract but has no caller frame")
      val frame = prepareContractFrame()
      ExternalCallerAddress.runWith(frame).leftValue isE ExternalCallerNotAvailable
    }

    {
      info("Current frame is a contract with caller script frame")
      val scriptFrame = prepareScriptFrame()
      val frame       = prepareContractFrame(callerFrameOpt = Some(scriptFrame))
      test(ExternalCallerAddress, uniqueAddress, frame)
    }

    {
      info("Current frame caller is a contract from the same contract")
      val contractId  = ContractId.random
      val scriptFrame = prepareScriptFrame()
      val callerFrame =
        prepareContractFrame(callerFrameOpt = Some(scriptFrame), contractIdOpt = Some(contractId))

      val frame =
        prepareContractFrame(callerFrameOpt = Some(callerFrame), contractIdOpt = Some(contractId))

      test(ExternalCallerAddress, uniqueAddress, frame)
    }

    {
      info("Current frame caller is a contract from a different contract")
      val callerContractId = ContractId.random
      val callerFrame = prepareContractFrame(
        callerFrameOpt = Some(prepareScriptFrame()),
        contractIdOpt = Some(callerContractId)
      )
      val frame = prepareContractFrame(callerFrameOpt = Some(callerFrame))
      test(ExternalCallerAddress, Val.Address(LockupScript.p2c(callerContractId)), frame)
    }

    {
      info("The external caller of current frame is a contract from a different contract")
      val contractId       = ContractId.random
      val callerContractId = ContractId.random
      val callerFrame0 = prepareContractFrame(
        callerFrameOpt = Some(prepareScriptFrame()),
        contractIdOpt = Some(callerContractId)
      )
      val callerFrame1 =
        prepareContractFrame(callerFrameOpt = Some(callerFrame0), contractIdOpt = Some(contractId))
      val frame =
        prepareContractFrame(callerFrameOpt = Some(callerFrame1), contractIdOpt = Some(contractId))
      test(ExternalCallerAddress, Val.Address(LockupScript.p2c(callerContractId)), frame)
    }
  }

  it should "ExternalCallerId" in new ExternalCallerFixture {
    {
      info("Not activated in PreDanube")
      ExternalCallerContractId.runWith(preparePreDanubeFrame()).leftValue isE
        InactiveInstr(ExternalCallerContractId)
    }

    {
      info("Current frame is a script frame")
      val scriptFrame = prepareScriptFrame()
      ExternalCallerContractId.runWith(scriptFrame).leftValue isE ExternalCallerIsNotContract
    }

    {
      info("Current frame caller is a contract from a different contract")
      val callerContractId = ContractId.random
      val callerFrame = prepareContractFrame(
        callerFrameOpt = Some(prepareScriptFrame()),
        contractIdOpt = Some(callerContractId)
      )
      val frame = prepareContractFrame(callerFrameOpt = Some(callerFrame))
      test(ExternalCallerContractId, Val.ByteVec(callerContractId.bytes), frame)
    }
  }

  it should "IsCalledFromTxScript" in new CallerFrameFixture {
    test(IsCalledFromTxScript, Val.Bool(false))
  }

  it should "CallerInitialStateHash" in new CallerFrameFixture {
    test(
      CallerInitialStateHash,
      Val.ByteVec(callerFrame.obj.asInstanceOf[StatefulContractObject].initialStateHash.bytes)
    )
  }

  it should "CallerCodeHash" in new CallerFrameFixture {
    test(
      CallerCodeHash,
      Val.ByteVec(callerFrame.obj.asInstanceOf[StatefulContractObject].codeHash.bytes)
    )
  }

  it should "ContractInitialStateHash" in new ContractInstrFixture {
    stack.push(Val.ByteVec(frame.obj.contractIdOpt.get.bytes))
    ContractInitialStateHash.runWith(frame) isE ()

    stack.size is 1
    stack.top.get is Val.ByteVec(
      frame.obj.asInstanceOf[StatefulContractObject].initialStateHash.bytes
    )
  }

  it should "ContractCodeHash" in new ContractInstrFixture {
    stack.push(Val.ByteVec(frame.obj.contractIdOpt.get.bytes))
    ContractCodeHash.runWith(frame) isE ()

    stack.size is 1
    stack.top.get is Val.ByteVec(frame.obj.code.hash.bytes)
  }

  it should "NullContractAddress" in new StatefulInstrFixture {
    runAndCheckGas(NullContractAddress)
    frame.opStack.pop() isE Val.NullContractAddress
    frame.opStack.isEmpty is true
  }

  it should "MinimalContractDeposit" in new StatefulInstrFixture {
    runAndCheckGas(MinimalContractDeposit)
    frame.opStack.pop() isE Val.U256(model.minimalAlphInContract)
    frame.opStack.isEmpty is true
  }

  it should "BlockHash" in new StatelessInstrFixture {
    val frameWithBlockHash = prepareFrame(AVector.empty)
    frameWithBlockHash.ctx.blockEnv.blockId.nonEmpty is true
    runAndCheckGas(vm.BlockHash, frameWithBlockHash)

    val frameWithoutBlockHash =
      prepareFrame(AVector.empty, Some(genBlockEnv().copy(blockId = None)))
    frameWithoutBlockHash.ctx.blockEnv.blockId.nonEmpty is false
    vm.BlockHash.runWith(frameWithoutBlockHash).leftValue isE NoBlockHashAvailable
  }

  it should "combine debug messages" in {
    DEBUG(AVector(Val.ByteVec.fromString("Hello"))).combineUnsafe(AVector.empty) is
      Val.ByteVec.fromString("Hello")
    DEBUG(AVector(Val.ByteVec.fromString("Hello "), Val.ByteVec.fromString("!")))
      .combineUnsafe(AVector(Val.ByteVec.fromString("Alephium"))) is
      Val.ByteVec.fromString("Hello 416c65706869756d!")
  }

  it should "Debug" in new StatefulInstrFixture {
    {
      info("No debug message")
      DEBUG(AVector.empty).runWith(frame).leftValue isE DebugMessageIsEmpty
    }

    {
      info("Simple message")
      DEBUG(AVector(Val.ByteVec.fromString("Hello, 416c65706869756d!"))).runWith(frame) isE ()
    }

    {
      info("Interpolation")
      val frame = prepareFrame()
      DEBUG(AVector(Val.ByteVec.fromString("Hello, "), Val.ByteVec.fromString("!")))
        .runWith(frame)
        .leftValue isE
        StackUnderflow

      frame.pushOpStack(Val.ByteVec.fromString("Alephium"))
      DEBUG(AVector(Val.ByteVec.fromString("Hello, "), Val.ByteVec.fromString("!")))
        .runWith(frame) isE ()
      frame.ctx.worldState.logState.eventLog
        .get(LogStatesId(frame.obj.contractIdOpt.value, 0))
        .rightValue
        .states
        .head
        .fields is AVector[Val](Val.ByteVec.fromString("Hello, 416c65706869756d!"))
    }
  }

  trait VerifyToStringFixture extends StatelessInstrFixture {
    def check[I <: Instr[StatelessContext] with GasFormula](
        instr: I,
        value: Val,
        expected: ByteString
    ) = {
      stack.push(value)
      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      initialGas.subUnsafe(context.gasRemaining) is instr.gas(expected.length)
      stack.size is 1
      stack.top.get is Val.ByteVec(expected)
      stack.pop()
    }

    def toHex(string: String) = {
      ByteString(string.getBytes(StandardCharsets.US_ASCII))
    }
  }

  it should "U256ToString" in new VerifyToStringFixture {
    forAll(u256Gen) { value =>
      check(U256ToString, Val.U256(value), toHex(value.toString()))
    }
  }

  it should "I256ToString" in new VerifyToStringFixture {
    forAll(i256Gen) { value =>
      check(I256ToString, Val.I256(value), toHex(value.toString()))
    }
  }

  it should "BoolToString" in new VerifyToStringFixture {
    Seq(true, false).foreach { value =>
      check(BoolToString, Val.Bool(value), toHex(value.toString()))
    }
  }

  it should "test gas amount" in new FrameFixture {
    val bytes      = AVector[Byte](0, 255.toByte, Byte.MaxValue, Byte.MinValue)
    val ints       = AVector[Int](0, 1 << 16, -(1 << 16))
    def byte: Byte = bytes.sample()
    def int: Int   = ints.sample()
    // format: off
    val statelessCases: AVector[(Instr[_], Int)] = AVector(
      ConstTrue -> 2, ConstFalse -> 2,
      I256Const0 -> 2, I256Const1 -> 2, I256Const2 -> 2, I256Const3 -> 2, I256Const4 -> 2, I256Const5 -> 2, I256ConstN1 -> 2,
      U256Const0 -> 2, U256Const1 -> 2, U256Const2 -> 2, U256Const3 -> 2, U256Const4 -> 2, U256Const5 -> 2,
      I256Const(Val.I256(UnsecureRandom.nextI256())) -> 2, U256Const(Val.U256(UnsecureRandom.nextU256())) -> 2,
      BytesConst(Val.ByteVec.default) -> 2, AddressConst(Val.Address.default) -> 2,
      LoadLocal(byte) -> 3, StoreLocal(byte) -> 3,
      Pop -> 2,
      BoolNot -> 3, BoolAnd -> 3, BoolOr -> 3, BoolEq -> 3, BoolNeq -> 3, BoolToByteVec -> 1,
      I256Add -> 3, I256Sub -> 3, I256Mul -> 5, I256Div -> 5, I256Mod -> 5, I256Eq -> 3, I256Neq -> 3, I256Lt -> 3, I256Le -> 3, I256Gt -> 3, I256Ge -> 3,
      U256Add -> 3, U256Sub -> 3, U256Mul -> 5, U256Div -> 5, U256Mod -> 5, U256Eq -> 3, U256Neq -> 3, U256Lt -> 3, U256Le -> 3, U256Gt -> 3, U256Ge -> 3,
      U256ModAdd -> 8, U256ModSub -> 8, U256ModMul -> 8, NumericBitAnd -> 5, NumericBitOr -> 5, NumericXor -> 5, NumericSHL -> 5, NumericSHR -> 5,
      I256ToU256 -> 3, I256ToByteVec -> 5, U256ToI256 -> 3, U256ToByteVec -> 5,
      ByteVecEq -> 7, ByteVecNeq -> 7, ByteVecSize -> 2, ByteVecConcat -> 1, AddressEq -> 3, AddressNeq -> 3, AddressToByteVec -> 5,
      IsAssetAddress -> 3, IsContractAddress -> 3,
      Jump(int) -> 8, IfTrue(int) -> 8, IfFalse(int) -> 8,
      /* CallLocal(byte) -> ???, */ Return -> 0,
      Assert -> 3,
      Blake2b -> 54, Keccak256 -> 54, Sha256 -> 54, Sha3 -> 54, VerifyTxSignature -> 2000, VerifySecP256K1 -> 2000, VerifyED25519 -> 2000,
      NetworkId -> 3, BlockTimeStamp -> 3, BlockTarget -> 3, TxId -> 3, TxInputAddressAt -> 3, TxInputsSize -> 3,
      VerifyAbsoluteLocktime -> 5, VerifyRelativeLocktime -> 8,
      Log1 -> 120, Log2 -> 140, Log3 -> 160, Log4 -> 180, Log5 -> 200,
      /* Below are instructions for Leman hard fork */
      ByteVecSlice -> 1, ByteVecToAddress -> 5, Encode -> 1, Zeros -> 1,
      U256To1Byte -> 1, U256To2Byte -> 1, U256To4Byte -> 1, U256To8Byte -> 1, U256To16Byte -> 2, U256To32Byte -> 4,
      U256From1Byte -> 1, U256From2Byte -> 1, U256From4Byte -> 1, U256From8Byte -> 1, U256From16Byte -> 2, U256From32Byte -> 4,
      EthEcRecover -> 2500,
      Log6 -> 220, Log7 -> 240, Log8 -> 260, Log9 -> 280,
      ContractIdToAddress -> 5,
      LoadLocalByIndex -> 5, StoreLocalByIndex -> 5, Dup -> 2, AssertWithErrorCode -> 3, Swap -> 2,
      vm.BlockHash -> 2, DEBUG(AVector.empty) -> 0, TxGasPrice -> 2, TxGasAmount -> 2, TxGasFee -> 2,
      I256Exp -> 1610, U256Exp -> 1610, U256ModExp -> 1610, VerifyBIP340Schnorr -> 2000, GetSegregatedSignature -> 3, MulModN -> 13, AddModN -> 8,
      U256ToString -> 4, I256ToString -> 4, BoolToString -> 4,
      /* Below are instructions for Rhone hard fork */
      GroupOfAddress -> 5,
      /* Below are instructions for Danube hard fork */
      VerifySignature -> 2000, GetSegregatedWebAuthnSignature -> 8
    )
    val statefulCases: AVector[(Instr[_], Int)] = AVector(
      LoadMutField(byte) -> 3, StoreMutField(byte) -> 3, /* CallExternal(byte) -> ???, */
      ApproveAlph -> 30, ApproveToken -> 30, AlphRemaining -> 30, TokenRemaining -> 30, IsPaying -> 30,
      TransferAlph -> 30, TransferAlphFromSelf -> 30, TransferAlphToSelf -> 30, TransferToken -> 30, TransferTokenFromSelf -> 30, TransferTokenToSelf -> 30,
      CreateContract -> 32000, CreateContractWithToken -> 32000, CopyCreateContract -> 24000, DestroySelf -> 2000, SelfContractId -> 3, SelfAddress -> 3,
      CallerContractId -> 5, CallerAddress -> 5, IsCalledFromTxScript -> 5, CallerInitialStateHash -> 5, CallerCodeHash -> 5, ContractInitialStateHash -> 5, ContractCodeHash -> 5,
      /* Below are instructions for Leman hard fork */
      MigrateSimple -> 32000, MigrateWithFields -> 32000, CopyCreateContractWithToken -> 24000,
      BurnToken -> 30, LockApprovedAssets -> 30,
      CreateSubContract -> 32000, CreateSubContractWithToken -> 32000, CopyCreateSubContract -> 24000, CopyCreateSubContractWithToken -> 24000,
      LoadMutFieldByIndex -> 5, StoreMutFieldByIndex -> 5, ContractExists -> 800, CreateContractAndTransferToken -> 32000,
      CopyCreateContractAndTransferToken -> 24000, CreateSubContractAndTransferToken -> 32000, CopyCreateSubContractAndTransferToken -> 24000,
      NullContractAddress -> 2, SubContractId -> 199, SubContractIdOf -> 199, ALPHTokenId -> 2,
      LoadImmField(byte) -> 3, LoadImmFieldByIndex -> 5, PayGasFee -> 30, MinimalContractDeposit -> 2, CreateMapEntry(byte, byte) -> 32000,
      MethodSelector(Method.Selector(0)) -> 10, /* CallExternalBySelector(selector) -> ??? */
      /* Below are instructions for Danube hard fork */
      ExternalCallerContractId -> 5, ExternalCallerAddress -> 5
    )
    // format: on
    statelessCases.length is Instr.statelessInstrs0.length - 1
    statefulCases.length is Instr.statefulInstrs0.length - 2

    def test(instr: Instr[_], gas: Int) = {
      instr match {
        case i: ToByteVecInstr[_]     => testToByteVec(i, gas)
        case _: ByteVecConcat.type    => testByteVecConcatGas(gas)
        case _: ByteVecSlice.type     => testByteVecSliceGas(gas)
        case _: Encode.type           => testEncode(gas)
        case i: Zeros.type            => i.gas(33).value is (3 + 5 * gas)
        case i: U256ToBytesInstr      => testU256ToBytes(i, gas)
        case i: U256FromBytesInstr    => testU256FromBytes(i, gas)
        case i: ByteVecToAddress.type => i.gas(33).value is gas
        case i: LogInstr              => testLog(i, gas)
        case i: GasSimple             => i.gas().value is gas
        case i: GasFormula            => i.gas(32).value is gas
        case _: TemplateVariable      => ???
      }
    }
    def testToByteVec(instr: ToByteVecInstr[_], gas: Int) = instr match {
      case i: BoolToByteVec.type    => i.gas(1).value is gas
      case i: I256ToByteVec.type    => i.gas(33).value is gas
      case i: U256ToByteVec.type    => i.gas(33).value is gas
      case i: AddressToByteVec.type => i.gas(33).value is gas
      case _                        => true is false
    }
    def testU256ToBytes(instr: U256ToBytesInstr, gas: Int) = {
      instr.gas(instr.size).value is gas
    }
    def testU256FromBytes(instr: U256FromBytesInstr, gas: Int) = {
      instr.gas(instr.size).value is gas
    }
    def testByteVecConcatGas(gas: Int) = {
      val frame = genStatefulFrame()
      frame.pushOpStack(Val.ByteVec(ByteString.fromArrayUnsafe(Array.ofDim[Byte](123)))) isE ()
      frame.pushOpStack(Val.ByteVec(ByteString.fromArrayUnsafe(Array.ofDim[Byte](200)))) isE ()
      val initialGas = frame.ctx.gasRemaining
      ByteVecConcat.runWith(frame) isE ()
      (initialGas.value - frame.ctx.gasRemaining.value) is (326 * gas)
    }
    def testByteVecSliceGas(gas: Int) = {
      val frame = genStatefulFrame()
      frame.pushOpStack(Val.ByteVec(ByteString.fromArrayUnsafe(Array.ofDim[Byte](20)))) isE ()
      frame.pushOpStack(Val.U256(U256.unsafe(1))) isE ()
      frame.pushOpStack(Val.U256(U256.unsafe(10))) isE ()
      val initialGas = frame.ctx.gasRemaining
      ByteVecSlice.runWith(frame) isE ()
      (initialGas.value - frame.ctx.gasRemaining.value) is (GasVeryLow.gas.value + 9 * gas)
    }
    def testEncode(gas: Int) = {
      val frame = genStatefulFrame()
      frame.pushOpStack(Val.True) isE ()
      frame.pushOpStack(Val.False) isE ()
      frame.pushOpStack(Val.U256(U256.Zero)) isE ()
      frame.pushOpStack(Val.U256(U256.unsafe(3)))
      val initialGas = frame.ctx.gasRemaining
      Encode.runWith(frame) isE ()
      (initialGas.value - frame.ctx.gasRemaining.value) is (GasVeryLow.gas.value + 7 * gas)
    }
    def testLog(instr: LogInstr, gas: Int) = instr match {
      case i: Log1.type => i.gas(1).value is gas
      case i: Log2.type => i.gas(2).value is gas
      case i: Log3.type => i.gas(3).value is gas
      case i: Log4.type => i.gas(4).value is gas
      case i: Log5.type => i.gas(5).value is gas
      case i: Log6.type => i.gas(6).value is gas
      case i: Log7.type => i.gas(7).value is gas
      case i: Log8.type => i.gas(8).value is gas
      case i: Log9.type => i.gas(9).value is gas
    }
    statelessCases.foreach(p => test(p._1, p._2))
    statefulCases.foreach(p => test(p._1, p._2))
  }

  it should "test bytecode" in new FrameFixture {
    val bytes      = AVector[Byte](0, 255.toByte, Byte.MaxValue, Byte.MinValue)
    val ints       = AVector[Int](0, 1 << 16, -(1 << 16))
    def byte: Byte = bytes.sample()
    def int: Int   = ints.sample()
    // format: off
    val allInstrs: AVector[(Instr[_], Int)] = AVector(
      CallLocal(byte) -> 0, CallExternal(byte) -> 1, Return -> 2,

      ConstTrue -> 3, ConstFalse -> 4,
      I256Const0 -> 5, I256Const1 -> 6, I256Const2 -> 7, I256Const3 -> 8, I256Const4 -> 9, I256Const5 -> 10, I256ConstN1 -> 11,
      U256Const0 -> 12, U256Const1 -> 13, U256Const2 -> 14, U256Const3 -> 15, U256Const4 -> 16, U256Const5 -> 17,
      I256Const(Val.I256(UnsecureRandom.nextI256())) -> 18, U256Const(Val.U256(UnsecureRandom.nextU256())) -> 19,
      BytesConst(Val.ByteVec.default) -> 20, AddressConst(Val.Address.default) -> 21,
      LoadLocal(byte) -> 22, StoreLocal(byte) -> 23,
      Pop -> 24,
      BoolNot -> 25, BoolAnd -> 26, BoolOr -> 27, BoolEq -> 28, BoolNeq -> 29, BoolToByteVec -> 30,
      I256Add -> 31, I256Sub -> 32, I256Mul -> 33, I256Div -> 34, I256Mod -> 35, I256Eq -> 36, I256Neq -> 37, I256Lt -> 38, I256Le -> 39, I256Gt -> 40, I256Ge -> 41,
      U256Add -> 42, U256Sub -> 43, U256Mul -> 44, U256Div -> 45, U256Mod -> 46, U256Eq -> 47, U256Neq -> 48, U256Lt -> 49, U256Le -> 50, U256Gt -> 51, U256Ge -> 52,
      U256ModAdd -> 53, U256ModSub -> 54, U256ModMul -> 55, NumericBitAnd -> 56, NumericBitOr -> 57, NumericXor -> 58, NumericSHL -> 59, NumericSHR -> 60,
      I256ToU256 -> 61, I256ToByteVec -> 62, U256ToI256 -> 63, U256ToByteVec -> 64,
      ByteVecEq -> 65, ByteVecNeq -> 66, ByteVecSize -> 67, ByteVecConcat -> 68, AddressEq -> 69, AddressNeq -> 70, AddressToByteVec -> 71,
      IsAssetAddress -> 72, IsContractAddress -> 73,
      Jump(int) -> 74, IfTrue(int) -> 75, IfFalse(int) -> 76,
      Assert -> 77,
      Blake2b -> 78, Keccak256 -> 79, Sha256 -> 80, Sha3 -> 81, VerifyTxSignature -> 82, VerifySecP256K1 -> 83, VerifyED25519 -> 84,
      NetworkId -> 85, BlockTimeStamp -> 86, BlockTarget -> 87, TxId -> 88, TxInputAddressAt -> 89, TxInputsSize -> 90,
      VerifyAbsoluteLocktime -> 91, VerifyRelativeLocktime -> 92,
      Log1 -> 93, Log2 -> 94, Log3 -> 95, Log4 -> 96, Log5 -> 97,
      /* Below are instructions for Leman hard fork */
      ByteVecSlice -> 98, ByteVecToAddress -> 99, Encode -> 100, Zeros -> 101,
      U256To1Byte -> 102, U256To2Byte -> 103, U256To4Byte -> 104, U256To8Byte -> 105, U256To16Byte -> 106, U256To32Byte -> 107,
      U256From1Byte -> 108, U256From2Byte -> 109, U256From4Byte -> 110, U256From8Byte -> 111, U256From16Byte -> 112, U256From32Byte -> 113,
      EthEcRecover -> 114,
      Log6 -> 115, Log7 -> 116, Log8 -> 117, Log9 -> 118,
      ContractIdToAddress -> 119,
      LoadLocalByIndex -> 120, StoreLocalByIndex -> 121, Dup -> 122, AssertWithErrorCode -> 123, Swap -> 124,
      vm.BlockHash -> 125, DEBUG(AVector.empty) -> 126, TxGasPrice -> 127, TxGasAmount -> 128, TxGasFee -> 129,
      I256Exp -> 130, U256Exp -> 131, U256ModExp -> 132, VerifyBIP340Schnorr -> 133, GetSegregatedSignature -> 134, MulModN -> 135, AddModN -> 136,
      U256ToString -> 137, I256ToString -> 138, BoolToString -> 139,
      /* Below are instructions for Rhone hard fork */
      GroupOfAddress -> 140,
      /* Below are instructions for Danube hard fork */
      VerifySignature -> 141, GetSegregatedWebAuthnSignature -> 142,
      // stateful instructions
      LoadMutField(byte) -> 160, StoreMutField(byte) -> 161,
      ApproveAlph -> 162, ApproveToken -> 163, AlphRemaining -> 164, TokenRemaining -> 165, IsPaying -> 166,
      TransferAlph -> 167, TransferAlphFromSelf -> 168, TransferAlphToSelf -> 169, TransferToken -> 170, TransferTokenFromSelf -> 171, TransferTokenToSelf -> 172,
      CreateContract -> 173, CreateContractWithToken -> 174, CopyCreateContract -> 175, DestroySelf -> 176, SelfContractId -> 177, SelfAddress -> 178,
      CallerContractId -> 179, CallerAddress -> 180, IsCalledFromTxScript -> 181, CallerInitialStateHash -> 182, CallerCodeHash -> 183, ContractInitialStateHash -> 184, ContractCodeHash -> 185,
      /* Below are instructions for Leman hard fork */
      MigrateSimple -> 186, MigrateWithFields -> 187, CopyCreateContractWithToken -> 188,
      BurnToken -> 189, LockApprovedAssets -> 190,
      CreateSubContract -> 191, CreateSubContractWithToken -> 192, CopyCreateSubContract -> 193, CopyCreateSubContractWithToken -> 194,
      LoadMutFieldByIndex -> 195, StoreMutFieldByIndex -> 196, ContractExists -> 197, CreateContractAndTransferToken -> 198,
      CopyCreateContractAndTransferToken -> 199, CreateSubContractAndTransferToken -> 200, CopyCreateSubContractAndTransferToken -> 201,
      NullContractAddress -> 202, SubContractId -> 203, SubContractIdOf -> 204, ALPHTokenId -> 205,
      /* Below are instructions for Rhone hard fork */
      LoadImmField(byte) -> 206, LoadImmFieldByIndex -> 207, PayGasFee -> 208, MinimalContractDeposit -> 209, CreateMapEntry(0, 0) -> 210,
      MethodSelector(Method.Selector(0)) -> 211, CallExternalBySelector(Method.Selector(0)) -> 212,
      /* Below are instructions for Danube hard fork */
      ExternalCallerContractId -> 213, ExternalCallerAddress -> 214
    )
    // format: on

    def test(instr: Instr[_], code: Int) = instr.code is code.toByte
    allInstrs.length is toCode.size
    allInstrs.foreach(p => test(p._1, p._2))
    val rhoneInstrs = allInstrs.map(_._1).filter(_.isInstanceOf[RhoneInstr[_]])
    rhoneInstrs is AVector[Instr[_]](
      GroupOfAddress,
      PayGasFee,
      MinimalContractDeposit,
      CreateMapEntry(0, 0),
      MethodSelector(Method.Selector(0)),
      CallExternalBySelector(Method.Selector(0))
    )
  }

  trait AllInstrsFixture {
    val bytes      = AVector[Byte](0, 255.toByte, Byte.MaxValue, Byte.MinValue)
    val ints       = AVector[Int](0, 1 << 16, -(1 << 16))
    def byte: Byte = bytes.sample()
    val twoBytes   = (bytes.tail.sample(), byte)
    def int: Int   = ints.sample()
    // format: off
    val statelessInstrs: AVector[Instr[StatelessContext]] = AVector(
      ConstTrue, ConstFalse,
      I256Const0, I256Const1, I256Const2, I256Const3, I256Const4, I256Const5, I256ConstN1,
      U256Const0, U256Const1, U256Const2, U256Const3, U256Const4, U256Const5,
      I256Const(Val.I256(UnsecureRandom.nextI256())), U256Const(Val.U256(UnsecureRandom.nextU256())),
      BytesConst(Val.ByteVec.default), AddressConst(Val.Address.default),
      LoadLocal(byte), StoreLocal(byte),
      Pop,
      BoolNot, BoolAnd, BoolOr, BoolEq, BoolNeq, BoolToByteVec,
      I256Add, I256Sub, I256Mul, I256Div, I256Mod, I256Eq, I256Neq, I256Lt, I256Le, I256Gt, I256Ge,
      U256Add, U256Sub, U256Mul, U256Div, U256Mod, U256Eq, U256Neq, U256Lt, U256Le, U256Gt, U256Ge,
      U256ModAdd, U256ModSub, U256ModMul, NumericBitAnd, NumericBitOr, NumericXor, NumericSHL, NumericSHR,
      I256ToU256, I256ToByteVec, U256ToI256, U256ToByteVec,
      ByteVecEq, ByteVecNeq, ByteVecSize, ByteVecConcat, AddressEq, AddressNeq, AddressToByteVec,
      IsAssetAddress, IsContractAddress,
      Jump(int), IfTrue(int), IfFalse(int),
      CallLocal(byte), Return,
      Assert,
      Blake2b, Keccak256, Sha256, Sha3, VerifyTxSignature, VerifySecP256K1, VerifyED25519,
      NetworkId, BlockTimeStamp, BlockTarget, TxId, TxInputAddressAt, TxInputsSize,
      VerifyAbsoluteLocktime, VerifyRelativeLocktime,
      Log1, Log2, Log3, Log4, Log5,
      /* Below are instructions for Leman hard fork */
      ByteVecSlice, ByteVecToAddress, Encode, Zeros,
      U256To1Byte, U256To2Byte, U256To4Byte, U256To8Byte, U256To16Byte, U256To32Byte,
      U256From1Byte, U256From2Byte, U256From4Byte, U256From8Byte, U256From16Byte, U256From32Byte,
      EthEcRecover,
      Log6, Log7, Log8, Log9,
      ContractIdToAddress,
      LoadLocalByIndex, StoreLocalByIndex, Dup, AssertWithErrorCode, Swap,
      vm.BlockHash, DEBUG(AVector.empty), TxGasPrice, TxGasAmount, TxGasFee,
      I256Exp, U256Exp, U256ModExp, VerifyBIP340Schnorr, GetSegregatedSignature, MulModN, AddModN,
      U256ToString, I256ToString, BoolToString,
      /* Below are instructions for Rhone hard fork */
      GroupOfAddress,
      /* Below are instructions for Danube hard fork */
      VerifySignature, GetSegregatedWebAuthnSignature
    )
    val statefulInstrs: AVector[Instr[StatefulContext]] = AVector(
      LoadMutField(byte), StoreMutField(byte), CallExternal(byte),
      ApproveAlph, ApproveToken, AlphRemaining, TokenRemaining, IsPaying,
      TransferAlph, TransferAlphFromSelf, TransferAlphToSelf, TransferToken, TransferTokenFromSelf, TransferTokenToSelf,
      CreateContract, CreateContractWithToken, CopyCreateContract, DestroySelf, SelfContractId, SelfAddress,
      CallerContractId, CallerAddress, IsCalledFromTxScript, CallerInitialStateHash, CallerCodeHash, ContractInitialStateHash, ContractCodeHash,
      /* Below are instructions for Leman hard fork */
      MigrateSimple, MigrateWithFields, CopyCreateContractWithToken, BurnToken, LockApprovedAssets,
      CreateSubContract, CreateSubContractWithToken, CopyCreateSubContract, CopyCreateSubContractWithToken,
      LoadMutFieldByIndex, StoreMutFieldByIndex, ContractExists, CreateContractAndTransferToken, CopyCreateContractAndTransferToken,
      CreateSubContractAndTransferToken, CopyCreateSubContractAndTransferToken,
      NullContractAddress, SubContractId, SubContractIdOf, ALPHTokenId,
      LoadImmField(0.toByte), LoadImmFieldByIndex,
      /* Below are instructions for Rhone hard fork */
      PayGasFee, MinimalContractDeposit, CreateMapEntry(twoBytes),
      MethodSelector(Method.Selector(0)), CallExternalBySelector(Method.Selector(0)),
      /* Below are instructions for Danube hard fork */
      ExternalCallerContractId, ExternalCallerAddress
    )
    // format: on
  }

  it should "test Transfer.checkAddressForContractTransfer" in new GenFixture {
    val p2c   = LockupScript.P2C(ContractId.random)
    val p2pkh = assetLockupScriptGen.sample.get

    // Pre-Danube hardfork - should not validate equality
    Transfer.checkAddressForSelfTransfer(
      NetworkConfigFixture.PreDanube.getHardFork(TimeStamp.now()),
      p2c,
      p2c
    ) isE ()
    Transfer.checkAddressForSelfTransfer(
      NetworkConfigFixture.PreDanube.getHardFork(TimeStamp.now()),
      p2pkh,
      p2pkh
    ) isE ()
    Transfer.checkAddressForSelfTransfer(
      NetworkConfigFixture.PreDanube.getHardFork(TimeStamp.now()),
      p2c,
      p2pkh
    ) isE ()
    Transfer.checkAddressForSelfTransfer(
      NetworkConfigFixture.PreDanube.getHardFork(TimeStamp.now()),
      p2pkh,
      p2c
    ) isE ()

    // Danube hardfork - should validate equality for contract transfers
    Transfer
      .checkAddressForSelfTransfer(
        NetworkConfigFixture.Danube.getHardFork(TimeStamp.now()),
        p2c,
        p2c
      )
      .leftValue isE InvalidSelfTransfer
    Transfer
      .checkAddressForSelfTransfer(
        NetworkConfigFixture.Danube.getHardFork(TimeStamp.now()),
        p2pkh,
        p2pkh
      )
      .leftValue isE InvalidSelfTransfer
    Transfer.checkAddressForSelfTransfer(
      NetworkConfigFixture.Danube.getHardFork(TimeStamp.now()),
      p2c,
      p2pkh
    ) isE ()
    Transfer.checkAddressForSelfTransfer(
      NetworkConfigFixture.Danube.getHardFork(TimeStamp.now()),
      p2pkh,
      p2c
    ) isE ()
  }

  it should "validate contract self transfers in transfer instructions with Danube hardfork" in new StatefulFixture {
    val contractId           = ContractId.from(tokenId.bytes).value
    val contractLockupScript = LockupScript.P2C(contractId)
    val assetLockupScript    = assetLockupScriptGen.sample.get

    val contractOutput    = ContractOutput(ALPH.alph(0), contractLockupScript, AVector.empty)
    val txId              = TransactionId.generate
    val contractOutputRef = ContractOutputRef.from(txId, contractOutput, 0)
    val contractOutputOpt = Some((contractId, contractOutput, contractOutputRef))

    val balanceState =
      MutBalanceState.from(
        balances(contractLockupScript, Some(ALPH.oneAlph), Map(tokenId -> ALPH.oneAlph))
      )
    balanceState.remaining.addAlph(assetLockupScript, ALPH.oneAlph)
    balanceState.remaining.addToken(assetLockupScript, tokenId, ALPH.oneAlph)

    // Test Transfer*Self instructions with same contract address (should fail in Danube)
    def testSelfTransferFails(
        instruction: Instr[StatefulContext],
        setupStack: Frame[StatefulContext] => Any,
        message: String
    ) = {
      info(message)
      val frame =
        prepareFrame(Some(balanceState), contractOutputOpt)(NetworkConfigFixture.Danube)
      setupStack(frame)
      instruction.runWith(frame).leftValue isE InvalidSelfTransfer
    }

    // Test transfers between different addresses (should succeed)
    def testDifferentAddressTransferSucceeds(
        instruction: Instr[StatefulContext],
        setupStack: Frame[StatefulContext] => Any,
        message: String
    ) = {
      info(message)
      val frame =
        prepareFrame(Some(balanceState), contractOutputOpt)(NetworkConfigFixture.Danube)
      setupStack(frame)
      instruction.runWith(frame) isE ()
    }

    // Test TransferAlphFromSelf with same contract address
    testSelfTransferFails(
      TransferAlphFromSelf,
      frame => {
        frame.opStack.push(Val.Address(contractLockupScript))
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      },
      "TransferAlphFromSelf to same contract should fail"
    )

    // Test TransferAlphToSelf with same contract address
    testSelfTransferFails(
      TransferAlphToSelf,
      frame => {
        frame.opStack.push(Val.Address(contractLockupScript))
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      },
      "TransferAlphToSelf from same contract should fail"
    )

    // Test TransferTokenFromSelf with same contract address
    testSelfTransferFails(
      TransferTokenFromSelf,
      frame => {
        frame.opStack.push(Val.Address(contractLockupScript))
        frame.opStack.push(Val.ByteVec(tokenId.bytes))
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      },
      "TransferTokenFromSelf to same contract should fail"
    )

    // Test TransferTokenToSelf with same contract address
    testSelfTransferFails(
      TransferTokenToSelf,
      frame => {
        frame.opStack.push(Val.Address(contractLockupScript))
        frame.opStack.push(Val.ByteVec(tokenId.bytes))
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      },
      "TransferTokenToSelf from same contract should fail"
    )

    // Test TransferAlphFromSelf with different addresses (should succeed)
    testDifferentAddressTransferSucceeds(
      TransferAlphFromSelf,
      frame => {
        frame.opStack.push(Val.Address(assetLockupScript))
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      },
      "TransferAlphFromSelf to different address should succeed"
    )

    // Test TransferAlphToSelf with different addresses (should succeed)
    testDifferentAddressTransferSucceeds(
      TransferAlphToSelf,
      frame => {
        frame.opStack.push(Val.Address(assetLockupScript))
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      },
      "TransferAlphToSelf from different address should succeed"
    )

    // Test TransferTokenFromSelf with different addresses (should succeed)
    testDifferentAddressTransferSucceeds(
      TransferTokenFromSelf,
      frame => {
        frame.opStack.push(Val.Address(assetLockupScript))
        frame.opStack.push(Val.ByteVec(tokenId.bytes))
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      },
      "TransferTokenFromSelf to different address should succeed"
    )

    // Test TransferTokenToSelf with different addresses (should succeed)
    testDifferentAddressTransferSucceeds(
      TransferTokenToSelf,
      frame => {
        frame.opStack.push(Val.Address(assetLockupScript))
        frame.opStack.push(Val.ByteVec(tokenId.bytes))
        frame.opStack.push(Val.U256(ALPH.oneNanoAlph))
      },
      "TransferTokenToSelf to different address should succeed"
    )
  }
}
