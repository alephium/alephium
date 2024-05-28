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

import scala.annotation.switch

import akka.util.ByteString

import org.alephium.crypto
import org.alephium.crypto.SecP256K1
import org.alephium.macros.ByteCode
import org.alephium.protocol.{PublicKey, SignatureSchema}
import org.alephium.protocol.model
import org.alephium.protocol.model.{Address, AssetOutput, ContractId, GroupIndex, HardFork, TokenId}
import org.alephium.protocol.vm.TokenIssuance.{
  IssueTokenAndTransfer,
  IssueTokenWithoutTransfer,
  NoIssuance
}
import org.alephium.serde.{deserialize => decode, serialize => encode, _}
import org.alephium.util.{AVector, Bytes, Duration, TimeStamp, U256}
import org.alephium.util

// scalastyle:off file.size.limit number.of.types

sealed trait Instr[-Ctx <: StatelessContext] extends GasSchedule {
  def code: Byte

  def serialize(): ByteString
  def toTemplateString(): String = util.Hex.toHexString(serialize())

  // this function needs to charge gas manually
  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit]

  def mockup(): Instr[Ctx] = this
}

sealed trait LemanInstr[-Ctx <: StatelessContext] extends Instr[Ctx] {
  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- frame.ctx.checkLemanHardFork(this)
      _ <- runWithLeman(frame)
    } yield ()
  }

  def runWithLeman[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

sealed trait RhoneInstr[-Ctx <: StatelessContext] extends Instr[Ctx] {
  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- frame.ctx.checkRhoneHardFork(this)
      _ <- runWithRhone(frame)
    } yield ()
  }

  def runWithRhone[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

sealed trait InstrWithSimpleGas[-Ctx <: StatelessContext] extends Instr[Ctx] with GasSimple {
  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- frame.ctx.chargeGas(this)
      _ <- _runWith(frame)
    } yield ()
  }

  // this function will not need to take care of charge gas
  def _runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

sealed trait LemanInstrWithSimpleGas[-Ctx <: StatelessContext]
    extends LemanInstr[Ctx]
    with GasSimple {
  def _runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = ???

  override def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- frame.ctx.checkLemanHardFork(this)
      _ <- frame.ctx.chargeGas(this)
      _ <- runWithLeman(frame)
    } yield ()
  }

  def runWithLeman[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

sealed trait RhoneInstrWithSimpleGas[-Ctx <: StatelessContext]
    extends RhoneInstr[Ctx]
    with GasSimple {
  def _runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = ???

  override def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- frame.ctx.checkRhoneHardFork(this)
      _ <- frame.ctx.chargeGas(this)
      _ <- runWithRhone(frame)
    } yield ()
  }
}

object Instr {
  implicit val statelessSerde: Serde[Instr[StatelessContext]] = new Serde[Instr[StatelessContext]] {
    def serialize(input: Instr[StatelessContext]): ByteString = input.serialize()

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def _deserialize(input: ByteString): SerdeResult[Staging[Instr[StatelessContext]]] = {
      for {
        code <- input.headOption.toRight(SerdeError.incompleteData(1, 0))
        instrCompanion <- getStatelessCompanion(code).toRight(
          SerdeError.validation(s"Instruction - invalid code $code")
        )
        output <- instrCompanion.deserialize[StatelessContext](input.tail)
      } yield output
    }
  }
  implicit val statefulSerde: Serde[Instr[StatefulContext]] = new Serde[Instr[StatefulContext]] {
    def serialize(input: Instr[StatefulContext]): ByteString = input.serialize()

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def _deserialize(input: ByteString): SerdeResult[Staging[Instr[StatefulContext]]] = {
      for {
        code <- input.headOption.toRight(SerdeError.incompleteData(1, 0))
        instrCompanion <- getStatefulCompanion(code).toRight(
          SerdeError.validation(s"Instruction - invalid code $code")
        )
        output <- instrCompanion.deserialize[StatefulContext](input.tail)
      } yield output
    }
  }

  def getStatelessCompanion(byte: Byte): Option[InstrCompanion[StatelessContext]] = {
    statelessInstrs(Bytes.toPosInt(byte))
  }

  def getStatefulCompanion(byte: Byte): Option[InstrCompanion[StatefulContext]] = {
    statefulInstrs(Bytes.toPosInt(byte))
  }

  // format: off
  val statelessInstrs0: AVector[InstrCompanion[StatelessContext]] = AVector(
    ConstTrue, ConstFalse,
    I256Const0, I256Const1, I256Const2, I256Const3, I256Const4, I256Const5, I256ConstN1,
    U256Const0, U256Const1, U256Const2, U256Const3, U256Const4, U256Const5,
    I256Const, U256Const,
    BytesConst, AddressConst,
    LoadLocal, StoreLocal,
    Pop,
    BoolNot, BoolAnd, BoolOr, BoolEq, BoolNeq, BoolToByteVec,
    I256Add, I256Sub, I256Mul, I256Div, I256Mod, I256Eq, I256Neq, I256Lt, I256Le, I256Gt, I256Ge,
    U256Add, U256Sub, U256Mul, U256Div, U256Mod, U256Eq, U256Neq, U256Lt, U256Le, U256Gt, U256Ge,
    U256ModAdd, U256ModSub, U256ModMul, U256BitAnd, U256BitOr, U256Xor, U256SHL, U256SHR,
    I256ToU256, I256ToByteVec, U256ToI256, U256ToByteVec,
    ByteVecEq, ByteVecNeq, ByteVecSize, ByteVecConcat, AddressEq, AddressNeq, AddressToByteVec,
    IsAssetAddress, IsContractAddress,
    Jump, IfTrue, IfFalse,
    CallLocal, Return,
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
    BlockHash, DEBUG, TxGasPrice, TxGasAmount, TxGasFee,
    I256Exp, U256Exp, U256ModExp, VerifyBIP340Schnorr, GetSegregatedSignature, MulModN, AddModN,
    U256ToString, I256ToString, BoolToString,
    /* Below are instructions for Rhone hard fork */
    GroupOfAddress
  )
  val statefulInstrs0: AVector[InstrCompanion[StatefulContext]] = AVector(
    LoadMutField, StoreMutField, CallExternal,
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
    LoadImmField, LoadImmFieldByIndex,
    /* Below are instructions for Rhone hard fork */
    PayGasFee, MinimalContractDeposit, CreateMapEntry, MethodSelector, CallExternalBySelector
  )
  // format: on

  val toCode: Map[InstrCompanion[_ <: StatelessContext], Int] = {
    val instrs0: AVector[InstrCompanion[_ <: StatelessContext]] =
      AVector(CallLocal, CallExternal, Return)
    val instrs1: AVector[InstrCompanion[_ <: StatelessContext]] =
      statelessInstrs0.filter(!instrs0.contains(_)).as[InstrCompanion[_]]
    val instrs2: AVector[InstrCompanion[_ <: StatelessContext]] =
      statefulInstrs0.filter(!instrs0.contains(_)).as[InstrCompanion[_]]
    (instrs0.mapWithIndex((instr, index) => (instr, index)) ++
      instrs1.mapWithIndex((instr, index) => (instr, index + 3)) ++
      instrs2.mapWithIndex((instr, index) => (instr, index + 160))).toIterable.toMap
  }

  val statelessInstrs: AVector[Option[InstrCompanion[StatelessContext]]] = {
    val array = Array.fill[Option[InstrCompanion[StatelessContext]]](256)(None)
    statelessInstrs0.foreach(instr => array(toCode(instr)) = Some(instr))
    AVector.unsafe(array)
  }

  val statefulInstrs: AVector[Option[InstrCompanion[StatefulContext]]] = {
    val table = Array.fill[Option[InstrCompanion[StatefulContext]]](256)(None)
    statelessInstrs0.foreach(instr => table(toCode(instr)) = Some(instr))
    statefulInstrs0.foreach(instr => table(toCode(instr)) = Some(instr))
    AVector.unsafe(table)
  }

  val allLogInstrs: AVector[LogInstr] =
    AVector(Log1, Log2, Log3, Log4, Log5, Log6, Log7, Log8, Log9)

  val mockupCode: Byte = 0xff.toByte
}

sealed trait StatefulInstr  extends Instr[StatefulContext] with GasSchedule                     {}
sealed trait StatelessInstr extends StatefulInstr with Instr[StatelessContext] with GasSchedule {}
sealed trait StatefulInstrSimpleGas extends StatefulInstr with InstrWithSimpleGas[StatefulContext]
sealed trait StatelessInstrSimpleGas
    extends StatelessInstr
    with InstrWithSimpleGas[StatelessContext]

sealed trait InstrCompanion[-Ctx <: StatelessContext] {
  lazy val code: Byte = Instr.toCode(this).toByte

  def deserialize[C <: Ctx](input: ByteString): SerdeResult[Staging[Instr[C]]]
}

sealed abstract class InstrCompanion1[Ctx <: StatelessContext, T: Serde]
    extends InstrCompanion[Ctx] {
  def apply(t: T): Instr[Ctx]

  @inline def from[C <: Ctx](t: T): Instr[C] = apply(t)

  def deserialize[C <: Ctx](input: ByteString): SerdeResult[Staging[Instr[C]]] = {
    serdeImpl[T]._deserialize(input).map(_.mapValue(from))
  }
}

sealed abstract class StatelessInstrCompanion1[T: Serde]
    extends InstrCompanion1[StatelessContext, T]

sealed abstract class StatefulInstrCompanion1[T: Serde] extends InstrCompanion1[StatefulContext, T]

sealed trait StatelessInstrCompanion0
    extends InstrCompanion[StatelessContext]
    with Instr[StatelessContext]
    with GasSchedule {
  def serialize(): ByteString = ByteString(code)

  def deserialize[C <: StatelessContext](input: ByteString): SerdeResult[Staging[Instr[C]]] =
    Right(Staging(this, input))
}

sealed trait StatefulInstrCompanion0
    extends InstrCompanion[StatefulContext]
    with Instr[StatefulContext]
    with GasSchedule {
  def serialize(): ByteString = ByteString(code)

  def deserialize[C <: StatefulContext](input: ByteString): SerdeResult[Staging[Instr[C]]] =
    Right(Staging(this, input))
}

sealed trait OperandStackInstr extends StatelessInstrSimpleGas with GasSimple {}

sealed trait ConstInstr extends OperandStackInstr with GasBase
object ConstInstr {
  def i256(v: Val.I256): ConstInstr = {
    val bi = v.v.v
    if (bi.bitLength() <= 8) {
      (bi.intValue(): @switch) match {
        case -1 => I256ConstN1
        case 0  => I256Const0
        case 1  => I256Const1
        case 2  => I256Const2
        case 3  => I256Const3
        case 4  => I256Const4
        case 5  => I256Const5
        case _  => I256Const(v)
      }
    } else {
      I256Const(v)
    }
  }

  def u256(v: Val.U256): ConstInstr = {
    val bi = v.v.v
    if (bi.bitLength() <= 8) {
      (bi.intValue(): @switch) match {
        case 0 => U256Const0
        case 1 => U256Const1
        case 2 => U256Const2
        case 3 => U256Const3
        case 4 => U256Const4
        case 5 => U256Const5
        case _ => U256Const(v)
      }
    } else {
      U256Const(v)
    }
  }
}

sealed trait ConstInstr0 extends ConstInstr with StatelessInstrCompanion0 {
  def const: Val

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(const)
  }
}

sealed abstract class ConstInstr1[T <: Val] extends ConstInstr {
  def const: T

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(const)
  }
}

object ConstTrue  extends ConstInstr0 { val const: Val = Val.Bool(true)  }
object ConstFalse extends ConstInstr0 { val const: Val = Val.Bool(false) }

object I256ConstN1 extends ConstInstr0 { val const: Val = Val.I256(util.I256.NegOne)   }
object I256Const0  extends ConstInstr0 { val const: Val = Val.I256(util.I256.Zero)     }
object I256Const1  extends ConstInstr0 { val const: Val = Val.I256(util.I256.One)      }
object I256Const2  extends ConstInstr0 { val const: Val = Val.I256(util.I256.Two)      }
object I256Const3  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(3L)) }
object I256Const4  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(4L)) }
object I256Const5  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(5L)) }

object U256Const0 extends ConstInstr0 { val const: Val = Val.U256(util.U256.Zero)       }
object U256Const1 extends ConstInstr0 { val const: Val = Val.U256(util.U256.One)        }
object U256Const2 extends ConstInstr0 { val const: Val = Val.U256(util.U256.Two)        }
object U256Const3 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(3L)) }
object U256Const4 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(4L)) }
object U256Const5 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(5L)) }

@ByteCode
final case class I256Const(const: Val.I256) extends ConstInstr1[Val.I256] {
  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[util.I256].serialize(const.v)
}
object I256Const extends StatelessInstrCompanion1[Val.I256]
@ByteCode
final case class U256Const(const: Val.U256) extends ConstInstr1[Val.U256] {
  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[util.U256].serialize(const.v)
}
object U256Const extends StatelessInstrCompanion1[Val.U256]

@ByteCode
final case class BytesConst(const: Val.ByteVec) extends ConstInstr1[Val.ByteVec] {
  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[Val.ByteVec].serialize(const)
}
object BytesConst extends StatelessInstrCompanion1[Val.ByteVec]

@ByteCode
final case class AddressConst(const: Val.Address) extends ConstInstr1[Val.Address] {
  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[Val.Address].serialize(const)
}
object AddressConst extends StatelessInstrCompanion1[Val.Address]

// Note: 0 <= index <= 0xFF
@ByteCode
final case class LoadLocal(index: Byte) extends OperandStackInstr with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getLocalVal(Bytes.toPosInt(index))
      _ <- frame.pushOpStack(v)
    } yield ()
  }
}
object LoadLocal extends StatelessInstrCompanion1[Byte]
@ByteCode
final case class StoreLocal(index: Byte) extends OperandStackInstr with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.popOpStack()
      _ <- frame.setLocalVal(Bytes.toPosInt(index), v)
    } yield ()
  }
}
object StoreLocal extends StatelessInstrCompanion1[Byte]

sealed trait VarIndexInstr[Ctx <: StatelessContext]
    extends LemanInstrWithSimpleGas[Ctx]
    with GasLow {
  def popIndex[C <: Ctx](
      frame: Frame[C],
      error: (BigInteger, Int) => ExeFailure
  ): ExeResult[Int] = {
    val maxIndex = 0xff

    for {
      u256 <- frame.popOpStackU256()
      index <- u256.v.toInt
        .flatMap(v => if (v > maxIndex) None else Some(v))
        .toRight(Right(error(u256.v.v, maxIndex)))
    } yield index
  }
}

case object LoadLocalByIndex extends VarIndexInstr[StatelessContext] with StatelessInstrCompanion0 {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      index <- popIndex(frame, InvalidVarIndex)
      v     <- frame.getLocalVal(index)
      _     <- frame.pushOpStack(v)
    } yield ()
  }
}

case object StoreLocalByIndex
    extends VarIndexInstr[StatelessContext]
    with StatelessInstrCompanion0 {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      index <- popIndex(frame, InvalidVarIndex)
      v     <- frame.popOpStack()
      _     <- frame.setLocalVal(index, v)
    } yield ()
  }
}

sealed trait FieldInstr extends Instr[StatefulContext] with GasSimple {}
@ByteCode
final case class LoadImmField(index: Byte)
    extends LemanInstrWithSimpleGas[StatefulContext]
    with FieldInstr
    with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getImmField(Bytes.toPosInt(index))
      _ <- frame.pushOpStack(v)
    } yield ()
  }
}
object LoadImmField extends StatefulInstrCompanion1[Byte]
@ByteCode
final case class LoadMutField(index: Byte)
    extends FieldInstr
    with StatefulInstrSimpleGas
    with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getMutField(Bytes.toPosInt(index))
      _ <- frame.pushOpStack(v)
    } yield ()
  }
}
object LoadMutField extends StatefulInstrCompanion1[Byte]
@ByteCode
final case class StoreMutField(index: Byte)
    extends FieldInstr
    with StatefulInstrSimpleGas
    with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.popOpStack()
      _ <- frame.setMutField(Bytes.toPosInt(index), v)
    } yield ()
  }
}
object StoreMutField extends StatefulInstrCompanion1[Byte]

case object LoadImmFieldByIndex
    extends VarIndexInstr[StatefulContext]
    with StatefulInstrCompanion0 {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      index <- popIndex(frame, InvalidMutFieldIndex)
      v     <- frame.getImmField(index)
      _     <- frame.pushOpStack(v)
    } yield ()
  }
}

case object LoadMutFieldByIndex
    extends VarIndexInstr[StatefulContext]
    with StatefulInstrCompanion0 {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      index <- popIndex(frame, InvalidMutFieldIndex)
      v     <- frame.getMutField(index)
      _     <- frame.pushOpStack(v)
    } yield ()
  }
}

case object StoreMutFieldByIndex
    extends VarIndexInstr[StatefulContext]
    with StatefulInstrCompanion0 {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      index <- popIndex(frame, InvalidMutFieldIndex)
      v     <- frame.popOpStack()
      _     <- frame.setMutField(index, v)
    } yield ()
  }
}

case object PayGasFee
    extends RhoneInstrWithSimpleGas[StatefulContext]
    with GasBalance
    with StatefulInstrCompanion0 {

  def checkGasAmount[C <: StatefulContext](
      frame: Frame[C],
      alphAmount: U256
  ): ExeResult[Unit] = {
    val gasFee     = frame.ctx.txEnv.gasFeeUnsafe
    val gasFeePaid = frame.ctx.gasFeePaid

    assume(gasFee >= gasFeePaid) // This should always be true, so we check with assume

    val gasRemainingToPay = gasFee.subUnsafe(gasFeePaid)
    if (gasRemainingToPay < alphAmount) failed(GasOverPaid) else okay
  }

  def runWithRhone[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      balanceState <- frame.getBalanceState()
      amount       <- frame.popOpStackU256()
      payer        <- frame.popOpStackAddress().map(_.lockupScript)
      _            <- checkGasAmount(frame, amount.v)
      _ <- balanceState
        .useAlph(payer, amount.v)
        .toRight(
          Right(
            NotEnoughApprovedBalance(
              payer,
              TokenId.alph,
              amount.v,
              balanceState.remaining.getAttoAlphAmount(payer).getOrElse(U256.Zero)
            )
          )
        )
      _ <- frame.ctx.payGasFee(amount.v)
    } yield ()
  }
}

case object MinimalContractDeposit
    extends RhoneInstrWithSimpleGas[StatefulContext]
    with GasBase
    with StatefulInstrCompanion0 {
  def runWithRhone[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(Val.U256(model.minimalAlphInContract))
  }
}

case object GroupOfAddress
    extends RhoneInstrWithSimpleGas[StatelessContext]
    with GasLow
    with StatelessInstrCompanion0 {
  def runWithRhone[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address <- frame.popOpStackAddress()
      group = address.lockupScript.groupIndex(frame.ctx.groupConfig)
      _ <- frame.pushOpStack(Val.U256(util.U256.unsafe(group.value)))
    } yield ()
  }
}

sealed trait PureStackInstr extends OperandStackInstr with StatelessInstrCompanion0 with GasBase

case object Pop extends PureStackInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.remove(1)
  }
}

case object Dup extends PureStackInstr with LemanInstrWithSimpleGas[StatelessContext] {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.dupTop()
  }
}

case object Swap extends PureStackInstr with LemanInstrWithSimpleGas[StatelessContext] {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.swapTopTwo()
  }
}

sealed trait ArithmeticInstr
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasSimple {}

trait StackOps[T <: Val] {
  @inline def popOpStack(frame: Frame[_]): ExeResult[T]
}

trait I256StackOps extends StackOps[Val.I256] {
  @inline def popOpStack(frame: Frame[_]): ExeResult[Val.I256] = frame.popOpStackI256()
}

trait U256StackOps extends StackOps[Val.U256] {
  @inline def popOpStack(frame: Frame[_]): ExeResult[Val.U256] = frame.popOpStackU256()
}

trait BoolStackOps extends StackOps[Val.Bool] {
  @inline def popOpStack(frame: Frame[_]): ExeResult[Val.Bool] = frame.popOpStackBool()
}

trait AddressStackOps extends StackOps[Val.Address] {
  @inline def popOpStack(frame: Frame[_]): ExeResult[Val.Address] = frame.popOpStackAddress()
}

sealed trait BinaryInstr[T <: Val] extends StatelessInstr

sealed trait BinaryArithmeticInstr[T <: Val]
    extends BinaryInstr[T]
    with ArithmeticInstr
    with StackOps[T]
    with GasSimple {
  protected def op(x: T, y: T): ExeResult[Val]

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value2 <- popOpStack(frame)
      value1 <- popOpStack(frame)
      out    <- op(value1, value2)
      _      <- frame.pushOpStack(out)
    } yield ()
  }
}

object BinaryArithmeticInstr {
  def error(a: Val, b: Val, op: ArithmeticInstr): ArithmeticError = {
    ArithmeticError(s"Arithmetic error: $op($a, $b)")
  }

  @inline def i256Op(
      op: (util.I256, util.I256) => util.I256
  )(x: Val.I256, y: Val.I256): ExeResult[Val.I256] =
    Right(Val.I256.apply(op(x.v, y.v)))

  @inline def i256SafeOp(
      instr: ArithmeticInstr,
      op: (util.I256, util.I256) => Option[util.I256]
  )(x: Val.I256, y: Val.I256): ExeResult[Val.I256] =
    op(x.v, y.v).map(Val.I256.apply).toRight(Right(BinaryArithmeticInstr.error(x, y, instr)))

  @inline def u256Op(
      op: (util.U256, util.U256) => util.U256
  )(x: Val.U256, y: Val.U256): ExeResult[Val.U256] =
    Right(Val.U256.apply(op(x.v, y.v)))

  @inline def u256SafeOp(
      instr: ArithmeticInstr,
      op: (util.U256, util.U256) => Option[util.U256]
  )(x: Val.U256, y: Val.U256): ExeResult[Val.U256] =
    op(x.v, y.v).map(Val.U256.apply).toRight(Right(BinaryArithmeticInstr.error(x, y, instr)))

  @inline def i256Comp(
      op: (util.I256, util.I256) => Boolean
  )(x: Val.I256, y: Val.I256): ExeResult[Val.Bool] =
    Right(Val.Bool(op(x.v, y.v)))

  @inline def u256Comp(
      op: (util.U256, util.U256) => Boolean
  )(x: Val.U256, y: Val.U256): ExeResult[Val.Bool] =
    Right(Val.Bool(op(x.v, y.v)))
}
object I256Add extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] = {
    BinaryArithmeticInstr.i256SafeOp(this, _.add(_))(x, y)
  }
}
object I256Sub extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.sub(_))(x, y)
}
object I256Mul extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.mul(_))(x, y)
}
object I256Div extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.div(_))(x, y)
}
object I256Mod extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.mod(_))(x, y)
}
object I256Eq extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.==(_))(x, y)
}
object I256Neq extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.!=(_))(x, y)
}
object I256Lt extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.<(_))(x, y)
}
object I256Le extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.<=(_))(x, y)
}
object I256Gt extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.>(_))(x, y)
}
object I256Ge extends BinaryArithmeticInstr[Val.I256] with I256StackOps with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.>=(_))(x, y)
}
object U256Add extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.add(_))(x, y)
}
object U256Sub extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.sub(_))(x, y)
}
object U256Mul extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.mul(_))(x, y)
}
object U256Div extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.div(_))(x, y)
}
object U256Mod extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.mod(_))(x, y)
}
object U256ModAdd extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasMid {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.modAdd(_))(x, y)
}
object U256ModSub extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasMid {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.modSub(_))(x, y)
}
object U256ModMul extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasMid {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.modMul(_))(x, y)
}
object U256BitAnd extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.bitAnd(_))(x, y)
}
object U256BitOr extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.bitOr(_))(x, y)
}
object U256Xor extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.xor(_))(x, y)
}
object U256SHL extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op((x, y) => x.shl(y))(x, y)
}
object U256SHR extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op((x, y) => x.shr(y))(x, y)
}
object U256Eq extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.==(_))(x, y)
}
object U256Neq extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.!=(_))(x, y)
}
object U256Lt extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.<(_))(x, y)
}
object U256Le extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.<=(_))(x, y)
}
object U256Gt extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.>(_))(x, y)
}
object U256Ge extends BinaryArithmeticInstr[Val.U256] with U256StackOps with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.>=(_))(x, y)
}

sealed trait ExpInstr[T <: Val]
    extends BinaryInstr[T]
    with StatelessInstrCompanion0
    with LemanInstr[StatelessContext]
    with GasExp {
  def op[C <: StatelessContext](frame: Frame[C], exp: util.U256): ExeResult[T]

  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      exp    <- frame.popOpStackU256()
      result <- op(frame, exp.v)
      _      <- frame.ctx.chargeGasWithSize(this, exp.v.byteLength())
      _      <- frame.pushOpStack(result)
    } yield ()
  }
}
object I256Exp extends ExpInstr[Val.I256] {
  def op[C <: StatelessContext](frame: Frame[C], exp: util.U256): ExeResult[Val.I256] =
    frame
      .popOpStackI256()
      .flatMap(base =>
        base.v
          .pow(exp)
          .map(v => Val.I256(v))
          .toRight(Right(ArithmeticError(s"Exp overflow: $base ** $exp")))
      )
}

object U256Exp extends ExpInstr[Val.U256] {
  def op[C <: StatelessContext](frame: Frame[C], exp: util.U256): ExeResult[Val.U256] =
    frame
      .popOpStackU256()
      .flatMap(base =>
        base.v
          .pow(exp)
          .map(v => Val.U256(v))
          .toRight(Right(ArithmeticError(s"Exp overflow: $base ** $exp")))
      )
}
object U256ModExp extends ExpInstr[Val.U256] {
  def op[C <: StatelessContext](frame: Frame[C], exp: util.U256): ExeResult[Val.U256] =
    frame.popOpStackU256().map(base => Val.U256(base.v.modPow(exp)))
}

sealed trait ModNInstr
    extends StatelessInstrCompanion0
    with LemanInstr[StatelessContext]
    with GasSimple {
  def op(x: Val.U256, y: Val.U256, n: Val.U256): ExeResult[Val.U256]

  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      n      <- frame.popOpStackU256()
      y      <- frame.popOpStackU256()
      x      <- frame.popOpStackU256()
      result <- op(x, y, n)
      _      <- frame.ctx.chargeGas(this)
      _      <- frame.pushOpStack(result)
    } yield ()
  }
}

object MulModN extends ModNInstr with GasMulModN {
  def op(x: Val.U256, y: Val.U256, n: Val.U256): ExeResult[Val.U256] = {
    x.v.mulModN(y.v, n.v).map(Val.U256.apply).toRight(Right(ArithmeticError(s"($x * $y) % $n")))
  }
}

object AddModN extends ModNInstr with GasAddModN {
  def op(x: Val.U256, y: Val.U256, n: Val.U256): ExeResult[Val.U256] = {
    x.v.addModN(y.v, n.v).map(Val.U256.apply).toRight(Right(ArithmeticError(s"($x + $y) % $n")))
  }
}

sealed trait LogicInstr
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasSimple {}
case object BoolNot extends LogicInstr with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool <- frame.popOpStackBool()
      _    <- frame.pushOpStack(bool.not)
    } yield ()
  }
}
sealed trait BinaryBool extends LogicInstr with GasVeryLow {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool2 <- frame.popOpStackBool()
      bool1 <- frame.popOpStackBool()
      _     <- frame.pushOpStack(op(bool1, bool2))
    } yield ()
  }
}
case object BoolAnd extends BinaryBool {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool = bool1.and(bool2)
}
case object BoolOr extends BinaryBool {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool = bool1.or(bool2)
}
case object BoolEq extends BinaryBool {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool = Val.Bool(bool1 == bool2)
}
case object BoolNeq extends BinaryBool {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool = Val.Bool(bool1 != bool2)
}

sealed abstract class U256ToBytesInstr(val size: Int)
    extends StatelessInstr
    with LemanInstr[StatelessContext]
    with GasToByte
    with StatelessInstrCompanion0 {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value <- frame.popOpStackU256()
      bytes <- value.v.toFixedSizeBytes(size).toRight(Right(InvalidConversion(value, Val.ByteVec)))
      byteVec = Val.ByteVec(bytes)
      _ <- frame.pushOpStack(byteVec)
      _ <- frame.ctx.chargeGasWithSize(this, size)
    } yield ()
  }
}

case object U256To1Byte  extends U256ToBytesInstr(1)
case object U256To2Byte  extends U256ToBytesInstr(2)
case object U256To4Byte  extends U256ToBytesInstr(4)
case object U256To8Byte  extends U256ToBytesInstr(8)
case object U256To16Byte extends U256ToBytesInstr(16)
case object U256To32Byte extends U256ToBytesInstr(32)

sealed trait ToStringInstr
    extends StatelessInstr
    with LemanInstr[StatelessContext]
    with GasToByte
    with StatelessInstrCompanion0 {
  def toString[C <: StatelessContext](frame: Frame[C]): ExeResult[String]

  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      string <- toString(frame)
      bytes = ByteString.fromArrayUnsafe(string.getBytes(StandardCharsets.US_ASCII))
      _ <- frame.pushOpStack(Val.ByteVec(bytes))
      _ <- frame.ctx.chargeGasWithSize(this, bytes.length)
    } yield ()
  }
}

object U256ToString extends ToStringInstr {
  def toString[C <: StatelessContext](frame: Frame[C]): ExeResult[String] = {
    frame.popOpStackU256().map(_.v.v.toString())
  }
}

object I256ToString extends ToStringInstr {
  def toString[C <: StatelessContext](frame: Frame[C]): ExeResult[String] = {
    frame.popOpStackI256().map(_.v.v.toString())
  }
}

object BoolToString extends ToStringInstr {
  private val trueString  = "true"
  private val falseString = "false"

  def toString[C <: StatelessContext](frame: Frame[C]): ExeResult[String] = {
    frame.popOpStackBool().map(bool => if (bool.v) trueString else falseString)
  }
}

sealed abstract class U256FromBytesInstr(val size: Int)
    extends StatelessInstr
    with LemanInstr[StatelessContext]
    with GasToByte
    with StatelessInstrCompanion0 {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      byteVec <- frame.popOpStackByteVec()
      _       <- if (byteVec.bytes.length == size) okay else failed(InvalidBytesSize)
      number  <- util.U256.from(byteVec.bytes).toRight(Right(InvalidConversion(byteVec, Val.U256)))
      _       <- frame.pushOpStack(Val.U256(number))
      _       <- frame.ctx.chargeGasWithSize(this, size)
    } yield ()
  }
}

case object U256From1Byte  extends U256FromBytesInstr(1)
case object U256From2Byte  extends U256FromBytesInstr(2)
case object U256From4Byte  extends U256FromBytesInstr(4)
case object U256From8Byte  extends U256FromBytesInstr(8)
case object U256From16Byte extends U256FromBytesInstr(16)
case object U256From32Byte extends U256FromBytesInstr(32)

sealed trait ToByteVecInstr[R <: Val]
    extends StatelessInstr
    with StackOps[R]
    with GasToByte
    with StatelessInstrCompanion0 {

  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      from <- popOpStack(frame)
      byteVec = from.toByteVec()
      _ <- frame.pushOpStack(byteVec)
      _ <- frame.ctx.chargeGasWithSize(this, byteVec.bytes.size)
    } yield ()
  }
}
case object BoolToByteVec extends ToByteVecInstr[Val.Bool] with BoolStackOps

sealed trait ConversionInstr[R <: Val, U <: Val]
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with StackOps[R]
    with GasSimple {

  def converse(from: R): ExeResult[U]

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      from <- popOpStack(frame)
      to   <- converse(from)
      _    <- frame.pushOpStack(to)
    } yield ()
  }
}

case object I256ToU256
    extends ConversionInstr[Val.I256, Val.U256]
    with I256StackOps
    with GasVeryLow {
  def converse(from: Val.I256): ExeResult[Val.U256] = {
    util.U256.fromI256(from.v).map(Val.U256.apply).toRight(Right(InvalidConversion(from, Val.U256)))
  }
}
case object I256ToByteVec extends ToByteVecInstr[Val.I256] with I256StackOps
case object U256ToI256
    extends ConversionInstr[Val.U256, Val.I256]
    with U256StackOps
    with GasVeryLow {
  def converse(from: Val.U256): ExeResult[Val.I256] = {
    util.I256.fromU256(from.v).map(Val.I256.apply).toRight(Right(InvalidConversion(from, Val.I256)))
  }
}
case object U256ToByteVec extends ToByteVecInstr[Val.U256] with U256StackOps

sealed trait ComparisonInstr[T <: Val]
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with StackOps[T]
    with GasVeryLow {
  def op(x: T, y: T): Val.Bool

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      x <- popOpStack(frame)
      y <- popOpStack(frame)
      _ <- frame.pushOpStack(op(x, y))
    } yield ()
  }
}
sealed trait EqT[T <: Val] extends ComparisonInstr[T] {
  def op(x: T, y: T): Val.Bool = Val.Bool(x == y)
}
sealed trait NeT[T <: Val] extends ComparisonInstr[T] {
  def op(x: T, y: T): Val.Bool = Val.Bool(x != y)
}

sealed trait ByteVecComparison
    extends StatelessInstr
    with StatelessInstrCompanion0
    with GasBytesEq {
  def op(x: Val.ByteVec, y: Val.ByteVec): Val.Bool

  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      x <- frame.popOpStackByteVec()
      y <- frame.popOpStackByteVec()
      _ <- frame.ctx.chargeGasWithSizeLeman(this, x.bytes.size)
      _ <- frame.pushOpStack(op(x, y))
    } yield ()
  }
}
case object ByteVecEq extends ByteVecComparison {
  def op(x: Val.ByteVec, y: Val.ByteVec): Val.Bool = Val.Bool(x == y)
}
case object ByteVecNeq extends ByteVecComparison {
  def op(x: Val.ByteVec, y: Val.ByteVec): Val.Bool = Val.Bool(x != y)
}
case object ByteVecSize extends StatelessInstrSimpleGas with StatelessInstrCompanion0 with GasBase {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.popOpStackByteVec()
      _ <- frame.pushOpStack(Val.U256(util.U256.unsafe(v.bytes.size)))
    } yield ()
  }
}
case object ByteVecConcat extends StatelessInstr with StatelessInstrCompanion0 with GasBytesConcat {
  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v2 <- frame.popOpStackByteVec()
      v1 <- frame.popOpStackByteVec()
      result = Val.ByteVec(v1.bytes ++ v2.bytes)
      _ <- frame.ctx.chargeGasWithSizeLeman(this, result.estimateByteSize())
      _ <- frame.pushOpStack(result)
    } yield ()
  }
}
case object ByteVecSlice
    extends StatelessInstr
    with LemanInstr[StatelessContext]
    with StatelessInstrCompanion0
    with GasBytesSlice {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      end   <- frame.popOpStackU256().flatMap(_.v.toInt.toRight(Right(InvalidBytesSliceArg)))
      begin <- frame.popOpStackU256().flatMap(_.v.toInt.toRight(Right(InvalidBytesSliceArg)))
      bytes <- frame.popOpStackByteVec().map(_.bytes)
      result <-
        if (0 <= begin && begin <= end && end <= bytes.length) {
          Right(Val.ByteVec(bytes.slice(begin, end)))
        } else {
          failed(InvalidBytesSliceArg)
        }
      _ <- frame.ctx.chargeGasWithSize(this, result.estimateByteSize())
      _ <- frame.pushOpStack(result)
    } yield ()
  }
}
case object ByteVecToAddress
    extends StatelessInstr
    with LemanInstr[StatelessContext]
    with StatelessInstrCompanion0
    with GasToByte {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bytes <- frame.popOpStackByteVec().map(_.bytes)
      address <- decode[Val.Address](bytes).left.map(e =>
        Right(SerdeErrorByteVecToAddress(bytes, e))
      )
      _ <- frame.ctx.chargeGasWithSize(this, bytes.length)
      _ <- frame.pushOpStack(address)
    } yield ()
  }
}

case object ContractIdToAddress
    extends StatelessInstr
    with LemanInstr[StatelessContext]
    with StatelessInstrCompanion0
    with GasLow {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      contractIdRaw <- frame.popOpStackByteVec()
      contractId    <- ContractId.from(contractIdRaw.bytes).toRight(Right(InvalidContractId))
      address = Val.Address(LockupScript.p2c(contractId))
      _ <- frame.ctx.chargeGas(gas())
      _ <- frame.pushOpStack(address)
    } yield ()
  }
}

case object AddressEq        extends EqT[Val.Address] with AddressStackOps
case object AddressNeq       extends NeT[Val.Address] with AddressStackOps
case object AddressToByteVec extends ToByteVecInstr[Val.Address] with AddressStackOps

case object Encode
    extends StatelessInstr
    with LemanInstr[StatelessContext]
    with StatelessInstrCompanion0
    with GasEncode {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      u256   <- frame.popOpStackU256()
      n      <- u256.v.toInt.toRight(Right(InvalidLengthForEncodeInstr))
      values <- frame.opStack.pop(n)
      bytes = encode(values)
      _ <- frame.ctx.chargeGasWithSize(this, bytes.length)
      _ <- frame.pushOpStack(Val.ByteVec(bytes))
    } yield ()
  }
}

case object Zeros
    extends StatelessInstr
    with LemanInstr[StatelessContext]
    with StatelessInstrCompanion0
    with GasZeros {
  // scalastyle:off magic.number
  val maxSize: util.U256 = util.U256.unsafe(4096)
  // scalastyle:on magic.number

  @inline def checkSizeRange(size: util.U256): ExeResult[Int] = {
    if (size <= maxSize) Right(size.toIntUnsafe) else failed(InvalidSizeForZeros)
  }

  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      u256 <- frame.popOpStackU256()
      size <- checkSizeRange(u256.v)
      _    <- frame.ctx.chargeGasWithSize(this, size)
      _    <- frame.pushOpStack(Val.ByteVec(ByteString.fromArrayUnsafe(Array.fill(size)(0.toByte))))
    } yield ()
  }
}

case object IsAssetAddress
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address <- frame.popOpStackAddress()
      _       <- frame.pushOpStack(Val.Bool(address.lockupScript.isAssetType))
    } yield ()
  }
}

case object IsContractAddress
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address <- frame.popOpStackAddress()
      _       <- frame.pushOpStack(Val.Bool(!address.lockupScript.isAssetType))
    } yield ()
  }
}

sealed trait ControlInstr extends StatelessInstrSimpleGas with GasMid {
  def code: Byte
  def offset: Int

  def serialize(): ByteString = ByteString(code) ++ serdeImpl[Int].serialize(offset)
}

sealed trait ControlCompanion[T <: StatelessInstr] extends InstrCompanion[StatelessContext] {
  def apply(offset: Int): T

  def deserialize[C <: StatelessContext](input: ByteString): SerdeResult[Staging[T]] = {
    ControlCompanion.offsetSerde._deserialize(input).map(_.mapValue(apply))
  }
}
object ControlCompanion {
  def validate(n: Int): Boolean = (n <= (1 << 16)) && (n >= -(1 << 16))

  val offsetSerde: Serde[Int] = serdeImpl[Int].validate(offset =>
    if (validate(offset)) Right(()) else Left(s"Invalid offset $offset")
  )
}

@ByteCode
final case class Jump(offset: Int) extends ControlInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.offsetPC(offset)
  }
}
object Jump extends ControlCompanion[Jump]

sealed trait IfJumpInstr extends ControlInstr {
  def condition(value: Val.Bool): Boolean

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value <- frame.popOpStackBool()
      _     <- if (condition(value)) frame.offsetPC(offset) else okay
    } yield ()
  }
}
@ByteCode
final case class IfTrue(offset: Int) extends IfJumpInstr {
  def condition(value: Val.Bool): Boolean = value.v
}
object IfTrue extends ControlCompanion[IfTrue]

@ByteCode
final case class IfFalse(offset: Int) extends IfJumpInstr {
  def condition(value: Val.Bool): Boolean = !value.v
}
object IfFalse extends ControlCompanion[IfFalse]

sealed trait CallInstr
@ByteCode
final case class CallLocal(index: Byte) extends CallInstr with StatelessInstr with GasCall {
  def serialize(): ByteString = ByteString(code, index)

  // Implemented in frame instead
  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = ???
}
object CallLocal extends StatelessInstrCompanion1[Byte]
@ByteCode
final case class CallExternal(index: Byte) extends CallInstr with StatefulInstr with GasCall {
  def serialize(): ByteString = ByteString(code, index)

  // Implemented in frame instead
  def runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = ???
}
object CallExternal extends StatefulInstrCompanion1[Byte]

@ByteCode
final case class MethodSelector(selector: Method.Selector)
    extends CallInstr
    with StatefulInstr
    with GasHigh {
  def serialize(): ByteString = ByteString(code) ++ encode(selector)

  // The execution is skipped. It's only used to provide method selector
  def runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = okay
}
object MethodSelector extends InstrCompanion[StatefulContext] {

  def deserialize[C <: StatefulContext](
      input: ByteString
  ): SerdeResult[Staging[MethodSelector]] = {
    implicitly[Serde[Method.Selector]]._deserialize(input).map(_.mapValue(MethodSelector(_)))
  }
}

@ByteCode
final case class CallExternalBySelector(selector: Method.Selector)
    extends CallInstr
    with StatefulInstr
    with GasCall {
  def serialize(): ByteString = ByteString(code) ++ encode(selector)

  // Implemented in frame instead
  def runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = ???
}
object CallExternalBySelector extends InstrCompanion[StatefulContext] {
  def deserialize[C <: StatefulContext](
      input: ByteString
  ): SerdeResult[Staging[Instr[StatefulContext]]] = {
    implicitly[Serde[Method.Selector]]
      ._deserialize(input)
      .map(_.mapValue(CallExternalBySelector(_)))
  }
}

case object Return extends StatelessInstrSimpleGas with StatelessInstrCompanion0 with GasZero {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value <- frame.opStack.pop(frame.method.returnLength)
      _     <- frame.returnTo(value)
    } yield frame.complete()
  }
}

case object Assert extends StatelessInstrSimpleGas with StatelessInstrCompanion0 with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      predicate <- frame.popOpStackBool()
      _         <- if (predicate.v) okay else failed(AssertionFailed)
    } yield ()
  }
}

case object AssertWithErrorCode
    extends LemanInstrWithSimpleGas[StatelessContext]
    with StatelessInstrCompanion0
    with GasVeryLow {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      errorCodeU256 <- frame.popOpStackU256()
      errorCode     <- errorCodeU256.v.toInt.toRight(Right(InvalidErrorCode(errorCodeU256.v)))
      predicate     <- frame.popOpStackBool()
      _ <-
        if (predicate.v) {
          okay
        } else {
          failed(AssertionFailedWithErrorCode(frame.obj.contractIdOpt, errorCode))
        }
    } yield ()
  }
}

sealed trait CryptoInstr extends StatelessInstr with GasSchedule

sealed abstract class HashAlg[H <: RandomBytes]
    extends CryptoInstr
    with StatelessInstrCompanion0
    with GasHash {
  def hash(bs: ByteString): H

  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      input <- frame.popOpStackByteVec()
      _     <- frame.ctx.chargeGasWithSize(this, input.bytes.length)
      _     <- frame.pushOpStack(Val.ByteVec.from(hash(input.bytes)))
    } yield ()
  }
}

object HashAlg {
  trait Blake2bHash {
    def hash(bs: ByteString): crypto.Blake2b = crypto.Blake2b.hash(bs)
  }

  trait Keccak256Hash {
    def hash(bs: ByteString): crypto.Keccak256 = crypto.Keccak256.hash(bs)
  }

  trait Sha256Hash {
    def hash(bs: ByteString): crypto.Sha256 = crypto.Sha256.hash(bs)
  }

  trait Sha3Hash {
    def hash(bs: ByteString): crypto.Sha3 = crypto.Sha3.hash(bs)
  }
}

case object Blake2b   extends HashAlg[crypto.Blake2b] with HashAlg.Blake2bHash
case object Keccak256 extends HashAlg[crypto.Keccak256] with HashAlg.Keccak256Hash
case object Sha256    extends HashAlg[crypto.Sha256] with HashAlg.Sha256Hash
case object Sha3      extends HashAlg[crypto.Sha3] with HashAlg.Sha3Hash

sealed trait SignatureInstr extends CryptoInstr with StatelessInstrSimpleGas with GasSimple

case object VerifyTxSignature
    extends SignatureInstr
    with StatelessInstrCompanion0
    with GasSignature {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    val rawData    = frame.ctx.txId.bytes
    val signatures = frame.ctx.signatures
    for {
      rawPublicKey <- frame.popOpStackByteVec()
      publicKey <- PublicKey
        .from(rawPublicKey.bytes)
        .toRight(Right(InvalidPublicKey(rawPublicKey.bytes)))
      signature <- signatures.pop()
      _ <- {
        if (SignatureSchema.verify(rawData, signature, publicKey)) {
          okay
        } else {
          failed(InvalidSignature(rawPublicKey.bytes, rawData, signature.bytes))
        }
      }
    } yield ()
  }

  override def mockup(): Instr[StatelessContext] = {
    assume(this.gas() == VerifyTxSignatureMockup.gas())
    VerifyTxSignatureMockup
  }
}

case object VerifyTxSignatureMockup
    extends SignatureInstr
    with StatelessInstrCompanion0
    with GasSignature {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    val signatures = frame.ctx.signatures
    for {
      _ <- frame.popOpStackByteVec() // rawPublicKey
      _ <- signatures.pop()          // signature
    } yield ()
  }

  override lazy val code: Byte = Instr.mockupCode
}

sealed trait GenericVerifySignature[PubKey, Sig]
    extends SignatureInstr
    with StatelessInstrCompanion0
    with GasSignature {
  def buildPubKey(value: Val.ByteVec): Option[PubKey]
  def buildSignature(value: Val.ByteVec): Option[Sig]
  def verify(data: ByteString, signature: Sig, pubKey: PubKey): Boolean

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      rawSignature <- frame.popOpStackByteVec()
      signature <- buildSignature(rawSignature).toRight(
        Right(InvalidSignatureFormat(rawSignature.bytes))
      )
      rawPublicKey <- frame.popOpStackByteVec()
      publicKey    <- buildPubKey(rawPublicKey).toRight(Right(InvalidPublicKey(rawPublicKey.bytes)))
      rawData      <- frame.popOpStackByteVec()
      _ <-
        if (rawData.bytes.length == 32) {
          okay
        } else {
          failed(SignedDataIsNot32Bytes(rawData.bytes.length))
        }
      _ <-
        if (verify(rawData.bytes, signature, publicKey)) {
          okay
        } else {
          failed(InvalidSignature(rawPublicKey.bytes, rawData.bytes, rawSignature.bytes))
        }
    } yield ()
  }

  override def mockup(): Instr[StatelessContext] = {
    assume(this.gas() == VerifySignatureMockup.gas())
    VerifySignatureMockup
  }
}

sealed trait GenericVerifySignatureMockup[PubKey, Sig]
    extends SignatureInstr
    with StatelessInstrCompanion0
    with GasSignature {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- frame.popOpStackByteVec() // rawSignature
      _ <- frame.popOpStackByteVec() // rawPublicKey
      _ <- frame.popOpStackByteVec() // rawData
    } yield ()
  }
}

case object VerifySignatureMockup
    extends GenericVerifySignatureMockup[crypto.SecP256K1PublicKey, crypto.SecP256K1Signature] {
  override lazy val code: Byte = Instr.mockupCode
}

case object VerifySecP256K1
    extends GenericVerifySignature[crypto.SecP256K1PublicKey, crypto.SecP256K1Signature] {
  def buildPubKey(value: Val.ByteVec): Option[crypto.SecP256K1PublicKey] =
    crypto.SecP256K1PublicKey.from(value.bytes)
  def buildSignature(value: Val.ByteVec): Option[crypto.SecP256K1Signature] =
    crypto.SecP256K1Signature.from(value.bytes)
  def verify(
      data: ByteString,
      signature: crypto.SecP256K1Signature,
      pubKey: crypto.SecP256K1PublicKey
  ): Boolean =
    crypto.SecP256K1.verify(data, signature, pubKey)
}

case object VerifyED25519
    extends GenericVerifySignature[crypto.ED25519PublicKey, crypto.ED25519Signature] {
  def buildPubKey(value: Val.ByteVec): Option[crypto.ED25519PublicKey] =
    crypto.ED25519PublicKey.from(value.bytes)
  def buildSignature(value: Val.ByteVec): Option[crypto.ED25519Signature] =
    crypto.ED25519Signature.from(value.bytes)
  def verify(
      data: ByteString,
      signature: crypto.ED25519Signature,
      pubKey: crypto.ED25519PublicKey
  ): Boolean =
    crypto.ED25519.verify(data, signature, pubKey)
}

case object VerifyBIP340Schnorr
    extends GenericVerifySignature[crypto.BIP340SchnorrPublicKey, crypto.BIP340SchnorrSignature]
    with LemanInstrWithSimpleGas[StatelessContext] {
  def buildPubKey(value: Val.ByteVec): Option[crypto.BIP340SchnorrPublicKey] =
    crypto.BIP340SchnorrPublicKey.from(value.bytes)
  def buildSignature(value: Val.ByteVec): Option[crypto.BIP340SchnorrSignature] =
    crypto.BIP340SchnorrSignature.from(value.bytes)
  def verify(
      data: ByteString,
      signature: crypto.BIP340SchnorrSignature,
      pubKey: crypto.BIP340SchnorrPublicKey
  ): Boolean =
    crypto.BIP340Schnorr.verify(data, signature, pubKey)

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] =
    super[GenericVerifySignature]._runWith(frame)

  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] =
    _runWith(frame)

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] =
    super[LemanInstrWithSimpleGas].runWith(frame)
}

case object GetSegregatedSignature
    extends SignatureInstr
    with LemanInstrWithSimpleGas[StatelessContext]
    with StatelessInstrCompanion0
    with GasVeryLow {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.ctx.signatures.pop().flatMap { signature =>
      frame.pushOpStack(Val.ByteVec(signature.bytes))
    }
  }
}

case object EthEcRecover
    extends CryptoInstr
    with LemanInstrWithSimpleGas[StatelessContext]
    with StatelessInstrCompanion0
    with GasEcRecover {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      sigBytes    <- frame.popOpStackByteVec()
      messageHash <- frame.popOpStackByteVec()
      address <- SecP256K1
        .ethEcRecover(messageHash.bytes, sigBytes.bytes)
        .toRight(Right(FailedInRecoverEthAddress))
      _ <- frame.pushOpStack(Val.ByteVec(address))
    } yield ()
  }
}

sealed trait AssetInstr extends StatefulInstrSimpleGas with GasBalance

sealed trait LemanAssetInstr extends LemanInstrWithSimpleGas[StatefulContext] with GasBalance

object BurnToken extends LemanAssetInstr with StatefulInstrCompanion0 {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      tokenAmount  <- frame.popOpStackU256()
      tokenIdRaw   <- frame.popOpStackByteVec()
      tokenId      <- TokenId.from(tokenIdRaw.bytes).toRight(Right(InvalidTokenId))
      fromAddress  <- frame.popOpStackAddress()
      balanceState <- frame.getBalanceState()
      _ <-
        if (tokenId == TokenId.alph) {
          Left(Right(BurningAlphNotAllowed))
        } else {
          balanceState
            .useToken(fromAddress.lockupScript, tokenId, tokenAmount.v)
            .toRight(
              Right(
                NotEnoughApprovedBalance(
                  fromAddress.lockupScript,
                  tokenId,
                  tokenAmount.v,
                  balanceState.tokenRemainingUnsafe(fromAddress.lockupScript, tokenId)
                )
              )
            )
        }
    } yield ()
  }
}

sealed trait LockApprovedAssetsInstr extends LemanAssetInstr with StatefulInstrCompanion0 {
  def popTimestamp[C <: StatefulContext](frame: Frame[C]): ExeResult[TimeStamp] = {
    for {
      timestampU256 <- frame.popOpStackU256()
      timestamp     <- timestampU256.v.toLong.map(TimeStamp.unsafe).toRight(Right(LockTimeOverflow))
      blockTime = frame.ctx.blockEnv.timeStamp
      _ <-
        if (timestamp > blockTime) okay else failed(InvalidLockTime(timestamp, blockTime))
    } yield timestamp
  }
}

object LockApprovedAssets extends LockApprovedAssetsInstr {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      lockTime     <- popTimestamp(frame)
      lockupScript <- frame.popAssetAddress()
      balanceState <- frame.getBalanceState()
      approved <- balanceState
        .useAllApproved(lockupScript)
        .toRight(Right(NoAssetsApproved(Address.Asset(lockupScript))))
      outputs <- approved.toLockedTxOutput(lockupScript, lockTime, frame.ctx.getHardFork())
      _       <- outputs.foreachE(frame.ctx.generateOutput)
    } yield ()
  }
}

sealed trait ApproveAssetBase {
  @inline protected def approveALPH(
      balanceState: MutBalanceState,
      from: LockupScript,
      amount: U256,
      hardFork: HardFork
  ): ExeResult[Unit] = {
    if (amount.isZero && hardFork.isRhoneEnabled()) {
      okay
    } else {
      balanceState
        .approveALPH(from, amount)
        .toRight(
          Right(
            NotEnoughApprovedBalance(
              from,
              TokenId.alph,
              amount,
              balanceState.alphRemainingUnsafe(from)
            )
          )
        )
    }
  }

  @inline protected def approveToken(
      balanceState: MutBalanceState,
      from: LockupScript,
      tokenId: TokenId,
      amount: U256,
      hardFork: HardFork
  ): ExeResult[Unit] = {
    if (amount.isZero && hardFork.isRhoneEnabled()) {
      okay
    } else {
      balanceState
        .approveToken(from, tokenId, amount)
        .toRight(
          Right(
            NotEnoughApprovedBalance(
              from,
              tokenId,
              amount,
              balanceState.tokenRemainingUnsafe(from, tokenId)
            )
          )
        )
    }
  }
}

object ApproveAlph extends AssetInstr with StatefulInstrCompanion0 with ApproveAssetBase {
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.JavaSerializable",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable"
    )
  )
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      amount       <- frame.popOpStackU256()
      address      <- frame.popOpStackAddress()
      balanceState <- frame.getBalanceState()
      _ <- approveALPH(balanceState, address.lockupScript, amount.v, frame.ctx.getHardFork())
    } yield ()
  }
}

object ApproveToken extends AssetInstr with StatefulInstrCompanion0 with ApproveAssetBase {
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.JavaSerializable",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable"
    )
  )
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      amount       <- frame.popOpStackU256()
      tokenIdRaw   <- frame.popOpStackByteVec()
      tokenId      <- TokenId.from(tokenIdRaw.bytes).toRight(Right(InvalidTokenId))
      address      <- frame.popOpStackAddress()
      balanceState <- frame.getBalanceState()
      hardFork = frame.ctx.getHardFork()
      _ <-
        if (hardFork.isLemanEnabled() && tokenId == TokenId.alph) {
          approveALPH(balanceState, address.lockupScript, amount.v, hardFork)
        } else {
          approveToken(balanceState, address.lockupScript, tokenId, amount.v, hardFork)
        }
    } yield ()
  }
}

object AlphRemaining extends AssetInstr with StatefulInstrCompanion0 {
  def getAmount(
      hardFork: HardFork,
      balanceState: MutBalanceState,
      address: Val.Address
  ): ExeResult[U256] = {
    val amountOpt = balanceState.alphRemaining(address.lockupScript)
    if (hardFork.isRhoneEnabled()) {
      Right(amountOpt.getOrElse(U256.Zero))
    } else {
      amountOpt.toRight(Right(NoAlphBalanceForTheAddress(Address.from(address.lockupScript))))
    }
  }

  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address      <- frame.popOpStackAddress()
      balanceState <- frame.getBalanceState()
      amount       <- getAmount(frame.ctx.getHardFork(), balanceState, address)
      _            <- frame.pushOpStack(Val.U256(amount))
    } yield ()
  }
}

object TokenRemaining extends AssetInstr with StatefulInstrCompanion0 {
  def getAmount(
      hardFork: HardFork,
      balanceState: MutBalanceState,
      address: Val.Address,
      tokenId: TokenId
  ): ExeResult[U256] = {
    val isALPH = tokenId == TokenId.alph
    val amountOpt = if (hardFork.isLemanEnabled() && isALPH) {
      balanceState.alphRemaining(address.lockupScript)
    } else {
      balanceState.tokenRemaining(address.lockupScript, tokenId)
    }
    if (hardFork.isRhoneEnabled()) {
      Right(amountOpt.getOrElse(U256.Zero))
    } else {
      amountOpt.toRight(
        if (isALPH) {
          Right(NoAlphBalanceForTheAddress(Address.from(address.lockupScript)))
        } else {
          Right(NoTokenBalanceForTheAddress(tokenId, Address.from(address.lockupScript)))
        }
      )
    }
  }

  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      tokenIdRaw   <- frame.popOpStackByteVec()
      address      <- frame.popOpStackAddress()
      tokenId      <- TokenId.from(tokenIdRaw.bytes).toRight(Right(InvalidTokenId))
      balanceState <- frame.getBalanceState()
      amount       <- getAmount(frame.ctx.getHardFork(), balanceState, address, tokenId)
      _            <- frame.pushOpStack(Val.U256(amount))
    } yield ()
  }
}

object IsPaying extends AssetInstr with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address      <- frame.popOpStackAddress()
      balanceState <- frame.getBalanceState()
      isPaying = balanceState.isPaying(address.lockupScript)
      _ <- frame.pushOpStack(Val.Bool(isPaying))
    } yield ()
  }
}

sealed trait Transfer extends AssetInstr {
  def getContractLockupScript[C <: StatefulContext](frame: Frame[C]): ExeResult[LockupScript] = {
    frame.obj.getContractId().map(LockupScript.p2c)
  }

  def getToAddressFromStack[C <: StatefulContext](frame: Frame[C]): ExeResult[LockupScript] = {
    frame.popOpStackAddress().flatMap {
      case Val.Address(asset: LockupScript.Asset) => Right(asset)
      case Val.Address(contract: LockupScript.P2C) =>
        if (frame.ctx.getHardFork().isLemanEnabled()) {
          frame.checkPayToContractAddressInCallerTrace(contract).map(_ => contract)
        } else {
          Right(contract)
        }
    }
  }

  @inline def transferAlph[C <: StatefulContext](
      frame: Frame[C],
      from: LockupScript,
      to: LockupScript,
      amount: Val.U256
  ): ExeResult[Unit] = {
    if (amount.v.isZero && frame.ctx.getHardFork().isRhoneEnabled()) {
      okay
    } else {
      for {
        balanceState <- frame.getBalanceState()
        _ <- balanceState
          .useAlph(from, amount.v)
          .toRight(
            Right(
              NotEnoughApprovedBalance(
                from,
                TokenId.alph,
                amount.v,
                balanceState.alphRemainingUnsafe(from)
              )
            )
          )
        _ <- frame.ctx.outputBalances
          .addAlph(to, amount.v)
          .toRight(Right(BalanceOverflow))
      } yield ()
    }
  }

  @inline def transferAlph[C <: StatefulContext](
      frame: Frame[C],
      fromThunk: => ExeResult[LockupScript],
      toThunk: => ExeResult[LockupScript]
  ): ExeResult[Unit] = {
    for {
      amount <- frame.popOpStackU256()
      to     <- toThunk
      from   <- fromThunk
      _      <- transferAlph(frame, from, to, amount)
    } yield ()
  }

  @inline def transferToken[C <: StatefulContext](
      frame: Frame[C],
      tokenId: TokenId,
      from: LockupScript,
      to: LockupScript,
      amount: Val.U256
  ): ExeResult[Unit] = {
    if (amount.v.isZero && frame.ctx.getHardFork().isRhoneEnabled()) {
      okay
    } else {
      for {
        balanceState <- frame.getBalanceState()
        _ <- balanceState
          .useToken(from, tokenId, amount.v)
          .toRight(
            Right(
              NotEnoughApprovedBalance(
                from,
                tokenId,
                amount.v,
                balanceState.tokenRemainingUnsafe(from, tokenId)
              )
            )
          )
        _ <- frame.ctx.outputBalances
          .addToken(to, tokenId, amount.v)
          .toRight(Right(BalanceOverflow))
      } yield ()
    }
  }

  @inline def transferToken[C <: StatefulContext](
      frame: Frame[C],
      fromThunk: => ExeResult[LockupScript],
      toThunk: => ExeResult[LockupScript]
  ): ExeResult[Unit] = {
    for {
      amount     <- frame.popOpStackU256()
      tokenIdRaw <- frame.popOpStackByteVec()
      tokenId    <- TokenId.from(tokenIdRaw.bytes).toRight(Right(InvalidTokenId))
      to         <- toThunk
      from       <- fromThunk
      _ <-
        if (frame.ctx.getHardFork().isLemanEnabled() && tokenId == TokenId.alph) {
          transferAlph(frame, from, to, amount)
        } else {
          transferToken(frame, tokenId, from, to, amount)
        }
    } yield ()
  }
}

object TransferAlph extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlph(
      frame,
      frame.popOpStackAddress().map(_.lockupScript),
      getToAddressFromStack(frame)
    )
  }
}

object TransferAlphFromSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlph(
      frame,
      getContractLockupScript(frame),
      getToAddressFromStack(frame)
    )
  }
}

object TransferAlphToSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlph(
      frame,
      frame.popOpStackAddress().map(_.lockupScript),
      getContractLockupScript(frame)
    )
  }
}

object TransferToken extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      frame.popOpStackAddress().map(_.lockupScript),
      getToAddressFromStack(frame)
    )
  }
}

object TransferTokenFromSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      getContractLockupScript(frame),
      getToAddressFromStack(frame)
    )
  }
}

object TransferTokenToSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      frame.popOpStackAddress().map(_.lockupScript),
      getContractLockupScript(frame)
    )
  }
}

sealed trait ContractInstr
    extends StatefulInstrSimpleGas
    with StatefulInstrCompanion0
    with GasSimple {}

sealed trait TokenIssuance
object TokenIssuance {
  case object NoIssuance                extends TokenIssuance
  case object IssueTokenWithoutTransfer extends TokenIssuance
  case object IssueTokenAndTransfer     extends TokenIssuance

  final case class Info(amount: Val.U256, transferTo: Option[LockupScript.Asset])
  object Info {
    def apply(amount: Val.U256): Info  = Info(amount, None)
    def apply(amount: util.U256): Info = Info(Val.U256(amount), None)
  }
}

sealed trait ContractFactory extends StatefulInstrSimpleGas with GasSimple {
  def subContract: Boolean
  def copyCreate: Boolean

  @inline protected def getTokenIssuanceInfo[C <: StatefulContext](
      frame: Frame[C],
      tokenIssuance: TokenIssuance
  ): ExeResult[Option[TokenIssuance.Info]] = {
    tokenIssuance match {
      case TokenIssuance.NoIssuance =>
        Right(None)
      case TokenIssuance.IssueTokenWithoutTransfer =>
        frame.popOpStackU256().map(amount => Some(TokenIssuance.Info(amount)))
      case TokenIssuance.IssueTokenAndTransfer =>
        for {
          transferTo <- frame.popAssetAddress()
          amount     <- frame.popOpStackU256()
        } yield Some(TokenIssuance.Info(amount, Some(transferTo)))
    }
  }

  protected def prepareContractCode[C <: StatefulContext](
      frame: Frame[C]
  ): ExeResult[StatefulContract.HalfDecoded] = {
    if (copyCreate) {
      for {
        contractId  <- frame.popContractId()
        contractObj <- frame.ctx.loadContractObj(contractId)
      } yield contractObj.code
    } else {
      for {
        contractCodeRaw <- frame.popOpStackByteVec()
        contractCode <- decode[StatefulContract](contractCodeRaw.bytes).left.map(e =>
          Right(SerdeErrorCreateContract(e))
        )
        _ <- contractCode.checkAssetsModifier(frame.ctx)
        _ <- frame.ctx.chargeContractCodeSize(contractCodeRaw.bytes, frame.ctx.getHardFork())
        _ <- StatefulContract.check(contractCode, frame.ctx.getHardFork())
      } yield contractCode.toHalfDecoded()
    }
  }

  protected def prepareMutFields[C <: StatefulContext](frame: Frame[C]): ExeResult[AVector[Val]] = {
    frame.popFields()
  }

  protected def prepareImmFields[C <: StatefulContext](frame: Frame[C]): ExeResult[AVector[Val]] = {
    if (frame.ctx.getHardFork().isLemanEnabled()) {
      frame.popFields()
    } else {
      Right(CreateContractAbstract.emptyImmFields)
    }
  }

  def returnContractId: Boolean = true

  def __runWith[C <: StatefulContext](
      frame: Frame[C],
      tokenIssuance: TokenIssuance
  ): ExeResult[Unit] = {
    for {
      tokenIssuanceInfo <- getTokenIssuanceInfo(frame, tokenIssuance)
      mutFields         <- prepareMutFields(frame)
      immFields         <- prepareImmFields(frame)
      _                 <- frame.ctx.chargeFieldSize(immFields.toIterable ++ mutFields.toIterable)
      contractCode      <- prepareContractCode(frame)
      newContractId <- CreateContractAbstract.getContractId(
        frame,
        subContract,
        frame.ctx.blockEnv.chainIndex.from
      )
      _ <- frame.createContract(
        newContractId,
        if (subContract) frame.obj.contractIdOpt else None,
        contractCode,
        immFields,
        mutFields,
        tokenIssuanceInfo
      )
      _ <-
        if (frame.ctx.getHardFork().isLemanEnabled() && returnContractId) {
          frame.pushOpStack(Val.ByteVec(newContractId.bytes))
        } else {
          okay
        }
    } yield ()
  }
}

sealed trait CreateContractAbstract extends ContractFactory with StatefulInstrCompanion0

object CreateContractAbstract {
  val emptyImmFields: AVector[Val] = AVector.empty

  def getContractId[C <: StatefulContext](
      frame: Frame[C],
      subContract: Boolean,
      groupIndex: GroupIndex
  ): ExeResult[ContractId] = {
    if (subContract) {
      for {
        parentContractId <- frame.obj.getContractId()
        path             <- frame.popOpStackByteVec()
        subContractIdPreImage = parentContractId.bytes ++ path.bytes
        _ <- frame.ctx.chargeDoubleHash(subContractIdPreImage.length)
      } yield {
        if (frame.ctx.getHardFork().isLemanEnabled()) {
          ContractId.subContract(subContractIdPreImage, groupIndex)
        } else {
          throw new RuntimeException("Dead branch while creating a new contract")
        }
      }
    } else {
      if (frame.ctx.getHardFork().isLemanEnabled()) {
        Right(ContractId.from(frame.ctx.txId, frame.ctx.nextOutputIndex, groupIndex))
      } else {
        Right(ContractId.deprecatedFrom(frame.ctx.txId, frame.ctx.nextOutputIndex))
      }
    }
  }
}

sealed trait CreateContractBase extends CreateContractAbstract with GasCreate {
  def subContract: Boolean = false
  def copyCreate: Boolean  = false
}

object CreateContract extends CreateContractBase {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = NoIssuance)
  }
}

object CreateContractWithToken extends CreateContractBase {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = IssueTokenWithoutTransfer)
  }
}

object CreateContractAndTransferToken
    extends CreateContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = IssueTokenAndTransfer)
  }
}

sealed trait CopyCreateContractBase extends CreateContractAbstract with GasCopyCreate {
  def subContract: Boolean = false
  def copyCreate: Boolean  = true
}

object CopyCreateContract extends CopyCreateContractBase {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = NoIssuance)
  }
}

object CopyCreateContractWithToken
    extends CopyCreateContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = IssueTokenWithoutTransfer)
  }
}

object CopyCreateContractAndTransferToken
    extends CopyCreateContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = IssueTokenAndTransfer)
  }
}

sealed trait CreateSubContractBase extends CreateContractAbstract with GasCreate {
  def subContract: Boolean = true
  def copyCreate: Boolean  = false
}

object CreateSubContract
    extends CreateSubContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = NoIssuance)
  }
}

object CreateSubContractWithToken
    extends CreateSubContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = IssueTokenWithoutTransfer)
  }
}

object CreateSubContractAndTransferToken
    extends CreateSubContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = IssueTokenAndTransfer)
  }
}

@ByteCode
final case class CreateMapEntry(immFieldsNum: Byte, mutFieldsNum: Byte)
    extends ContractFactory
    with RhoneInstrWithSimpleGas[StatefulContext]
    with GasCreate {
  def subContract: Boolean = true
  def copyCreate: Boolean  = false

  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[Byte, Byte].serialize((immFieldsNum, mutFieldsNum))

  override def prepareMutFields[C <: StatefulContext](frame: Frame[C]): ExeResult[AVector[Val]] = {
    frame.opStack.pop(Bytes.toPosInt(mutFieldsNum))
  }

  override def prepareImmFields[C <: StatefulContext](frame: Frame[C]): ExeResult[AVector[Val]] = {
    frame.opStack.pop(Bytes.toPosInt(immFieldsNum))
  }

  override def prepareContractCode[C <: StatefulContext](
      frame: Frame[C]
  ): ExeResult[StatefulContract.HalfDecoded] = {
    val contract =
      CreateMapEntry.genContract(Bytes.toPosInt(immFieldsNum), Bytes.toPosInt(mutFieldsNum))
    val bytecode = encode(contract)
    frame.ctx
      .chargeContractCodeSize(bytecode, frame.ctx.getHardFork())
      .map(_ => contract.toHalfDecoded())
  }

  override def returnContractId: Boolean = false

  def runWithRhone[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = NoIssuance)
  }
}
object CreateMapEntry extends StatefulInstrCompanion1[(Byte, Byte)]()(serdeImpl[Byte, Byte]) {
  def apply(value: (Byte, Byte)): CreateMapEntry = CreateMapEntry(value._1, value._2)

  val LoadImmFieldMethodIndex: Byte  = 0
  val LoadMutFieldMethodIndex: Byte  = 1
  val StoreMutFieldMethodIndex: Byte = 2
  val DestroyMethodIndex: Byte       = 3

  private def genLoadImmFieldByIndex(parentContractIdIndex: Byte) =
    Method[StatefulContext](
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = false,
      usePayToContractOnly = false,
      argsLength = 1,
      localsLength = 1,
      returnLength = 1,
      instrs = AVector(
        CallerContractId,
        LoadImmField(parentContractIdIndex),
        ByteVecEq,
        Assert,
        LoadLocal(0),
        LoadImmFieldByIndex
      )
    )

  private def genLoadMutFieldByIndex(parentContractIdIndex: Byte) = {
    Method[StatefulContext](
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = false,
      usePayToContractOnly = false,
      argsLength = 1,
      localsLength = 1,
      returnLength = 1,
      instrs = AVector(
        CallerContractId,
        LoadImmField(parentContractIdIndex),
        ByteVecEq,
        Assert,
        LoadLocal(0),
        LoadMutFieldByIndex
      )
    )
  }

  private def genStoreMutFieldByIndex(parentContractIdIndex: Byte): Method[StatefulContext] = {
    Method(
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = false,
      usePayToContractOnly = false,
      argsLength = 2,
      localsLength = 2,
      returnLength = 0,
      instrs = AVector(
        CallerContractId,
        LoadImmField(parentContractIdIndex),
        ByteVecEq,
        Assert,
        LoadLocal(0), // value
        LoadLocal(1), // index
        StoreMutFieldByIndex
      )
    )
  }

  private def genDestroy(parentContractIdIndex: Byte): Method[StatefulContext] = {
    Method(
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = true,
      usePayToContractOnly = false,
      argsLength = 1,
      localsLength = 1,
      returnLength = 0,
      instrs = AVector(
        CallerContractId,
        LoadImmField(parentContractIdIndex),
        ByteVecEq,
        Assert,
        LoadLocal(0),
        DestroySelf
      )
    )
  }

  def genContract(immFields: Int, mutFields: Int): StatefulContract = {
    assume(immFields >= 1) // parent contract id
    val parentContractIdIndex = (immFields - 1).toByte
    StatefulContract(
      immFields + mutFields,
      AVector(
        genLoadImmFieldByIndex(parentContractIdIndex),
        genLoadMutFieldByIndex(parentContractIdIndex),
        genStoreMutFieldByIndex(parentContractIdIndex),
        genDestroy(parentContractIdIndex)
      )
    )
  }
}

sealed trait CopyCreateSubContractBase extends CreateContractAbstract with GasCopyCreate {
  def subContract: Boolean = true
  def copyCreate: Boolean  = true
}

object CopyCreateSubContract
    extends CopyCreateSubContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = NoIssuance)
  }
}

object CopyCreateSubContractWithToken
    extends CopyCreateSubContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = IssueTokenWithoutTransfer)
  }
}

object CopyCreateSubContractAndTransferToken
    extends CopyCreateSubContractBase
    with LemanInstrWithSimpleGas[StatefulContext] {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    __runWith(frame, tokenIssuance = IssueTokenAndTransfer)
  }
}

object ContractExists
    extends StatefulInstrCompanion0
    with LemanInstrWithSimpleGas[StatefulContext]
    with GasContractExists {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      contractId <- frame.popContractId()
      exist      <- frame.ctx.contractExists(contractId)
      _          <- frame.pushOpStack(Val.Bool(exist))
    } yield ()
  }
}

// This instruction can only be called from Tx, i.e. IsCalledFromTxScript should return true
// This instruction will result in an immediate run of Return
object DestroySelf extends ContractInstr with GasDestroy {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address <- frame.popOpStackAddress()
      _       <- frame.destroyContract(address.lockupScript)
    } yield ()
  }
}

sealed trait MigrateBase
    extends LemanInstrWithSimpleGas[StatefulContext]
    with StatefulInstrCompanion0
    with GasMigrate {
  def migrate[C <: StatefulContext](
      frame: Frame[C],
      newImmFieldsOpt: Option[AVector[Val]],
      newMutFieldsOpt: Option[AVector[Val]]
  ): ExeResult[Unit] = {
    for {
      contractCodeRaw <- frame.popOpStackByteVec()
      _ <- frame.ctx.chargeContractCodeSize(contractCodeRaw.bytes, frame.ctx.getHardFork())
      contractCode <- decode[StatefulContract](contractCodeRaw.bytes).left.map(e =>
        Right(SerdeErrorCreateContract(e))
      )
      _ <- frame.migrateContract(contractCode, newImmFieldsOpt, newMutFieldsOpt)
    } yield ()
  }
}

object MigrateSimple extends MigrateBase {
  def runWithLeman[C <: StatefulContext](
      frame: Frame[C]
  ): ExeResult[Unit] = {
    migrate(frame, None, None)
  }
}

object MigrateWithFields extends MigrateBase {
  def runWithLeman[C <: StatefulContext](
      frame: Frame[C]
  ): ExeResult[Unit] = {
    for {
      newMutFields <- frame.popFields()
      newImmFields <- frame.popFields()
      _            <- migrate(frame, Some(newImmFields), Some(newMutFields))
    } yield ()
  }
}

object SelfAddress extends ContractInstr with GasVeryLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address <- frame.obj.getAddress()
      _       <- frame.pushOpStack(address)
    } yield ()
  }
}

object SelfContractId extends ContractInstr with GasVeryLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      contractId <- frame.obj.getContractId()
      _          <- frame.pushOpStack(Val.ByteVec(contractId.bytes))
    } yield ()
  }
}

sealed trait SubContractIdBase
    extends LemanInstr[StatefulContext]
    with StatefulInstrCompanion0
    with GasFormula {
  def gas(pathLength: Int): GasBox = {
    val byteLength = 32 + pathLength
    ByteVecConcat.gas(byteLength).addUnsafe(GasHash.gas(byteLength)).addUnsafe(GasHash.gas(32))
  }

  def runWithLeman[C <: StatefulContext](
      frame: Frame[C],
      isParentContractSelf: Boolean
  ): ExeResult[Unit] = {
    for {
      path <- frame.popOpStackByteVec()
      contractId <-
        if (isParentContractSelf) {
          frame.obj.getContractId()
        } else {
          frame.popContractId()
        }
      _ <- frame.ctx.chargeGasWithSize(this, path.bytes.length)
      _ <- {
        val subContractId = contractId.subContractId(path.bytes, frame.ctx.blockEnv.chainIndex.from)
        frame.pushOpStack(Val.ByteVec(subContractId.bytes))
      }
    } yield {}
  }
}

object SubContractId extends SubContractIdBase {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    runWithLeman(frame, isParentContractSelf = true)
  }
}

object SubContractIdOf extends SubContractIdBase {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    runWithLeman(frame, isParentContractSelf = false)
  }
}

object ALPHTokenId
    extends LemanInstrWithSimpleGas[StatefulContext]
    with StatefulInstrCompanion0
    with GasBase {
  private val alphTokenId = Val.ByteVec(TokenId.alph.bytes)
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(alphTokenId)
  }
}

object CallerContractId extends ContractInstr with GasLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      callerFrame <- frame.getCallerFrame()
      contractId  <- callerFrame.obj.getContractId()
      _           <- frame.pushOpStack(Val.ByteVec(contractId.bytes))
    } yield ()
  }
}

// only return the address when the caller is a contract
object CallerAddress extends ContractInstr with GasLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address <- frame.getCallerAddress()
      _       <- frame.pushOpStack(address)
    } yield ()
  }
}

object IsCalledFromTxScript extends ContractInstr with GasLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      callerFrame <- frame.getCallerFrame()
      _           <- frame.pushOpStack(Val.Bool(callerFrame.obj.isScript()))
    } yield ()
  }
}

sealed trait CallerStateInstr extends ContractInstr with GasLow {
  def extractVal(callerObj: StatefulContractObject): Val

  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      callerFrame <- frame.getCallerFrame()
      callerObj <- callerFrame.obj match {
        case obj: StatefulContractObject => Right(obj)
        case _                           => failed(ExpectStatefulContractObj)
      }
      _ <- frame.pushOpStack(extractVal(callerObj))
    } yield ()
  }
}

object CallerInitialStateHash extends CallerStateInstr {
  def extractVal(callerObj: StatefulContractObject): Val = callerObj.getInitialStateHash()
}

object CallerCodeHash extends CallerStateInstr {
  def extractVal(callerObj: StatefulContractObject): Val = callerObj.getCodeHash()
}

sealed trait ContractStateInstr extends ContractInstr with GasLow {
  def extractVal(contractObj: StatefulContractObject): Val

  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      contractId  <- frame.popContractId()
      contractObj <- frame.ctx.loadContractObj(contractId)
      _           <- frame.pushOpStack(extractVal(contractObj))
    } yield ()
  }
}

object ContractInitialStateHash extends ContractStateInstr {
  def extractVal(contractObj: StatefulContractObject): Val = contractObj.getInitialStateHash()
}

object ContractCodeHash extends ContractStateInstr {
  def extractVal(contractObj: StatefulContractObject): Val = contractObj.getCodeHash()
}

sealed trait BlockInstr
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasVeryLow

object NetworkId extends BlockInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack {
      val id = frame.ctx.blockEnv.networkId.id
      Val.ByteVec(ByteString(id))
    }
  }
}

object BlockTimeStamp extends BlockInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      timestamp <- {
        val millis = frame.ctx.blockEnv.timeStamp.millis
        util.U256.fromLong(millis).toRight(Right(NegativeTimeStamp(millis)))
      }
      _ <- frame.pushOpStack(Val.U256(timestamp))
    } yield ()
  }
}

object BlockTarget extends BlockInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      target <- {
        val value = frame.ctx.blockEnv.target.value
        util.U256.from(value).toRight(Right(InvalidBlockTarget(value)))
      }
      _ <- frame.pushOpStack(Val.U256(target))
    } yield ()
  }
}

sealed trait TxInstr extends StatelessInstrSimpleGas with StatelessInstrCompanion0

object TxInstr {
  def checkScriptFrameForLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    if (frame.ctx.getHardFork().isLemanEnabled() && !frame.obj.isScript()) {
      failed(AccessTxInputAddressInContract)
    } else {
      okay
    }
  }
}

sealed trait LemanTxInstr
    extends LemanInstrWithSimpleGas[StatelessContext]
    with StatelessInstrCompanion0

object TxGasPrice extends LemanTxInstr with GasBase {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(Val.U256(frame.ctx.txEnv.gasPrice.value))
  }
}

object TxGasAmount extends LemanTxInstr with GasBase {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(Val.U256(frame.ctx.txEnv.gasAmount.toU256))
  }
}

object TxGasFee extends LemanTxInstr with GasBase {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(Val.U256(frame.ctx.txEnv.gasFeeUnsafe))
  }
}

object TxId extends TxInstr with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(Val.ByteVec(frame.ctx.txId.bytes))
  }
}

object TxInputAddressAt extends TxInstr with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _           <- TxInstr.checkScriptFrameForLeman(frame)
      callerIndex <- frame.popOpStackU256()
      caller      <- frame.ctx.getTxInputAddressAt(callerIndex)
      _           <- frame.pushOpStack(caller)
    } yield ()
  }
}

object TxInputsSize extends TxInstr with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- TxInstr.checkScriptFrameForLeman(frame)
      _ <- frame.pushOpStack(Val.U256(util.U256.unsafe(frame.ctx.txEnv.prevOutputs.length)))
    } yield ()
  }
}

sealed trait LockTimeInstr extends TxInstr {
  def popTimeStamp[C <: StatelessContext](frame: Frame[C]): ExeResult[TimeStamp] = {
    for {
      u256 <- frame.popOpStackU256()
      res  <- u256.v.toLong.map(TimeStamp.unsafe).toRight(Right(LockTimeOverflow))
    } yield res
  }

  def popDuration[C <: StatelessContext](frame: Frame[C]): ExeResult[Duration] = {
    for {
      u256 <- frame.popOpStackU256()
      res  <- u256.v.toLong.map(Duration.unsafe).toRight(Right(LockTimeOverflow))
    } yield res
  }
}

object VerifyAbsoluteLocktime extends LockTimeInstr with GasLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      lockUntil <- popTimeStamp(frame)
      _ <-
        if (lockUntil > frame.ctx.blockEnv.timeStamp) {
          failed(AbsoluteLockTimeVerificationFailed(lockUntil, frame.ctx.blockEnv.timeStamp))
        } else {
          okay
        }
    } yield ()
  }
}

object VerifyRelativeLocktime extends LockTimeInstr with GasMid {
  def getLockUntil(output: AssetOutput, lockDuration: Duration): ExeResult[TimeStamp] = {
    val lockTime = output.lockTime
    if (lockTime.isZero()) {
      // when the lock time of the Utxo is zero, it's not persisted into worldstate yet
      failed(RelativeLockTimeExpectPersistedUtxo)
    } else {
      lockTime.plus(lockDuration).toRight(Right(LockTimeOverflow))
    }
  }

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      lockDuration    <- popDuration(frame)
      prevOutputIndex <- frame.popOpStackU256()
      preOutput       <- frame.ctx.getTxPrevOutput(prevOutputIndex)
      lockUntil       <- getLockUntil(preOutput, lockDuration)
      _ <-
        if (lockUntil > frame.ctx.blockEnv.timeStamp) {
          failed(RelativeLockTimeVerificationFailed(lockUntil, frame.ctx.blockEnv.timeStamp))
        } else {
          okay
        }
    } yield {}
  }
}

sealed trait LogInstr extends StatelessInstr with StatelessInstrCompanion0 with GasLog {
  def n: Int

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _      <- frame.ctx.chargeGasWithSize(this, n)
      fields <- frame.opStack.pop(n)
      _      <- frame.ctx.writeLog(frame.obj.contractIdOpt, fields, systemEvent = false)
    } yield ()
  }
}
sealed trait MainnetLogInstr extends LogInstr {
  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = _runWith(frame)
}
sealed trait LemanLogInstr extends LogInstr with LemanInstr[StatelessContext] {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = _runWith(frame)
}
object Log1 extends MainnetLogInstr { val n: Int = 1 }
object Log2 extends MainnetLogInstr { val n: Int = 2 }
object Log3 extends MainnetLogInstr { val n: Int = 3 }
object Log4 extends MainnetLogInstr { val n: Int = 4 }
object Log5 extends MainnetLogInstr { val n: Int = 5 }
object Log6 extends LemanLogInstr   { val n: Int = 6 }
object Log7 extends LemanLogInstr   { val n: Int = 7 }
object Log8 extends LemanLogInstr   { val n: Int = 8 }
object Log9 extends LemanLogInstr   { val n: Int = 9 }

case object NullContractAddress
    extends LemanInstrWithSimpleGas[StatefulContext]
    with StatefulInstrCompanion0
    with GasBase {
  def runWithLeman[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(Val.NullContractAddress)
  }
}

case object BlockHash
    extends LemanInstrWithSimpleGas[StatelessContext]
    with StatelessInstrCompanion0
    with GasBase {
  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.ctx.blockEnv.blockId match {
      case Some(blockHash) => frame.pushOpStack(Val.ByteVec(blockHash.bytes))
      case None            => failed(NoBlockHashAvailable)
    }
  }
}

final case class TemplateVariable(name: String, tpe: Val.Type, index: Int) extends StatelessInstr {
  def serialize(): ByteString = ???
  def code: Byte              = ???

  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = ???

  override def toTemplateString(): String = s"{$index}"
}

final case class DEBUG(stringParts: AVector[Val.ByteVec])
    extends LemanInstrWithSimpleGas[StatelessContext]
    with GasZero {
  def code: Byte = DEBUG.code

  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[AVector[Val.ByteVec]].serialize(stringParts)

  @inline private[vm] def combineUnsafe(values: AVector[Val]): Val.ByteVec = {
    var result = ByteString.empty
    values.indices.foreach { k =>
      result = result ++ stringParts(k).bytes ++ values(k).toDebugString()
    }
    Val.ByteVec(result ++ stringParts.last.bytes)
  }

  def runWithLeman[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    if (frame.ctx.networkConfig.networkId == model.NetworkId.AlephiumMainNet) {
      failed(DebugIsNotSupportedForMainnet)
    } else if (stringParts.isEmpty) {
      failed(DebugMessageIsEmpty)
    } else {
      for {
        interpolationParts <- frame.opStack.pop(stringParts.length - 1)
        _ <- frame.ctx.writeLog(
          Some(frame.obj.contractIdOpt.getOrElse(ContractId.zero)),
          AVector(debugEventIndex, combineUnsafe(interpolationParts)),
          systemEvent = false
        )
      } yield ()
    }
  }
}
object DEBUG extends StatelessInstrCompanion1[AVector[Val.ByteVec]]
