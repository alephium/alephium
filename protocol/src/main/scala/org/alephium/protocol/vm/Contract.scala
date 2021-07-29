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

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.protocol.model.ContractId
import org.alephium.serde._
import org.alephium.util.AVector

final case class Method[Ctx <: Context](
    isPublic: Boolean,
    isPayable: Boolean,
    argsType: AVector[Val.Type],
    localsLength: Int,
    returnType: AVector[Val.Type],
    instrs: AVector[Instr[Ctx]]
) {
  def check(args: AVector[Val]): ExeResult[Unit] = {
    if (args.length != argsType.length) {
      failed(InvalidMethodArgLength(args.length, argsType.length))
    } else if (!args.forallWithIndex((v, index) => v.tpe == argsType(index))) {
      failed(InvalidMethodParamsType)
    } else {
      okay
    }
  }
}

object Method {
  implicit val statelessSerde: Serde[Method[StatelessContext]] =
    Serde.forProduct6(
      Method[StatelessContext],
      t => (t.isPublic, t.isPayable, t.argsType, t.localsLength, t.returnType, t.instrs)
    )
  implicit val statefulSerde: Serde[Method[StatefulContext]] =
    Serde.forProduct6(
      Method[StatefulContext],
      t => (t.isPublic, t.isPayable, t.argsType, t.localsLength, t.returnType, t.instrs)
    )

  def forSMT: Method[StatefulContext] =
    Method[StatefulContext](
      isPublic = false,
      isPayable = false,
      AVector.empty,
      0,
      AVector.empty,
      AVector(Pop)
    )
}

sealed trait Contract[Ctx <: Context] {
  def fields: AVector[Val.Type]
  def methods: AVector[Method[Ctx]]
}

object Contract {
  val emptyFields: AVector[Val.Type] = AVector.ofSize(0)
}

sealed abstract class Script[Ctx <: Context] extends Contract[Ctx] {
  val fields: AVector[Val.Type] = Contract.emptyFields

  def toObject: ScriptObj[Ctx]
}

final case class StatelessScript(methods: AVector[Method[StatelessContext]])
    extends Script[StatelessContext] {
  override def toObject: ScriptObj[StatelessContext] = {
    StatelessScriptObject(this)
  }
}

object StatelessScript {
  implicit val serde: Serde[StatelessScript] =
    Serde.forProduct1(StatelessScript.apply, _.methods)
}

final case class StatefulScript private (methods: AVector[Method[StatefulContext]])
    extends Script[StatefulContext] {
  def entryMethod: Method[StatefulContext] = methods.head

  override def toObject: ScriptObj[StatefulContext] = {
    StatefulScriptObject(this)
  }
}

object StatefulScript {
  implicit val serde: Serde[StatefulScript] = Serde
    .forProduct1[AVector[Method[StatefulContext]], StatefulScript](StatefulScript.unsafe, _.methods)
    .validate(script => if (validate(script.methods)) Right(()) else Left("Invalid TxScript"))

  def unsafe(methods: AVector[Method[StatefulContext]]): StatefulScript = {
    new StatefulScript(methods)
  }

  def from(methods: AVector[Method[StatefulContext]]): Option[StatefulScript] = {
    val ok = methods.nonEmpty && methods.head.isPublic && methods.tail.forall(m => !m.isPublic)
    Option.when(ok)(new StatefulScript(methods))
  }

  def validate(methods: AVector[Method[StatefulContext]]): Boolean = {
    methods.nonEmpty && methods.head.isPublic && methods.tail.forall(m => !m.isPublic)
  }

  def alwaysFail: StatefulScript =
    StatefulScript(
      AVector(
        Method[StatefulContext](
          isPublic = true,
          isPayable = false,
          argsType = AVector.empty,
          localsLength = 0,
          returnType = AVector.empty,
          instrs = AVector(ConstFalse, ConstTrue, CheckEqBool)
        )
      )
    )
}

final case class StatefulContract(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends HashSerde[StatefulContract]
    with Contract[StatefulContext] {
  override lazy val hash: Hash = _getHash

  def toObject(address: Hash, contractState: ContractState): StatefulContractObject = {
    StatefulContractObject(this, contractState.fields, contractState.fields.toArray, address)
  }

  def toObject(address: Hash, fields: AVector[Val]): StatefulContractObject = {
    StatefulContractObject(this, fields, fields.toArray, address)
  }
}

object StatefulContract {
  implicit val serde: Serde[StatefulContract] =
    Serde.forProduct2(StatefulContract.apply, t => (t.fields, t.methods))

  val forSMT: StatefulContract = StatefulContract(AVector.empty, AVector(Method.forSMT))
}

sealed trait ContractObj[Ctx <: Context] {
  def addressOpt: Option[Hash]
  def code: Contract[Ctx]
  def fields: mutable.ArraySeq[Val]

  def getMethod(index: Int): Option[Method[Ctx]] = {
    code.methods.get(index)
  }

  def buildNonPayableFrame(
      ctx: Ctx,
      obj: ContractObj[Ctx],
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[Ctx]

  def buildPayableFrame(
      ctx: Ctx,
      balanceState: BalanceState,
      obj: ContractObj[Ctx],
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[Ctx]

  private val noReturnTo: AVector[Val] => ExeResult[Unit] = returns =>
    if (returns.nonEmpty) failed(NonEmptyReturnForMainFunction) else okay

  def startFrame(
      ctx: Ctx,
      methodIndex: Int,
      args: AVector[Val],
      operandStack: Stack[Val],
      returnToOpt: Option[AVector[Val] => ExeResult[Unit]]
  ): ExeResult[Frame[Ctx]] = {
    for {
      method <- getMethod(methodIndex).toRight(Right(InvalidMethodIndex(methodIndex)))
      _      <- if (method.isPublic) okay else failed(ExternalPrivateMethodCall)
      frame <- {
        val returnTo = returnToOpt.getOrElse(noReturnTo)
        if (method.isPayable) {
          startPayableFrame(ctx, method, args, operandStack, returnTo)
        } else {
          startNonPayableFrame(ctx, method, args, operandStack, returnTo)
        }
      }
    } yield frame
  }

  protected def startPayableFrame(
      ctx: Ctx,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[Ctx]] = {
    ctx.getInitialBalances.map { balances =>
      buildPayableFrame(
        ctx,
        BalanceState.from(balances),
        this,
        method,
        args,
        operandStack,
        returnTo
      )
    }
  }

  protected def startNonPayableFrame(
      ctx: Ctx,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[Ctx]] = {
    Right(buildNonPayableFrame(ctx, this, method, args, operandStack, returnTo))
  }
}

sealed trait ScriptObj[Ctx <: Context] extends ContractObj[Ctx] {
  val addressOpt: Option[Hash]      = None
  val fields: mutable.ArraySeq[Val] = mutable.ArraySeq.empty
}

final case class StatelessScriptObject(code: StatelessScript) extends ScriptObj[StatelessContext] {
  def buildNonPayableFrame(
      ctx: StatelessContext,
      obj: ContractObj[StatelessContext],
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatelessContext] =
    Frame.stateless(ctx, obj, method, args, operandStack, returnTo)

  def buildPayableFrame(
      ctx: StatelessContext,
      balanceState: BalanceState,
      obj: ContractObj[StatelessContext],
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatelessContext] =
    ??? // should not be called
}

final case class StatefulScriptObject(code: StatefulScript) extends ScriptObj[StatefulContext] {
  def buildNonPayableFrame(
      ctx: StatefulContext,
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatefulContext] =
    Frame.stateful(ctx, None, obj, method, args, operandStack, returnTo)

  def buildPayableFrame(
      ctx: StatefulContext,
      balanceState: BalanceState,
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatefulContext] =
    Frame.stateful(ctx, Some(balanceState), obj, method, args, operandStack, returnTo)
}

final case class StatefulContractObject(
    code: StatefulContract,
    initialFields: AVector[Val],
    fields: mutable.ArraySeq[Val],
    address: ContractId
) extends ContractObj[StatefulContext] {
  override def addressOpt: Option[ContractId] = Some(address)

  def isUpdated: Boolean = !fields.indices.forall(index => fields(index) == initialFields(index))

  def buildNonPayableFrame(
      ctx: StatefulContext,
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatefulContext] =
    Frame.stateful(ctx, None, obj, method, args, operandStack, returnTo)

  def buildPayableFrame(
      ctx: StatefulContext,
      balanceState: BalanceState,
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatefulContext] =
    Frame.stateful(ctx, Some(balanceState), obj, method, args, operandStack, returnTo)
}
