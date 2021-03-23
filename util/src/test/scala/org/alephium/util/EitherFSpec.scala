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
        val result = EitherF.foldTry[Int, Unit, Unit](ns, ()) { case (_, n) =>
          if (n.equals(r)) Left(()) else Right(())
        }
        result.isLeft is true
      }
    }
  }
}
