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

import akka.util.ByteString

import org.alephium.protocol.Signature
import org.alephium.protocol.model.NetworkId.AlephiumMainNet
import org.alephium.protocol.model.Target
import org.alephium.util._

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
      ApproveAlf, ApproveToken, AlfRemaining, TokenRemaining, IsPaying,
      TransferAlf, TransferAlfFromSelf, TransferAlfToSelf, TransferToken, TransferTokenFromSelf, TransferTokenToSelf,
      CreateContract, CreateContractWithToken, CopyCreateContract, DestroySelf, SelfContractId, SelfAddress,
      CallerContractId, CallerAddress, IsCalledFromTxScript, CallerInitialStateHash, ContractInitialStateHash
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
    def prepareFrame(
        instrs: AVector[Instr[StatelessContext]],
        blockEnv: Option[BlockEnv] = None,
        txEnv: Option[TxEnv] = None
    ): Frame[StatelessContext] = {
      val baseMethod = Method[StatelessContext](
        isPublic = true,
        isPayable = false,
        argsLength = 0,
        localsLength = 0,
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
  }

  it should "verify absolute lock time" in new StatelessFixture {
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

  it should "verify relative lock time" in new StatelessFixture {
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
      ApproveAlf -> 30, ApproveToken -> 30, AlfRemaining -> 30, TokenRemaining -> 30, IsPaying -> 30,
      TransferAlf -> 30, TransferAlfFromSelf -> 30, TransferAlfToSelf -> 30, TransferToken -> 30, TransferTokenFromSelf -> 30, TransferTokenToSelf -> 30,
      CreateContract -> 32000, CreateContractWithToken -> 32000, CopyCreateContract -> 24000, DestroySelf -> 2000, SelfContractId -> 3, SelfAddress -> 3,
      CallerContractId -> 5, CallerAddress -> 5, IsCalledFromTxScript -> 5, CallerInitialStateHash -> 5, ContractInitialStateHash -> 5
    )
    // format: on
    statelessCases.length is Instr.statelessInstrs0.length - 1
    statefulCases.length is Instr.statefulInstrs0.length - 1

    def test(instr: Instr[_], gas: Int) = {
      instr match {
        case i: ToByteVecInstr[_, _] => testToByteVec(i, gas)
        case _: ByteVecConcat.type   => testByteVecConcatGas(gas)
        case i: LogInstr             => testLog(i, gas)
        case i: GasSimple            => i.gas().value is gas
        case i: GasFormula           => i.gas(32).value is gas
      }
    }
    def testToByteVec(instr: ToByteVecInstr[_, _], gas: Int) = instr match {
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
}
