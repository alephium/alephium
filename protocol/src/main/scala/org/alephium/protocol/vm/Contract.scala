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

final case class Method[Ctx <: StatelessContext](
    isPublic: Boolean,
    isPayable: Boolean,
    argsLength: Int,
    localsLength: Int,
    returnLength: Int,
    instrs: AVector[Instr[Ctx]]
)

object Method {
  implicit val statelessSerde: Serde[Method[StatelessContext]] =
    Serde.forProduct6(
      Method[StatelessContext],
      t => (t.isPublic, t.isPayable, t.argsLength, t.localsLength, t.returnLength, t.instrs)
    )
  implicit val statefulSerde: Serde[Method[StatefulContext]] =
    Serde.forProduct6(
      Method[StatefulContext],
      t => (t.isPublic, t.isPayable, t.argsLength, t.localsLength, t.returnLength, t.instrs)
    )

  def forSMT: Method[StatefulContext] =
    Method[StatefulContext](
      isPublic = false,
      isPayable = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      AVector(Pop)
    )
}

sealed trait Contract[Ctx <: StatelessContext] {
  def fieldLength: Int
  def methods: AVector[Method[Ctx]]
}

sealed abstract class Script[Ctx <: StatelessContext] extends Contract[Ctx] {
  def fieldLength: Int = 0

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
          argsLength = 0,
          localsLength = 0,
          returnLength = 0,
          instrs = AVector(ConstFalse, Assert)
        )
      )
    )
}

final case class StatefulContract(
    fieldLength: Int,
    methods: AVector[Method[StatefulContext]]
) extends HashSerde[StatefulContract]
    with Contract[StatefulContext] {
  override lazy val hash: Hash = _getHash

  def check(initialFields: AVector[Val]): ExeResult[Unit] = {
    if (validate(initialFields)) {
      okay
    } else {
      failed(InvalidFieldType)
    }
  }

  def validate(initialFields: AVector[Val]): Boolean = {
    initialFields.length == fieldLength
  }

  def toObject(address: Hash, contractState: ContractState): StatefulContractObject = {
    StatefulContractObject(this, contractState.fields, address)
  }

  def toObject(address: Hash, fields: AVector[Val]): StatefulContractObject = {
    StatefulContractObject(this, fields, address)
  }
}

object StatefulContract {
  implicit val serde: Serde[StatefulContract] =
    Serde.forProduct2(StatefulContract.apply, t => (t.fieldLength, t.methods))

  val forSMT: StatefulContract = StatefulContract(0, AVector(Method.forSMT))
}

sealed trait ContractObj[Ctx <: StatelessContext] {
  def addressOpt: Option[Hash]
  def code: Contract[Ctx]
  def fields: mutable.ArraySeq[Val]

  def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    code.methods.get(index).toRight(Right(InvalidMethodIndex(index)))
  }

  def getField(index: Int): ExeResult[Val] = {
    if (fields.isDefinedAt(index)) Right(fields(index)) else failed(InvalidFieldIndex)
  }

  def setField(index: Int, v: Val): ExeResult[Unit] = {
    if (!fields.isDefinedAt(index)) {
      failed(InvalidFieldIndex)
    } else if (fields(index).tpe != v.tpe) {
      failed(InvalidFieldType)
    } else {
      Right(fields.update(index, v))
    }
  }

  def startNonPayableFrame(
      ctx: Ctx,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[Ctx]]

  def startPayableFrame(
      ctx: Ctx,
      balanceState: BalanceState,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[Ctx]]

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
      method <- getMethod(methodIndex)
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
    ctx.getInitialBalances().flatMap { balances =>
      startPayableFrame(
        ctx,
        BalanceState.from(balances),
        method,
        args,
        operandStack,
        returnTo
      )
    }
  }
}

sealed trait ScriptObj[Ctx <: StatelessContext] extends ContractObj[Ctx] {
  val addressOpt: Option[Hash]      = None
  val fields: mutable.ArraySeq[Val] = mutable.ArraySeq.empty
}

final case class StatelessScriptObject(code: StatelessScript) extends ScriptObj[StatelessContext] {
  def startNonPayableFrame(
      ctx: StatelessContext,
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatelessContext]] =
    Frame.stateless(ctx, this, method, args, operandStack, returnTo)

  def startPayableFrame(
      ctx: StatelessContext,
      balanceState: BalanceState,
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatelessContext]] = failed(NonPayableFrame)
}

final case class StatefulScriptObject(code: StatefulScript) extends ScriptObj[StatefulContext] {
  def startNonPayableFrame(
      ctx: StatefulContext,
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] =
    Frame.stateful(ctx, None, this, method, args, operandStack, returnTo)

  def startPayableFrame(
      ctx: StatefulContext,
      balanceState: BalanceState,
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] =
    Frame.stateful(ctx, Some(balanceState), this, method, args, operandStack, returnTo)
}

final case class StatefulContractObject private (
    code: StatefulContract,
    initialFields: AVector[Val],
    fields: mutable.ArraySeq[Val],
    address: ContractId
) extends ContractObj[StatefulContext] {
  override def addressOpt: Option[ContractId] = Some(address)

  def isUpdated: Boolean = !fields.indices.forall(index => fields(index) == initialFields(index))

  def startNonPayableFrame(
      ctx: StatefulContext,
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] =
    Frame.stateful(ctx, None, this, method, args, operandStack, returnTo)

  def startPayableFrame(
      ctx: StatefulContext,
      balanceState: BalanceState,
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] =
    Frame.stateful(ctx, Some(balanceState), this, method, args, operandStack, returnTo)
}

object StatefulContractObject {
  def apply(
      code: StatefulContract,
      initialFields: AVector[Val],
      address: ContractId
  ): StatefulContractObject = {
    new StatefulContractObject(code, initialFields, initialFields.toArray, address)
  }
}
