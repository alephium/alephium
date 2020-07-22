package org.alephium.util

class EitherFSpec extends AlephiumSpec {
  it should "foreach for positive case" in {
    forAll { ns: Seq[Int] =>
      var sum    = 0
      val result = EitherF.foreachTry[Int, Unit](ns)(n => Right(sum += n))
      result.isRight is true
      sum is ns.sum
    }
  }

  it should "foreach for negative case" in {
    forAll { ns: Seq[Int] =>
      if (ns.nonEmpty) {
        val r = ns(Random.source.nextInt(ns.length))
        val result = EitherF.foreachTry[Int, Unit](ns) { n =>
          if (n.equals(r)) Left(()) else Right(())
        }
        result.isLeft is true
      }
    }
  }

  it should "fold for positive case" in {
    forAll { ns: Seq[Int] =>
      val result = EitherF.foldTry[Int, Unit, Int](ns, 0) { case (acc, n) => Right(acc + n) }
      result isE ns.sum
    }
  }

  it should "fold for negative case" in {
    forAll { ns: Seq[Int] =>
      if (ns.nonEmpty) {
        val r = ns(Random.source.nextInt(ns.length))
        val result = EitherF.foldTry[Int, Unit, Unit](ns, ()) {
          case (_, n) => if (n.equals(r)) Left(()) else Right(())
        }
        result.isLeft is true
      }
    }
  }
}
