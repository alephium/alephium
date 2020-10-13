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

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.alephium.protocol
import org.alephium.protocol.{Hash, SignatureSchema}
import org.alephium.serde._
import org.alephium.util._

class VMSpec extends AlephiumSpec with ContextGenerators {
  it should "not call from private function" in {
    val method =
      Method[StatefulContext](isPublic   = false,
                              isPayable  = false,
                              localsType = AVector.empty,
                              returnType = AVector.empty,
                              instrs     = AVector.empty)
    val contract = StatefulContract(AVector.empty, methods = AVector(method))
    val (obj, context) =
      prepareContract(contract, AVector[Val]())
    StatefulVM.execute(context, obj, AVector(Val.U64(U64.Two))) is Left(PrivateExternalMethodCall)
  }

  it should "execute the following script" in {
    val method =
      Method[StatefulContext](
        isPublic   = true,
        isPayable  = false,
        localsType = AVector(Val.U64),
        returnType = AVector(Val.U64),
        instrs     = AVector(LoadLocal(0), LoadField(1), U64Add, U64Const5, U64Add, Return)
      )
    val contract = StatefulContract(AVector(Val.U64, Val.U64), methods = AVector(method))
    val (obj, context) =
      prepareContract(contract, AVector[Val](Val.U64(U64.Zero), Val.U64(U64.One)))
    StatefulVM.executeWithOutputs(context, obj, AVector(Val.U64(U64.Two))) isE
      AVector[Val](Val.U64(U64.unsafe(8)))
  }

  it should "call method" in {
    val method0 = Method[StatelessContext](isPublic = true,
                                           isPayable  = false,
                                           localsType = AVector(Val.U64),
                                           returnType = AVector(Val.U64),
                                           instrs     = AVector(LoadLocal(0), CallLocal(1), Return))
    val method1 =
      Method[StatelessContext](isPublic   = false,
                               isPayable  = false,
                               localsType = AVector(Val.U64),
                               returnType = AVector(Val.U64),
                               instrs     = AVector(LoadLocal(0), U64Const1, U64Add, Return))
    val script = StatelessScript(methods = AVector(method0, method1))
    val obj    = script.toObject
    StatelessVM.executeWithOutputs(statelessContext, obj, AVector(Val.U64(U64.Two))) isE
      AVector[Val](Val.U64(U64.unsafe(3)))
  }

  trait BalancesFixture {
    val (_, pubKey0) = SignatureSchema.generatePriPub()
    val address0     = Val.Address(LockupScript.p2pkh(pubKey0))
    val balances0    = Frame.BalancesPerLockup(100, mutable.Map.empty)
    val (_, pubKey1) = SignatureSchema.generatePriPub()
    val address1     = Val.Address(LockupScript.p2pkh(pubKey1))
    val tokenId      = Hash.random
    val balances1    = Frame.BalancesPerLockup(0, mutable.Map(tokenId -> 99))

    def mockContext(): StatefulContext = new StatefulContext {
      var worldState: WorldState                = cachedWorldState
      def txHash: Hash                          = Hash.zero
      def signatures: Stack[protocol.Signature] = Stack.ofCapacity(0)
      def nextOutputIndex: Int                  = 0

      def getInitialBalances: ExeResult[Frame.Balances] = {
        Right(
          Frame.Balances(
            ArrayBuffer(address0.lockupScript -> balances0, address1.lockupScript -> balances1)))
      }

      override val outputBalances: Frame.Balances = Frame.Balances.empty
    }

    def testInstrs(instrs: AVector[Instr[StatefulContext]], expected: ExeResult[AVector[Val]]) = {
      val method = Method[StatefulContext](
        isPublic   = true,
        isPayable  = true,
        localsType = AVector.empty,
        returnType = expected.fold(_ => AVector.empty[Val.Type], _.map(_.tpe)),
        instrs)
      val context = mockContext()
      val obj     = StatefulScript(AVector(method)).toObject

      StatefulVM.executeWithOutputs(context, obj, AVector.empty) is expected

      context
    }

    def pass(instrs: AVector[Instr[StatefulContext]], expected: AVector[Val]) = {
      testInstrs(instrs, Right(expected))
    }

    def fail(instrs: AVector[Instr[StatefulContext]], expected: ExeFailure) = {
      testInstrs(instrs, Left(expected))
    }
  }

  it should "show remaining balances" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      AlfRemaining,
      AddressConst(address1),
      AlfRemaining,
      BytesConst(Val.ByteVec(mutable.ArraySeq.make(tokenId.bytes.toArray))),
      AddressConst(address1),
      TokenRemaining
    )
    pass(instrs, AVector[Val](Val.U64(100), Val.U64(0), Val.U64(99)))
  }

  it should "fail when there is no token balances" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      BytesConst(Val.ByteVec(mutable.ArraySeq.make(tokenId.bytes.toArray))),
      AddressConst(address0),
      TokenRemaining
    )
    fail(instrs, NoTokenBalanceForTheAddress)
  }

  it should "approve balances" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      U64Const(Val.U64(10)),
      AddressConst(address0),
      ApproveAlf,
      AddressConst(address0),
      AlfRemaining,
      U64Const(Val.U64(10)),
      BytesConst(Val.ByteVec(mutable.ArraySeq.make(tokenId.bytes.toArray))),
      AddressConst(address1),
      ApproveToken,
      AddressConst(address1),
      AlfRemaining,
      BytesConst(Val.ByteVec(mutable.ArraySeq.make(tokenId.bytes.toArray))),
      AddressConst(address1),
      TokenRemaining
    )
    pass(instrs, AVector[Val](Val.U64(90), Val.U64(0), Val.U64(89)))
  }

  it should "fail when no enough balance for approval" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      U64Const(Val.U64(10)),
      BytesConst(Val.ByteVec(mutable.ArraySeq.make(tokenId.bytes.toArray))),
      AddressConst(address0),
      ApproveToken
    )
    fail(instrs, NotEnoughBalance)
  }

  it should "transfer asset to output" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      U64Const(Val.U64(10)),
      AddressConst(address1),
      AddressConst(address0),
      TransferAlf,
      U64Const(Val.U64(1)),
      BytesConst(Val.ByteVec(mutable.ArraySeq.make(tokenId.bytes.toArray))),
      AddressConst(address0),
      AddressConst(address1),
      TransferToken,
      AddressConst(address0),
      AlfRemaining,
      AddressConst(address1),
      AlfRemaining,
      BytesConst(Val.ByteVec(mutable.ArraySeq.make(tokenId.bytes.toArray))),
      AddressConst(address1),
      TokenRemaining
    )

    val context = pass(instrs, AVector[Val](Val.U64(90), Val.U64(0), Val.U64(98)))
    context.outputBalances.getAlfAmount(address0.lockupScript).get is 0
    context.outputBalances.getAlfAmount(address1.lockupScript).get is 10
    context.outputBalances.getTokenAmount(address0.lockupScript, tokenId).get is 1
    context.outputBalances.getTokenAmount(address1.lockupScript, tokenId).isEmpty is true
  }

  it should "serde instructions" in {
    Instr.statefulInstrs.foreach {
      case instrCompanion: StatefulInstrCompanion0 =>
        deserialize[Instr[StatefulContext]](instrCompanion.serialize()).toOption.get is instrCompanion
      case _ => ()
    }
  }

  it should "serde script" in {
    val method =
      Method[StatefulContext](
        isPublic   = true,
        isPayable  = false,
        localsType = AVector(Val.U64),
        returnType = AVector.empty,
        instrs     = AVector(LoadLocal(0), LoadField(1), U64Add, U64Const1, U64Add, StoreField(1))
      )
    val contract = StatefulContract(AVector(Val.U64, Val.U64), methods = AVector(method))
    serialize(contract)(StatefulContract.serde).nonEmpty is true
  }
}
