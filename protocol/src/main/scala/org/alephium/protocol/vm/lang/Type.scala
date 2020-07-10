package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.Val
import org.alephium.util.AVector

sealed trait Type {
  def toVal: Val.Type
}

object Type {
  val primitives: AVector[Type] = AVector[Type](Bool, Byte, I64, U64, I256, U256, Byte32) ++
    AVector[Type](BoolVec, ByteVec, I64Vec, U64Vec, I256Vec, U256Vec, Byte32Vec)

  def fromVal(tpe: Val.Type): Type = {
    tpe match {
      case Val.Bool      => Bool
      case Val.Byte      => Byte
      case Val.I64       => I64
      case Val.U64       => U64
      case Val.I256      => I256
      case Val.U256      => U256
      case Val.Byte32    => Byte32
      case Val.BoolVec   => BoolVec
      case Val.ByteVec   => ByteVec
      case Val.I64Vec    => I64Vec
      case Val.U64Vec    => U64Vec
      case Val.I256Vec   => I256Vec
      case Val.U256Vec   => U256Vec
      case Val.Byte32Vec => Byte32Vec
    }
  }

  case object Bool      extends Type { def toVal: Val.Type = Val.Bool }
  case object Byte      extends Type { def toVal: Val.Type = Val.Byte }
  case object I64       extends Type { def toVal: Val.Type = Val.I64 }
  case object U64       extends Type { def toVal: Val.Type = Val.U64 }
  case object I256      extends Type { def toVal: Val.Type = Val.I256 }
  case object U256      extends Type { def toVal: Val.Type = Val.U256 }
  case object Byte32    extends Type { def toVal: Val.Type = Val.Byte32 }
  case object BoolVec   extends Type { def toVal: Val.Type = Val.BoolVec }
  case object ByteVec   extends Type { def toVal: Val.Type = Val.ByteVec }
  case object I64Vec    extends Type { def toVal: Val.Type = Val.I64Vec }
  case object U64Vec    extends Type { def toVal: Val.Type = Val.U64Vec }
  case object I256Vec   extends Type { def toVal: Val.Type = Val.I256Vec }
  case object U256Vec   extends Type { def toVal: Val.Type = Val.U256Vec }
  case object Byte32Vec extends Type { def toVal: Val.Type = Val.Byte32Vec }

  sealed trait Contract extends Type {
    def toVal: Val.Type = Val.Byte32
  }
  final case class LocalVarContract(id: Ast.TypeId, variable: Ast.Ident)  extends Contract
  final case class GlobalVarContract(id: Ast.TypeId, variable: Ast.Ident) extends Contract
  final case class StackVarContract(id: Ast.TypeId)                       extends Contract
}
