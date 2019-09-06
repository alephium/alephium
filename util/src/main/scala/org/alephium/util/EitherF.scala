package org.alephium.util

object EitherF {
  // scalastyle:off return
  def fold[E, L, R](elems: Iterable[E], zero: R)(op: (R, E) => Either[L, R]): Either[L, R] = {
    var result = zero
    elems.foreach { e =>
      op(result, e) match {
        case Left(l)  => return Left(l)
        case Right(r) => result = r
      }
    }
    Right(result)
  }
  // scalastyle:on return
}
