package org.alephium.protocol.vm

import org.alephium.protocol.ALF
import org.alephium.serde._
import org.alephium.util.AVector

trait ContractAddress

case class Method[Ctx <: Context](
    localsType: AVector[Val.Type],
    instrs: AVector[Instr[Ctx]]
)

object Method {
  implicit val statelessSerde: Serde[Method[StatelessContext]] =
    Serde.forProduct2(Method[StatelessContext], t => (t.localsType, t.instrs))
  implicit val statefulSerde: Serde[Method[StatefulContext]] =
    Serde.forProduct2(Method[StatefulContext], t => (t.localsType, t.instrs))
}

sealed trait Contract[Ctx <: Context] {
  def fields: AVector[Val.Type]
  def methods: AVector[Method[Ctx]]
}

sealed trait Script[Ctx <: Context] extends Contract[Ctx] {
  def toObject: ScriptObj[Ctx]

  def startFrame: Frame[Ctx] = {
    val obj = this.toObject
    Frame.build(obj)
  }
}

case class StatelessScript(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatelessContext]]
) extends Script[StatelessContext] {
  override def toObject: ScriptObj[StatelessContext] = {
    StatelessScriptObject(this, fields.mapToArray(_.default))
  }
}

object StatelessScript {
  implicit val serde: Serde[StatelessScript] =
    Serde.forProduct2(StatelessScript.apply, t => (t.fields, t.methods))
}

case class StatefulScript(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends Script[StatefulContext] {
  override def toObject: ScriptObj[StatefulContext] = {
    StatefulScriptObject(this, fields.mapToArray(_.default))
  }
}

object StatefulScript {
  implicit val serde: Serde[StatefulScript] =
    Serde.forProduct2(StatefulScript.apply, t => (t.fields, t.methods))
}

case class StatefulContract(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends Contract[StatefulContext]

object StatefulContract {
  implicit val serde: Serde[StatefulContract] =
    Serde.forProduct2(StatefulContract.apply, t => (t.fields, t.methods))
}

trait ContractObj[Ctx <: Context] {
  def code: Contract[Ctx]
  def fields: Array[Val]
}

trait ScriptObj[Ctx <: Context] extends ContractObj[Ctx]

case class StatelessScriptObject(code: StatelessScript, fields: Array[Val])
    extends ScriptObj[StatelessContext]

case class StatefulScriptObject(code: StatefulScript, fields: Array[Val])
    extends ScriptObj[StatefulContext]

case class StatefulContractObject(code: StatefulContract,
                                  fields: Array[Val],
                                  address: ContractAddress,
                                  codeHash: ALF.Hash)
    extends ContractObj[StatefulContext]
