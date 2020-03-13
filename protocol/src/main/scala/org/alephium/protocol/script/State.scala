package org.alephium.protocol.script

class State[S, T](val run: S => (RunResult[T], S)) {
  def foreach[R](f: T => R): State[S, R] = {
    new State(s0 => {
      val (result, s1) = run(s0)
      (result.map(f), s1)
    })
  }

  def flatMap[R](f: T => State[S, R]): State[S, R] = {
    new State(s0 => {
      val (result0, s1) = run(s0)
      result0 match {
        case Left(error) => (Left(error), s1)
        case Right(t)    => f(t).run.apply(s1)
      }
    })
  }
}
