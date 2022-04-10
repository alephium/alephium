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

import akka.util.ByteString

import org.alephium.io.IOError
import org.alephium.macros.HashSerde
import org.alephium.protocol.Hash
import org.alephium.protocol.model.ContractId
import org.alephium.serde._
import org.alephium.util.{AVector, Hex}

final case class Method[Ctx <: StatelessContext](
    isPublic: Boolean,
    isPayable: Boolean,
    argsLength: Int,
    localsLength: Int,
    returnLength: Int,
    instrs: AVector[Instr[Ctx]]
) {
  def toTemplateString(): String = {
    val prefix = Hex.toHexString(
      serialize(isPublic) ++
        serialize(isPayable) ++
        serialize(argsLength) ++
        serialize(localsLength) ++
        serialize(returnLength) ++
        serialize(instrs.length)
    )
    prefix ++ instrs.map(_.toTemplateString()).mkString("")
  }
}

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

  def validate(method: Method[_]): Boolean =
    method.argsLength >= 0 && method.localsLength >= method.argsLength && method.returnLength >= 0

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
  def methodsLength: Int
  def getMethod(index: Int): ExeResult[Method[Ctx]]
  def hash: Hash

  def initialStateHash(fields: AVector[Val]): Hash =
    Hash.doubleHash(hash.bytes ++ ContractState.fieldsSerde.serialize(fields))
}

sealed trait Script[Ctx <: StatelessContext] extends Contract[Ctx] {
  def fieldLength: Int = 0
  def methods: AVector[Method[Ctx]]
  def toObject: ScriptObj[Ctx]

  def methodsLength: Int = methods.length

  def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    methods.get(index).toRight(Right(InvalidMethodIndex(index)))
  }

  def toTemplateString(): String = {
    Hex.toHexString(serialize(methods.length)) ++ methods.map(_.toTemplateString()).mkString("")
  }
}

@HashSerde
final case class StatelessScript private (methods: AVector[Method[StatelessContext]])
    extends Script[StatelessContext] {
  override def toObject: ScriptObj[StatelessContext] = {
    StatelessScriptObject(this)
  }
}

object StatelessScript {
  implicit val serde: Serde[StatelessScript] = {
    val serde: Serde[StatelessScript] = Serde.forProduct1(StatelessScript.apply, _.methods)
    serde.validate(script => Either.cond(validate(script.methods), (), s"Invalid script: $script"))
  }

  private def validate(methods: AVector[Method[StatelessContext]]): Boolean = {
    methods.nonEmpty &&
    methods.head.isPublic &&
    methods.forall(m => !m.isPayable && Method.validate(m))
  }

  def from(methods: AVector[Method[StatelessContext]]): Option[StatelessScript] = {
    Option.when(validate(methods))(new StatelessScript(methods))
  }

  def unsafe(methods: AVector[Method[StatelessContext]]): StatelessScript = {
    new StatelessScript(methods)
  }
}

@HashSerde
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
    Option.when(validate(methods))(new StatefulScript(methods))
  }

  def validate(methods: AVector[Method[StatefulContext]]): Boolean = {
    methods.nonEmpty && methods.head.isPublic && methods.forall(Method.validate)
  }

  def alwaysFail: StatefulScript =
    StatefulScript(
      AVector(
        Method[StatefulContext](
          isPublic = true,
          isPayable = true,
          argsLength = 0,
          localsLength = 0,
          returnLength = 0,
          instrs = AVector(ConstFalse, Assert)
        )
      )
    )
}

@HashSerde
final case class StatefulContract(
    fieldLength: Int,
    methods: AVector[Method[StatefulContext]]
) extends Contract[StatefulContext] {
  def methodsLength: Int = methods.length

  def getMethod(index: Int): ExeResult[Method[StatefulContext]] = {
    methods.get(index).toRight(Right(InvalidMethodIndex(index)))
  }

  def validate(initialFields: AVector[Val]): Boolean = {
    initialFields.length == fieldLength
  }

  def toHalfDecoded(): StatefulContract.HalfDecoded = {
    val methodsBytes = methods.map(Method.statefulSerde.serialize)
    var count        = 0
    val methodIndexes = AVector.tabulate(methods.length) { k =>
      count += methodsBytes(k).length
      count
    }
    StatefulContract.HalfDecoded(
      fieldLength,
      methodIndexes,
      methodsBytes.fold(ByteString.empty)(_ ++ _)
    )
  }
}

object StatefulContract {
  @HashSerde
  // We don't need to deserialize the whole contract if we only access part of the methods
  final case class HalfDecoded(
      fieldLength: Int,
      methodIndexes: AVector[Int], // end positions of methods in methodBytes
      methodsBytes: ByteString
  ) extends Contract[StatefulContext] {
    def methodsLength: Int = methodIndexes.length

    def check(initialFields: AVector[Val]): ExeResult[Unit] = {
      if (validate(initialFields)) {
        okay
      } else {
        failed(InvalidFieldLength)
      }
    }

    def validate(initialFields: AVector[Val]): Boolean = {
      initialFields.length == fieldLength
    }

    private[vm] lazy val methods = Array.ofDim[Method[StatefulContext]](methodsLength)

    def getMethod(index: Int): ExeResult[Method[StatefulContext]] = {
      if (index >= 0 && index < methodsLength) {
        val method = methods(index)
        if (method == null) {
          deserializeMethod(index) match {
            case Left(error) => ioFailed(IOErrorLoadContract(IOError.Serde(error)))
            case Right(method) =>
              methods(index) = method
              Right(method)
          }
        } else {
          Right(method)
        }
      } else {
        failed(InvalidMethodIndex(index))
      }
    }

    private def deserializeMethod(index: Int): SerdeResult[Method[StatefulContext]] = {
      val methodBytes = if (index == 0) {
        methodsBytes.take(methodIndexes(0))
      } else {
        methodsBytes.slice(methodIndexes(index - 1), methodIndexes(index))
      }
      Method.statefulSerde.deserialize(methodBytes)
    }

    def toContract(): SerdeResult[StatefulContract] = {
      AVector
        .tabulateE(methodsLength)(deserializeMethod)
        .map(StatefulContract(fieldLength, _))
    }

    // For testing purpose
    def toObjectUnsafe(
        address: Hash,
        fields: AVector[Val]
    ): StatefulContractObject = {
      val initialStateHash =
        Hash.doubleHash(hash.bytes ++ ContractState.fieldsSerde.serialize(fields))
      StatefulContractObject.unsafe(this.hash, this, initialStateHash, fields, address)
    }
  }

  object HalfDecoded {
    private val intsSerde: Serde[AVector[Int]] = avectorSerde[Int]
    implicit val serde: Serde[HalfDecoded] = new Serde[HalfDecoded] {
      override def serialize(input: HalfDecoded): ByteString = {
        intSerde.serialize(input.fieldLength) ++
          intsSerde.serialize(input.methodIndexes) ++
          input.methodsBytes
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[HalfDecoded]] = {
        for {
          fieldLengthRest   <- intSerde._deserialize(input)
          methodIndexesRest <- intsSerde._deserialize(fieldLengthRest.rest)
        } yield {
          // all the `take` and `drop` calls are safe since contracts are checked in the creation
          val length      = methodIndexesRest.value.lastOption.getOrElse(0)
          val data        = methodIndexesRest.rest
          val methodBytes = data.take(length)
          val rest        = data.drop(length)
          Staging(
            HalfDecoded(
              fieldLengthRest.value,
              methodIndexesRest.value,
              methodBytes
            ),
            rest
          )
        }
      }
    }
  }

  implicit val serde: Serde[StatefulContract] = new Serde[StatefulContract] {
    override def serialize(input: StatefulContract): ByteString = {
      HalfDecoded.serde.serialize(input.toHalfDecoded())
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[StatefulContract]] = {
      for {
        halfDecodedRest <- HalfDecoded.serde._deserialize(input)
        contract        <- halfDecodedRest.value.toContract()
      } yield Staging(contract, halfDecodedRest.rest)
    }
  }

  def check(contract: StatefulContract): ExeResult[Unit] = {
    if (contract.fieldLength < 0) {
      failed(InvalidFieldLength)
    } else if (contract.methods.isEmpty) {
      failed(EmptyMethods)
    } else if (!contract.methods.forall(Method.validate)) {
      failed(InvalidMethod)
    } else {
      okay
    }
  }

  val forSMT: StatefulContract.HalfDecoded =
    StatefulContract(0, AVector(Method.forSMT)).toHalfDecoded()
}

sealed trait ContractObj[Ctx <: StatelessContext] {
  def contractIdOpt: Option[ContractId]
  def code: Contract[Ctx]
  def fields: mutable.ArraySeq[Val]

  def getContractId(): ExeResult[ContractId] = contractIdOpt.toRight(Right(ExpectAContract))

  def getAddress(): ExeResult[Val.Address] =
    getContractId().map(id => Val.Address(LockupScript.p2c(id)))

  def isScript(): Boolean = contractIdOpt.isEmpty

  def getMethod(index: Int): ExeResult[Method[Ctx]] = code.getMethod(index)

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
}

sealed trait ScriptObj[Ctx <: StatelessContext] extends ContractObj[Ctx] {
  val contractIdOpt: Option[Hash]   = None
  val fields: mutable.ArraySeq[Val] = mutable.ArraySeq.empty
}

final case class StatelessScriptObject(code: StatelessScript) extends ScriptObj[StatelessContext]

final case class StatefulScriptObject(code: StatefulScript) extends ScriptObj[StatefulContext]

final case class StatefulContractObject private (
    codeHash: Hash,
    code: StatefulContract.HalfDecoded,
    initialStateHash: Hash,      // the state hash when the contract is created
    initialFields: AVector[Val], // the initial field values when the contract is loaded
    fields: mutable.ArraySeq[Val],
    contractId: ContractId
) extends ContractObj[StatefulContext] {
  def contractIdOpt: Option[ContractId] = Some(contractId)

  def getInitialStateHash(): Val.ByteVec = Val.ByteVec(initialStateHash.bytes)

  def getCodeHash(): Val.ByteVec = Val.ByteVec(codeHash.bytes)

  def isUpdated: Boolean = !fields.indices.forall(index => fields(index) == initialFields(index))

  def estimateByteSize(): Int =
    fields.foldLeft(0)(_ + _.estimateByteSize()) + code.methodsBytes.length
}

object StatefulContractObject {
  def unsafe(
      codeHash: Hash,
      code: StatefulContract.HalfDecoded,
      initialStateHash: Hash,
      initialFields: AVector[Val],
      contractId: ContractId
  ): StatefulContractObject = {
    assume(code.hash == codeHash)
    new StatefulContractObject(
      codeHash,
      code,
      initialStateHash,
      initialFields,
      initialFields.toArray,
      contractId
    )
  }

  def from(
      contract: StatefulContract,
      initialFields: AVector[Val],
      contractId: ContractId
  ): StatefulContractObject = {
    val code             = contract.toHalfDecoded()
    val codeHash         = code.hash
    val initialStateHash = code.initialStateHash(initialFields)
    unsafe(codeHash, code, initialStateHash, initialFields, contractId)
  }
}
