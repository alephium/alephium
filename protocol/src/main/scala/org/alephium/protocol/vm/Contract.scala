package org.alephium.protocol.vm

import org.alephium.protocol.ALF
import org.alephium.util.AVector

trait ContractAddress

case class Method[Ctx <: Context](
    localsType: AVector[Val.Type],
    instrs: AVector[Instr[Ctx]]
)

trait Contract[Ctx <: Context] {
  def fields: AVector[Val.Type]
  def methods: AVector[Method[Ctx]]
}

trait Script[Ctx <: Context] extends Contract[Ctx] {
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

case class StatefulScript(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends Script[StatefulContext] {
  override def toObject: ScriptObj[StatefulContext] = {
    StatefulScriptObject(this, fields.mapToArray(_.default))
  }
}

case class StatefulContract(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends Contract[StatefulContext]

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
