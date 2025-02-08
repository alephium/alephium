// auto-generated boilerplate
package org.alephium.serde

import org.alephium.util.AlephiumSpec

class ProductSerdeSpec extends AlephiumSpec {

  behavior of "Serde for case class"

  case class Test1(a0: Int)
  object Test1 {
    implicit val serde: Serde[Test1] = Serde.forProduct1(apply, t => (t.a0))
  }

  it should "serde 1 fields" in {
    forAll { (a0: Int) =>
      val input  = Test1(a0)
      val output = deserialize[Test1](serialize(input)).rightValue
      output is input
    }
  }

  it should "serde 1 tuple" in {
    forAll { (a0: Int) =>
      val input  = (a0)
      val serde = Serde.tuple1[Int]
      val output = serde.deserialize(serde.serialize(input)).rightValue
      output is input
    }
  }

  case class Test2(a0: Int, a1: Int)
  object Test2 {
    implicit val serde: Serde[Test2] = Serde.forProduct2(apply, t => (t.a0, t.a1))
  }

  it should "serde 2 fields" in {
    forAll { (a0: Int, a1: Int) =>
      val input  = Test2(a0, a1)
      val output = deserialize[Test2](serialize(input)).rightValue
      output is input
    }
  }

  it should "serde 2 tuple" in {
    forAll { (a0: Int, a1: Int) =>
      val input  = (a0, a1)
      val serde = Serde.tuple2[Int, Int]
      val output = serde.deserialize(serde.serialize(input)).rightValue
      output is input
    }
  }

  case class Test3(a0: Int, a1: Int, a2: Int)
  object Test3 {
    implicit val serde: Serde[Test3] = Serde.forProduct3(apply, t => (t.a0, t.a1, t.a2))
  }

  it should "serde 3 fields" in {
    forAll { (a0: Int, a1: Int, a2: Int) =>
      val input  = Test3(a0, a1, a2)
      val output = deserialize[Test3](serialize(input)).rightValue
      output is input
    }
  }

  it should "serde 3 tuple" in {
    forAll { (a0: Int, a1: Int, a2: Int) =>
      val input  = (a0, a1, a2)
      val serde = Serde.tuple3[Int, Int, Int]
      val output = serde.deserialize(serde.serialize(input)).rightValue
      output is input
    }
  }

  case class Test4(a0: Int, a1: Int, a2: Int, a3: Int)
  object Test4 {
    implicit val serde: Serde[Test4] = Serde.forProduct4(apply, t => (t.a0, t.a1, t.a2, t.a3))
  }

  it should "serde 4 fields" in {
    forAll { (a0: Int, a1: Int, a2: Int, a3: Int) =>
      val input  = Test4(a0, a1, a2, a3)
      val output = deserialize[Test4](serialize(input)).rightValue
      output is input
    }
  }

  it should "serde 4 tuple" in {
    forAll { (a0: Int, a1: Int, a2: Int, a3: Int) =>
      val input  = (a0, a1, a2, a3)
      val serde = Serde.tuple4[Int, Int, Int, Int]
      val output = serde.deserialize(serde.serialize(input)).rightValue
      output is input
    }
  }

  case class Test5(a0: Int, a1: Int, a2: Int, a3: Int, a4: Int)
  object Test5 {
    implicit val serde: Serde[Test5] = Serde.forProduct5(apply, t => (t.a0, t.a1, t.a2, t.a3, t.a4))
  }

  it should "serde 5 fields" in {
    forAll { (a0: Int, a1: Int, a2: Int, a3: Int, a4: Int) =>
      val input  = Test5(a0, a1, a2, a3, a4)
      val output = deserialize[Test5](serialize(input)).rightValue
      output is input
    }
  }

  it should "serde 5 tuple" in {
    forAll { (a0: Int, a1: Int, a2: Int, a3: Int, a4: Int) =>
      val input  = (a0, a1, a2, a3, a4)
      val serde = Serde.tuple5[Int, Int, Int, Int, Int]
      val output = serde.deserialize(serde.serialize(input)).rightValue
      output is input
    }
  }

  case class Test6(a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int)
  object Test6 {
    implicit val serde: Serde[Test6] = Serde.forProduct6(apply, t => (t.a0, t.a1, t.a2, t.a3, t.a4, t.a5))
  }

  it should "serde 6 fields" in {
    forAll { (a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int) =>
      val input  = Test6(a0, a1, a2, a3, a4, a5)
      val output = deserialize[Test6](serialize(input)).rightValue
      output is input
    }
  }

  it should "serde 6 tuple" in {
    forAll { (a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int) =>
      val input  = (a0, a1, a2, a3, a4, a5)
      val serde = Serde.tuple6[Int, Int, Int, Int, Int, Int]
      val output = serde.deserialize(serde.serialize(input)).rightValue
      output is input
    }
  }
}