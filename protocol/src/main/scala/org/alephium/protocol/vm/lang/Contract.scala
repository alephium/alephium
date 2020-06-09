//package org.alephium.protocol.vm.lang
//
//import org.alephium.protocol.vm
//import org.alephium.protocol.vm._
//import org.alephium.util.AVector

//sealed trait Contract
//
//case class StatelessScript(fields: Seq[Field], methods: Seq[StatelessMethod]) extends Contract
//case class StatelessMethod(inputs: Seq[Val.Type],
//                           output: Seq[Val.Type],
//                           instrs: Seq[vm.Instr[StatelessContext]])
//
//case class StatefulScript(fields: AVector[Field], methods: AVector[StatefulMethod])
//case class StatefulMethod(inputs: AVector[Val.Type],
//                          outputs: AVector[Val.Type],
//                          instrs: AVector[vm.Instr[StatelessContext]])
//
//case class Field(id: String, tpe: Val.Type)
