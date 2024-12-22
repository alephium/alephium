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

import java.nio.charset.StandardCharsets

import scala.annotation.{nowarn, tailrec}
import scala.collection.mutable

import akka.util.ByteString

import org.alephium.protocol.vm
import org.alephium.protocol.vm.{ALPHTokenId => ALPHTokenIdInstr, Contract => VmContract, _}
import org.alephium.ralph.LogicalOperator.Not
import org.alephium.ralph.Parser.FunctionUsingAnnotation
import org.alephium.util.{AVector, DjbHash, Hex, I256, U256}

// scalastyle:off number.of.methods number.of.types file.size.limit
object Ast {
  type StdInterfaceId = Val.ByteVec
  val StdInterfaceIdPrefix: ByteString = ByteString("ALPH", StandardCharsets.UTF_8)
  private val stdArg: Argument =
    Argument(Ident("__stdInterfaceId"), Type.ByteVec, isMutable = false, isUnused = true)

  trait Positioned {
    var sourceIndex: Option[SourceIndex] = None

    def atSourceIndex(fromIndex: Int, endIndex: Int, fileURI: Option[java.net.URI]): this.type = {
      require(this.sourceIndex.isEmpty)
      this.sourceIndex = Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
      this
    }
    def atSourceIndex(sourceIndex: Option[SourceIndex]): this.type = {
      require(this.sourceIndex.isEmpty)
      this.sourceIndex = sourceIndex
      this
    }
    def overwriteSourceIndex(
        fromIndex: Int,
        endIndex: Int,
        fileURI: Option[java.net.URI]
    ): this.type = {
      require(this.sourceIndex.isDefined)
      this.sourceIndex = Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
      this
    }

    /*
     * This function update a `CompilerError` when the source index was not
     * available at the time of the error.
     * For example for `Operator` or `BuiltIn`, we could add the `SourceIndex`
     * to the `getReturnType` function, but it implies a lot of changes in the
     * all `ralph` module, while the position is not useful along the way.
     */
    def positionedError[T](f: => T): T = {
      try {
        f
      } catch {
        case e: error.CompilerError.Default =>
          if (sourceIndex.isDefined && e.sourceIndex.isEmpty) {
            throw e.copy(sourceIndex = sourceIndex)
          } else {
            throw e
          }
      }
    }
  }

  final case class Ident(name: String)                      extends Positioned
  final case class TypeId(name: String)                     extends Positioned
  final case class FuncId(name: String, isBuiltIn: Boolean) extends Positioned

  final case class Argument(ident: Ident, tpe: Type, isMutable: Boolean, isUnused: Boolean)
      extends Positioned {
    def signature: String = {
      val prefix = if (isMutable) "mut " else ""
      s"${prefix}${ident.name}:${tpe.signature}"
    }
  }

  final case class EventField(ident: Ident, tpe: Type) extends Positioned {
    def signature: String = s"${ident.name}:${tpe.signature}"
  }

  final case class AnnotationField[Ctx <: StatelessContext](ident: Ident, value: Const[Ctx])
      extends Positioned
  final case class Annotation[Ctx <: StatelessContext](id: Ident, fields: Seq[AnnotationField[Ctx]])
      extends Positioned

  object FuncId {
    lazy val empty: FuncId = FuncId("", isBuiltIn = false)
  }

  def funcName(typeId: TypeId, funcId: FuncId): String = quote(s"${typeId.name}.${funcId.name}")

  final case class ApproveAsset[Ctx <: StatelessContext](
      address: Expr[Ctx],
      tokenAmounts: Seq[(Expr[Ctx], Expr[Ctx])]
  ) extends Positioned {
    def check(state: Compiler.State[Ctx]): Unit = {
      if (address.getType(state) != Seq(Type.Address)) {
        throw Compiler.Error(s"Invalid address type: ${address}", address.sourceIndex)
      }
      tokenAmounts
        .find(p =>
          (p._1.getType(state), p._2.getType(state)) != (Seq(Type.ByteVec), Seq(Type.U256))
        ) match {
        case None => ()
        case Some((exp, _)) =>
          throw Compiler.Error(s"Invalid token amount type: ${tokenAmounts}", exp.sourceIndex)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val approveCount = tokenAmounts.length
      assume(approveCount >= 1)
      val approveTokens: Seq[Instr[Ctx]] = tokenAmounts.flatMap {
        case (ALPHTokenId(), amount) =>
          amount.genCode(state) :+ ApproveAlph.asInstanceOf[Instr[Ctx]]
        case (tokenId, amount) =>
          tokenId.genCode(state) ++ amount.genCode(state) :+ ApproveToken.asInstanceOf[Instr[Ctx]]
      }
      address.genCode(state) ++ Seq.fill(approveCount - 1)(Dup) ++ approveTokens
    }

    def reset(): Unit = {
      address.reset()
      tokenAmounts.foreach { case (tokenExpr, amountExpr) =>
        tokenExpr.reset()
        amountExpr.reset()
      }
    }
  }

  sealed trait ContractAssetsAnnotation {
    def assetsEnabled: Boolean
  }
  case object NotUseContractAssets extends ContractAssetsAnnotation {
    val assetsEnabled = false
  }
  case object UseContractAssets extends ContractAssetsAnnotation {
    val assetsEnabled = true
  }
  case object EnforcedUseContractAssets extends ContractAssetsAnnotation {
    val assetsEnabled = true
  }

  trait ApproveAssets[Ctx <: StatelessContext] extends Positioned {
    def approveAssets: Seq[ApproveAsset[Ctx]]

    def checkApproveAssets(state: Compiler.State[Ctx]): Unit = {
      approveAssets.foreach(_.check(state))
    }

    def genApproveCode(
        state: Compiler.State[Ctx],
        func: Compiler.FuncInfo[Ctx]
    ): Seq[Instr[Ctx]] = {
      (approveAssets.nonEmpty, func.usePreapprovedAssets) match {
        case (true, false) =>
          throw Compiler.Error(
            s"Function `${func.name}` does not use preapproved assets",
            sourceIndex
          )
        case (false, true) =>
          throw Compiler.Error(
            s"Function `${func.name}` needs preapproved assets, please use braces syntax",
            sourceIndex
          )
        case _ => ()
      }
      approveAssets.flatMap(_.genCode(state))
    }
  }

  trait Typed[Ctx <: StatelessContext, T] extends Positioned {
    private var tpe: Option[T] = None
    protected def _getType(state: Compiler.State[Ctx]): T
    def getCachedType(): Option[T] = tpe
    def getType(state: Compiler.State[Ctx]): T =
      tpe match {
        case Some(ts) => ts
        case None =>
          val t = _getType(state)
          tpe = Some(t)
          t
      }
    def reset(): Unit = tpe = None
  }

  sealed trait Expr[Ctx <: StatelessContext]
      extends Typed[Ctx, Seq[Type]]
      with Product
      with Serializable {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }

  final case class ALPHTokenId[Ctx <: StatelessContext]() extends Expr[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.ByteVec)

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = Seq(
      ALPHTokenIdInstr.asInstanceOf[Instr[Ctx]]
    )
  }
  final case class Const[Ctx <: StatelessContext](v: Val) extends Expr[Ctx] {
    def toConstInstr: Instr[StatelessContext]                    = v.toConstInstr
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.fromVal(v.tpe))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      Seq(v.toConstInstr)
    }
  }
  sealed trait CreateArrayExpr[Ctx <: StatelessContext] extends Expr[Ctx] {
    def elementExpr: Expr[Ctx]
    protected def getElementType(state: Compiler.State[Ctx]): Type = {
      val baseType = elementExpr.getType(state)
      if (baseType.length != 1) {
        throw Compiler.Error(
          s"Expected single type for array element, got ${quote(baseType)}",
          sourceIndex
        )
      }
      baseType(0)
    }
  }
  final case class CreateArrayExpr1[Ctx <: StatelessContext](elements: Seq[Expr[Ctx]])
      extends CreateArrayExpr[Ctx] {
    def elementExpr: Expr[Ctx] = {
      assume(elements.nonEmpty)
      elements(0)
    }
    override def _getType(state: Compiler.State[Ctx]): Seq[Type.FixedSizeArray] = {
      val elementType = getElementType(state)
      if (elements.drop(0).exists(_.getType(state) != Seq(elementType))) {
        throw Compiler.Error(s"Array elements should have same type", sourceIndex)
      }
      Seq(Type.FixedSizeArray(elementType, Left(elements.size)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      elements.flatMap(_.genCode(state))
    }
    override def reset(): Unit = {
      elements.foreach(_.reset())
      super.reset()
    }
  }
  final case class CreateArrayExpr2[Ctx <: StatelessContext](
      elementExpr: Expr[Ctx],
      sizeExpr: Expr[Ctx]
  ) extends CreateArrayExpr[Ctx] {
    private var size: Option[Int] = None

    override def _getType(state: Compiler.State[Ctx]): Seq[Type.FixedSizeArray] = {
      val elementType = getElementType(state)
      val arraySize   = state.calcArraySize(sizeExpr)
      size = Some(arraySize)
      Seq(Type.FixedSizeArray(elementType, Left(arraySize)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val arraySize = size.getOrElse(state.calcArraySize(sizeExpr))
      Seq.fill(arraySize)(elementExpr).flatMap(_.genCode(state))
    }
    override def reset(): Unit = {
      size = None
      elementExpr.reset()
      sizeExpr.reset()
      super.reset()
    }
  }

  sealed trait AccessDataT[Ctx <: StatelessContext] { self: Positioned =>
    def selectors: Seq[DataSelector]
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    protected def mapKeyIndex: Expr[Ctx] = {
      selectors(0).asInstanceOf[IndexSelector[Ctx]].index
    }
    protected def _getType(
        state: Compiler.State[Ctx],
        rootType: Type,
        sourceIndex: Option[SourceIndex]
    ): Type = {
      selectors
        .foldLeft((rootType, sourceIndex)) { case ((tpe, sourceIndex), selector) =>
          (tpe, selector) match {
            case (array: Type.FixedSizeArray, selector: IndexSelector[Ctx @unchecked]) =>
              state.checkArrayIndexType(selector.index)
              (array.baseType, selector.sourceIndex)
            case (struct: Type.Struct, IdentSelector(ident)) =>
              val field = state.getStruct(struct.id).getField(ident)
              (state.resolveType(field.tpe), selector.sourceIndex)
            case (map: Type.Map, selector: IndexSelector[Ctx @unchecked]) =>
              state.checkMapKeyType(map, selector.index)
              (map.value, selector.sourceIndex)
            case (tpe, _: IndexSelector[Ctx @unchecked]) =>
              throw Compiler.Error(
                s"Expected array or map type, got ${quote(tpe)}",
                SourceIndex(this.sourceIndex, sourceIndex)
              )
            case (tpe, _: IdentSelector) =>
              throw Compiler.Error(
                s"Expected struct type, got ${quote(tpe)}",
                SourceIndex(this.sourceIndex, sourceIndex)
              )
          }
        }
        ._1
    }
  }

  object MapOps {
    private def genMapKey[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        expr: Ast.Expr[Ctx]
    ): Seq[Instr[Ctx]] = {
      val codes = expr.genCode(state)
      expr.getType(state)(0) match {
        case Type.Bool    => codes :+ BoolToByteVec
        case Type.U256    => codes :+ U256ToByteVec
        case Type.I256    => codes :+ I256ToByteVec
        case Type.Address => codes :+ AddressToByteVec
        case Type.ByteVec => codes
        case tpe => // dead branch
          throw Compiler.Error(s"Invalid key type $tpe", expr.sourceIndex)
      }
    }

    @inline def genSubContractPath[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        ident: Ast.Ident,
        index: Ast.Expr[Ctx]
    ): Seq[Instr[Ctx]] = {
      (state.genLoadCode(ident) ++ genMapKey(state, index)) :+ ByteVecConcat
    }

    @inline def genSubContractPath[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        map: Ast.Expr[Ctx],
        index: Ast.Expr[Ctx]
    ): Seq[Instr[Ctx]] = {
      (map.genCode(state) ++ genMapKey(state, index)) :+ ByteVecConcat
    }

    @tailrec
    private def calcDataOffset(
        state: Compiler.State[StatefulContext],
        tpe: Type,
        selectors: Seq[DataSelector],
        isMutable: Boolean,
        dataOffset: DataRefOffset[StatefulContext]
    ): (DataRefOffset[StatefulContext], Boolean) = {
      (state.resolveType(tpe), selectors.headOption) match {
        case (_, None) => (dataOffset, isMutable)
        case (tpe: Type.FixedSizeArray, Some(s: IndexSelector[StatefulContext @unchecked])) =>
          val newOffset = dataOffset.calcArrayElementOffset(state, tpe, s.index, isMutable)
          calcDataOffset(state, tpe.baseType, selectors.drop(1), isMutable, newOffset)
        case (tpe: Type.Struct, Some(IdentSelector(ident))) =>
          val ast            = state.getStruct(tpe.id)
          val newOffset      = dataOffset.calcStructFieldOffset(state, ast, ident, isMutable)
          val field          = ast.getField(ident)
          val isFieldMutable = isMutable && field.isMutable
          calcDataOffset(state, field.tpe, selectors.drop(1), isFieldMutable, newOffset)
        case _ => // dead branch
          throw Compiler.Error(
            s"Invalid type $tpe and selectors $selectors",
            selectors.headOption.flatMap(_.sourceIndex)
          )
      }
    }

    private def calcDataOffset(
        state: Compiler.State[StatefulContext],
        rootType: Type,
        selectors: Seq[DataSelector]
    ): (VarOffset[StatefulContext], VarOffset[StatefulContext], Boolean) = {
      val initOffset = DataRefOffset[StatefulContext](ConstantVarOffset(0), ConstantVarOffset(0))
      val (offset, isMutable) = calcDataOffset(
        state,
        rootType,
        selectors,
        isMutable = true,
        initOffset
      )
      (offset.immDataOffset, offset.mutDataOffset, isMutable)
    }

    private def genSubContractId(
        state: Compiler.State[StatefulContext],
        objCodes: Seq[Instr[StatefulContext]],
        size: Int
    ): (Seq[Instr[StatefulContext]], Seq[Instr[StatefulContext]]) = {
      if (size == 1) {
        (Seq.empty, objCodes)
      } else {
        val ident = state.getSubContractIdVar()
        (objCodes ++ state.genStoreCode(ident).flatten, state.genLoadCode(ident))
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genLoad[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        rootType: Type,
        selectedDataType: Type,
        pathCodes: Seq[Instr[Ctx]],
        selectors: Seq[DataSelector]
    ): Seq[Instr[Ctx]] = {
      val statefulState                     = state.asInstanceOf[Compiler.State[StatefulContext]]
      val (immOffset, mutOffset, isMutable) = calcDataOffset(statefulState, rootType, selectors)
      val mutability = state.flattenTypeMutability(selectedDataType, isMutable)
      val (initCodes, subContractIdCodes) = genSubContractId(
        statefulState,
        pathCodes.asInstanceOf[Seq[Instr[StatefulContext]]] :+ SubContractId,
        mutability.length
      )
      val funcArgLenAndRetLen =
        Seq(ConstInstr.u256(Val.U256(U256.One)), ConstInstr.u256(Val.U256(U256.One)))
      val instrs = mutability.indices
        .foldLeft((Seq.empty[Instr[StatefulContext]], immOffset, mutOffset)) {
          case ((instrs, immOffset, mutOffset), index) =>
            val objCodes = if (index == 0) initCodes ++ subContractIdCodes else subContractIdCodes
            if (mutability(index)) {
              val loadCodes = mutOffset.genCode() ++ funcArgLenAndRetLen ++
                objCodes :+ CallExternal(CreateMapEntry.LoadMutFieldMethodIndex)
              (instrs ++ loadCodes, immOffset, mutOffset.add(1))
            } else {
              val loadCodes = immOffset.genCode() ++ funcArgLenAndRetLen ++
                objCodes :+ CallExternal(CreateMapEntry.LoadImmFieldMethodIndex)
              (instrs ++ loadCodes, immOffset.add(1), mutOffset)
            }
        }
        ._1
      instrs.asInstanceOf[Seq[Instr[Ctx]]]
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genStore[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        rootType: Type,
        selectedDataType: Type,
        pathCodes: Seq[Instr[Ctx]],
        selectors: Seq[DataSelector]
    ): Seq[Seq[Instr[Ctx]]] = {
      val statefulState     = state.asInstanceOf[Compiler.State[StatefulContext]]
      val (_, mutOffset, _) = calcDataOffset(statefulState, rootType, selectors)
      val length            = state.flattenTypeLength(Seq(selectedDataType))
      val (initCodes, subContractIdCodes) = genSubContractId(
        statefulState,
        pathCodes.asInstanceOf[Seq[Instr[StatefulContext]]] :+ SubContractId,
        length
      )
      val instrs = (0 until length).map { index =>
        val indexCodes = if (index == 0) mutOffset.genCode() else mutOffset.add(index).genCode()
        val objCodes =
          if (index == length - 1) initCodes ++ subContractIdCodes else subContractIdCodes
        indexCodes ++ Seq(
          ConstInstr.u256(Val.U256(U256.Two)),
          ConstInstr.u256(Val.U256(U256.Zero))
        ) ++ objCodes :+ CallExternal(CreateMapEntry.StoreMutFieldMethodIndex)
      }
      instrs.asInstanceOf[Seq[Seq[Instr[Ctx]]]]
    }
  }

  final case class LoadDataBySelectors[Ctx <: StatelessContext](
      base: Expr[Ctx],
      selectors: Seq[DataSelector]
  ) extends Expr[Ctx]
      with AccessDataT[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      assume(selectors.nonEmpty)
      base.getType(state) match {
        case Seq(t: Type.FixedSizeArray) =>
          Seq(_getType(state, t, base.sourceIndex))
        case Seq(t: Type.Struct) => Seq(_getType(state, t, base.sourceIndex))
        case Seq(t: Type.Map)    => Seq(_getType(state, t, base.sourceIndex))
        case tpe =>
          val tpeStr = quoteTypes(tpe)
          selectors.headOption match {
            case Some(IndexSelector(_)) =>
              throw Compiler.Error(s"Expected array or map type, got $tpeStr", base.sourceIndex)
            case _ =>
              throw Compiler.Error(s"Expected struct type, got $tpeStr", base.sourceIndex)
          }
      }
    }
    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      base.getType(state) match {
        case Seq(map: Type.Map) =>
          val pathCodes = MapOps.genSubContractPath(state, base, mapKeyIndex)
          MapOps.genLoad(state, map.value, getType(state).head, pathCodes, selectors.tail)
        case _ =>
          val (ref, codes) = state.getOrCreateVariablesRef(base)
          val subRef       = ref.subRef(state, selectors.init)
          codes ++ subRef.genLoadCode(state, selectors.last)
      }
    }
    override def reset(): Unit = {
      base.reset()
      selectors.foreach(_.reset())
      super.reset()
    }
  }
  final case class MapContains(ident: Ident, index: Expr[StatefulContext])
      extends Expr[StatefulContext] {
    def _getType(state: Compiler.State[StatefulContext]): Seq[Type] = {
      val mapType = state.getVariable(ident).tpe match {
        case t: Type.Map => t
        case t           => throw Compiler.Error(s"Expected map type, got $t", ident.sourceIndex)
      }
      val expected = Seq(mapType.key)
      val argTypes = index.getType(state)
      if (argTypes != expected) {
        throw Compiler.Error(s"Invalid args type $argTypes, expected $expected", sourceIndex)
      }
      Seq(Type.Bool)
    }

    def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      val pathCodes = MapOps.genSubContractPath(state, ident, index)
      pathCodes ++ Seq(SubContractId, ContractExists)
    }
    override def reset(): Unit = {
      index.reset()
      super.reset()
    }
  }
  final case class Variable[Ctx <: StatelessContext](id: Ident) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(state.resolveType(id))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.genLoadCode(id)
    }
  }
  final case class EnumFieldSelector[Ctx <: StatelessContext](enumId: TypeId, field: Ident)
      extends Expr[Ctx] {
    lazy val fieldIdent = EnumDef.fieldIdent(enumId, field)

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] =
      Seq(state.getVariable(fieldIdent).tpe)

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.genLoadCode(fieldIdent)
    }
  }
  final case class UnaryOp[Ctx <: StatelessContext](op: Operator, expr: Expr[Ctx])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      positionedError(op.getReturnType(expr.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      expr.genCode(state) ++ op.genCode(expr.getType(state))
    }
    override def reset(): Unit = {
      expr.reset()
      super.reset()
    }
  }
  final case class Binop[Ctx <: StatelessContext](op: Operator, left: Expr[Ctx], right: Expr[Ctx])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      positionedError(op.getReturnType(left.getType(state) ++ right.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      positionedError(
        left.genCode(state) ++ right.genCode(state) ++ op.genCode(
          left.getType(state) ++ right.getType(state)
        )
      )
    }
    override def reset(): Unit = {
      left.reset()
      right.reset()
      super.reset()
    }
  }
  final case class ContractConv[Ctx <: StatelessContext](contractType: TypeId, address: Expr[Ctx])
      extends Expr[Ctx] {
    override protected def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      state.checkContractType(contractType)

      if (address.getType(state) != Seq(Type.ByteVec)) {
        throw Compiler.Error(s"Invalid expr $address for contract address", address.sourceIndex)
      }

      val contractInfo = state.getContractInfo(contractType)
      if (!contractInfo.kind.instantiable) {
        throw Compiler.Error(s"${contractType.name} is not instantiable", sourceIndex)
      }

      Seq(Type.Contract(contractType))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      address.genCode(state)
    override def reset(): Unit = {
      address.reset()
      super.reset()
    }
  }

  sealed trait CallAst[Ctx <: StatelessContext] extends ApproveAssets[Ctx] {
    def id: FuncId
    def args: Seq[Expr[Ctx]]
    def ignoreReturn: Boolean

    def getFunc(state: Compiler.State[Ctx]): Compiler.FuncInfo[Ctx]

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def _genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      (id, args) match {
        case (BuiltIn.approveToken.funcId, Seq(from, ALPHTokenId(), amount)) =>
          Seq(from, amount).flatMap(_.genCode(state)) :+ ApproveAlph.asInstanceOf[Instr[Ctx]]
        case (BuiltIn.tokenRemaining.funcId, Seq(from, ALPHTokenId())) =>
          val instrs = from.genCode(state) :+ AlphRemaining.asInstanceOf[Instr[Ctx]]
          if (ignoreReturn) instrs :+ Pop.asInstanceOf[Instr[Ctx]] else instrs
        case (BuiltIn.transferToken.funcId, Seq(from, to, ALPHTokenId(), amount)) =>
          Seq(from, to, amount).flatMap(_.genCode(state)) :+ TransferAlph.asInstanceOf[Instr[Ctx]]
        case (BuiltIn.transferTokenFromSelf.funcId, Seq(to, ALPHTokenId(), amount)) =>
          Seq(to, amount).flatMap(_.genCode(state)) :+ TransferAlphFromSelf.asInstanceOf[Instr[Ctx]]
        case (BuiltIn.transferTokenToSelf.funcId, Seq(from, ALPHTokenId(), amount)) =>
          Seq(from, amount).flatMap(_.genCode(state)) :+ TransferAlphToSelf.asInstanceOf[Instr[Ctx]]
        case _ =>
          val func = getFunc(state)
          if (func.inline && state.genInlineCode) {
            func.genInlineCode(args, state, this)
          } else {
            val argsType = args.flatMap(_.getType(state))
            val variadicInstrs = if (func.isVariadic) {
              Seq(U256Const(Val.U256.unsafe(state.flattenTypeLength(argsType))))
            } else {
              Seq.empty
            }
            val instrs = genApproveCode(state, func) ++
              func.genCodeForArgs(args, state) ++
              variadicInstrs ++
              func.genCode(argsType)
            if (ignoreReturn) {
              val returnType = positionedError(func.getReturnType(argsType, state))
              instrs ++ Seq.fill(state.flattenTypeLength(returnType))(Pop)
            } else {
              instrs
            }
          }
      }
    }

    @inline final def checkStaticContractFunction(
        typeId: TypeId,
        funcId: FuncId,
        func: Compiler.ContractFunc[Ctx]
    ): Unit = {
      if (!func.isStatic) {
        throw Compiler.Error(
          s"Expected static function, got ${funcName(typeId, funcId)}",
          funcId.sourceIndex
        )
      }
    }
  }

  final case class CallExpr[Ctx <: StatelessContext](
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends Expr[Ctx]
      with CallAst[Ctx] {
    def ignoreReturn: Boolean = false

    def getFunc(state: Compiler.State[Ctx]): Compiler.FuncInfo[Ctx] = state.getFunc(id)

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      checkApproveAssets(state)
      val funcInfo = state.getFunc(id)
      positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.addInternalCall(
        id
      ) // don't put this in _getType, otherwise the statement might get skipped
      _genCode(state)
    }

    override def reset(): Unit = {
      approveAssets.foreach(_.reset())
      args.foreach(_.reset())
      super.reset()
    }
  }

  final case class ContractStaticCallExpr[Ctx <: StatelessContext](
      contractId: TypeId,
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends Expr[Ctx]
      with CallAst[Ctx] {
    def ignoreReturn: Boolean = false

    def getFunc(state: Compiler.State[Ctx]): Compiler.ContractFunc[Ctx] =
      state.getFunc(contractId, id)

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      checkApproveAssets(state)
      val funcInfo = getFunc(state)
      checkStaticContractFunction(contractId, id, funcInfo)
      positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      _genCode(state)
    }

    override def reset(): Unit = {
      approveAssets.foreach(_.reset())
      args.foreach(_.reset())
      super.reset()
    }
  }

  trait ContractCallBase extends ApproveAssets[StatefulContext] {
    def obj: Expr[StatefulContext]
    def callId: FuncId
    def args: Seq[Expr[StatefulContext]]

    @inline def getContractType(state: Compiler.State[StatefulContext]): Type.Contract = {
      val objType = obj.getType(state)
      if (objType.length != 1) {
        throw Compiler.Error(
          s"Expected a single parameter for contract object, got ${quote(obj)}",
          obj.sourceIndex
        )
      } else {
        state.resolveType(objType(0)) match {
          case contract: Type.Contract => contract
          case _ =>
            throw Compiler.Error(
              s"Expected a contract for ${quote(callId)}, got ${quote(obj)}",
              obj.sourceIndex
            )
        }
      }
    }

    def _getTypeBase(state: Compiler.State[StatefulContext]): (TypeId, Seq[Type]) = {
      val contractType = getContractType(state)
      val contractInfo = state.getContractInfo(contractType.id)
      if (contractInfo.kind == Compiler.ContractKind.Interface) {
        state.addInterfaceFuncCall(state.currentScope)
      }
      val funcInfo = state.getFunc(contractType.id, callId)
      checkNonStaticContractFunction(contractType.id, callId, funcInfo)
      state.addExternalCall(contractType.id, callId)
      val retTypes = positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
      (contractType.id, retTypes)
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genContractCall(
        state: Compiler.State[StatefulContext],
        popReturnValues: Boolean
    ): Seq[Instr[StatefulContext]] = {
      val contract  = obj.getType(state)(0).asInstanceOf[Type.Contract]
      val func      = state.getFunc(contract.id, callId)
      val argTypes  = args.flatMap(_.getType(state))
      val retTypes  = func.getReturnType(argTypes, state)
      val retLength = state.flattenTypeLength(retTypes)
      genApproveCode(state, func) ++
        args.flatMap(_.genCode(state)) ++
        func.genExternalCallCode(state, obj.genCode(state), contract.id) ++
        (if (popReturnValues) Seq.fill[Instr[StatefulContext]](retLength)(Pop) else Seq.empty)
    }

    @inline final def checkNonStaticContractFunction(
        typeId: TypeId,
        funcId: FuncId,
        func: Compiler.ContractFunc[StatefulContext]
    ): Unit = {
      if (func.isStatic) {
        // TODO: use `obj.funcId` instead of `typeId.funcId`
        throw Compiler.Error(
          s"Expected non-static function, got ${funcName(typeId, funcId)}",
          funcId.sourceIndex
        )
      }
    }
  }
  final case class ContractCallExpr(
      obj: Expr[StatefulContext],
      callId: FuncId,
      approveAssets: Seq[ApproveAsset[StatefulContext]],
      args: Seq[Expr[StatefulContext]]
  ) extends Expr[StatefulContext]
      with ContractCallBase {
    override def _getType(state: Compiler.State[StatefulContext]): Seq[Type] = {
      checkApproveAssets(state)
      _getTypeBase(state)._2
    }

    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      genContractCall(state, false)
    }
    override def reset(): Unit = {
      obj.reset()
      approveAssets.foreach(_.reset())
      args.foreach(_.reset())
      super.reset()
    }
  }
  final case class ParenExpr[Ctx <: StatelessContext](expr: Expr[Ctx]) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] =
      expr.getType(state: Compiler.State[Ctx])

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      expr.genCode(state)
    override def reset(): Unit = {
      expr.reset()
      super.reset()
    }
  }

  trait IfBranch[Ctx <: StatelessContext] extends Positioned {
    def condition: Expr[Ctx]
    def checkCondition(state: Compiler.State[Ctx]): Unit = {
      val conditionType = condition.getType(state)
      if (conditionType != Seq(Type.Bool)) {
        throw Compiler.Error(
          s"Invalid type of condition expr: $conditionType",
          condition.sourceIndex
        )
      }
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  trait ElseBranch[Ctx <: StatelessContext] extends Positioned {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  trait IfElse[Ctx <: StatelessContext] extends Positioned {
    def ifBranches: Seq[IfBranch[Ctx]]
    def elseBranchOpt: Option[ElseBranch[Ctx]]

    private def genElseBodyIRs(state: Compiler.State[Ctx]) = {
      elseBranchOpt
        .map { branch =>
          state.withScope(branch)(branch.genCode(state))
        }
        .getOrElse(Seq.empty)
    }

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val ifBranchesIRs = Array.ofDim[Seq[Instr[Ctx]]](ifBranches.length + 1)
      val elseOffsets   = Array.ofDim[Int](ifBranches.length + 1)
      val elseBodyIRs   = genElseBodyIRs(state)
      ifBranchesIRs(ifBranches.length) = elseBodyIRs
      elseOffsets(ifBranches.length) = elseBodyIRs.length
      ifBranches.zipWithIndex.view.reverse.foreach { case (ifBranch, index) =>
        val initialOffset    = elseOffsets(index + 1)
        val notTheLastBranch = index < ifBranches.length - 1 || elseBranchOpt.nonEmpty

        val bodyIRsWithoutOffset = state.withScope(ifBranch) { ifBranch.genCode(state) }
        val bodyOffsetIR = if (notTheLastBranch) {
          Seq(Jump(initialOffset))
        } else {
          Seq.empty
        }
        val bodyIRs = bodyIRsWithoutOffset ++ bodyOffsetIR

        val conditionOffset =
          if (notTheLastBranch) bodyIRs.length else bodyIRs.length + initialOffset
        val conditionIRs = Statement.getCondIR(ifBranch.condition, state, conditionOffset)
        ifBranchesIRs(index) = conditionIRs ++ bodyIRs
        elseOffsets(index) = initialOffset + bodyIRs.length + conditionIRs.length
      }
      ifBranchesIRs.reduce(_ ++ _)
    }
  }

  final case class IfBranchExpr[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      expr: Expr[Ctx]
  ) extends IfBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = expr.genCode(state)
    def reset(): Unit = {
      condition.reset()
      expr.reset()
    }
  }
  final case class ElseBranchExpr[Ctx <: StatelessContext](
      expr: Expr[Ctx]
  ) extends ElseBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = expr.genCode(state)
    def reset(): Unit                                        = expr.reset()
  }
  final case class IfElseExpr[Ctx <: StatelessContext](
      ifBranches: Seq[IfBranchExpr[Ctx]],
      elseBranch: ElseBranchExpr[Ctx]
  ) extends IfElse[Ctx]
      with Expr[Ctx] {
    def elseBranchOpt: Option[ElseBranch[Ctx]] = Some(elseBranch)

    def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      val elseBranchType = elseBranch.expr.getType(state)
      ifBranches.foreach { ifBranch =>
        ifBranch.checkCondition(state)
        val ifBranchType = ifBranch.expr.getType(state)
        if (ifBranchType != elseBranchType) {
          throw Compiler.Error(
            s"Invalid types of if-else expression branches, expected ${quote(elseBranchType)}, got ${quote(ifBranchType)}",
            sourceIndex
          )
        }
      }
      elseBranchType
    }
    override def reset(): Unit = {
      ifBranches.foreach(_.reset())
      elseBranch.reset()
      super.reset()
    }
  }

  final case class StructField(ident: Ident, isMutable: Boolean, tpe: Type) extends UniqueDef {
    def name: String      = ident.name
    def signature: String = s"${ident.name}:${tpe.signature}"
  }

  sealed trait GlobalDefinition extends UniqueDef
  final case class Struct(id: TypeId, fields: Seq[StructField]) extends GlobalDefinition {
    lazy val tpe: Type.Struct = Type.Struct(id)

    def name: String = id.name

    def getFieldNames(): AVector[String]          = AVector.from(fields.view.map(_.ident.name))
    def getFieldTypeSignatures(): AVector[String] = AVector.from(fields.view.map(_.tpe.signature))
    def getFieldsMutability(): AVector[Boolean]   = AVector.from(fields.view.map(_.isMutable))

    def getField(selector: Ident): StructField = {
      fields
        .find(_.ident == selector)
        .getOrElse(
          throw Compiler.Error(
            s"Field ${selector.name} does not exist in struct ${id.name}",
            selector.sourceIndex
          )
        )
    }

    def calcFieldOffset[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        selector: Ast.Ident,
        isMutable: Boolean
    ): (Int, Int) = {
      val result = fields.slice(0, fields.indexWhere(_.ident == selector)).flatMap { field =>
        val isFieldMutable = isMutable && field.isMutable
        state.flattenTypeMutability(field.tpe, isFieldMutable)
      }
      val mutFieldSize = result.count(identity)
      (result.length - mutFieldSize, mutFieldSize)
    }

    def calcLocalOffset[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        selector: Ast.Ident
    ): Int = {
      val types = fields.slice(0, fields.indexWhere(_.ident == selector)).map(_.tpe)
      state.flattenTypeLength(types)
    }
  }

  final case class StructCtor[Ctx <: StatelessContext](
      id: TypeId,
      fields: Seq[(Ident, Option[Expr[Ctx]])]
  ) extends Expr[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      val struct   = state.getStruct(id)
      val expected = struct.fields.map(field => (field.ident, Seq(state.resolveType(field.tpe))))
      val have = fields.map { case (ident, expr) =>
        val tpe = expr.map(_.getType(state)).getOrElse(Seq(state.getVariable(ident).tpe))
        (ident, tpe)
      }
      if (expected.length != have.length || have.exists(f => !expected.contains(f))) {
        throw Compiler.Error(
          s"Invalid struct fields, expect ${struct.fields.map(_.signature)}",
          sourceIndex
        )
      }
      Seq(struct.tpe)
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val struct = state.getStruct(id)
      val sortedFields = struct.fields.map { field =>
        fields
          .find(_._1 == field.ident)
          .getOrElse(
            throw Compiler.Error(s"Struct field ${field.ident} does not exist", id.sourceIndex)
          )
      }
      sortedFields.flatMap {
        case (_, Some(expr)) => expr.genCode(state)
        case (field, None)   => state.genLoadCode(field)
      }
    }
    override def reset(): Unit = {
      fields.foreach { case (_, expr) => expr.foreach(_.reset()) }
      super.reset()
    }
  }

  final case class MapDef(ident: Ident, tpe: Type.Map) extends UniqueDef with Positioned {
    def name: String = ident.name
  }

  sealed trait Statement[Ctx <: StatelessContext]
      extends Positioned
      with Product
      with Serializable {
    def check(state: Compiler.State[Ctx]): Unit
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
    def reset(): Unit
  }
  object Statement {
    @inline def getCondIR[Ctx <: StatelessContext](
        condition: Expr[Ctx],
        state: Compiler.State[Ctx],
        offset: Int
    ): Seq[Instr[Ctx]] = {
      condition match {
        case UnaryOp(Not, expr) =>
          expr.genCode(state) :+ IfTrue(offset)
        case _ =>
          condition.genCode(state) :+ IfFalse(offset)
      }
    }
  }

  sealed trait MapFuncCall extends Statement[StatefulContext] {
    def ident: Ident
    def args: Seq[Expr[StatefulContext]]
    private var mapType: Option[Type.Map] = None
    def getMapType(state: Compiler.State[StatefulContext]): Type.Map = {
      mapType match {
        case Some(tpe) => tpe
        case None =>
          state.getVariable(ident, isWrite = true).tpe match {
            case tpe: Type.Map =>
              mapType = Some(tpe)
              tpe
            case t => throw Compiler.Error(s"Expected map type, got $t", ident.sourceIndex)
          }
      }
    }
    def checkArgTypes(state: Compiler.State[StatefulContext], expected: Seq[Type]): Unit = {
      if (args.length != expected.length) {
        throw Compiler.Error(
          s"Invalid args length, expected ${expected.length}, got ${args.length}",
          sourceIndex
        )
      }
      val argTypes = args.flatMap(_.getType(state))
      if (argTypes != expected) {
        throw Compiler.Error(s"Invalid args type $argTypes, expected $expected", sourceIndex)
      }
    }
    override def reset(): Unit = mapType = None
  }

  private def genMapDebug(
      state: Compiler.State[StatefulContext],
      pathCodes: Seq[Instr[StatefulContext]],
      isInsert: Boolean
  ): Seq[Instr[StatefulContext]] = {
    if (state.allowDebug) {
      val operation   = if (isInsert) "insert" else "remove"
      val message     = s"$operation at map path: "
      val stringParts = AVector(ByteString.fromString(message), ByteString.empty)
      pathCodes ++ Seq[Instr[StatefulContext]](Dup, DEBUG(stringParts.map(Val.ByteVec.apply)))
    } else {
      pathCodes
    }
  }

  final case class InsertToMap(
      ident: Ident,
      args: Seq[Expr[StatefulContext]]
  ) extends MapFuncCall {
    def check(state: Compiler.State[StatefulContext]): Unit = {
      val mapType = getMapType(state)
      checkArgTypes(state, Seq(Type.Address, mapType.key, mapType.value))
    }
    private def checkFieldLength(length: Int): Unit = {
      if (length > 0xff) {
        throw Compiler.Error(
          s"The number of struct fields exceeds the maximum limit",
          args(2).sourceIndex
        )
      }
    }
    private def genCreateContract(
        state: Compiler.State[StatefulContext]
    ): Seq[Instr[StatefulContext]] = {
      val mapType          = getMapType(state)
      val fieldsMutability = state.flattenTypeMutability(mapType.value, isMutable = true)
      val mutFieldLength   = fieldsMutability.count(identity)
      val immFieldLength   = fieldsMutability.length - mutFieldLength + 1 // parent contract id
      checkFieldLength(mutFieldLength)
      checkFieldLength(immFieldLength)

      val pathCodes              = MapOps.genSubContractPath(state, ident, args(1))
      val (immFields, mutFields) = state.genFieldsInitCodes(fieldsMutability, Seq(args(2)))
      val insertWithDebug        = genMapDebug(state, pathCodes, isInsert = true)
      insertWithDebug ++ (immFields :+ SelfContractId) ++
        mutFields :+ CreateMapEntry(immFieldLength.toByte, mutFieldLength.toByte)
    }
    def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      val approveALPHCodes    = args(0).genCode(state) ++ Seq(MinimalContractDeposit, ApproveAlph)
      val createContractCodes = genCreateContract(state)
      approveALPHCodes ++ createContractCodes
    }
    override def reset(): Unit = {
      args.foreach(_.reset())
      super.reset()
    }
  }

  final case class RemoveFromMap(ident: Ident, args: Seq[Expr[StatefulContext]])
      extends MapFuncCall {
    def check(state: Compiler.State[StatefulContext]): Unit = {
      val mapType = getMapType(state)
      checkArgTypes(state, Seq(Type.Address, mapType.key))
    }
    def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      val pathCodes = MapOps.genSubContractPath(state, ident, args(1))
      val objCodes  = genMapDebug(state, pathCodes, isInsert = false) :+ SubContractId
      args(0).genCode(state) ++ Seq(
        ConstInstr.u256(Val.U256(U256.One)), // the `address` parameter
        ConstInstr.u256(Val.U256(U256.Zero))
      ) ++ objCodes :+ CallExternal(CreateMapEntry.DestroyMethodIndex)
    }
    override def reset(): Unit = {
      args.foreach(_.reset())
      super.reset()
    }
  }

  sealed trait VarDeclaration                               extends Positioned
  final case class NamedVar(mutable: Boolean, ident: Ident) extends VarDeclaration
  final case class AnonymousVar()                           extends VarDeclaration

  final case class VarDef[Ctx <: StatelessContext](
      vars: Seq[VarDeclaration],
      value: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val types = value.getType(state)
      if (types.length != vars.length) {
        throw Compiler.Error(
          s"Invalid variable declaration, expected ${types.length} variables, got ${vars.length} variables",
          sourceIndex
        )
      }
      vars.zip(types).foreach {
        case (NamedVar(isMutable, ident), tpe) =>
          if (tpe.isMapType) {
            throw Compiler
              .Error(s"Cannot define local map variable ${ident.name}", ident.sourceIndex)
          }
          state.addLocalVariable(ident, tpe, isMutable, isUnused = false, isGenerated = false)
        case _ =>
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val storeCodes = vars.zip(value.getType(state)).flatMap {
        case (NamedVar(_, ident), _) => state.genStoreCode(ident)
        case (_: AnonymousVar, tpe) =>
          Seq(Seq.fill(state.flattenTypeLength(Seq(tpe)))(Pop))
      }
      value.genCode(state) ++ storeCodes.reverse.flatten
    }
    def reset(): Unit = value.reset()
  }

  trait UniqueDef extends Positioned {
    def name: String
  }

  object UniqueDef {
    def checkDuplicates(defs: Seq[UniqueDef], name: String): Unit = {
      if (defs.distinctBy(_.name).size != defs.size) {
        val (dups, sourceIndex) = duplicates(defs)
        throw Compiler.Error(
          s"These $name are defined multiple times: ${dups}",
          sourceIndex
        )
      }
    }

    def duplicates(defs: Seq[UniqueDef]): (String, Option[SourceIndex]) = {
      val dups = defs
        .groupBy(_.name)
        .filter(_._2.size > 1)
      val sourceIndex = dups.values.headOption.flatMap(_.drop(1).headOption.flatMap(_.sourceIndex))

      (dups.keys.mkString(", "), sourceIndex)
    }
  }

  final case class FuncSignature(
      id: FuncId,
      isPublic: Boolean,
      usePreapprovedAssets: Boolean,
      inline: Boolean,
      args: Seq[(Type, Boolean)],
      rtypes: Seq[Type]
  )

  final case class FuncDef[Ctx <: StatelessContext](
      annotations: Seq[Annotation[Ctx]],
      id: FuncId,
      isPublic: Boolean,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Ast.ContractAssetsAnnotation,
      usePayToContractOnly: Boolean,
      useCheckExternalCaller: Boolean,
      useUpdateFields: Boolean,
      useMethodIndex: Option[Int],
      inline: Boolean,
      args: Seq[Argument],
      rtypes: Seq[Type],
      bodyOpt: Option[Seq[Statement[Ctx]]]
  ) extends UniqueDef
      with OriginContractInfo {
    def name: String              = id.name
    def isPrivate: Boolean        = !isPublic
    val body: Seq[Statement[Ctx]] = bodyOpt.getOrElse(Seq.empty)

    private var funcAccessedVarsCache: Option[Set[Compiler.AccessVariable]] = None

    private[ralph] var methodSelector: Option[Method.Selector] = None
    def getMethodSelector(globalState: GlobalState[_]): Method.Selector = {
      methodSelector match {
        case Some(selector) => selector
        case None =>
          val argTypes = args.view
            .flatMap(arg => globalState.flattenType(arg.tpe))
            .map(_.signature)
            .mkString(",")
          val retTypes = rtypes.view.flatMap(globalState.flattenType).map(_.signature).mkString(",")
          val bytes    = ByteString.fromString(s"$name($argTypes)->($retTypes)")
          val selector = Method.Selector(DjbHash.intHash(bytes))
          methodSelector = Some(selector)
          selector
      }
    }

    def hasCheckExternalCallerAnnotation: Boolean = {
      annotations.find(_.id.name == FunctionUsingAnnotation.id) match {
        case Some(usingAnnotation) =>
          usingAnnotation.fields.exists(
            _.ident.name == FunctionUsingAnnotation.useCheckExternalCallerKey
          )
        case None => false
      }
    }

    @inline private def isUpdateMap(state: Compiler.State[Ctx]): Boolean = {
      body.exists {
        case _: InsertToMap | _: RemoveFromMap => true
        case Assign(targets, _) =>
          targets.exists {
            case AssignmentSelectedTarget(ident, _) => state.hasMapVar(ident)
            case _                                  => false
          }
        case _ => false
      }
    }

    def isSimpleViewFunc(state: Compiler.State[Ctx]): Boolean = {
      val hasInterfaceFuncCall = state.hasInterfaceFuncCallSet.contains(id)
      val hasMigrateSimple = body.exists {
        case FuncCall(id, _, _) => id.isBuiltIn && id.name == "migrate"
        case _                  => false
      }
      !(useUpdateFields
        || usePreapprovedAssets
        || useAssetsInContract != Ast.NotUseContractAssets
        || hasInterfaceFuncCall
        || hasMigrateSimple
        || isUpdateMap(state))
    }

    lazy val signature: FuncSignature = FuncSignature(
      id,
      isPublic,
      usePreapprovedAssets,
      inline,
      args.map(arg => (arg.tpe, arg.isMutable)),
      rtypes
    )
    def getArgNames(): AVector[String]          = AVector.from(args.view.map(_.ident.name))
    def getArgTypeSignatures(): AVector[String] = AVector.from(args.view.map(_.tpe.signature))
    def getArgMutability(): AVector[Boolean]    = AVector.from(args.view.map(_.isMutable))
    def getReturnSignatures(): AVector[String]  = AVector.from(rtypes.view.map(_.signature))

    def hasDirectCheckExternalCaller(): Boolean = {
      !useCheckExternalCaller || // check external caller manually disabled
      body.exists {
        case FuncCall(id, _, _) => id.isBuiltIn && id.name == "checkCaller"
        case _                  => false
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def checkRetTypes(stmt: Option[Statement[Ctx]]): Unit = {
      stmt match {
        case Some(_: ReturnStmt[Ctx]) => () // we checked the `rtypes` in `ReturnStmt`
        case Some(IfElseStatement(ifBranches, elseBranchOpt)) =>
          ifBranches.foreach(branch => checkRetTypes(branch.body.lastOption))
          checkRetTypes(elseBranchOpt.flatMap(_.body.lastOption))
        case Some(call: FuncCall[_]) if call.id.name == "panic" && call.id.isBuiltIn == true => ()
        case _ =>
          throw Compiler.Error(
            s"Expected return statement for function ${quote(id.name)}",
            id.sourceIndex
          )
      }
    }

    @inline private def checkInline(): Unit = {
      if (isPublic) {
        throw Compiler.Error("Inline functions cannot be public", id.sourceIndex)
      }
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.setFuncScope(id)
      state.checkArguments(args)
      args.foreach { arg =>
        val argTpe = state.resolveType(arg.tpe)
        state.addLocalVariable(
          arg.ident,
          argTpe,
          arg.isMutable,
          arg.isUnused,
          isGenerated = false
        )
      }
      if (inline) checkInline()
      if (bodyOpt.isDefined) {
        funcAccessedVarsCache match {
          case Some(vars) => // the function has been compiled before
            state.addAccessedVars(vars)
            body.foreach(_.check(state))
          case None =>
            body.foreach(_.check(state))
            val currentScopeUsedVars = Set.from(state.currentScopeAccessedVars)
            funcAccessedVarsCache = Some(currentScopeUsedVars)
            state.addAccessedVars(currentScopeUsedVars)
        }
        state.checkUnusedLocalVars(id)
        state.checkUnassignedLocalMutableVars(id)
        if (rtypes.nonEmpty) checkRetTypes(body.lastOption)
      }
    }

    def genMethod(state: Compiler.State[Ctx]): Method[Ctx] = {
      state.setFuncScope(id)
      val instrs       = body.flatMap(_.genCode(state))
      val localVarSize = state.getLocalVarSize(id)

      Method[Ctx](
        isPublic,
        usePreapprovedAssets,
        useAssetsInContract != Ast.NotUseContractAssets,
        usePayToContractOnly = usePayToContractOnly,
        argsLength = state.flattenTypeLength(args.map(_.tpe)),
        localsLength = localVarSize,
        returnLength = state.flattenTypeLength(rtypes),
        AVector.from(instrs)
      )
    }

    def reset(): Unit = {
      funcAccessedVarsCache = None
      methodSelector = None
      body.foreach(_.reset())
    }
  }

  object FuncDef {
    def main(
        stmts: Seq[Ast.Statement[StatefulContext]],
        usePreapprovedAssets: Boolean,
        useAssetsInContract: Ast.ContractAssetsAnnotation,
        useUpdateFields: Boolean
    ): FuncDef[StatefulContext] = {
      FuncDef[StatefulContext](
        Seq.empty,
        id = FuncId("main", false),
        isPublic = true,
        usePreapprovedAssets = usePreapprovedAssets,
        useAssetsInContract = useAssetsInContract,
        usePayToContractOnly = false,
        useCheckExternalCaller = true,
        useUpdateFields = useUpdateFields,
        useMethodIndex = None,
        inline = false,
        args = Seq.empty,
        rtypes = Seq.empty,
        bodyOpt = Some(stmts)
      )
    }
  }

  final case class StructFieldAlias(isMutable: Boolean, ident: Ident, alias: Option[Ident])

  final case class StructDestruction[Ctx <: StatelessContext](
      id: TypeId,
      vars: Seq[StructFieldAlias],
      expr: Expr[Ctx]
  ) extends Statement[Ctx] {
    def check(state: Compiler.State[Ctx]): Unit = {
      val struct = expr.getType(state) match {
        case Seq(tpe: Type.Struct) if tpe.id == id => state.getStruct(id)
        case types =>
          throw Compiler.Error(
            s"Expected struct type ${quote(id.name)}, got ${quoteTypes(types)}",
            expr.sourceIndex
          )
      }
      vars.foreach { v =>
        val fieldType = state.resolveType(struct.getField(v.ident).tpe)
        val varIdent  = v.alias.getOrElse(v.ident)
        state.addLocalVariable(
          varIdent,
          fieldType,
          v.isMutable,
          isUnused = false,
          isGenerated = false
        )
      }
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val (structRef, instrs) = state.getOrCreateVariablesRef(expr)
      instrs ++ vars.flatMap { v =>
        val varIdent   = v.alias.getOrElse(v.ident)
        val loadCodes  = structRef.genLoadCode(state, IdentSelector(v.ident))
        val storeCodes = state.genStoreCode(varIdent).reverse.flatten
        loadCodes ++ storeCodes
      }
    }
    def reset(): Unit = expr.reset()
  }

  sealed trait AssignmentTarget[Ctx <: StatelessContext] extends Typed[Ctx, Type] {
    def ident: Ident

    def checkMutable(state: Compiler.State[Ctx], sourceIndex: Option[SourceIndex]): Unit
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]]
  }
  final case class AssignmentSimpleTarget[Ctx <: StatelessContext](ident: Ident)
      extends AssignmentTarget[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Type = {
      val variable = state.getVariable(ident, isWrite = true)
      state.resolveType(variable.tpe)
    }
    def checkMutable(state: Compiler.State[Ctx], sourceIndex: Option[SourceIndex]): Unit = {
      val variable = state.getVariable(ident)
      variable match {
        case _: Compiler.VarInfo.MapVar =>
          throw Compiler.Error(s"Cannot assign to map variable ${ident.name}.", sourceIndex)
        case _ =>
      }
      if (!variable.isMutable) {
        throw Compiler.Error(s"Cannot assign to immutable variable ${ident.name}.", sourceIndex)
      }
      if (!state.isTypeMutable(getType(state))) {
        throw Compiler.Error(
          s"Cannot assign to variable ${ident.name}. Assignment only works when all of the (nested) fields are mutable.",
          sourceIndex
        )
      }
    }
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = state.genStoreCode(ident)
  }
  sealed trait DataSelector extends Positioned {
    def reset(): Unit = this match {
      case IndexSelector(expr) => expr.reset()
      case _: IdentSelector    => ()
    }
  }
  final case class IndexSelector[Ctx <: StatelessContext](index: Expr[Ctx]) extends DataSelector
  final case class IdentSelector(ident: Ident)                              extends DataSelector
  final case class AssignmentSelectedTarget[Ctx <: StatelessContext](
      ident: Ident,
      selectors: Seq[DataSelector]
  ) extends AssignmentTarget[Ctx]
      with AccessDataT[Ctx] {
    // scalastyle:off method.length
    private def checkMap(
        state: Compiler.State[Ctx],
        mapType: Type.Map,
        selectors: Seq[DataSelector],
        sourceIndex: Option[SourceIndex]
    ): Unit = {
      if (selectors.isEmpty) {
        if (!state.isTypeMutable(mapType.value)) {
          throw Compiler.Error(
            s"Cannot assign to value in map ${quote(ident.name)}. Assignment only works when all of the (nested) fields are mutable.",
            sourceIndex
          )
        }
      } else {
        checkMutable(
          state,
          mapType.value,
          selectors,
          Ident(s"${ident.name}[${mapType.key.signature}]"),
          None,
          sourceIndex
        )
      }
    }

    @scala.annotation.tailrec
    private def checkMutable(
        state: Compiler.State[Ctx],
        rootType: Type,
        selectors: Seq[DataSelector],
        lastField: Ident,
        structId: Option[TypeId],
        sourceIndex: Option[SourceIndex]
    ): Unit = {
      (rootType, selectors) match {
        case (array: Type.FixedSizeArray, Seq(IndexSelector(_))) =>
          if (!state.isTypeMutable(array.baseType)) {
            val arraySelector =
              structId.map(id => s"${id.name}.${lastField.name}").getOrElse(lastField.name)
            throw Compiler.Error(
              s"Cannot assign to immutable element in array $arraySelector. Assignment only works when all of the (nested) fields are mutable.",
              sourceIndex
            )
          }
        case (array: Type.FixedSizeArray, IndexSelector(_) +: tail) =>
          checkMutable(state, array.baseType, tail, lastField, structId, sourceIndex)
        case (map: Type.Map, (_: IndexSelector[Ctx @unchecked]) +: tail) =>
          checkMap(state, map, tail, sourceIndex)
        case (struct: Type.Struct, Seq(IdentSelector(ident))) =>
          val field = state.getStruct(struct.id).getField(ident)
          if (!field.isMutable) {
            throw Compiler.Error(
              s"Cannot assign to immutable field ${field.name} in struct ${struct.id.name}.",
              sourceIndex
            )
          }
          if (!state.isTypeMutable(field.tpe)) {
            throw Compiler.Error(
              s"Cannot assign to field ${field.name} in struct ${struct.id.name}. Assignment only works when all of the (nested) fields are mutable.",
              sourceIndex
            )
          }
        case (struct: Type.Struct, IdentSelector(ident) +: tail) =>
          val field = state.getStruct(struct.id).getField(ident)
          if (!field.isMutable) {
            throw Compiler.Error(
              s"Cannot assign to immutable field ${field.name} in struct ${struct.id.name}.",
              sourceIndex
            )
          }
          val fieldType = state.resolveType(field.tpe)
          checkMutable(state, fieldType, tail, field.ident, Some(struct.id), sourceIndex)
        case _ => // dead branch
          throw Compiler.Error(s"Invalid selectors ${selectors} for type $rootType", sourceIndex)
      }
    }
    // scalastyle:on method.length

    def _getType(state: Compiler.State[Ctx]): Type = {
      val variable = state.getVariable(ident, isWrite = true)
      _getType(state, state.resolveType(variable.tpe), ident.sourceIndex)
    }
    def checkMutable(state: Compiler.State[Ctx], sourceIndex: Option[SourceIndex]): Unit = {
      val variable = state.getVariable(ident)
      if (!variable.isMutable) {
        throw Compiler.Error(s"Cannot assign to immutable variable ${ident.name}.", sourceIndex)
      }
      checkMutable(state, state.resolveType(variable.tpe), selectors, ident, None, sourceIndex)
    }
    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
      val variable = state.getVariable(ident)
      variable.tpe match {
        case map: Type.Map =>
          val pathCodes = MapOps.genSubContractPath(state, ident, mapKeyIndex)
          MapOps.genStore(state, map.value, getType(state), pathCodes, selectors.tail)
        case _ =>
          val ref    = state.getVariablesRef(ident)
          val subRef = ref.subRef(state, selectors.init)
          subRef.genStoreCode(state, selectors.last)
      }
    }
    override def reset(): Unit = {
      selectors.foreach(_.reset())
      super.reset()
    }
  }

  trait OriginContractInfo {
    private var originContractId: Option[TypeId] = None
    def withOrigin(typeId: TypeId): this.type = {
      originContractId = Some(typeId)
      this
    }
    def origin: Option[TypeId]             = originContractId
    def definedIn(typeId: TypeId): Boolean = origin.contains(typeId)
  }

  sealed trait ConstantDefinition extends OriginContractInfo {
    def ident: Ident
    def name: String = ident.name
  }

  final case class ConstantVarDef[Ctx <: StatelessContext](
      ident: Ident,
      expr: Expr[Ctx]
  ) extends GlobalDefinition
      with ConstantDefinition

  final case class RawEnumField[Ctx <: StatelessContext](ident: Ident, value: Option[Const[Ctx]])
      extends UniqueDef
      with ConstantDefinition {
    def validateAsFirstField(): EnumField[Ctx] = {
      value match {
        case Some(v) =>
          EnumField(ident, v).atSourceIndex(sourceIndex)
        case None =>
          throw Compiler.Error(
            s"Enum field ${ident.name} must have explicit value",
            ident.sourceIndex
          )
      }
    }

    def validate(enumName: String, firstFieldVal: Val): Unit = {
      value match {
        case Some(v) if v.v.tpe != firstFieldVal.tpe =>
          throw Compiler.Error(
            s"Fields have different types in Enum $enumName",
            ident.sourceIndex
          )
        case None if firstFieldVal.tpe != Val.U256 =>
          throw Compiler.Error(
            s"Enum field ${ident.name} must have explicit value",
            ident.sourceIndex
          )
        case _ => ()
      }
    }
  }

  final case class EnumField[Ctx <: StatelessContext](ident: Ident, value: Const[Ctx])
      extends UniqueDef
      with ConstantDefinition

  final case class EnumDef[Ctx <: StatelessContext](id: TypeId, fields: Seq[EnumField[Ctx]])
      extends GlobalDefinition {
    def name: String = id.name
  }
  object EnumDef {
    def fieldIdent(enumId: TypeId, field: Ident): Ident =
      Ident(s"${enumId.name}.${field.name}").atSourceIndex(field.sourceIndex)
  }

  final case class EventDef(
      id: TypeId,
      fields: Seq[EventField]
  ) extends UniqueDef {
    def name: String = id.name

    def signature: String = s"event ${id.name}(${fields.map(_.signature).mkString(",")})"

    def getFieldNames(): AVector[String]          = AVector.from(fields.view.map(_.ident.name))
    def getFieldTypeSignatures(): AVector[String] = AVector.from(fields.view.map(_.tpe.signature))
  }

  final case class EmitEvent[Ctx <: StatefulContext](id: TypeId, args: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val eventInfo = state.getEvent(id)
      val argsType  = args.flatMap(_.getType(state))
      if (argsType.exists(t => t.isArrayType || t.isStructType)) {
        throw Compiler.Error(
          s"Array and struct types are not supported for event ${quote(s"${state.typeId.name}.${id.name}")}",
          sourceIndex
        )
      }
      eventInfo.checkFieldTypes(state, argsType, args.headOption.flatMap(_.sourceIndex))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val eventIndex = {
        val index = state.eventsInfo.map(_.typeId).indexOf(id)
        // `check` method ensures that this event is defined
        assume(index >= 0)

        Const[Ctx](Val.I256(I256.from(index))).genCode(state)
      }
      val logOpCode = Compiler.genLogs(args.length, id.sourceIndex)
      eventIndex ++ args.flatMap(_.genCode(state)) :+ logOpCode
    }
    def reset(): Unit = args.foreach(_.reset())
  }

  final case class Assign[Ctx <: StatelessContext](
      targets: Seq[AssignmentTarget[Ctx]],
      rhs: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val leftTypes  = targets.map(_.getType(state))
      val rightTypes = rhs.getType(state)
      if (leftTypes != rightTypes) {
        throw Compiler.Error(
          s"Cannot assign ${quoteTypes(rightTypes)} to ${quoteTypes(leftTypes)}",
          sourceIndex
        )
      }
      targets.foreach(_.checkMutable(state, sourceIndex))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      rhs.genCode(state) ++ targets.flatMap(_.genStore(state)).reverse.flatten
    }
    def reset(): Unit = {
      targets.foreach(_.reset())
      rhs.reset()
    }
  }
  sealed trait CallStatement[Ctx <: StatelessContext] extends Statement[Ctx] {
    def checkReturnValueUsed(
        state: Compiler.State[Ctx],
        typeId: TypeId,
        funcId: FuncId,
        retTypes: Seq[Type]
    ): Unit = {
      if (retTypes.nonEmpty && retTypes != Seq(Type.Panic)) {
        state.warningUnusedCallReturn(typeId, funcId, retTypes.length)
      }
    }
  }
  final case class FuncCall[Ctx <: StatelessContext](
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends CallStatement[Ctx]
      with CallAst[Ctx] {
    def ignoreReturn: Boolean = true

    def getFunc(state: Compiler.State[Ctx]): Compiler.FuncInfo[Ctx] = state.getFunc(id)

    override def check(state: Compiler.State[Ctx]): Unit = {
      checkApproveAssets(state)
      val funcInfo = getFunc(state)
      val retTypes = positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
      checkReturnValueUsed(state, state.typeId, id, retTypes)
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.addInternalCall(
        id
      ) // don't put this in _getType, otherwise the statement might get skipped
      _genCode(state)
    }
    def reset(): Unit = {
      approveAssets.foreach(_.reset())
      args.foreach(_.reset())
    }
  }
  final case class StaticContractFuncCall[Ctx <: StatelessContext](
      contractId: TypeId,
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends CallStatement[Ctx]
      with CallAst[Ctx] {
    def ignoreReturn: Boolean = true

    def getFunc(state: Compiler.State[Ctx]): Compiler.ContractFunc[Ctx] =
      state.getFunc(contractId, id)

    override def check(state: Compiler.State[Ctx]): Unit = {
      checkApproveAssets(state)
      val funcInfo = getFunc(state)
      checkStaticContractFunction(contractId, id, funcInfo)
      val retTypes = positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
      checkReturnValueUsed(state, contractId, id, retTypes)
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      _genCode(state)
    }
    def reset(): Unit = {
      approveAssets.foreach(_.reset())
      args.foreach(_.reset())
    }
  }
  final case class ContractCall(
      obj: Expr[StatefulContext],
      callId: FuncId,
      approveAssets: Seq[ApproveAsset[StatefulContext]],
      args: Seq[Expr[StatefulContext]]
  ) extends CallStatement[StatefulContext]
      with ContractCallBase {
    override def check(state: Compiler.State[StatefulContext]): Unit = {
      checkApproveAssets(state)
      val (contractId, retTypes) = _getTypeBase(state)
      checkReturnValueUsed(state, contractId, callId, retTypes)
    }

    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      genContractCall(state, true)
    }
    def reset(): Unit = {
      obj.reset()
      approveAssets.foreach(_.reset())
      args.foreach(_.reset())
    }
  }

  final case class IfBranchStatement[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      body: Seq[Statement[Ctx]]
  ) extends IfBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = body.flatMap(_.genCode(state))
    def reset(): Unit = {
      condition.reset()
      body.foreach(_.reset())
    }
  }
  final case class ElseBranchStatement[Ctx <: StatelessContext](
      body: Seq[Statement[Ctx]]
  ) extends ElseBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = body.flatMap(_.genCode(state))
    def reset(): Unit                                        = body.foreach(_.reset())
  }
  final case class IfElseStatement[Ctx <: StatelessContext](
      ifBranches: Seq[IfBranchStatement[Ctx]],
      elseBranchOpt: Option[ElseBranchStatement[Ctx]]
  ) extends IfElse[Ctx]
      with Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      ifBranches.foreach(_.checkCondition(state))
      ifBranches.foreach { ifBranch =>
        state.withScope(ifBranch) { ifBranch.body.foreach(_.check(state)) }
      }
      elseBranchOpt.foreach { elseBranch =>
        state.withScope(elseBranch) { elseBranch.body.foreach(_.check(state)) }
      }
    }
    def reset(): Unit = {
      ifBranches.foreach(_.reset())
      elseBranchOpt.foreach(_.reset())
    }
  }
  final case class While[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      body: Seq[Statement[Ctx]]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = state.withScope(this) {
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of conditional expr ${quote(condition)}", sourceIndex)
      }
      body.foreach(_.check(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = state.withScope(this) {
      val bodyIR   = body.flatMap(_.genCode(state))
      val condIR   = Statement.getCondIR(condition, state, bodyIR.length + 1)
      val whileLen = condIR.length + bodyIR.length + 1
      if (whileLen > 0xff) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instructions for if-else branches", sourceIndex)
      }
      condIR ++ bodyIR :+ Jump(-whileLen)
    }
    def reset(): Unit = {
      condition.reset()
      body.foreach(_.reset())
    }
  }
  final case class ForLoop[Ctx <: StatelessContext](
      initialize: Statement[Ctx],
      condition: Expr[Ctx],
      update: Statement[Ctx],
      body: Seq[Statement[Ctx]]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = state.withScope(this) {
      initialize.check(state)
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid condition type: $condition", sourceIndex)
      }
      update.check(state)
      body.foreach(_.check(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = state.withScope(this) {
      val initializeIR   = initialize.genCode(state)
      val bodyIR         = body.flatMap(_.genCode(state))
      val updateIR       = update.genCode(state)
      val fullBodyLength = bodyIR.length + updateIR.length + 1
      val condIR         = Statement.getCondIR(condition, state, fullBodyLength)
      val jumpLength     = condIR.length + fullBodyLength
      initializeIR ++ condIR ++ bodyIR ++ updateIR :+ Jump(-jumpLength)
    }
    def reset(): Unit = {
      initialize.reset()
      condition.reset()
      update.reset()
      body.foreach(_.reset())
    }
  }
  final case class ReturnStmt[Ctx <: StatelessContext](exprs: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      state.checkReturn(exprs.flatMap(_.getType(state)), sourceIndex)
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      exprs.flatMap(_.genCode(state)) :+ Return
    def reset(): Unit = exprs.foreach(_.reset())
  }

  final case class Debug[Ctx <: StatelessContext](
      stringParts: AVector[Val.ByteVec],
      interpolationParts: Seq[Expr[Ctx]]
  ) extends Statement[Ctx] {
    def check(state: Compiler.State[Ctx]): Unit = {
      interpolationParts.foreach(_.getType(state))
    }

    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      if (state.allowDebug) {
        interpolationParts.flatMap(_.genCode(state)) :+
          vm.DEBUG(stringParts)
      } else {
        Seq.empty
      }
    }
    def reset(): Unit = interpolationParts.foreach(_.reset())
  }

  object TemplateVar {
    private val arraySuffix  = "-template-array"
    private val structSuffix = "-template-struct"

    @inline private[ralph] def rename(ident: Ident, tpe: Type): Ident = {
      tpe match {
        case _: Type.FixedSizeArray => Ident(s"_${ident.name}$arraySuffix")
        case _: Type.Struct         => Ident(s"_${ident.name}$structSuffix")
        case _                      => ident
      }
    }
  }

  final case class GlobalState[Ctx <: StatelessContext](
      structs: Seq[Struct],
      constantVars: Seq[Ast.ConstantVarDef[Ctx]],
      enums: Seq[Ast.EnumDef[Ctx]]
  ) extends Constants[Ctx] {
    private[ralph] val constants = mutable.Map.empty[Ast.Ident, Compiler.VarInfo.Constant[Ctx]]
    private val usedConstants: mutable.Set[Ast.Ident] = mutable.Set.empty

    def getCalculatedConstants(): Seq[(Ident, Val)] = {
      constantVars.map(c => (c.ident, constants(c.ident).value))
    }

    @inline def getConstantOpt(ident: Ident): Option[Compiler.VarInfo.Constant[Ctx]] = {
      usedConstants.addOne(ident)
      constants.get(ident)
    }

    def getUnusedGlobalConstantsWarning(): AVector[Warning] = {
      val unused = mutable.ArrayBuffer.empty[(String, Positioned)]
      constantVars.foreach { c =>
        if (!usedConstants.contains(c.ident)) unused.addOne((c.name, c))
      }
      enums.foreach(e =>
        e.fields.foreach { field =>
          val fieldIdent = EnumDef.fieldIdent(e.id, field.ident)
          if (!usedConstants.contains(fieldIdent)) unused.addOne((fieldIdent.name, fieldIdent))
        }
      )
      Warnings.unusedGlobalConstants(unused)
    }

    def getConstant(ident: Ident): Compiler.VarInfo.Constant[Ctx] = {
      getConstantOpt(ident) match {
        case Some(v: Compiler.VarInfo.Constant[Ctx @unchecked]) => v
        case _ =>
          throw Compiler.Error(
            s"Constant variable ${ident.name} does not exist or is used before declaration",
            ident.sourceIndex
          )
      }
    }
    def addConstant(ident: Ident, value: Val, constantDef: Ast.ConstantDefinition): Unit = {
      val tpe = Type.fromVal(value.tpe)
      constants(ident) =
        Compiler.VarInfo.Constant(ident, tpe, value, Seq(value.toConstInstr), constantDef)
    }

    private val flattenSizeCache = mutable.Map.empty[Type, Int]
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def flattenSize(tpe: Type, accessedTypes: Seq[TypeId]): Int = {
      tpe match {
        case Type.NamedType(id) =>
          if (accessedTypes.contains(id)) {
            throw Compiler.Error(
              s"These structs ${quote(accessedTypes.map(_.name))} have circular references",
              id.sourceIndex
            )
          }
          structs.find(_.id == id) match {
            case Some(struct) =>
              struct.fields.map(f => getFlattenSize(f.tpe, accessedTypes :+ id)).sum
            case None => 1
          }
        case t: Type.FixedSizeArray =>
          calcArraySize(t) * flattenSize(t.baseType, accessedTypes)
        case Type.Struct(id) => flattenSize(Type.NamedType(id), accessedTypes)
        case _               => 1
      }
    }

    private def getFlattenSize(tpe: Type, accessedTypes: Seq[TypeId]): Int = {
      flattenSizeCache.get(tpe) match {
        case Some(size) => size
        case None =>
          val size = flattenSize(tpe, accessedTypes)
          flattenSizeCache(tpe) = size
          size
      }
    }

    private val typeCache: mutable.Map[Type, Type] = mutable.Map.empty
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def _resolveType(tpe: Type): Type = {
      tpe match {
        case t: Type.NamedType =>
          structs.find(_.id == t.id) match {
            case Some(struct) => struct.tpe
            case None         => Type.Contract(t.id)
          }
        case t: Type.FixedSizeArray =>
          Type.FixedSizeArray(resolveType(t.baseType), Left(calcArraySize(t)))
        case Type.Map(key, value) =>
          Type.Map(resolveType(key), resolveType(value))
        case _ => tpe
      }
    }

    @inline def resolveType(tpe: Type): Type = {
      tpe match {
        case _: Type.NamedType | _: Type.FixedSizeArray | _: Type.Map =>
          typeCache.get(tpe) match {
            case Some(tpe) => tpe
            case None =>
              val resolvedType = _resolveType(tpe)
              typeCache.update(tpe, resolvedType)
              resolvedType
          }
        case _ => tpe
      }
    }

    @inline def resolveTypes(types: Seq[Type]): Seq[Type] = types.map(resolveType)

    def flattenTypeLength(types: Seq[Type]): Int = {
      types.foldLeft(0) { case (acc, tpe) =>
        tpe match {
          case _: Type.FixedSizeArray | _: Type.NamedType | _: Type.Struct =>
            acc + getFlattenSize(tpe, Seq.empty)
          case _ => acc + 1
        }
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def flattenType(tpe: Type): Seq[Type] = {
      resolveType(tpe) match {
        case Type.Struct(id) =>
          getStruct(id).fields.flatMap(field => flattenType(field.tpe))
        case t: Type.FixedSizeArray =>
          val baseTypes = flattenType(t.baseType)
          Seq.fill(calcArraySize(t))(baseTypes).flatten
        case tpe => Seq(tpe)
      }
    }

    def getStruct(typeId: Ast.TypeId): Ast.Struct = {
      structs.find(_.id == typeId) match {
        case Some(struct) => struct
        case None =>
          throw Compiler.Error(s"Struct ${quote(typeId.name)} does not exist", typeId.sourceIndex)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def flattenTypeMutability(tpe: Type, isMutable: Boolean): Seq[Boolean] = {
      val resolvedType = resolveType(tpe)
      if (isMutable) {
        resolvedType match {
          case t: Type.FixedSizeArray =>
            val array = flattenTypeMutability(t.baseType, isMutable)
            Seq.fill(calcArraySize(t))(array).flatten
          case Type.Struct(id) =>
            getStruct(id).fields.flatMap(field =>
              flattenTypeMutability(resolveType(field.tpe), field.isMutable && isMutable)
            )
          case _ => Seq(isMutable)
        }
      } else {
        Seq.fill(flattenTypeLength(Seq(resolvedType)))(false)
      }
    }
  }

  object GlobalState {
    def from[Ctx <: StatelessContext](definitions: Seq[GlobalDefinition]): GlobalState[Ctx] = {
      val structs      = mutable.ArrayBuffer.empty[Ast.Struct]
      val constantVars = mutable.ArrayBuffer.empty[Ast.ConstantVarDef[Ctx]]
      val enums        = mutable.ArrayBuffer.empty[Ast.EnumDef[Ctx]]
      definitions.foreach {
        case s: Ast.Struct                         => structs.addOne(s)
        case c: Ast.ConstantVarDef[Ctx @unchecked] => constantVars.addOne(c)
        case e: Ast.EnumDef[Ctx @unchecked]        => enums.addOne(e)
        case d => throw Compiler.Error(s"Invalid global definition: ${d.name}", d.sourceIndex)
      }
      val globalState = GlobalState[Ctx](structs.toSeq, constantVars.toSeq, enums.toSeq)
      globalState.addConstants(globalState.constantVars)
      globalState.addEnums(globalState.enums)
      globalState.structs.foreach(_.fields.foreach(_.tpe match {
        case t: Type.FixedSizeArray => globalState.calcArraySize(t); ()
        case _                      => ()
      }))
      globalState
    }
  }

  sealed trait ContractT[Ctx <: StatelessContext] extends GlobalDefinition {
    def ident: TypeId
    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def funcs: Seq[FuncDef[Ctx]]
    lazy val nonInlineFuncs: Seq[FuncDef[Ctx]] = funcs.filterNot(_.inline)
    lazy val inlineFuncs: Seq[FuncDef[Ctx]]    = funcs.filter(_.inline)
    lazy val orderedFuncs: Seq[FuncDef[Ctx]]   = nonInlineFuncs ++ inlineFuncs

    def name: String = ident.name

    def builtInContractFuncs(globalState: GlobalState[Ctx]): Seq[Compiler.ContractFunc[Ctx]]

    private var functionTable: Option[Map[FuncId, Compiler.ContractFunc[Ctx]]] = None

    def funcTable(globalState: GlobalState[Ctx]): Map[FuncId, Compiler.ContractFunc[Ctx]] = {
      functionTable match {
        case Some(funcs) => funcs
        case None =>
          val builtInFuncs = builtInContractFuncs(globalState)
          val isInterface = this match {
            case _: ContractInterface => true
            case _                    => false
          }
          var table = Compiler.SimpleFunc
            .from(funcs, isInterface)
            .map(f => f.funcDef.id -> f)
            .toMap[FuncId, Compiler.ContractFunc[Ctx]]
          builtInFuncs.foreach(func =>
            table = table + (FuncId(func.name, isBuiltIn = true) -> func)
          )
          if (table.size != (funcs.size + builtInFuncs.length)) {
            val (duplicates, sourceIndex) = UniqueDef.duplicates(funcs)
            throw Compiler.Error(
              s"These functions are defined multiple times: $duplicates",
              sourceIndex
            )
          }
          functionTable = Some(table)
          table
      }
    }

    private def addTemplateVars(state: Compiler.State[Ctx]): Unit = {
      templateVars.foreach { templateVar =>
        val tpe   = state.resolveType(templateVar.tpe)
        val ident = TemplateVar.rename(templateVar.ident, tpe)
        state.addTemplateVariable(ident, tpe)
      }
      if (state.templateVarIndex >= Compiler.State.maxVarIndex) {
        throw Compiler.Error(
          s"Number of template variables more than ${Compiler.State.maxVarIndex}",
          ident.sourceIndex
        )
      }
    }

    protected def checkConstants(@nowarn state: Compiler.State[Ctx]): Unit = {}

    private def checkAndAddFields(state: Compiler.State[Ctx]): Unit = {
      fields.foreach { field =>
        state.addFieldVariable(
          field.ident,
          state.resolveType(field.tpe),
          field.isMutable,
          field.isUnused,
          isGenerated = false
        )
      }
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.setCheckPhase()
      state.checkArguments(fields)
      addTemplateVars(state)
      checkAndAddFields(state)
      checkConstants(state)
      funcs.foreach(_.check(state))
      this match {
        case c: Contract if c.isAbstract =>
          // We don't need to check for unused variables in the abstract contract
          // because some of them might be used in the child contract
          ()
        case _ =>
          state.checkUnusedMaps()
          state.checkUnusedFieldsAndConstants()
          state.checkUnassignedMutableFields()
      }
    }

    def genMethodsForNonInlineFuncs(state: Compiler.State[Ctx]): AVector[Method[Ctx]] = {
      AVector.from(nonInlineFuncs.view.map(_.genMethod(state)))
    }

    def genMethods(state: Compiler.State[Ctx]): AVector[Method[Ctx]] = {
      val nonInlineMethods = genMethodsForNonInlineFuncs(state)
      if (state.allowDebug) {
        val inlineMethods = inlineFuncs.map(_.genMethod(state))
        nonInlineMethods ++ AVector.from(inlineMethods)
      } else {
        nonInlineMethods
      }
    }

    def genCode(state: Compiler.State[Ctx]): VmContract[Ctx]
    def reset(): Unit = funcs.foreach(_.reset())
  }

  final case class AssetScript(
      ident: TypeId,
      templateVars: Seq[Argument],
      funcs: Seq[FuncDef[StatelessContext]]
  ) extends ContractT[StatelessContext] {
    val fields: Seq[Argument] = Seq.empty

    def builtInContractFuncs(
        globalState: GlobalState[StatelessContext]
    ): Seq[Compiler.ContractFunc[StatelessContext]] = Seq.empty

    def genCode(state: Compiler.State[StatelessContext]): StatelessScript = {
      state.setGenCodePhase()
      StatelessScript
        .from(genMethodsForNonInlineFuncs(state))
        .getOrElse(
          throw Compiler.Error(s"No methods found in ${quote(ident.name)}", ident.sourceIndex)
        )
    }

    def genCodeFull(state: Compiler.State[StatelessContext]): StatelessScript = {
      check(state)
      val script = genCode(state)
      StaticAnalysis.checkMethodsStateless(this, state)
      script
    }
  }

  sealed trait ContractWithState extends ContractT[StatefulContext] {
    def inheritances: Seq[Inheritance]

    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def maps: Seq[MapDef]
    def events: Seq[EventDef]
    def constantVars: Seq[ConstantVarDef[StatefulContext]]
    def enums: Seq[EnumDef[StatefulContext]]

    def builtInContractFuncs(
        globalState: GlobalState[StatefulContext]
    ): Seq[Compiler.ContractFunc[StatefulContext]] = Seq.empty

    def eventsInfo(): Seq[Compiler.EventInfo] = {
      UniqueDef.checkDuplicates(events, "events")
      events.map { event =>
        Compiler.EventInfo(event.id, event.fields.map(_.tpe))
      }
    }
  }

  final case class TxScript(
      ident: TypeId,
      templateVars: Seq[Argument],
      funcs: Seq[FuncDef[StatefulContext]]
  ) extends ContractWithState {
    val fields: Seq[Argument]                  = Seq.empty
    val events: Seq[EventDef]                  = Seq.empty
    val inheritances: Seq[ContractInheritance] = Seq.empty

    def error(tpe: String): Compiler.Error =
      Compiler.Error(s"TxScript ${ident.name} should not contain any $tpe", sourceIndex)
    def constantVars: Seq[ConstantVarDef[StatefulContext]] = throw error("constant variable")
    def enums: Seq[EnumDef[StatefulContext]]               = throw error("enum")
    def maps: Seq[MapDef]                                  = throw error("map")
    def getTemplateVarsSignature(): String =
      s"TxScript ${name}(${templateVars.map(_.signature).mkString(",")})"
    def getTemplateVarsNames(): AVector[String] = AVector.from(templateVars.view.map(_.ident.name))
    def getTemplateVarsTypes(): AVector[String] =
      AVector.from(templateVars.view.map(_.tpe.signature))
    def getTemplateVarsMutability(): AVector[Boolean] =
      AVector.from(templateVars.view.map(_.isMutable))

    def withTemplateVarDefs(globalState: GlobalState[StatefulContext]): TxScript = {
      val templateVarDefs = templateVars.foldLeft(Seq.empty[Statement[StatefulContext]]) {
        case (acc, arg) =>
          val argType = globalState.resolveType(arg.tpe)
          argType match {
            case _: Type.FixedSizeArray | _: Type.Struct =>
              acc :+ VarDef(
                Seq(NamedVar(mutable = false, arg.ident)),
                Variable(TemplateVar.rename(arg.ident, argType))
              )
            case _ => acc
          }
      }
      val newFuncs =
        funcs.map(func =>
          func
            .copy(bodyOpt = Some(templateVarDefs ++ func.body))
            .withOrigin(ident)
            .atSourceIndex(func.sourceIndex)
        )
      this.copy(funcs = newFuncs)
    }

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genCode(state: Compiler.State[StatefulContext]): StatefulScript = {
      state.setGenCodePhase()
      StatefulScript
        .from(genMethods(state))
        .getOrElse(
          throw Compiler.Error(
            "Expected the 1st function to be public and the other functions to be private for tx script",
            sourceIndex
          )
        )
    }

    def genCodeFull(state: Compiler.State[StatefulContext]): (StatefulScript, StatefulScript) = {
      check(state)
      state.setGenDebugCode()
      val debugCode = genCode(state)
      StaticAnalysis.checkTxScript(this, debugCode, state)
      if (
        inlineFuncs.isEmpty && !debugCode.methods.exists(_.instrs.exists(_.isInstanceOf[DEBUG]))
      ) {
        (debugCode, debugCode)
      } else {
        state.setGenReleaseCode()
        (debugCode, genCode(state))
      }
    }
  }

  sealed trait Inheritance extends Positioned {
    def parentId: TypeId
  }
  final case class ContractInheritance(parentId: TypeId, idents: Seq[Ident]) extends Inheritance
  final case class InterfaceInheritance(parentId: TypeId)                    extends Inheritance
  final case class Contract(
      stdIdEnabled: Option[Boolean],
      stdInterfaceId: Option[StdInterfaceId],
      isAbstract: Boolean,
      ident: TypeId,
      templateVars: Seq[Argument],
      fields: Seq[Argument],
      funcs: Seq[FuncDef[StatefulContext]],
      maps: Seq[MapDef],
      events: Seq[EventDef],
      constantVars: Seq[ConstantVarDef[StatefulContext]],
      enums: Seq[EnumDef[StatefulContext]],
      inheritances: Seq[Inheritance]
  ) extends ContractWithState {
    lazy val hasStdIdField: Boolean = stdIdEnabled.exists(identity) && stdInterfaceId.nonEmpty
    lazy val contractFields: Seq[Argument] = if (hasStdIdField) fields :+ Ast.stdArg else fields

    lazy val selfDefinedConstants: Seq[Ident] = {
      val constants = mutable.ArrayBuffer.empty[Ident]
      constantVars.foreach { c =>
        if (c.definedIn(ident)) constants.addOne(c.ident)
      }
      enums.foreach(e =>
        e.fields.foreach { field =>
          if (field.definedIn(ident)) {
            constants.addOne(EnumDef.fieldIdent(e.id, field.ident))
          }
        }
      )
      constants.toSeq
    }

    def getFieldsSignature(): String =
      s"Contract ${name}(${contractFields.map(_.signature).mkString(",")})"
    def getFieldNames(): AVector[String] = AVector.from(contractFields.view.map(_.ident.name))
    def getFieldTypes(): AVector[String] = AVector.from(contractFields.view.map(_.tpe.signature))
    def getFieldMutability(): AVector[Boolean] = AVector.from(contractFields.view.map(_.isMutable))

    override def builtInContractFuncs(
        globalState: GlobalState[StatefulContext]
    ): Seq[Compiler.ContractFunc[StatefulContext]] = {
      val stdInterfaceIdOpt = if (hasStdIdField) stdInterfaceId else None
      Seq(BuiltIn.encodeFields(stdInterfaceIdOpt, fields, globalState))
    }

    private def checkFuncs(): Unit = {
      if (!isAbstract && funcs.length < 1) {
        throw Compiler.Error(
          s"No function found in Contract ${quote(ident.name)}",
          ident.sourceIndex
        )
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    def getFuncUnsafe(funcId: FuncId): FuncDef[StatefulContext] = funcs.find(_.id == funcId).get

    private var calculatedConstants: Option[Seq[(Ident, Val)]] = None
    def getCalculatedConstants(): Seq[(Ident, Val)] = calculatedConstants.getOrElse(Seq.empty)

    override def checkConstants(state: Compiler.State[StatefulContext]): Unit = {
      constantVars.foreach { c =>
        if (state.globalState.constantVars.exists(_.ident == c.ident)) {
          throw Compiler.Error(
            s"Local constant ${c.name} conflicts with an existing global constant, please use a fresh name",
            c.sourceIndex
          )
        }
      }
      val constants = state.addConstants(constantVars)
      if (constants.nonEmpty) calculatedConstants = Some(constants)
      enums.foreach { e =>
        if (state.globalState.enums.exists(_.id == e.id)) {
          throw Compiler.Error(
            s"Local enum ${e.name} conflicts with an existing global enum, please use a fresh name",
            e.sourceIndex
          )
        }
      }
      state.addEnums(enums)
    }

    private def checkInheritances(state: Compiler.State[StatefulContext]): Unit = {
      inheritances.foreach { inheritance =>
        val id   = inheritance.parentId
        val kind = state.getContractInfo(id).kind
        if (!kind.inheritable) {
          throw Compiler.Error(s"$kind ${id.name} can not be inherited", id.sourceIndex)
        }
      }
    }

    private def checkFields(state: Compiler.State[StatefulContext]): Unit = {
      fields.foreach { case field @ Argument(fieldId, tpe, isFieldMutable, _) =>
        state.resolveType(tpe) match {
          case Type.Struct(structId) =>
            val isStructImmutable = state.flattenTypeMutability(tpe, isMutable = true).forall(!_)
            if (isFieldMutable && isStructImmutable) {
              throw Compiler.Error(
                s"The struct ${structId.name} is immutable, please remove the `mut` from ${ident.name}.${fieldId.name}",
                field.sourceIndex
              )
            }
          case _ => ()
        }
      }
    }

    private def checkMaps(state: Compiler.State[StatefulContext]): Unit = {
      UniqueDef.checkDuplicates(maps, "maps")
      maps.find(mapDef => fields.exists(_.ident == mapDef.ident)) match {
        case Some(mapDef) =>
          throw Compiler.Error(
            s"The map ${mapDef.ident.name} cannot have the same name as the contract field",
            mapDef.ident.sourceIndex
          )
        case _ => ()
      }
      maps.view.zipWithIndex.foreach { case (m, index) =>
        val mapType = Type.Map(m.tpe.key, state.resolveType(m.tpe.value))
        state.addMapVar(m.ident, mapType, index)
      }
    }

    override def check(state: Compiler.State[StatefulContext]): Unit = {
      state.setCheckPhase()
      checkFields(state)
      checkMaps(state)
      checkFuncs()
      checkInheritances(state)
      super.check(state)
    }

    override def genMethodsForNonInlineFuncs(
        state: Compiler.State[StatefulContext]
    ): AVector[Method[StatefulContext]] = {
      val selectors = mutable.Map.empty[Method.Selector, FuncId]
      AVector.from(nonInlineFuncs.view.map { func =>
        val method = func.genMethod(state)
        if (func.isPublic && state.isUseMethodSelector(ident, func.id)) {
          val methodSelector = func.getMethodSelector(state.globalState)
          selectors.get(methodSelector) match {
            case Some(funcId) =>
              throw Compiler.Error(
                s"Function ${func.name}'s method selector conflicts with function ${funcId.name}'s method selector. Please use a new function name.",
                func.id.sourceIndex
              )
            case _ => ()
          }
          selectors(methodSelector) = func.id
          val methodSelectorInstr = MethodSelector(methodSelector)
          method.copy(instrs = methodSelectorInstr +: method.instrs)
        } else {
          method
        }
      })
    }

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      assume(!isAbstract)
      state.setGenCodePhase()
      val fieldsLength =
        state.flattenTypeLength(fields.map(_.tpe)) + (if (hasStdIdField) 1 else 0)
      StatefulContract(fieldsLength, genMethods(state))
    }

    // the state must have been updated in the check pass
    def buildCheckExternalCallerTable(
        state: Compiler.State[StatefulContext]
    ): mutable.Map[FuncId, Boolean] = {
      val checkExternalCallerTable = mutable.Map.empty[FuncId, Boolean]
      funcs.foreach(func => checkExternalCallerTable(func.id) = false)

      // TODO: optimize these two functions
      def updateCheckedRecursivelyForPrivateMethod(checkedPrivateCalleeId: FuncId): Unit = {
        state.internalCallsReversed.get(checkedPrivateCalleeId) match {
          case Some(callers) =>
            callers.foreach { caller =>
              updateCheckedRecursively(getFuncUnsafe(caller))
            }
          case None => ()
        }
      }
      def updateCheckedRecursively(func: FuncDef[StatefulContext]): Unit = {
        if (!checkExternalCallerTable(func.id)) {
          checkExternalCallerTable(func.id) = true
          if (func.isPrivate) { // indirect check external caller should be in private methods
            updateCheckedRecursivelyForPrivateMethod(func.id)
          }
        }
      }

      funcs.foreach { func =>
        if (!func.isPublic && func.hasCheckExternalCallerAnnotation) {
          state.warnPrivateFuncHasCheckExternalCaller(ident, func.id)
        }
        if (func.hasDirectCheckExternalCaller()) {
          updateCheckedRecursively(func)
        }
      }
      checkExternalCallerTable
    }
  }

  final case class ContractInterface(
      stdId: Option[StdInterfaceId],
      useMethodSelector: Boolean,
      ident: TypeId,
      funcs: Seq[FuncDef[StatefulContext]],
      events: Seq[EventDef],
      inheritances: Seq[InterfaceInheritance]
  ) extends ContractWithState {
    def error(tpe: String): Compiler.Error =
      Compiler.Error(
        s"Interface ${quote(ident.name)} should not contain any ${quote(tpe)}",
        sourceIndex
      )

    def templateVars: Seq[Argument]                        = throw error("template variable")
    def fields: Seq[Argument]                              = throw error("field")
    def maps: Seq[MapDef]                                  = throw error("map")
    def getFieldsSignature(): String                       = throw error("field")
    def getFieldTypes(): Seq[String]                       = throw error("field")
    def constantVars: Seq[ConstantVarDef[StatefulContext]] = throw error("constant variable")
    def enums: Seq[EnumDef[StatefulContext]]               = throw error("enum")

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      throw Compiler.Error(s"Interface ${quote(ident.name)} should not generate code", sourceIndex)
    }
  }

  final case class MultiContract(
      contracts: Seq[ContractWithState],
      globalState: GlobalState[StatefulContext],
      dependencies: Option[Map[TypeId, Seq[TypeId]]],
      methodSelectorTable: Option[Map[(TypeId, FuncId), Boolean]]
  ) extends Positioned {
    lazy val contractsTable = contracts.map { contract =>
      val kind = contract match {
        case _: Ast.ContractInterface =>
          Compiler.ContractKind.Interface
        case _: Ast.TxScript =>
          Compiler.ContractKind.TxScript
        case txContract: Ast.Contract =>
          Compiler.ContractKind.Contract(txContract.isAbstract)
      }
      contract.ident -> Compiler.ContractInfo(kind, contract.funcTable(globalState))
    }.toMap

    def structs: Seq[Struct]                 = globalState.structs
    def enums: Seq[EnumDef[StatefulContext]] = globalState.enums

    def get(contractIndex: Int): ContractWithState = {
      if (contractIndex >= 0 && contractIndex < contracts.size) {
        contracts(contractIndex)
      } else {
        throw Compiler.Error(s"Invalid contract index $contractIndex", None)
      }
    }

    private def getContract(typeId: TypeId): ContractWithState = {
      contracts.find(_.ident.name == typeId.name) match {
        case None =>
          throw Compiler.Error(s"Contract ${quote(typeId.name)} does not exist", typeId.sourceIndex)
        case Some(ts: TxScript) =>
          throw Compiler.Error(
            s"Expected contract ${quote(typeId.name)}, but was script",
            ts.sourceIndex
          )
        case Some(contract: ContractWithState) => contract
      }
    }

    def isContract(typeId: TypeId): Boolean = {
      contracts.find(_.ident.name == typeId.name) match {
        case None =>
          throw Compiler.Error(s"Contract ${quote(typeId.name)} does not exist", typeId.sourceIndex)
        case Some(contract: Contract) if !contract.isAbstract => true
        case _                                                => false
      }
    }

    def getInterface(typeId: TypeId): ContractInterface = {
      getContract(typeId) match {
        case interface: ContractInterface => interface
        case _ =>
          throw Compiler.Error(s"Interface ${typeId.name} does not exist", typeId.sourceIndex)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def buildDependencies(
        contract: ContractWithState,
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        visited: mutable.Set[TypeId]
    ): Unit = {
      if (!visited.add(contract.ident)) {
        throw Compiler.Error(
          s"Cyclic inheritance detected for contract ${contract.ident.name}",
          contract.sourceIndex
        )
      }

      val allParents = mutable.LinkedHashMap.empty[TypeId, ContractWithState]
      contract.inheritances.foreach { inheritance =>
        val parentId       = inheritance.parentId
        val parentContract = getContract(parentId)
        MultiContract.checkInheritanceFields(contract, inheritance, parentContract)

        allParents += parentId -> parentContract
        if (!parentsCache.contains(parentId)) {
          buildDependencies(parentContract, parentsCache, visited)
        }
        parentsCache(parentId).foreach { grandParent =>
          allParents += grandParent.ident -> grandParent
        }
      }
      parentsCache += contract.ident -> allParents.values.toSeq
    }

    private def buildDependencies(): mutable.Map[TypeId, Seq[ContractWithState]] = {
      val parentsCache = mutable.Map.empty[TypeId, Seq[ContractWithState]]
      val visited      = mutable.Set.empty[TypeId]
      contracts.foreach {
        case _: TxScript => ()
        case contract =>
          if (!parentsCache.contains(contract.ident)) {
            buildDependencies(contract, parentsCache, visited)
          }
      }
      parentsCache
    }

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def extendedContracts(): MultiContract = {
      UniqueDef.checkDuplicates(
        contracts ++ structs ++ enums,
        "TxScript/Contract/Interface/Struct/Enum"
      )

      val methodSelectorTable = mutable.Map.empty[(TypeId, Ast.FuncId), Boolean]
      val parentsCache        = buildDependencies()
      val newContracts: Seq[ContractWithState] = contracts.map {
        case script: TxScript =>
          script.withTemplateVarDefs(globalState).atSourceIndex(script.sourceIndex)
        case c: Contract =>
          val (stdIdEnabled, stdId, funcs, maps, events, constantVars, enums) =
            MultiContract.extractContract(parentsCache, methodSelectorTable, c)
          Contract(
            Some(stdIdEnabled),
            stdId,
            c.isAbstract,
            c.ident,
            c.templateVars,
            c.fields,
            funcs,
            maps,
            events,
            constantVars,
            enums,
            c.inheritances
          ).atSourceIndex(c.sourceIndex)
        case i: ContractInterface =>
          val (stdId, funcs, events) =
            MultiContract.extractInterface(parentsCache, methodSelectorTable, i)
          ContractInterface(stdId, i.useMethodSelector, i.ident, funcs, events, i.inheritances)
            .atSourceIndex(i.sourceIndex)
      }
      val dependencies = Map.from(parentsCache.map(p => (p._1, p._2.map(_.ident))))
      MultiContract(newContracts, globalState, Some(dependencies), Some(methodSelectorTable.toMap))
    }

    def genStatefulScripts()(implicit compilerOptions: CompilerOptions): AVector[CompiledScript] = {
      AVector.from(contracts.view.zipWithIndex.collect { case (_: TxScript, index) =>
        genStatefulScript(index)
      })
    }

    def genStatefulScript(contractIndex: Int)(implicit
        compilerOptions: CompilerOptions
    ): CompiledScript = {
      val state = Compiler.State.buildFor(this, contractIndex)
      get(contractIndex) match {
        case script: TxScript =>
          val (debugCode, releaseCode) = script.genCodeFull(state)
          val warnings                 = state.getWarnings
          CompiledScript(releaseCode, script, warnings, debugCode)
        case c: Contract =>
          throw Compiler.Error(s"The code is for Contract, not for TxScript", c.sourceIndex)
        case ci: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for TxScript", ci.sourceIndex)
      }
    }

    private def checkUnusedDefsInParentContract(
        states: Map[TypeId, (Contract, Compiler.State[StatefulContext])],
        defsInParentContract: Contract => Iterable[(TypeId, (String, Option[SourceIndex]))],
        usedDefsInContract: (
            Contract,
            Compiler.State[StatefulContext]
        ) => Iterable[(TypeId, (String, Option[SourceIndex]))],
        genWarning: (TypeId, collection.Seq[(String, Option[SourceIndex])]) => AVector[Warning]
    ): AVector[Warning] = {
      val allDefs = mutable.Map.empty[(TypeId, String), Option[SourceIndex]]
      states.foreachEntry { case (_, (contract, _)) =>
        if (contract.isAbstract) {
          defsInParentContract(contract).map { case (typeId, (name, sourceIndex)) =>
            allDefs.addOne((typeId, name) -> sourceIndex)
          }
        }
      }

      states.foreachEntry { case (_, (contract, state)) =>
        if (!contract.isAbstract) {
          usedDefsInContract(contract, state).foreach { case (typeId, (name, _)) =>
            allDefs.remove((typeId, name))
          }
        }
      }

      if (allDefs.nonEmpty) {
        AVector.from(allDefs.groupBy(_._1._1).flatMap { case (parentId, defs0) =>
          val defs = defs0.map { case ((_, name), sourceIndex) => (name, sourceIndex) }
          genWarning(parentId, defs.toSeq)
        })
      } else {
        AVector.empty[Warning]
      }
    }

    private def checkUnusedLocalConstants(
        states: Map[TypeId, (Contract, Compiler.State[StatefulContext])]
    ): AVector[Warning] = {
      val defsInParentContract = (contract: Contract) => {
        contract.selfDefinedConstants.map(ident =>
          (contract.ident, (ident.name, ident.sourceIndex))
        )
      }
      checkUnusedDefsInParentContract(
        states,
        defsInParentContract,
        (_, state) => state.getUsedParentConstants(),
        Warnings.unusedLocalConstants
      )
    }

    private def checkUnusedPrivateFunctions(
        states: Map[TypeId, (Contract, Compiler.State[StatefulContext])]
    ): AVector[Warning] = {
      val defsInParentContract = (contract: Contract) => {
        contract.funcs.collect {
          case func if func.isPrivate && func.definedIn(contract.ident) =>
            (contract.ident, (func.name, func.sourceIndex))
        }
      }
      val usedDefsInContract = (contract: Contract, state: Compiler.State[StatefulContext]) => {
        val usedPrivateFuncs = mutable.ArrayBuffer.empty[(TypeId, (String, Option[SourceIndex]))]
        state.internalCallsReversed.keys.foreach { funcId =>
          contract.funcs.find(f => f.id == funcId && f.isPrivate).foreach { func =>
            func.origin.foreach { originId =>
              if (originId != contract.ident) {
                usedPrivateFuncs.addOne((originId, (func.name, func.sourceIndex)))
              }
            }
          }
        }
        usedPrivateFuncs
      }
      checkUnusedDefsInParentContract(
        states,
        defsInParentContract,
        usedDefsInContract,
        Warnings.unusedPrivateFunctions
      )
    }

    private def checkUnusedDefsInParentContract(
        states: AVector[Compiler.State[StatefulContext]]
    )(implicit compilerOptions: CompilerOptions): AVector[Warning] = {
      if (
        compilerOptions.ignoreUnusedConstantsWarnings && compilerOptions.ignoreUnusedPrivateFunctionsWarnings
      ) {
        AVector.empty[Warning]
      } else {
        val contractAndStates = contracts.view.zipWithIndex.collect {
          case (contract: Contract, index) => (contract.ident, (contract, states(index)))
        }.toMap
        val unusedConstantsWarnings = if (!compilerOptions.ignoreUnusedConstantsWarnings) {
          checkUnusedLocalConstants(contractAndStates)
        } else {
          AVector.empty[Warning]
        }
        val unusedPrivateFuncsWarnings =
          if (!compilerOptions.ignoreUnusedPrivateFunctionsWarnings) {
            checkUnusedPrivateFunctions(contractAndStates)
          } else {
            AVector.empty[Warning]
          }
        unusedConstantsWarnings ++ unusedPrivateFuncsWarnings
      }
    }

    private def checkAbstractContracts(states: AVector[Compiler.State[StatefulContext]]): Unit = {
      val abstractContracts = contracts.collect {
        case contract: Contract if contract.isAbstract => contract
      }
      val sortedAbstractContracts = dependencies match {
        case Some(deps) => abstractContracts.sortBy(c => deps(c.ident).length)
        case None =>
          val deps = buildDependencies()
          abstractContracts.sortBy(c => deps(c.ident).length)
      }
      sortedAbstractContracts.foreach { contract =>
        states.find(_.typeId == contract.ident).foreach(contract.check)
      }
    }

    def genStatefulContracts()(implicit
        compilerOptions: CompilerOptions
    ): (AVector[Warning], AVector[(CompiledContract, Int)]) = {
      val states = AVector.tabulate(contracts.length)(Compiler.State.buildFor(this, _))
      if (!compilerOptions.skipAbstractContractCheck) {
        checkAbstractContracts(states)
      }
      val statefulContracts = AVector.from(contracts.view.zipWithIndex.collect {
        case (contract: Contract, index) if !contract.isAbstract =>
          val state = states(index)
          contract.check(state)
          state.setGenDebugCode()
          val statefulDebugContract = contract.genCode(state)
          (statefulDebugContract, contract, state, index)
      })
      StaticAnalysis.checkExternalCalls(this, states)
      val warnings = checkUnusedDefsInParentContract(states)
      val compiled = statefulContracts.map { case (statefulDebugContract, contract, state, index) =>
        val (inlinedDebugCode, inlinedReleaseCode) =
          genInlineCode(contract, statefulDebugContract, state)
        StaticAnalysis.checkContract(contract, statefulDebugContract, state)
        CompiledContract(
          inlinedReleaseCode,
          contract,
          state.getWarnings,
          inlinedDebugCode
        ) -> index
      }
      (warnings, compiled)
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def calcUseContractAssetsInfo(
        cacheForInlineFuncs: mutable.HashMap[FuncId, Compiler.UseContractAssetsInfo],
        funcId: FuncId,
        state: Compiler.State[StatefulContext]
    ): Compiler.UseContractAssetsInfo = {
      val info = state.getFunc(funcId).useContractAssetsInfo
      if (info.useContractAssets) {
        info
      } else {
        state.internalCalls.get(funcId) match {
          case Some(callees) =>
            callees.view
              .filter(id => state.getFunc(id).inline)
              .map(id =>
                cacheForInlineFuncs
                  .getOrElseUpdate(id, calcUseContractAssetsInfo(cacheForInlineFuncs, id, state))
              )
              .foldLeft(info)(_ merge _)
          case None => info
        }
      }
    }

    private def getUseContractAssetsInfo(
        contract: Contract,
        state: Compiler.State[StatefulContext]
    ) = {
      val cacheForInlineFuncs = mutable.HashMap.empty[FuncId, Compiler.UseContractAssetsInfo]
      val result              = mutable.HashMap.empty[Int, Compiler.UseContractAssetsInfo]
      contract.nonInlineFuncs.view.zipWithIndex.foreach { case (func, index) =>
        val info = calcUseContractAssetsInfo(cacheForInlineFuncs, func.id, state)
        if (info.useContractAssets) result.addOne(index -> info)
      }
      result
    }

    private def updateUseContractAssetsInfo(
        code: StatefulContract,
        infos: mutable.HashMap[Int, Compiler.UseContractAssetsInfo]
    ) = {
      val newMethods = code.methods.mapWithIndex { case (method, index) =>
        infos.get(index) match {
          case Some(info) =>
            if (info.usePayToContractOnly) {
              method.copy(usePayToContractOnly = true)
            } else {
              assume(info.useAssetsInContract.assetsEnabled)
              method.copy(useContractAssets = true)
            }
          case _ => method
        }
      }
      code.copy(methods = newMethods)
    }

    def genInlineCode(
        contract: Contract,
        debugCode: StatefulContract,
        state: Compiler.State[StatefulContext]
    ): (StatefulContract, StatefulContract) = {
      val hasInlineFuncs = contract.inlineFuncs.nonEmpty
      val hasDebugCode   = debugCode.methods.exists(_.instrs.exists(_.isInstanceOf[DEBUG]))
      val inlinedDebugCode = if (hasInlineFuncs) {
        state.allowDebug = true
        state.genInlineCode = true
        contract.genCode(state)
      } else {
        debugCode
      }
      val inlinedReleaseCode = if (!hasInlineFuncs && !hasDebugCode) {
        inlinedDebugCode
      } else if (hasInlineFuncs && !hasDebugCode) {
        val nonInlineMethods = inlinedDebugCode.methods.dropRight(contract.inlineFuncs.length)
        inlinedDebugCode.copy(methods = nonInlineMethods)
      } else {
        state.setGenReleaseCode()
        contract.genCode(state)
      }
      if (hasInlineFuncs) {
        val infos = getUseContractAssetsInfo(contract, state)
        (
          updateUseContractAssetsInfo(inlinedDebugCode, infos),
          updateUseContractAssetsInfo(inlinedReleaseCode, infos)
        )
      } else {
        (inlinedDebugCode, inlinedReleaseCode)
      }
    }

    def genStatefulContract(contractIndex: Int)(implicit
        compilerOptions: CompilerOptions
    ): CompiledContract = {
      get(contractIndex) match {
        case contract: Contract =>
          if (contract.isAbstract) {
            throw Compiler.Error(
              s"Code generation is not supported for abstract contract ${quote(contract.ident.name)}",
              contract.sourceIndex
            )
          }
          val statefulContracts = genStatefulContracts()._2
          statefulContracts.find(_._2 == contractIndex) match {
            case Some(v) => v._1
            case None => // should never happen
              throw Compiler.Error(
                s"Failed to compile contract ${contract.ident.name}",
                contract.sourceIndex
              )
          }
        case ts: TxScript =>
          throw Compiler.Error(s"The code is for TxScript, not for Contract", ts.sourceIndex)
        case ci: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for Contract", ci.sourceIndex)
      }
    }
  }

  object MultiContract {
    def checkInheritanceFields(
        contract: ContractWithState,
        inheritance: Inheritance,
        parentContract: ContractWithState
    ): Unit = {
      inheritance match {
        case i: ContractInheritance => _checkInheritanceFields(contract, i, parentContract)
        case _                      => ()
      }
    }
    private def _checkInheritanceFields(
        contract: ContractWithState,
        inheritance: ContractInheritance,
        parentContract: ContractWithState
    ): Unit = {
      val fields = inheritance.idents.map { ident =>
        contract.fields
          .find(_.ident.name == ident.name)
          .getOrElse(
            throw Compiler.Error(
              s"Inherited field ${quote(ident.name)} does not exist in contract ${quote(contract.name)}",
              ident.sourceIndex
            )
          )
      }
      if (fields != parentContract.fields) {
        throw Compiler.Error(
          s"Invalid contract inheritance fields, expected ${quote(parentContract.fields)}, got ${quote(fields)}",
          fields.headOption.flatMap(_.sourceIndex)
        )
      }
    }

    @inline private[ralph] def getStdId(
        interfaces: Seq[ContractInterface]
    ): Option[StdInterfaceId] = {
      interfaces.foldLeft[Option[StdInterfaceId]](None) { case (parentStdIdOpt, interface) =>
        (parentStdIdOpt, interface.stdId) match {
          case (Some(parentStdId), Some(stdId)) =>
            if (stdId.bytes == parentStdId.bytes) {
              throw Compiler.Error(
                s"The std id of interface ${interface.ident.name} is the same as parent interface",
                interface.sourceIndex
              )
            }
            if (!stdId.bytes.startsWith(parentStdId.bytes)) {
              throw Compiler.Error(
                s"The std id of interface ${interface.ident.name} should start with ${Hex
                    .toHexString(parentStdId.bytes.drop(Ast.StdInterfaceIdPrefix.length))}",
                interface.sourceIndex
              )
            }
            Some(stdId)
          case (Some(parentStdId), None) => Some(parentStdId)
          case (None, stdId)             => stdId
        }
      }
    }

    @inline private[ralph] def getStdIdEnabled(
        contracts: Seq[Contract],
        typeId: Ast.TypeId
    ): Boolean = {
      contracts
        .foldLeft[Option[Boolean]](None) {
          case (None, contract) => contract.stdIdEnabled
          case (v, contract) =>
            if (contract.stdIdEnabled.nonEmpty && contract.stdIdEnabled != v) {
              throw Compiler.Error(
                s"There are different std id enabled options on the inheritance chain of contract ${typeId.name}",
                typeId.sourceIndex
              )
            }
            v
        }
        .getOrElse(true)
    }

    def extractInterface(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        methodSelectorTable: mutable.Map[(TypeId, FuncId), Boolean],
        interface: ContractInterface
    ): (Option[StdInterfaceId], Seq[FuncDef[StatefulContext]], Seq[EventDef]) = {
      val parents = parentsCache(interface.ident).map {
        case parent: ContractInterface => parent
        case p =>
          throw Compiler.Error(s"${p.ident.name} is not an interface", p.ident.sourceIndex)
      }
      if (!interface.useMethodSelector) {
        parents.find(_.useMethodSelector) match {
          case Some(parent) =>
            throw Compiler.Error(
              s"Interface ${interface.name} does not use method selector, but its parent ${parent.name} use method selector",
              interface.ident.sourceIndex
            )
          case None => ()
        }
      }
      val sortedInterfaces = sortInterfaces(parentsCache, parents :+ interface)
      val stdId            = getStdId(sortedInterfaces)
      val allFuncs = sortedInterfaces.flatMap { parentOrSelf =>
        parentOrSelf.funcs.foreach(func =>
          methodSelectorTable.update((interface.ident, func.id), parentOrSelf.useMethodSelector)
        )
        parentOrSelf.funcs
      }
      val (unimplementedFuncs, _) = checkFuncs(allFuncs)
      // call the `checkFuncs` first to avoid duplicate function definition
      checkInterfaceMethodIndex(sortedInterfaces)
      val events = sortedInterfaces.flatMap(_.events)
      (stdId, unimplementedFuncs, events)
    }

    // scalastyle:off method.length
    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def extractContract(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        methodSelectorTable: mutable.Map[(TypeId, FuncId), Boolean],
        contract: Contract
    ): (
        Boolean,
        Option[StdInterfaceId],
        Seq[FuncDef[StatefulContext]],
        Seq[MapDef],
        Seq[EventDef],
        Seq[ConstantVarDef[StatefulContext]],
        Seq[EnumDef[StatefulContext]]
    ) = {
      val parents                       = parentsCache(contract.ident)
      val (allContracts, allInterfaces) = (parents :+ contract).partition(_.isInstanceOf[Contract])
      val sortedInterfaces =
        sortInterfaces(parentsCache, allInterfaces.map(_.asInstanceOf[ContractInterface]))
      val stdId        = getStdId(sortedInterfaces)
      val stdIdEnabled = getStdIdEnabled(allContracts.map(_.asInstanceOf[Contract]), contract.ident)

      val interfaceFuncs = sortedInterfaces.flatMap { interface =>
        interface.funcs.foreach(func =>
          methodSelectorTable.update((contract.ident, func.id), interface.useMethodSelector)
        )
        interface.funcs
      }
      val allFuncs                             = interfaceFuncs ++ allContracts.flatMap(_.funcs)
      val (unimplementedFuncs, allUniqueFuncs) = checkFuncs(allFuncs)
      val constantVars                         = allContracts.flatMap(_.constantVars)
      val enums                                = mergeEnums(allContracts.flatMap(_.enums))

      // call the `checkFuncs` first to avoid duplicate function definition
      checkInterfaceMethodIndex(sortedInterfaces)

      val contractEvents = allContracts.flatMap(_.events)
      val maps           = allContracts.flatMap(_.maps)
      val events         = sortedInterfaces.flatMap(_.events) ++ contractEvents

      if (!contract.isAbstract && unimplementedFuncs.nonEmpty) {
        val methodNames = unimplementedFuncs.map(_.name).mkString(",")
        throw Compiler.Error(
          s"Contract ${contract.name} has unimplemented methods: $methodNames",
          contract.sourceIndex
        )
      }
      val funcs = if (contract.isAbstract) {
        allUniqueFuncs
      } else {
        rearrangeFuncs(sortedInterfaces, allUniqueFuncs)
      }
      (stdIdEnabled, stdId, funcs, maps, events, constantVars, enums)
    }
    // scalastyle:on method.length

    private def rearrangeFuncs(
        interfaces: Seq[ContractInterface],
        funcs: Seq[FuncDef[StatefulContext]]
    ): Seq[FuncDef[StatefulContext]] = {
      val interfaceFuncs = interfaces.flatMap(_.funcs)
      val (remains, preDefinedIndexFuncs) = funcs.partitionMap { func =>
        val methodIndex = interfaceFuncs.find(_.id == func.id).flatMap(_.useMethodIndex)
        if (methodIndex.isDefined) {
          Right(func.copy(useMethodIndex = methodIndex).atSourceIndex(func.sourceIndex))
        } else {
          Left(func)
        }
      }

      val invalidFuncs = preDefinedIndexFuncs.filter(_.useMethodIndex.exists(_ >= funcs.length))
      if (invalidFuncs.nonEmpty) {
        throw Compiler.Error(
          s"The method index of these functions is out of bound: ${invalidFuncs.map(_.name).mkString(",")}, total number of methods: ${funcs.length}",
          invalidFuncs.headOption.flatMap(_.id.sourceIndex)
        )
      }

      val remainFuncsIterator = remains.iterator
      funcs.indices.map { index =>
        preDefinedIndexFuncs.find(_.useMethodIndex.contains(index)) match {
          case Some(func) => func
          case None       => remainFuncsIterator.next()
        }
      }
    }

    @tailrec
    def ensureChainedInterfaces(sortedInterfaces: Seq[ContractInterface]): Unit = {
      if (sortedInterfaces.length >= 2) {
        val parent = sortedInterfaces(0)
        val child  = sortedInterfaces(1)
        if (!child.inheritances.exists(_.parentId.name == parent.ident.name)) {
          throw Compiler.Error(
            s"Interface ${child.name} does not inherit from ${parent.name}, " +
              s"please annotate ${child.name} with @using(methodSelector = true) annotation",
            child.sourceIndex
          )
        }

        ensureChainedInterfaces(sortedInterfaces.drop(1))
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def checkInterfaceMethodIndex(sortedInterfaces: Seq[ContractInterface]): Unit = {
      val methodLength = sortedInterfaces.map(_.funcs.length).sum
      val predefinedMethodIndexMax = sortedInterfaces
        .map(_.funcs.map(_.useMethodIndex.getOrElse(-1)).max)
        .maxOption
        .getOrElse(-1)
      val methodLengthMax = math.max(methodLength, predefinedMethodIndexMax + 1)
      assume(methodLengthMax <= 0xff + 1)
      val usedMethodIndexes = mutable.ArrayBuffer.fill(methodLengthMax)(false)
      var fromMethodIndex   = 0
      sortedInterfaces.foreach { interface =>
        val (preDefinedMethodIndexFuncs, remains) =
          interface.funcs.partition(_.useMethodIndex.nonEmpty)
        preDefinedMethodIndexFuncs.foreach { func =>
          func.useMethodIndex match {
            case Some(index) =>
              if (usedMethodIndexes(index)) {
                throw Compiler.Error(
                  s"Function ${interface.name}.${func.id.name} have invalid predefined method index $index",
                  func.id.sourceIndex
                )
              } else {
                usedMethodIndexes(index) = true
              }
            case _ => // dead branch
          }
        }
        remains.foreach { _ =>
          val methodIndex = usedMethodIndexes.indexOf(false, fromMethodIndex)
          assume(methodIndex != -1)
          usedMethodIndexes(methodIndex) = true
          fromMethodIndex = methodIndex + 1
        }
      }
    }

    private[ralph] def sortInterfaces(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        interfaces: Seq[ContractInterface]
    ): Seq[ContractInterface] = {
      val (useMethodSelector, notUseMethodSelector) = interfaces.partition(_.useMethodSelector)
      val chainedInterfaces =
        notUseMethodSelector.sortBy(interface => parentsCache(interface.ident).length)
      ensureChainedInterfaces(chainedInterfaces)
      chainedInterfaces ++ useMethodSelector.sorted(
        Ordering
          .by[ContractInterface, Int](interface => parentsCache(interface.ident).length)
          .orElse(Ordering.by[ContractInterface, String](_.name))
      )
    }

    def mergeEnums(enums: Seq[EnumDef[StatefulContext]]): Seq[EnumDef[StatefulContext]] = {
      val (enums0, enums1) = enums.partition(e => enums.count(_.id == e.id) == 1)
      val mergedEnums = mutable.Map.empty[TypeId, mutable.ArrayBuffer[EnumField[StatefulContext]]]
      enums1.foreach { enumDef =>
        mergedEnums.get(enumDef.id) match {
          case Some(fields) =>
            // enum fields will never be empty
            val expectedType = enumDef.fields(0).value.v.tpe
            val haveType     = fields(0).value.v.tpe
            if (expectedType != haveType) {
              throw Compiler.Error(
                s"There are different field types in the enum ${enumDef.id.name}: $expectedType,$haveType",
                fields(0).sourceIndex
              )
            }
            val conflictFields = enumDef.fields.filter(f => fields.exists(_.name == f.name))
            if (conflictFields.nonEmpty) {
              throw Compiler.Error(
                s"There are conflict fields in the enum ${enumDef.id.name}: ${conflictFields.map(_.name).mkString(",")}",
                conflictFields.headOption.flatMap(_.sourceIndex)
              )
            }
            fields.appendAll(enumDef.fields)
          case None => mergedEnums(enumDef.id) = mutable.ArrayBuffer.from(enumDef.fields)
        }
      }
      enums0 ++ mergedEnums.view.map(pair => EnumDef(pair._1, pair._2.toSeq)).toSeq
    }

    def checkFuncs(
        allFuncs: Seq[FuncDef[StatefulContext]]
    ): (Seq[FuncDef[StatefulContext]], Seq[FuncDef[StatefulContext]]) = {
      val (abstractFuncs, nonAbstractFuncs) = allFuncs.partition(_.bodyOpt.isEmpty)
      val nonAbstractFuncSet                = nonAbstractFuncs.view.map(f => f.id.name -> f).toMap
      val abstractFuncsSet                  = abstractFuncs.view.map(f => f.id.name -> f).toMap
      if (nonAbstractFuncSet.size != nonAbstractFuncs.size) {
        val (duplicates, sourceIndex) = UniqueDef.duplicates(nonAbstractFuncs)
        throw Compiler.Error(
          s"These functions are implemented multiple times: $duplicates",
          sourceIndex
        )
      }

      if (abstractFuncsSet.size != abstractFuncs.size) {
        val (duplicates, sourceIndex) = UniqueDef.duplicates(abstractFuncs)
        throw Compiler.Error(
          s"These abstract functions are defined multiple times: $duplicates",
          sourceIndex
        )
      }

      val (implementedFuncs, unimplementedFuncs) =
        abstractFuncs.partition(func => nonAbstractFuncSet.contains(func.id.name))

      implementedFuncs.foreach { abstractFunc =>
        val funcName                = abstractFunc.id.name
        val implementedAbstractFunc = nonAbstractFuncSet(funcName)
        if (implementedAbstractFunc.signature != abstractFunc.signature) {
          throw Compiler.Error(
            s"Function ${quote(funcName)} is implemented with wrong signature",
            implementedAbstractFunc.sourceIndex
          )
        }
      }

      val inherited    = abstractFuncs.map { f => nonAbstractFuncSet.getOrElse(f.id.name, f) }
      val nonInherited = nonAbstractFuncs.filter(f => !abstractFuncsSet.contains(f.id.name))
      (unimplementedFuncs, inherited ++ nonInherited)
    }
  }
}
// scalastyle:on number.of.methods number.of.types
