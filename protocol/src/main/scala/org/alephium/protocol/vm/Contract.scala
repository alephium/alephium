package org.alephium.protocol.vm

import org.alephium.protocol.ALF
import org.alephium.serde._
import org.alephium.util.AVector

final case class Method[Ctx <: Context](
    localsType: AVector[Val.Type],
    returnType: AVector[Val.Type],
    instrs: AVector[Instr[Ctx]]
) {
  def check(args: AVector[Val]): ExeResult[Unit] = {
    if (args.length != localsType.length)
      Left(InvalidMethodArgLength(args.length, localsType.length))
    else if (!args.forallWithIndex((v, index) => v.tpe == localsType(index))) {
      Left(InvalidMethodParamsType)
    } else Right(())
  }
}

object Method {
  implicit val statelessSerde: Serde[Method[StatelessContext]] =
    Serde.forProduct3(Method[StatelessContext], t => (t.localsType, t.returnType, t.instrs))
  implicit val statefulSerde: Serde[Method[StatefulContext]] =
    Serde.forProduct3(Method[StatefulContext], t => (t.localsType, t.returnType, t.instrs))
}

sealed trait Contract[Ctx <: Context] {
  def fields: AVector[Val.Type]
  def methods: AVector[Method[Ctx]]

  def startFrame(ctx: Ctx,
                 obj: ContractObj[Ctx],
                 methodIndex: Int,
                 args: AVector[Val],
                 returnTo: AVector[Val] => ExeResult[Unit]): Frame[Ctx] = {
    Frame.build(ctx, obj, methodIndex, args: AVector[Val], returnTo)
  }
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
    new StatelessScriptObject(None, this)
  }
}

object StatelessScript {
  implicit val serde: Serde[StatelessScript] =
    Serde.forProduct1(StatelessScript.apply, _.methods)

  val failure: StatelessScript = StatelessScript(
    AVector(Method[StatelessContext](AVector.empty, AVector.empty, AVector(Pop))))
}

final case class StatefulScript(methods: AVector[Method[StatefulContext]])
    extends Script[StatefulContext] {
  override def toObject: ScriptObj[StatefulContext] = {
    new StatefulScriptObject(this)
  }
}

object StatefulScript {
  implicit val serde: Serde[StatefulScript] = Serde.forProduct1(StatefulScript.apply, _.methods)
}

final case class StatefulContract(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends Contract[StatefulContext] {
  def toObject(address: ALF.Hash, fields: AVector[Val]): StatefulContractObject = {
    new StatefulContractObject(this, fields.toArray, address)
  }
}

object StatefulContract {
  implicit val serde: Serde[StatefulContract] =
    Serde.forProduct2(StatefulContract.apply, t => (t.fields, t.methods))

  // This is only used for initialize merkle tree of contract codes
  val failure: StatefulContract = StatefulContract(
    AVector.empty,
    AVector(Method[StatefulContext](AVector.empty, AVector.empty, AVector(Pop))))
}

sealed trait ContractObj[Ctx <: Context] {
  def addressOpt: Option[ALF.Hash] = None
  def code: Contract[Ctx]
  def fields: Array[Val]

  def getMethod(index: Int): Option[Method[Ctx]] = {
    code.methods.get(index)
  }

  def startFrame(ctx: Ctx,
                 methodIndex: Int,
                 args: AVector[Val],
                 returnTo: AVector[Val] => ExeResult[Unit]): Frame[Ctx] = {
    Frame.build(ctx, this, methodIndex, args: AVector[Val], returnTo)
  }
}

trait ScriptObj[Ctx <: Context] extends ContractObj[Ctx] {
  val fields: Array[Val] = Array.empty
}

class StatelessScriptObject(override val addressOpt: Option[ALF.Hash], val code: StatelessScript)
    extends ScriptObj[StatelessContext]

class StatefulScriptObject(val code: StatefulScript) extends ScriptObj[StatefulContext]

class StatefulContractObject(val code: StatefulContract,
                             val fields: Array[Val],
                             val address: ALF.Hash)
    extends ContractObj[StatefulContext]
