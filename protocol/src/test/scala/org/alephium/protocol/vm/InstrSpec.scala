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

import scala.collection.mutable.ArrayBuffer

import akka.util.ByteString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import org.alephium.crypto
import org.alephium.protocol.{ALPH, Hash, Signature, SignatureSchema}
import org.alephium.protocol.model.{ContractOutput, ContractOutputRef, Target, TokenId}
import org.alephium.protocol.model.NetworkId.AlephiumMainNet
import org.alephium.serde.{serialize, RandomBytes}
import org.alephium.util._

// scalastyle:off file.size.limit no.equal number.of.methods
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
  }

  it should "serde properly" in {
    val bytes      = AVector[Byte](0, 255.toByte, Byte.MaxValue, Byte.MinValue)
    val ints       = AVector[Int](0, 1 << 16, -(1 << 16))
    def byte: Byte = bytes.sample()
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
      U256ModAdd, U256ModSub, U256ModMul, U256BitAnd, U256BitOr, U256Xor, U256SHL, U256SHR,
      I256ToU256, I256ToByteVec, U256ToI256, U256ToByteVec,
      ByteVecEq, ByteVecNeq, ByteVecSize, ByteVecConcat, AddressEq, AddressNeq, AddressToByteVec,
      IsAssetAddress, IsContractAddress,
      Jump(int), IfTrue(int), IfFalse(int),
      CallLocal(byte), Return,
      Assert,
      Blake2b, Keccak256, Sha256, Sha3, VerifyTxSignature, VerifySecP256K1, VerifyED25519,
      NetworkId, BlockTimeStamp, BlockTarget, TxId, TxCaller, TxCallerSize,
      VerifyAbsoluteLocktime, VerifyRelativeLocktime,
      Log1, Log2, Log3, Log4, Log5
    )
    val statefulInstrs: AVector[Instr[StatefulContext]] = AVector(
      LoadField(byte), StoreField(byte), CallExternal(byte),
      ApproveAlph, ApproveToken, AlphRemaining, TokenRemaining, IsPaying,
      TransferAlph, TransferAlphFromSelf, TransferAlphToSelf, TransferToken, TransferTokenFromSelf, TransferTokenToSelf,
      CreateContract, CreateContractWithToken, CopyCreateContract, DestroySelf, SelfContractId, SelfAddress,
      CallerContractId, CallerAddress, IsCalledFromTxScript, CallerInitialStateHash, CallerCodeHash, ContractInitialStateHash, ContractCodeHash
    )
    // format: on

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

  trait StatelessFixture extends ContextGenerators {
    lazy val localsLength = 0
    def prepareFrame(
        instrs: AVector[Instr[StatelessContext]],
        blockEnv: Option[BlockEnv] = None,
        txEnv: Option[TxEnv] = None
    ): Frame[StatelessContext] = {
      val baseMethod = Method[StatelessContext](
        isPublic = true,
        isPayable = false,
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
  }

  it should "VerifyAbsoluteLocktime" in new StatelessFixture {
    def prepare(timeLock: TimeStamp, blockTs: TimeStamp): Frame[StatelessContext] = {
      val frame = prepareFrame(
        AVector.empty,
        blockEnv = Some(
          BlockEnv(
            AlephiumMainNet,
            blockTs,
            Target.Max
          )
        )
      )
      frame.pushOpStack(Val.U256(timeLock.millis))
      frame
    }

    {
      info("time lock is still locked")
      val now   = TimeStamp.now()
      val frame = prepare(now, now.minusUnsafe(Duration.ofSecondsUnsafe(1)))
      VerifyAbsoluteLocktime.runWith(frame) is failed(AbsoluteLockTimeVerificationFailed)
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
            AlephiumMainNet,
            blockTs,
            Target.Max
          )
        ),
        txEnv = Some(
          TxEnv(
            tx,
            prevOutputs.map(_.referredOutput.copy(lockTime = txLockTime)),
            Stack.ofCapacity[Signature](0)
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
      val frame = prepare(
        timeLock = Duration.ofSecondsUnsafe(1),
        blockTs = TimeStamp.now(),
        txLockTime = TimeStamp.now()
      )
      VerifyRelativeLocktime.runWith(frame) is failed(RelativeLockTimeVerificationFailed)
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

    val initialGas = context.gasRemaining
    val instr      = LoadLocal(0.toByte)
    instr.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is bool
    initialGas.subUnsafe(context.gasRemaining) is instr.gas()

    LoadLocal(1.toByte).runWith(frame).leftValue isE InvalidVarIndex
    LoadLocal(-1.toByte).runWith(frame).leftValue isE InvalidVarIndex
  }

  it should "StoreLocal" in new ConstInstrFixture {
    override lazy val localsLength = 1

    val bool: Val = Val.Bool(true)
    stack.push(bool)

    val initialGas = context.gasRemaining
    val instr      = StoreLocal(0.toByte)
    instr.runWith(frame) isE ()
    locals.getUnsafe(0) is bool
    initialGas.subUnsafe(context.gasRemaining) is instr.gas()

    StoreLocal(1.toByte).runWith(frame).leftValue isE StackUnderflow

    stack.push(bool)
    StoreLocal(1.toByte).runWith(frame).leftValue isE InvalidVarIndex
    stack.push(bool)
    StoreLocal(-1.toByte).runWith(frame).leftValue isE InvalidVarIndex
  }

  it should "Pop" in new ConstInstrFixture {
    val bool: Val = Val.Bool(true)
    stack.push(bool)

    val initialGas = context.gasRemaining
    Pop.runWith(frame) isE ()
    stack.size is 0
    initialGas.subUnsafe(context.gasRemaining) is Pop.gas()

    Pop.runWith(frame).leftValue isE StackUnderflow
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
    BoolNot.runWith(frame).leftValue isE InvalidType(zero)
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
      forAll(genB, genB) { case (b1, b2) =>
        binaryArithmeticTest(instr, buildArg, buildRes, op, b1, b2)
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
      val a1: A = buildArg(b1)
      val a2: A = buildArg(b2)
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
    }

    def binaryArithmeticfail[A <: Val, B](
        instr: BinaryArithmeticInstr[A],
        b1: B,
        b2: B,
        buildArg: B => A
    ) = {
      val a1: A = buildArg(b1)
      val a2: A = buildArg(b2)
      stack.push(a1)
      stack.push(a2)

      instr.runWith(frame).leftValue isE a[ArithmeticError]
    }
  }

  trait I256BinaryArithmeticInstrFixture extends BinaryArithmeticInstrFixture {
    val i256Gen: Gen[I256] = arbitrary[Long].map(I256.from)

    def testOp(instr: BinaryArithmeticInstr[Val.I256], op: (I256, I256) => I256) = {
      binaryArithmeticGenTest(instr, Val.I256.apply, Val.I256.apply, op, i256Gen)
    }

    def testOp(
        instr: BinaryArithmeticInstr[Val.I256],
        op: (I256, I256) => I256,
        b1: I256,
        b2: I256
    ) = {
      binaryArithmeticTest(instr, Val.I256.apply, Val.I256.apply, op, b1, b2)
    }

    def testComp(instr: BinaryArithmeticInstr[Val.I256], comp: (I256, I256) => Boolean) = {
      binaryArithmeticGenTest(instr, Val.I256.apply, Val.Bool.apply, comp, i256Gen)
    }

    def fail(instr: BinaryArithmeticInstr[Val.I256], b1: I256, b2: I256) = {
      binaryArithmeticfail(instr, b1, b2, Val.I256.apply)
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

  trait U256BinaryArithmeticInstrFixture extends BinaryArithmeticInstrFixture {
    val u256Gen: Gen[U256] = posLongGen.map(U256.unsafe)

    def testOp(instr: BinaryArithmeticInstr[Val.U256], op: (U256, U256) => U256) = {
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

    def testComp(instr: BinaryArithmeticInstr[Val.U256], comp: (U256, U256) => Boolean) = {
      binaryArithmeticGenTest(instr, Val.U256.apply, Val.Bool.apply, comp, u256Gen)
    }

    def fail(instr: BinaryArithmeticInstr[Val.U256], b1: U256, b2: U256) = {
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
    testOp(U256BitAnd, _ bitAnd _)
  }

  it should "U256BitOr" in new U256BinaryArithmeticInstrFixture {
    testOp(U256BitOr, _ bitOr _)
  }

  it should "U256Xor" in new U256BinaryArithmeticInstrFixture {
    testOp(U256Xor, _ xor _)
  }

  it should "U256SHL" in new U256BinaryArithmeticInstrFixture {
    testOp(U256SHL, _ shl _)
  }

  it should "U256SHR" in new U256BinaryArithmeticInstrFixture {
    testOp(U256SHR, _ shr _)
  }

  it should "I256ToU256" in new StatelessInstrFixture {
    val i256Gen: Gen[I256] = posLongGen.map(I256.from)

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
    val i256Gen: Gen[I256] = arbitrary[Long].map(I256.from)

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
    val u256Gen: Gen[U256] = posLongGen.map(U256.unsafe)

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
    val u256Gen: Gen[U256] = posLongGen.map(U256.unsafe)

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

  //TODO Not sure how to test this one
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

  it should "VerifyTxSignature" in new StatelessInstrFixture {
    val keysGen = for {
      group         <- groupIndexGen
      (_, pub, pri) <- addressGen(group)
    } yield ((pub, pri))

    val tx               = transactionGen().sample.get
    val (pubKey, priKey) = keysGen.sample.get

    val signature      = SignatureSchema.sign(tx.id.bytes, priKey)
    val signatureStack = Stack.ofCapacity[Signature](1)
    signatureStack.push(signature)

    override lazy val frame = prepareFrame(
      AVector.empty,
      txEnv = Some(TxEnv(tx, AVector.empty, signatureStack))
    )

    val initialGas = context.gasRemaining
    stack.push(Val.ByteVec(pubKey.bytes))
    VerifyTxSignature.runWith(frame) isE ()
    initialGas.subUnsafe(context.gasRemaining) is VerifyTxSignature.gas()

    val (wrongKey, _) = keysGen.sample.get

    signatureStack.push(signature)
    stack.push(Val.ByteVec(wrongKey.bytes))
    VerifyTxSignature.runWith(frame).leftValue isE InvalidSignature

    stack.push(Val.ByteVec(dataGen.sample.get))
    VerifyTxSignature.runWith(frame).leftValue isE InvalidPublicKey
  }

  trait GenericSignatureFixture extends StatelessInstrFixture {
    val data32Gen: Gen[ByteString] = for {
      bytes <- Gen.listOfN(32, arbitrary[Byte])
    } yield ByteString(bytes)

    def test[PriKey <: RandomBytes, PubKey <: RandomBytes, Sig <: RandomBytes](
        instr: GenericVerifySignature[PubKey, Sig],
        genratePriPub: => (PriKey, PubKey),
        sign: (ByteString, PriKey) => Sig
    ) = {
      val keysGen = for {
        (pri, pub) <- Gen.const(()).map(_ => genratePriPub)
      } yield ((pri, pub))

      val (priKey, pubKey) = keysGen.sample.get
      val data             = data32Gen.sample.get

      val signature = sign(data, priKey)

      stack.push(Val.ByteVec(data))
      stack.push(Val.ByteVec(pubKey.bytes))
      stack.push(Val.ByteVec(signature.bytes))

      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      initialGas.subUnsafe(context.gasRemaining) is instr.gas()

      stack.push(Val.ByteVec(ByteString("zzz")))
      instr.runWith(frame).leftValue isE InvalidSignatureFormat

      stack.push(Val.ByteVec(dataGen.sample.get))
      stack.push(Val.ByteVec(signature.bytes))
      instr.runWith(frame).leftValue isE InvalidPublicKey

      stack.push(Val.ByteVec(dataGen.sample.get))
      stack.push(Val.ByteVec(pubKey.bytes))
      stack.push(Val.ByteVec(signature.bytes))
      instr.runWith(frame).leftValue isE SignedDataIsNot32Bytes

      stack.push(Val.ByteVec(data))
      stack.push(Val.ByteVec(pubKey.bytes))
      stack.push(Val.ByteVec(sign(dataGen.sample.get, priKey).bytes))
      instr.runWith(frame).leftValue isE InvalidSignature
    }
  }

  it should "VerifySecP256K1" in new GenericSignatureFixture {
    test(VerifySecP256K1, crypto.SecP256K1.generatePriPub(), crypto.SecP256K1.sign)
  }

  it should "VerifyED25519" in new GenericSignatureFixture {
    test(VerifyED25519, crypto.ED25519.generatePriPub(), crypto.ED25519.sign)
  }

  it should "NetworkId" in new StatelessInstrFixture {
    override lazy val frame = prepareFrame(
      AVector.empty,
      blockEnv = Some(BlockEnv(AlephiumMainNet, TimeStamp.now(), Target.Max))
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
        blockEnv = Some(BlockEnv(AlephiumMainNet, timestamp, Target.Max))
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
        blockEnv = Some(BlockEnv(AlephiumMainNet, timestamp, Target.Max))
      )

      BlockTimeStamp.runWith(frame).leftValue isE NegativeTimeStamp(-1)
    }
  }

  it should "BlockTarget" in new StatelessInstrFixture {
    override lazy val frame = prepareFrame(
      AVector.empty,
      blockEnv = Some(BlockEnv(AlephiumMainNet, TimeStamp.now(), Target.Max))
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
      txEnv = Some(TxEnv(tx, AVector.empty, Stack.ofCapacity[Signature](0)))
    )

    val initialGas = context.gasRemaining
    TxId.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.ByteVec(tx.id.bytes)
    initialGas.subUnsafe(context.gasRemaining) is TxId.gas()
  }

  it should "TxCaller" in new StatelessInstrFixture {
    val (tx, prevOut) = transactionGenWithPreOutputs().sample.get
    val prevOutputs   = prevOut.map(_.referredOutput)
    override lazy val frame = prepareFrame(
      AVector.empty,
      txEnv = Some(
        TxEnv(
          tx,
          prevOutputs,
          Stack.ofCapacity[Signature](0)
        )
      )
    )

    val index      = prevOutputs.length - 1
    val initialGas = context.gasRemaining
    stack.push(Val.U256(U256.unsafe(index)))
    TxCaller.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.Address(prevOutputs.get(index).get.lockupScript)
    initialGas.subUnsafe(context.gasRemaining) is TxCaller.gas()
  }

  it should "TxCallerSize" in new StatelessInstrFixture {
    val (tx, prevOut) = transactionGenWithPreOutputs().sample.get
    val prevOutputs   = prevOut.map(_.referredOutput)
    override lazy val frame = prepareFrame(
      AVector.empty,
      txEnv = Some(
        TxEnv(
          tx,
          prevOutputs,
          Stack.ofCapacity[Signature](0)
        )
      )
    )

    val initialGas = context.gasRemaining
    TxCallerSize.runWith(frame) isE ()
    stack.size is 1
    stack.top.get is Val.U256(U256.unsafe(prevOutputs.length))
    initialGas.subUnsafe(context.gasRemaining) is TxCallerSize.gas()
  }

  trait LogFixture extends StatelessInstrFixture {
    def test(instr: LogInstr, n: Int) = {
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

  trait StatefulFixture extends ContextGenerators {
    val baseMethod =
      Method[StatefulContext](
        isPublic = true,
        isPayable = false,
        argsLength = 0,
        localsLength = 0,
        returnLength = 0,
        instrs = AVector()
      )
    val contract = StatefulContract(1, methods = AVector(baseMethod))

    val tokenId = Hash.generate

    def alphBalance(lockupScript: LockupScript, amount: U256): Balances = {
      Balances(ArrayBuffer((lockupScript, BalancesPerLockup.alph(amount))))
    }

    def tokenBalance(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Balances = {
      Balances(ArrayBuffer((lockupScript, BalancesPerLockup.token(tokenId, amount))))
    }

    def prepareFrame(
        balanceState: Option[BalanceState] = None,
        contractOutputOpt: Option[(ContractOutput, ContractOutputRef)] = None,
        txEnvOpt: Option[TxEnv] = None,
        callerFrameOpt: Option[Frame[StatefulContext]] = None
    ) = {
      val (obj, ctx) =
        prepareContract(
          contract,
          AVector[Val](Val.True),
          contractOutputOpt = contractOutputOpt,
          txEnvOpt = txEnvOpt
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

    val lockupScriptGen: Gen[LockupScript] = for {
      group        <- groupIndexGen
      lockupScript <- lockupGen(group)
    } yield lockupScript

    val p2cGen: Gen[LockupScript.P2C] = for {
      group <- groupIndexGen
      p2c   <- p2cLockupGen(group)
    } yield p2c

    val assetGen: Gen[LockupScript] = for {
      group <- groupIndexGen
      asset <- assetLockupGen(group)
    } yield asset

  }

  trait StatefulInstrFixture extends StatefulFixture {
    lazy val frame   = prepareFrame()
    lazy val stack   = frame.opStack
    lazy val context = frame.ctx

    def runAndCheckGas[I <: StatefulInstrSimpleGas](instr: I) = {
      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      initialGas.subUnsafe(context.gasRemaining) is instr.gas()
    }
  }

  it should "LoadField(byte)" in new StatefulInstrFixture {
    runAndCheckGas(LoadField(0.toByte))
    stack.size is 1
    stack.top.get is Val.True

    LoadField(1.toByte).runWith(frame).leftValue isE InvalidFieldIndex
    LoadField(-1.toByte).runWith(frame).leftValue isE InvalidFieldIndex
  }

  it should "StoreField(byte)" in new StatefulInstrFixture {
    stack.push(Val.False)
    runAndCheckGas(StoreField(0.toByte))
    stack.size is 0
    frame.obj.getField(0) isE Val.False

    stack.push(Val.True)
    StoreField(1.toByte).runWith(frame).leftValue isE InvalidFieldIndex
    stack.push(Val.True)
    StoreField(-1.toByte).runWith(frame).leftValue isE InvalidFieldIndex
  }

  it should "CallExternal(byte)" in new StatefulInstrFixture {
    intercept[NotImplementedError] {
      CallExternal(0.toByte).runWith(frame)
    }
  }

  it should "ApproveAlph" in new StatefulInstrFixture {
    val lockupScript        = lockupScriptGen.sample.get
    val balanceState        = BalanceState.from(alphBalance(lockupScript, ALPH.oneAlph))
    override lazy val frame = prepareFrame(Some(balanceState))

    frame.balanceStateOpt.get is balanceState
    stack.push(Val.Address(lockupScript))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    runAndCheckGas(ApproveAlph)

    frame.balanceStateOpt.get is BalanceState(
      alphBalance(lockupScript, ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph)),
      alphBalance(lockupScript, ALPH.oneNanoAlph)
    )
  }

  it should "ApproveToken" in new StatefulInstrFixture {
    val lockupScript = lockupScriptGen.sample.get
    val balanceState =
      BalanceState.from(
        tokenBalance(lockupScript, tokenId, ALPH.oneAlph)
      )
    override lazy val frame = prepareFrame(Some(balanceState))

    stack.push(Val.Address(lockupScript))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    frame.balanceStateOpt.get is balanceState

    runAndCheckGas(ApproveToken)

    frame.balanceStateOpt.get is BalanceState(
      tokenBalance(lockupScript, tokenId, ALPH.oneAlph.subUnsafe(ALPH.oneNanoAlph)),
      tokenBalance(lockupScript, tokenId, ALPH.oneNanoAlph)
    )
  }

  it should "AlphRemaining" in new StatefulInstrFixture {
    val lockupScript = lockupScriptGen.sample.get
    val balanceState =
      BalanceState.from(alphBalance(lockupScript, ALPH.oneAlph))
    override lazy val frame = prepareFrame(Some(balanceState))

    stack.push(Val.Address(lockupScript))

    runAndCheckGas(AlphRemaining)

    stack.size is 1
    stack.top.get is Val.U256(ALPH.oneAlph)
  }

  it should "TokenRemaining" in new StatefulInstrFixture {
    val lockupScript = lockupScriptGen.sample.get
    val balanceState =
      BalanceState.from(
        tokenBalance(lockupScript, tokenId, ALPH.oneAlph)
      )
    override lazy val frame = prepareFrame(Some(balanceState))

    stack.push(Val.Address(lockupScript))
    stack.push(Val.ByteVec(tokenId.bytes))

    runAndCheckGas(TokenRemaining)

    stack.size is 1
    stack.top.get is Val.U256(ALPH.oneAlph)
  }

  it should "IsPaying" in new StatefulFixture {
    {
      info("Alph")
      val lockupScript = lockupScriptGen.sample.get
      val balanceState =
        BalanceState.from(alphBalance(lockupScript, ALPH.oneAlph))
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
        BalanceState.from(
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

  it should "TransferAlph" in new StatefulInstrFixture {
    val from = lockupScriptGen.sample.get
    val to   = lockupScriptGen.sample.get
    val balanceState =
      BalanceState.from(alphBalance(from, ALPH.oneAlph))
    override lazy val frame = prepareFrame(Some(balanceState))

    stack.push(Val.Address(from))
    stack.push(Val.Address(to))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    runAndCheckGas(TransferAlph)

    frame.ctx.outputBalances is Balances(
      ArrayBuffer((to, BalancesPerLockup.alph(ALPH.oneNanoAlph)))
    )

    stack.push(Val.Address(from))
    stack.push(Val.Address(to))
    stack.push(Val.U256(ALPH.alph(10)))

    TransferAlph.runWith(frame).leftValue isE NotEnoughBalance
  }

  trait ContractOutputFixture extends StatefulInstrFixture {
    val contractOutput    = ContractOutput(ALPH.alph(0), p2cGen.sample.get, AVector.empty)
    val txId              = Hash.generate
    val contractOutputRef = ContractOutputRef.unsafe(txId, contractOutput, 0)
  }

  it should "TransferAlphFromSelf" in new ContractOutputFixture {
    val from = LockupScript.P2C(contractOutputRef.key)
    val to   = lockupScriptGen.sample.get

    val balanceState =
      BalanceState.from(alphBalance(from, ALPH.oneAlph))
    override lazy val frame =
      prepareFrame(Some(balanceState), Some((contractOutput, contractOutputRef)))

    stack.push(Val.Address(to))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    runAndCheckGas(TransferAlphFromSelf)
    frame.ctx.outputBalances is Balances(
      ArrayBuffer((to, BalancesPerLockup.alph(ALPH.oneNanoAlph)))
    )
  }

  it should "TransferAlphToSelf" in new ContractOutputFixture {
    val from = lockupScriptGen.sample.get
    val to   = LockupScript.P2C(contractOutputRef.key)

    val balanceState =
      BalanceState.from(alphBalance(from, ALPH.oneAlph))
    override lazy val frame =
      prepareFrame(Some(balanceState), Some((contractOutput, contractOutputRef)))

    stack.push(Val.Address(from))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    runAndCheckGas(TransferAlphToSelf)
    frame.ctx.outputBalances is Balances(
      ArrayBuffer((to, BalancesPerLockup.alph(ALPH.oneNanoAlph)))
    )
  }

  it should "TransferToken" in new ContractOutputFixture {
    val from = lockupScriptGen.sample.get
    val to   = lockupScriptGen.sample.get
    val balanceState =
      BalanceState.from(
        tokenBalance(from, tokenId, ALPH.oneAlph)
      )

    override lazy val frame = prepareFrame(Some(balanceState))

    stack.push(Val.Address(from))
    stack.push(Val.Address(to))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    runAndCheckGas(TransferToken)

    frame.ctx.outputBalances is Balances(
      ArrayBuffer((to, BalancesPerLockup.token(tokenId, ALPH.oneNanoAlph)))
    )
  }

  it should "TransferTokenFromSelf" in new ContractOutputFixture {
    val from = LockupScript.P2C(contractOutputRef.key)
    val to   = lockupScriptGen.sample.get

    val balanceState =
      BalanceState.from(
        tokenBalance(from, tokenId, ALPH.oneAlph)
      )

    override lazy val frame =
      prepareFrame(Some(balanceState), Some((contractOutput, contractOutputRef)))

    stack.push(Val.Address(to))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    runAndCheckGas(TransferTokenFromSelf)

    frame.ctx.outputBalances is Balances(
      ArrayBuffer((to, BalancesPerLockup.token(tokenId, ALPH.oneNanoAlph)))
    )
  }

  it should "TransferTokenToSelf" in new ContractOutputFixture {
    val from = lockupScriptGen.sample.get
    val to   = LockupScript.P2C(contractOutputRef.key)

    val balanceState =
      BalanceState.from(
        tokenBalance(from, tokenId, ALPH.oneAlph)
      )

    override lazy val frame =
      prepareFrame(Some(balanceState), Some((contractOutput, contractOutputRef)))

    stack.push(Val.Address(from))
    stack.push(Val.ByteVec(tokenId.bytes))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    runAndCheckGas(TransferTokenToSelf)

    frame.ctx.outputBalances is Balances(
      ArrayBuffer((to, BalancesPerLockup.token(tokenId, ALPH.oneNanoAlph)))
    )
  }

  trait CreateContractAbstractFixture extends StatefulInstrFixture {
    val from              = lockupScriptGen.sample.get
    val (tx, prevOutputs) = transactionGenWithPreOutputs().sample.get

    def balanceState: BalanceState

    override lazy val frame = prepareFrame(
      Some(balanceState),
      txEnvOpt = Some(
        TxEnv(
          tx,
          prevOutputs.map(_.referredOutput),
          Stack.ofCapacity[Signature](0)
        )
      )
    )
  }

  trait CreateContractFixture extends CreateContractAbstractFixture {
    val fields        = AVector[Val](Val.True)
    val contractBytes = serialize(contract)

    def test(instr: CreateContractBase) = {
      val initialGas = context.gasRemaining
      instr.runWith(frame) isE ()
      initialGas.subUnsafe(frame.ctx.gasRemaining) is GasBox.unsafe(
        instr
          .gas()
          .value + fields.length + contractBytes.length + 200 //TODO where those 200 come from?
      )
      //TODO Test the updated contract, like code, state, and assets
    }
  }

  it should "CreateContract" in new CreateContractFixture {
    val balanceState =
      BalanceState(Balances.empty, alphBalance(from, ALPH.oneAlph))

    stack.push(Val.ByteVec(contractBytes))
    stack.push(Val.ByteVec(serialize(fields)))

    test(CreateContract)
  }

  it should "CreateContractWithToken" in new CreateContractFixture {
    val balanceState =
      BalanceState(
        Balances.empty,
        tokenBalance(from, tokenId, ALPH.oneAlph)
      )

    stack.push(Val.ByteVec(contractBytes))
    stack.push(Val.ByteVec(serialize(fields)))
    stack.push(Val.U256(ALPH.oneNanoAlph))

    test(CreateContractWithToken)
  }

  it should "CopyCreateContract" in new CreateContractAbstractFixture {
    val balanceState =
      BalanceState(Balances.empty, alphBalance(from, ALPH.oneAlph))

    stack.push(Val.ByteVec(serialize(Hash.generate)))
    stack.push(Val.ByteVec(serialize(AVector[Val](Val.True))))
    CopyCreateContract.runWith(frame).leftValue isE a[NonExistContract]

    stack.push(Val.ByteVec(frame.obj.contractIdOpt.get.bytes))
    stack.push(Val.ByteVec(serialize(AVector[Val](Val.True))))
    CopyCreateContract.runWith(frame) isE ()
  }

  it should "DestroySelf" in new StatefulInstrFixture {
    val contractOutput = ContractOutput(ALPH.alph(0), p2cGen.sample.get, AVector.empty)
    val txId           = Hash.generate

    val contractOutputRef = ContractOutputRef.unsafe(txId, contractOutput, 0)

    val callerFrame = prepareFrame()

    val from = LockupScript.P2C(contractOutputRef.key)

    val balanceState =
      BalanceState.from(alphBalance(from, ALPH.oneAlph))
    override lazy val frame =
      prepareFrame(
        Some(balanceState),
        Some((contractOutput, contractOutputRef)),
        callerFrameOpt = Some(callerFrame)
      )

    stack.push(Val.Address(assetGen.sample.get))

    DestroySelf.runWith(frame).leftValue isE ContractAssetUnloaded
    //TODO how to get beyond that state?
  }

  trait ContractInstrFixture extends StatefulInstrFixture {
    override lazy val frame = prepareFrame()

    def test(instr: ContractInstr, value: Val) = {
      val initialGas = context.gasRemaining
      instr.runWith(frame)
      stack.size is 1
      stack.top.get is value
      initialGas.subUnsafe(context.gasRemaining) is instr.gas()
    }
  }

  it should "SelfContractId" in new ContractInstrFixture {
    test(SelfContractId, Val.ByteVec(frame.obj.contractIdOpt.get.bytes))
  }

  it should "SelfAddress" in new ContractInstrFixture {
    test(SelfAddress, Val.Address(LockupScript.p2c(frame.obj.contractIdOpt.get)))
  }

  trait CallerFrameFixture extends ContractInstrFixture {
    val callerFrame         = prepareFrame()
    override lazy val frame = prepareFrame(callerFrameOpt = Some(callerFrame))
  }

  it should "CallerContractId" in new CallerFrameFixture {
    test(CallerContractId, Val.ByteVec(callerFrame.obj.contractIdOpt.get.bytes))
  }

  it should "CallerAddress" in new CallerFrameFixture {
    test(CallerAddress, Val.Address(LockupScript.p2c(callerFrame.obj.contractIdOpt.get)))
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
      U256ModAdd -> 8, U256ModSub -> 8, U256ModMul -> 8, U256BitAnd -> 5, U256BitOr -> 5, U256Xor -> 5, U256SHL -> 5, U256SHR -> 5,
      I256ToU256 -> 3, I256ToByteVec -> 5, U256ToI256 -> 3, U256ToByteVec -> 5,
      ByteVecEq -> 4, ByteVecNeq -> 4, ByteVecSize -> 2, ByteVecConcat -> 1, AddressEq -> 3, AddressNeq -> 3, AddressToByteVec -> 5,
      IsAssetAddress -> 3, IsContractAddress -> 3,
      Jump(int) -> 8, IfTrue(int) -> 8, IfFalse(int) -> 8,
      /* CallLocal(byte) -> ???, */ Return -> 0,
      Assert -> 3,
      Blake2b -> 54, Keccak256 -> 54, Sha256 -> 54, Sha3 -> 54, VerifyTxSignature -> 2000, VerifySecP256K1 -> 2000, VerifyED25519 -> 2000,
      NetworkId -> 3, BlockTimeStamp -> 3, BlockTarget -> 3, TxId -> 3, TxCaller -> 3, TxCallerSize -> 3,
      VerifyAbsoluteLocktime -> 5, VerifyRelativeLocktime -> 8,
      Log1 -> 120, Log2 -> 140, Log3 -> 160, Log4 -> 180, Log5 -> 200
    )
    val statefulCases: AVector[(Instr[_], Int)] = AVector(
      LoadField(byte) -> 3, StoreField(byte) -> 3, /* CallExternal(byte) -> ???, */
      ApproveAlph -> 30, ApproveToken -> 30, AlphRemaining -> 30, TokenRemaining -> 30, IsPaying -> 30,
      TransferAlph -> 30, TransferAlphFromSelf -> 30, TransferAlphToSelf -> 30, TransferToken -> 30, TransferTokenFromSelf -> 30, TransferTokenToSelf -> 30,
      CreateContract -> 32000, CreateContractWithToken -> 32000, CopyCreateContract -> 24000, DestroySelf -> 2000, SelfContractId -> 3, SelfAddress -> 3,
      CallerContractId -> 5, CallerAddress -> 5, IsCalledFromTxScript -> 5, CallerInitialStateHash -> 5, CallerCodeHash -> 5, ContractInitialStateHash -> 5, ContractCodeHash -> 5
    )
    // format: on
    statelessCases.length is Instr.statelessInstrs0.length - 1
    statefulCases.length is Instr.statefulInstrs0.length - 1

    def test(instr: Instr[_], gas: Int) = {
      instr match {
        case i: ToByteVecInstr[_]  => testToByteVec(i, gas)
        case _: ByteVecConcat.type => testByteVecConcatGas(gas)
        case i: LogInstr           => testLog(i, gas)
        case i: GasSimple          => i.gas().value is gas
        case i: GasFormula         => i.gas(32).value is gas
      }
    }
    def testToByteVec(instr: ToByteVecInstr[_], gas: Int) = instr match {
      case i: BoolToByteVec.type    => i.gas(1).value is gas
      case i: I256ToByteVec.type    => i.gas(33).value is gas
      case i: U256ToByteVec.type    => i.gas(33).value is gas
      case i: AddressToByteVec.type => i.gas(33).value is gas
      case _                        => true is false
    }
    def testByteVecConcatGas(gas: Int) = {
      val frame = genStatefulFrame()
      frame.pushOpStack(Val.ByteVec(ByteString.fromArrayUnsafe(Array.ofDim[Byte](123)))) isE ()
      frame.pushOpStack(Val.ByteVec(ByteString.fromArrayUnsafe(Array.ofDim[Byte](200)))) isE ()
      val initialGas = frame.ctx.gasRemaining
      ByteVecConcat.runWith(frame) isE ()
      (initialGas.value - frame.ctx.gasRemaining.value) is (323 * gas)
    }
    def testLog(instr: LogInstr, gas: Int) = instr match {
      case i: Log1.type => i.gas(1).value is gas
      case i: Log2.type => i.gas(2).value is gas
      case i: Log3.type => i.gas(3).value is gas
      case i: Log4.type => i.gas(4).value is gas
      case i: Log5.type => i.gas(5).value is gas
    }
    statelessCases.foreach(test.tupled)
    statefulCases.foreach(test.tupled)
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
      U256ModAdd -> 53, U256ModSub -> 54, U256ModMul -> 55, U256BitAnd -> 56, U256BitOr -> 57, U256Xor -> 58, U256SHL -> 59, U256SHR -> 60,
      I256ToU256 -> 61, I256ToByteVec -> 62, U256ToI256 -> 63, U256ToByteVec -> 64,
      ByteVecEq -> 65, ByteVecNeq -> 66, ByteVecSize -> 67, ByteVecConcat -> 68, AddressEq -> 69, AddressNeq -> 70, AddressToByteVec -> 71,
      IsAssetAddress -> 72, IsContractAddress -> 73,
      Jump(int) -> 74, IfTrue(int) -> 75, IfFalse(int) -> 76,
      Assert -> 77,
      Blake2b -> 78, Keccak256 -> 79, Sha256 -> 80, Sha3 -> 81, VerifyTxSignature -> 82, VerifySecP256K1 -> 83, VerifyED25519 -> 84,
      NetworkId -> 85, BlockTimeStamp -> 86, BlockTarget -> 87, TxId -> 88, TxCaller -> 89, TxCallerSize -> 90,
      VerifyAbsoluteLocktime -> 91, VerifyRelativeLocktime -> 92,
      Log1 -> 93, Log2 -> 94, Log3 -> 95, Log4 -> 96, Log5 -> 97,

      LoadField(byte) -> 160, StoreField(byte) -> 161,
      ApproveAlph -> 162, ApproveToken -> 163, AlphRemaining -> 164, TokenRemaining -> 165, IsPaying -> 166,
      TransferAlph -> 167, TransferAlphFromSelf -> 168, TransferAlphToSelf -> 169, TransferToken -> 170, TransferTokenFromSelf -> 171, TransferTokenToSelf -> 172,
      CreateContract -> 173, CreateContractWithToken -> 174, CopyCreateContract -> 175, DestroySelf -> 176, SelfContractId -> 177, SelfAddress -> 178,
      CallerContractId -> 179, CallerAddress -> 180, IsCalledFromTxScript -> 181, CallerInitialStateHash -> 182, CallerCodeHash -> 183, ContractInitialStateHash -> 184, ContractCodeHash -> 185
    )
    // format: on

    def test(instr: Instr[_], code: Int) = instr.code is code.toByte
    allInstrs.length is toCode.size
    allInstrs.foreach(test.tupled)
  }
}
