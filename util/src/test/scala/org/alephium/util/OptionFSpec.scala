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

import scala.util.Random

class OptionFSpec extends AlephiumSpec {
  it should "fold for positive case" in {
    forAll { ns: Seq[Int] =>
      val result = OptionF.fold[Int, Int](ns, 0) { case (acc, n) => Some(acc + n) }
      result.value is ns.sum
    }
  }

  it should "fold for negative case" in {
    forAll { ns: Seq[Int] =>
      if (ns.nonEmpty) {
        val r = ns(Random.nextInt(ns.length))
        val result = OptionF.fold[Int, Unit](ns, ()) { case (_, n) =>
          if (n.equals(r)) None else Some(())
        }
        result is None
      }
    }
  }

  it should "get any" in {
    val ns = Seq(1, 2, 3)
    OptionF.getAny(ns)(n => if (n == 1) Some(1) else None) is Some(1)
    OptionF.getAny(ns)(n => if (n == 2) Some(2) else None) is Some(2)
    OptionF.getAny(ns)(n => if (n == 3) Some(3) else None) is Some(3)
    OptionF.getAny(ns)(_ => None) is None
  }
}
