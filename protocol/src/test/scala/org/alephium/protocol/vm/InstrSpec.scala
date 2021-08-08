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

import org.alephium.util.{AlephiumSpec, AVector, UnsecureRandom}

class InstrSpec extends AlephiumSpec {
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
      NotBool, AndBool, OrBool, EqBool, NeBool,
      I256Add, I256Sub, I256Mul, I256Div, I256Mod, EqI256, NeI256, LtI256, LeI256, GtI256, GeI256,
      U256Add, U256Sub, U256Mul, U256Div, U256Mod, EqU256, NeU256, LtU256, LeU256, GtU256, GeU256,
      U256ModAdd, U256ModSub, U256ModMul, U256BitAnd, U256BitOr, U256Xor, U256SHL, U256SHR,
      I256ToU256, U256ToI256,
      EqByteVec, NeByteVec, EqAddress, NeAddress,
      Jump(int), IfTrue(int), IfFalse(int),
      CallLocal(byte), Return,
      Assert,
      Blake2b, Keccak256, Sha256, Sha3, VerifySignature,
      BlockTimeStamp, BlockTarget
    )
    val statefulInstrs: AVector[Instr[StatefulContext]] = AVector(
      LoadField(byte), StoreField(byte), CallExternal(byte),
      ApproveAlf, ApproveToken, AlfRemaining, TokenRemaining,
      TransferAlf, TransferAlfFromSelf, TransferAlfToSelf, TransferToken, TransferTokenFromSelf, TransferTokenToSelf,
      CreateContract, CopyCreateContract, DestroyContract, SelfAddress, SelfContractId, IssueToken,
      CallerAddress, CallerCodeHash, ContractCodeHash
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
}
