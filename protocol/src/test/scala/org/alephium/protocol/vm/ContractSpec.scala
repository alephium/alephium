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

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.protocol.Hash
import org.alephium.protocol.config.NetworkConfigFixture
import org.alephium.protocol.model.HardFork
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Bytes}
import org.alephium.util.Hex.HexStringSyntax

class ContractSpec extends AlephiumSpec {
  trait ScriptFixture[Ctx <: StatelessContext] {
    val method = Method[Ctx](
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = false,
      usePayToContractOnly = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector.empty
    )

    def pass0(method: Method[Ctx]): Assertion = {
      pass1(AVector(method))
    }

    def pass1(methods: AVector[Method[Ctx]]): Assertion

    def fail0(method: Method[Ctx]): Assertion = {
      fail1(AVector(method))
    }

    def fail1(methods: AVector[Method[Ctx]]): Assertion
  }

  it should "validate stateless scripts" in new ScriptFixture[StatelessContext] {
    def pass1(methods: AVector[Method[StatelessContext]]): Assertion = {
      val script = StatelessScript.unsafe(methods)
      deserialize[StatelessScript](serialize(script)) isE script
    }
    def fail1(methods: AVector[Method[StatelessContext]]): Assertion = {
      val script = StatelessScript.unsafe(methods)
      deserialize[StatelessScript](serialize(script)).leftValue is a[SerdeError.Validation]
    }

    pass0(method)
    pass1(AVector(method, method))
    fail1(AVector.empty)
    fail0(method.copy(isPublic = false))
    fail0(method.copy(usePreapprovedAssets = true))
    fail0(method.copy(argsLength = -1))
    fail0(method.copy(localsLength = -1))
    fail0(method.copy(returnLength = -1))
    fail0(method.copy(argsLength = 1, localsLength = 0))
    pass1(AVector(method, method.copy(isPublic = false)))
    fail1(AVector(method, method.copy(usePreapprovedAssets = true)))
    fail1(AVector(method, method.copy(argsLength = -1)))
    fail1(AVector(method, method.copy(localsLength = -1)))
    fail1(AVector(method, method.copy(returnLength = -1)))
    fail1(AVector(method, method.copy(argsLength = 1, localsLength = 0)))
  }

  it should "validate stateful scripts" in new ScriptFixture[StatefulContext] {
    def pass1(methods: AVector[Method[StatefulContext]]): Assertion = {
      val script = StatefulScript.unsafe(methods)
      deserialize[StatefulScript](serialize(script)) isE script
    }
    def fail1(methods: AVector[Method[StatefulContext]]): Assertion = {
      val script = StatefulScript.unsafe(methods)
      deserialize[StatefulScript](serialize(script)).leftValue is a[SerdeError.Validation]
    }

    pass0(method)
    pass1(AVector(method, method))
    fail1(AVector.empty)
    fail0(method.copy(isPublic = false))
    fail0(method.copy(argsLength = -1))
    fail0(method.copy(localsLength = -1))
    fail0(method.copy(returnLength = -1))
    fail0(method.copy(argsLength = 1, localsLength = 0))
    pass1(AVector(method, method.copy(isPublic = false)))
    fail1(AVector(method, method.copy(argsLength = -1)))
    fail1(AVector(method, method.copy(localsLength = -1)))
    fail1(AVector(method, method.copy(returnLength = -1)))
    fail1(AVector(method, method.copy(argsLength = 1, localsLength = 0)))
  }

  it should "not validate empty scripts" in {
    def check(contract: StatefulContract, result: Any) = {
      val result0 = StatefulContract.check(contract, HardFork.Mainnet)
      val result1 = StatefulContract.check(contract, HardFork.SinceLemanForTest)

      result match {
        case error: ExeFailure =>
          result0.leftValue isE error
          result1.leftValue isE error
        case _ =>
          result0 isE ()
          result1 isE ()
      }
    }

    val contract0 = StatefulContract(0, AVector.empty)
    check(contract0, EmptyMethods)
    val contract1 = StatefulContract(-1, AVector.empty)
    check(contract1, InvalidFieldLength)

    val method = Method[StatefulContext](
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = false,
      usePayToContractOnly = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector.empty
    )
    val contract2 = StatefulContract(0, AVector(method))
    check(contract2, ())
    val contract3 = StatefulContract(0, AVector(method.copy(argsLength = -1)))
    check(contract3, InvalidMethod)
    val contract4 = StatefulContract(0, AVector(method.copy(localsLength = -1)))
    check(contract4, InvalidMethod)
    val contract5 = StatefulContract(0, AVector(method.copy(returnLength = -1)))
    check(contract5, InvalidMethod)
    val contract6 = StatefulContract(0, AVector(method, method.copy(argsLength = -1)))
    check(contract6, InvalidMethod)
    val contract7 = StatefulContract(0, AVector(method, method.copy(localsLength = -1)))
    check(contract7, InvalidMethod)
    val contract8 = StatefulContract(0, AVector(method, method.copy(returnLength = -1)))
    check(contract8, InvalidMethod)
    val contract9 = StatefulContract(0, AVector(method, method))
    check(contract9, ())
    val contract10 = StatefulContract(0, AVector(method.copy(argsLength = 1, localsLength = 0)))
    check(contract10, InvalidMethod)
    val contract11 =
      StatefulContract(0, AVector(method, method.copy(argsLength = 1, localsLength = 0)))
    check(contract11, InvalidMethod)

    info("Check field length")
    val contract12 = StatefulContract(0xff, AVector(method))
    check(contract12, ())
    val contract13 = StatefulContract(0xff + 1, AVector(method))
    StatefulContract.check(contract13, HardFork.Mainnet) isE ()
    StatefulContract.check(contract13, HardFork.SinceLemanForTest).leftValue isE TooManyFields
  }

  trait MethodsFixture {
    val statelessOldMethod0 = OldMethod[StatelessContext](true, true, 3, 4, 5, AVector.empty)
    val statelessOldMethod1 = OldMethod[StatelessContext](true, false, 3, 4, 5, AVector.empty)
    val statefulOldMethod0  = OldMethod[StatefulContext](true, true, 3, 4, 5, AVector.empty)
    val statefulOldMethod1  = OldMethod[StatefulContext](true, false, 3, 4, 5, AVector.empty)

    val statelessMethod0 = Method[StatelessContext](true, true, true, false, 3, 4, 5, AVector.empty)
    val statelessMethod1 =
      Method[StatelessContext](true, false, false, false, 3, 4, 5, AVector.empty)
    val statelessMethod2 =
      Method[StatelessContext](true, true, false, false, 3, 4, 5, AVector.empty)
    val statelessMethod3 =
      Method[StatelessContext](true, false, true, false, 3, 4, 5, AVector.empty)
    val statelessMethod4 = Method[StatelessContext](true, true, true, true, 3, 4, 5, AVector.empty)
    val statelessMethod5 =
      Method[StatelessContext](true, false, false, true, 3, 4, 5, AVector.empty)
    val statelessMethod6 = Method[StatelessContext](true, true, false, true, 3, 4, 5, AVector.empty)
    val statelessMethod7 = Method[StatelessContext](true, false, true, true, 3, 4, 5, AVector.empty)
    val statefulMethod0  = Method[StatefulContext](true, true, true, false, 3, 4, 5, AVector.empty)
    val statefulMethod1 = Method[StatefulContext](true, false, false, false, 3, 4, 5, AVector.empty)
    val statefulMethod2 = Method[StatefulContext](true, true, false, false, 3, 4, 5, AVector.empty)
    val statefulMethod3 = Method[StatefulContext](true, false, true, false, 3, 4, 5, AVector.empty)
    val statefulMethod4 = Method[StatefulContext](true, true, true, true, 3, 4, 5, AVector.empty)
    val statefulMethod5 = Method[StatefulContext](true, false, false, true, 3, 4, 5, AVector.empty)
    val statefulMethod6 = Method[StatefulContext](true, true, false, true, 3, 4, 5, AVector.empty)
    val statefulMethod7 = Method[StatefulContext](true, false, true, true, 3, 4, 5, AVector.empty)
  }

  it should "serialize method examples" in new MethodsFixture {
    serialize(statelessOldMethod0) is hex"010103040500"
    serialize(statelessOldMethod1) is hex"010003040500"
    serialize(statefulOldMethod0) is hex"010103040500"
    serialize(statefulOldMethod1) is hex"010003040500"

    serialize(statelessMethod0) is serialize(statelessOldMethod0)
    serialize(statelessMethod1) is serialize(statelessOldMethod1)
    serialize(statelessMethod2) is hex"010303040500"
    serialize(statelessMethod3) is hex"010203040500"
    serialize(statelessMethod4) is hex"010503040500"
    serialize(statelessMethod5) is hex"010403040500"
    serialize(statelessMethod6) is hex"010703040500"
    serialize(statelessMethod7) is hex"010603040500"
    serialize(statefulMethod0) is serialize(statefulOldMethod0)
    serialize(statefulMethod1) is serialize(statefulOldMethod1)
    serialize(statefulMethod2) is hex"010303040500"
    serialize(statefulMethod3) is hex"010203040500"
    serialize(statefulMethod4) is hex"010503040500"
    serialize(statefulMethod5) is hex"010403040500"
    serialize(statefulMethod6) is hex"010703040500"
    serialize(statefulMethod7) is hex"010603040500"
  }

  it should "serde methods" in {
    for {
      isPublic             <- Seq(true, false)
      usePreapprovedAssets <- Seq(true, false)
      useContractAssetss   <- Seq(true, false)
      usePayToContractOnly <- Seq(true, false)
    } {
      val statelessMethods =
        Method[StatelessContext](
          isPublic,
          usePreapprovedAssets,
          useContractAssetss,
          usePayToContractOnly,
          3,
          4,
          5,
          AVector.empty
        )
      deserialize[Method[StatelessContext]](serialize(statelessMethods)) isE statelessMethods

      val statefulMethods =
        Method[StatefulContext](
          isPublic,
          usePreapprovedAssets,
          useContractAssetss,
          usePayToContractOnly,
          3,
          4,
          5,
          AVector.empty
        )
      deserialize[Method[StatefulContext]](serialize(statefulMethods)) isE statefulMethods
    }
  }

  it should "check method modifier compatibility" in new MethodsFixture {
    statelessMethod0.checkModifierPreLeman() isE ()
    statelessMethod1.checkModifierPreLeman() isE ()
    statelessMethod2.checkModifierPreLeman().leftValue isE InvalidMethodModifierBeforeLeman
    statelessMethod3.checkModifierPreLeman().leftValue isE InvalidMethodModifierBeforeLeman

    statefulMethod0.checkModifierPreLeman() isE ()
    statefulMethod1.checkModifierPreLeman() isE ()
    statefulMethod2.checkModifierPreLeman().leftValue isE InvalidMethodModifierBeforeLeman
    statefulMethod3.checkModifierPreLeman().leftValue isE InvalidMethodModifierBeforeLeman
  }

  trait ContractFixture extends MethodsFixture with ContextGenerators {
    val preLemanContext = genStatefulContext(None)(NetworkConfigFixture.Genesis)
    val lemanContext    = genStatefulContext(None)(NetworkConfigFixture.Leman)
    val rhoneContext    = genStatefulContext(None)(NetworkConfigFixture.Rhone)
  }

  it should "check method modifier in contracts" in new ContractFixture {
    val contracts: Seq[Contract[_]] =
      Seq(
        statelessMethod0,
        statelessMethod1,
        statelessMethod2,
        statelessMethod3,
        statelessMethod4,
        statelessMethod5,
        statelessMethod6,
        statelessMethod7
      ).map(method => StatelessScript.unsafe(AVector(method)))

    contracts.zipWithIndex.foreach { case (method, index) =>
      if (index == 4 || index == 7) {
        // Cannot enable useContractAssets and usePayToContractOnly together
        method.checkAssetsModifier(rhoneContext).leftValue isE InvalidMethodModifierSinceRhone
      } else {
        method.checkAssetsModifier(rhoneContext) isE ()
      }
    }

    contracts(0).checkAssetsModifier(preLemanContext) isE ()
    contracts(1).checkAssetsModifier(preLemanContext) isE ()
    contracts(2).checkAssetsModifier(preLemanContext).leftValue isE InvalidMethodModifierBeforeLeman
    contracts(3).checkAssetsModifier(preLemanContext).leftValue isE InvalidMethodModifierBeforeLeman
    contracts
      .drop(4)
      .foreach(
        _.checkAssetsModifier(preLemanContext).leftValue isE InvalidMethodModifierBeforeRhone
      )

    contracts(0).checkAssetsModifier(lemanContext) isE ()
    contracts(1).checkAssetsModifier(lemanContext) isE ()
    contracts(2).checkAssetsModifier(lemanContext) isE ()
    contracts(3).checkAssetsModifier(lemanContext) isE ()
    contracts
      .drop(4)
      .foreach(_.checkAssetsModifier(lemanContext).leftValue isE InvalidMethodModifierBeforeRhone)
  }

  it should "serde Method.Selector" in {
    def test(index: Int, encoded: ByteString) = {
      val selector = Method.Selector(index)
      serialize(selector) is encoded
      deserialize[Method.Selector](encoded).rightValue is selector
    }

    test(0, ByteString(0, 0, 0, 0))
    test(0xffffffff, ByteString(0xff, 0xff, 0xff, 0xff))
  }

  trait MethodSelectorFixture {
    private val instrs = Instr.statelessInstrs0.filter(_.isInstanceOf[Instr[_]])
    def methodGen: Gen[Method[StatefulContext]] = {
      val size         = Random.nextInt(instrs.length)
      val methodInstrs = instrs.take(size)
      Gen.const(()).map { _ =>
        Method(
          isPublic = Random.nextBoolean(),
          usePreapprovedAssets = Random.nextBoolean(),
          useContractAssets = Random.nextBoolean(),
          usePayToContractOnly = false,
          argsLength = Random.nextInt(3),
          localsLength = Random.nextInt(3),
          returnLength = Random.nextInt(3),
          instrs = methodInstrs.asInstanceOf[AVector[Instr[StatefulContext]]]
        )
      }
    }
  }

  it should "not extract method selector for private functions" in new MethodSelectorFixture {
    val method0 = methodGen.sample.get.copy(isPublic = false)
    method0.instrs.head isnot a[MethodSelector]
    Method.extractSelector(serialize(method0)) is None

    val instrs  = MethodSelector(Method.Selector(1)) +: method0.instrs.toSeq
    val method1 = method0.copy(isPublic = false, instrs = AVector.from(instrs))
    method1.instrs.head is a[MethodSelector]
    Method.extractSelector(serialize(method1)) is None
  }

  it should "search method by selector" in new MethodSelectorFixture {
    @scala.annotation.tailrec
    def genContract(): (StatefulContract, Seq[Option[Method.Selector]]) = {
      val generated = (0 until 20).map { index =>
        val hasSelector = Random.nextBoolean()
        val method      = methodGen.sample.get
        if (hasSelector) {
          val selector =
            Method.Selector(Bytes.toIntUnsafe(Hash.hash(serialize(index)).bytes.take(4)))
          val newInstrs = MethodSelector(selector) +: method.instrs.toSeq
          val newMethod = method.copy(instrs = AVector.from(newInstrs))
          if (method.isPublic) {
            Method.extractSelector(serialize(newMethod)) is Some(selector)
            (newMethod, Some(selector))
          } else {
            Method.extractSelector(serialize(newMethod)) is None
            (newMethod, None)
          }
        } else {
          Method.extractSelector(serialize(method)) is None
          (method, None)
        }
      }
      val methods   = AVector.from(generated.map(_._1))
      val selectors = generated.map(_._2)
      val contract  = StatefulContract(Random.nextInt(5), methods)
      if (selectors.count(_.isDefined) >= 2) (contract, selectors) else genContract()
    }

    val (contract, selectors) = genContract()

    {
      info("search by order")
      val halfDecoded = contract.toHalfDecoded()
      halfDecoded.searchedMethodIndex is -1
      selectors.zipWithIndex.foreach {
        case (Some(selector), index) =>
          val lastIndex = selectors.take(index).lastIndexWhere(_.isDefined)
          val expected  = StatefulContract.SelectorSearchResult(index, index - lastIndex)
          halfDecoded.getMethodBySelector(selector).rightValue is expected
          halfDecoded.searchedMethodIndex is index
        case _ => ()
      }
    }

    {
      info("search the last one first")
      val halfDecoded = contract.toHalfDecoded()
      halfDecoded.searchedMethodIndex is -1
      val selector = selectors.findLast(_.isDefined).value.value
      val index    = selectors.lastIndexWhere(_.isDefined)
      val expected = StatefulContract.SelectorSearchResult(index, index + 1)
      halfDecoded.getMethodBySelector(selector).rightValue is expected
      selectors.take(index).zipWithIndex.foreach {
        case (Some(selector), index) =>
          halfDecoded.getMethodBySelector(selector).rightValue is StatefulContract
            .SelectorSearchResult(index, 0)
        case _ => ()
      }
    }

    {
      info("search by random order")
      val halfDecoded = contract.toHalfDecoded()
      halfDecoded.searchedMethodIndex is -1
      val allMethodSearched = AVector.from(selectors.zipWithIndex).shuffle().map {
        case (Some(selector), index) =>
          val result = halfDecoded.getMethodBySelector(selector).rightValue
          result.methodIndex is index
          result.methodSearched
        case _ => 0
      }
      allMethodSearched.sum is (selectors.lastIndexWhere(_.isDefined) + 1)
    }

    {
      info("selector does not exist")
      val halfDecoded    = contract.toHalfDecoded()
      val randomSelector = Method.Selector(Random.nextInt())
      selectors.exists(_.contains(randomSelector)) is false
      halfDecoded
        .getMethodBySelector(randomSelector)
        .leftValue
        .rightValue is a[InvalidMethodSelector]
    }
  }
}

final case class OldMethod[Ctx <: StatelessContext](
    isPublic: Boolean,
    usePreapprovedAssets: Boolean,
    argsLength: Int,
    localsLength: Int,
    returnLength: Int,
    instrs: AVector[Instr[Ctx]]
)
object OldMethod {
  implicit val statelessSerde: Serde[OldMethod[StatelessContext]] =
    Serde.forProduct6(
      OldMethod[StatelessContext],
      t =>
        (t.isPublic, t.usePreapprovedAssets, t.argsLength, t.localsLength, t.returnLength, t.instrs)
    )
  implicit val statefulSerde: Serde[OldMethod[StatefulContext]] =
    Serde.forProduct6(
      OldMethod[StatefulContext],
      t =>
        (t.isPublic, t.usePreapprovedAssets, t.argsLength, t.localsLength, t.returnLength, t.instrs)
    )
}
