package org.alephium.protocol.vm

import scala.collection.mutable

import org.alephium.protocol.Hash
import org.alephium.serde._
import org.alephium.util.AVector

final case class Method[Ctx <: Context](
    isPublic: Boolean,
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
    Serde.forProduct4(Method[StatelessContext],
                      t => (t.isPublic, t.localsType, t.returnType, t.instrs))
  implicit val statefulSerde: Serde[Method[StatefulContext]] =
    Serde.forProduct4(Method[StatefulContext],
                      t => (t.isPublic, t.localsType, t.returnType, t.instrs))

  def forMPT: Method[StatefulContext] =
    Method[StatefulContext](isPublic = false, AVector.empty, AVector.empty, AVector(Pop))
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
    new StatelessScriptObject(this)
  }
}

object StatelessScript {
  implicit val serde: Serde[StatelessScript] =
    Serde.forProduct1(StatelessScript.apply, _.methods)
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
  def toObject(address: Hash, contractState: ContractState): StatefulContractObject = {
    StatefulContractObject(this, contractState.fields.toArray, address)
  }

  def toObject(address: Hash, fields: AVector[Val]): StatefulContractObject = {
    StatefulContractObject(this, fields.toArray, address)
  }
}

object StatefulContract {
  implicit val serde: Serde[StatefulContract] =
    Serde.forProduct2(StatefulContract.apply, t => (t.fields, t.methods))

  val forMPT: StatefulContract = StatefulContract(AVector.empty, AVector(Method.forMPT))
}

sealed trait ContractObj[Ctx <: Context] {
  def addressOpt: Option[Hash]
  def code: Contract[Ctx]
  def fields: mutable.ArraySeq[Val]

  def getMethod(index: Int): Option[Method[Ctx]] = {
    code.methods.get(index)
  }

  def startFrame(ctx: Ctx,
                 methodIndex: Int,
                 args: AVector[Val],
                 returnTo: AVector[Val] => ExeResult[Unit]): ExeResult[Frame[Ctx]] = {
    for {
      method <- getMethod(methodIndex).toRight(InvalidMethodIndex(methodIndex))
      _      <- if (method.isPublic) Right(()) else Left(PrivateExternalMethodCall)
    } yield Frame.build(ctx, this, method, args, returnTo)
  }
}

sealed trait ScriptObj[Ctx <: Context] extends ContractObj[Ctx] {
  val addressOpt: Option[Hash]      = None
  val fields: mutable.ArraySeq[Val] = mutable.ArraySeq.empty
}

final case class StatelessScriptObject(val code: StatelessScript)
    extends ScriptObj[StatelessContext]

final case class StatefulScriptObject(val code: StatefulScript) extends ScriptObj[StatefulContext]

final case class StatefulContractObject(val code: StatefulContract,
                                        val fields: mutable.ArraySeq[Val],
                                        val address: Hash)
    extends ContractObj[StatefulContext] {
  override def addressOpt: Option[Hash] = Some(address)
}
