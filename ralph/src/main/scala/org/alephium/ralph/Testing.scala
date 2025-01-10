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

package org.alephium.ralph

import org.alephium.protocol.vm._

object Testing {
  sealed trait SettingDef[Ctx <: StatelessContext] extends Ast.UniqueDef with Ast.Positioned
  final case class GroupDef[Ctx <: StatelessContext](expr: Ast.Expr[Ctx]) extends SettingDef[Ctx] {
    def name: String = "group"
  }
  final case class BlockHashDef[Ctx <: StatelessContext](expr: Ast.Expr[Ctx])
      extends SettingDef[Ctx] {
    def name: String = "blockHash"
  }
  final case class BlockTimeStampDef[Ctx <: StatelessContext](expr: Ast.Expr[Ctx])
      extends SettingDef[Ctx] {
    def name: String = "blockTimeStamp"
  }

  final case class SettingsDef[Ctx <: StatelessContext](defs: Seq[SettingDef[Ctx]])
      extends Ast.Positioned

  final case class ApprovedAssetsDef[Ctx <: StatelessContext](assets: Seq[Ast.ApproveAsset[Ctx]])
      extends Ast.Positioned

  final case class CreateContractDef[Ctx <: StatelessContext](
      typeId: Ast.TypeId,
      tokens: Seq[(Ast.Expr[Ctx], Ast.Expr[Ctx])],
      fields: Seq[Ast.Expr[Ctx]],
      address: Option[Ast.Ident]
  ) extends Ast.Positioned

  final case class SingleTestDef[Ctx <: StatelessContext](
      contracts: Seq[CreateContractDef[Ctx]],
      assets: Option[ApprovedAssetsDef[Ctx]],
      body: Seq[Ast.Statement[Ctx]]
  ) extends Ast.Positioned

  final case class UnitTestDef[Ctx <: StatelessContext](
      name: String,
      settings: Option[SettingsDef[Ctx]],
      tests: Seq[SingleTestDef[Ctx]]
  ) extends Ast.UniqueDef
      with Ast.Positioned
}
