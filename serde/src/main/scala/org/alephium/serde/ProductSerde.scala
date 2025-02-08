// auto-generated boilerplate
package org.alephium.serde

import akka.util.ByteString

private[serde] trait ProductSerde {

  final def forProduct1[A0, T](pack: (A0) => T, unpack: T => (A0))(implicit
    serdeA0: Serde[A0]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0) = unpack(input)
      serdeA0.serialize(a0)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest)
      } yield Staging(pack(pair0.value), pair0.rest)
    }
  }

  final def tuple1[A0](implicit serdeA0: Serde[A0]): Serde[(A0)] = new Serde[(A0)] {
    override def serialize(input: (A0)): ByteString = {
      val (a0) = input
      serdeA0.serialize(a0)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest)
      } yield Staging((pair0.value), pair0.rest)
    }
  }

  final def forProduct2[A0, A1, T](pack: (A0, A1) => T, unpack: T => (A0, A1))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest)
      } yield Staging(pack(pair0.value, pair1.value), pair1.rest)
    }
  }

  final def tuple2[A0, A1](implicit serdeA0: Serde[A0], serdeA1: Serde[A1]): Serde[(A0, A1)] = new Serde[(A0, A1)] {
    override def serialize(input: (A0, A1)): ByteString = {
      val (a0, a1) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest)
      } yield Staging((pair0.value, pair1.value), pair1.rest)
    }
  }

  final def forProduct3[A0, A1, A2, T](pack: (A0, A1, A2) => T, unpack: T => (A0, A1, A2))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest)
      } yield Staging(pack(pair0.value, pair1.value, pair2.value), pair2.rest)
    }
  }

  final def tuple3[A0, A1, A2](implicit serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2]): Serde[(A0, A1, A2)] = new Serde[(A0, A1, A2)] {
    override def serialize(input: (A0, A1, A2)): ByteString = {
      val (a0, a1, a2) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1, A2)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest)
      } yield Staging((pair0.value, pair1.value, pair2.value), pair2.rest)
    }
  }

  final def forProduct4[A0, A1, A2, A3, T](pack: (A0, A1, A2, A3) => T, unpack: T => (A0, A1, A2, A3))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest)
      } yield Staging(pack(pair0.value, pair1.value, pair2.value, pair3.value), pair3.rest)
    }
  }

  final def tuple4[A0, A1, A2, A3](implicit serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3]): Serde[(A0, A1, A2, A3)] = new Serde[(A0, A1, A2, A3)] {
    override def serialize(input: (A0, A1, A2, A3)): ByteString = {
      val (a0, a1, a2, a3) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1, A2, A3)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest)
      } yield Staging((pair0.value, pair1.value, pair2.value, pair3.value), pair3.rest)
    }
  }

  final def forProduct5[A0, A1, A2, A3, A4, T](pack: (A0, A1, A2, A3, A4) => T, unpack: T => (A0, A1, A2, A3, A4))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest)
      } yield Staging(pack(pair0.value, pair1.value, pair2.value, pair3.value, pair4.value), pair4.rest)
    }
  }

  final def tuple5[A0, A1, A2, A3, A4](implicit serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4]): Serde[(A0, A1, A2, A3, A4)] = new Serde[(A0, A1, A2, A3, A4)] {
    override def serialize(input: (A0, A1, A2, A3, A4)): ByteString = {
      val (a0, a1, a2, a3, a4) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1, A2, A3, A4)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest)
      } yield Staging((pair0.value, pair1.value, pair2.value, pair3.value, pair4.value), pair4.rest)
    }
  }

  final def forProduct6[A0, A1, A2, A3, A4, A5, T](pack: (A0, A1, A2, A3, A4, A5) => T, unpack: T => (A0, A1, A2, A3, A4, A5))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest)
      } yield Staging(pack(pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value), pair5.rest)
    }
  }

  final def tuple6[A0, A1, A2, A3, A4, A5](implicit serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5]): Serde[(A0, A1, A2, A3, A4, A5)] = new Serde[(A0, A1, A2, A3, A4, A5)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5)): ByteString = {
      val (a0, a1, a2, a3, a4, a5) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1, A2, A3, A4, A5)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest)
      } yield Staging((pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value), pair5.rest)
    }
  }

  final def forProduct7[A0, A1, A2, A3, A4, A5, A6, T](pack: (A0, A1, A2, A3, A4, A5, A6) => T, unpack: T => (A0, A1, A2, A3, A4, A5, A6))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5], serdeA6: Serde[A6]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest); pair6 <- serdeA6._deserialize(pair5.rest)
      } yield Staging(pack(pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value, pair6.value), pair6.rest)
    }
  }

  final def tuple7[A0, A1, A2, A3, A4, A5, A6](implicit serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5], serdeA6: Serde[A6]): Serde[(A0, A1, A2, A3, A4, A5, A6)] = new Serde[(A0, A1, A2, A3, A4, A5, A6)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5, A6)): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1, A2, A3, A4, A5, A6)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest); pair6 <- serdeA6._deserialize(pair5.rest)
      } yield Staging((pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value, pair6.value), pair6.rest)
    }
  }

  final def forProduct8[A0, A1, A2, A3, A4, A5, A6, A7, T](pack: (A0, A1, A2, A3, A4, A5, A6, A7) => T, unpack: T => (A0, A1, A2, A3, A4, A5, A6, A7))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5], serdeA6: Serde[A6], serdeA7: Serde[A7]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest); pair6 <- serdeA6._deserialize(pair5.rest); pair7 <- serdeA7._deserialize(pair6.rest)
      } yield Staging(pack(pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value, pair6.value, pair7.value), pair7.rest)
    }
  }

  final def tuple8[A0, A1, A2, A3, A4, A5, A6, A7](implicit serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5], serdeA6: Serde[A6], serdeA7: Serde[A7]): Serde[(A0, A1, A2, A3, A4, A5, A6, A7)] = new Serde[(A0, A1, A2, A3, A4, A5, A6, A7)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5, A6, A7)): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1, A2, A3, A4, A5, A6, A7)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest); pair6 <- serdeA6._deserialize(pair5.rest); pair7 <- serdeA7._deserialize(pair6.rest)
      } yield Staging((pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value, pair6.value, pair7.value), pair7.rest)
    }
  }

  final def forProduct9[A0, A1, A2, A3, A4, A5, A6, A7, A8, T](pack: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => T, unpack: T => (A0, A1, A2, A3, A4, A5, A6, A7, A8))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5], serdeA6: Serde[A6], serdeA7: Serde[A7], serdeA8: Serde[A8]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7, a8) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7) ++ serdeA8.serialize(a8)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest); pair6 <- serdeA6._deserialize(pair5.rest); pair7 <- serdeA7._deserialize(pair6.rest); pair8 <- serdeA8._deserialize(pair7.rest)
      } yield Staging(pack(pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value, pair6.value, pair7.value, pair8.value), pair8.rest)
    }
  }

  final def tuple9[A0, A1, A2, A3, A4, A5, A6, A7, A8](implicit serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5], serdeA6: Serde[A6], serdeA7: Serde[A7], serdeA8: Serde[A8]): Serde[(A0, A1, A2, A3, A4, A5, A6, A7, A8)] = new Serde[(A0, A1, A2, A3, A4, A5, A6, A7, A8)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5, A6, A7, A8)): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7, a8) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7) ++ serdeA8.serialize(a8)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1, A2, A3, A4, A5, A6, A7, A8)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest); pair6 <- serdeA6._deserialize(pair5.rest); pair7 <- serdeA7._deserialize(pair6.rest); pair8 <- serdeA8._deserialize(pair7.rest)
      } yield Staging((pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value, pair6.value, pair7.value, pair8.value), pair8.rest)
    }
  }

  final def forProduct10[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, T](pack: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => T, unpack: T => (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9))(implicit
    serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5], serdeA6: Serde[A6], serdeA7: Serde[A7], serdeA8: Serde[A8], serdeA9: Serde[A9]
  ): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7) ++ serdeA8.serialize(a8) ++ serdeA9.serialize(a9)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest); pair6 <- serdeA6._deserialize(pair5.rest); pair7 <- serdeA7._deserialize(pair6.rest); pair8 <- serdeA8._deserialize(pair7.rest); pair9 <- serdeA9._deserialize(pair8.rest)
      } yield Staging(pack(pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value, pair6.value, pair7.value, pair8.value, pair9.value), pair9.rest)
    }
  }

  final def tuple10[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9](implicit serdeA0: Serde[A0], serdeA1: Serde[A1], serdeA2: Serde[A2], serdeA3: Serde[A3], serdeA4: Serde[A4], serdeA5: Serde[A5], serdeA6: Serde[A6], serdeA7: Serde[A7], serdeA8: Serde[A8], serdeA9: Serde[A9]): Serde[(A0, A1, A2, A3, A4, A5, A6, A7, A8, A9)] = new Serde[(A0, A1, A2, A3, A4, A5, A6, A7, A8, A9)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9)): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7) ++ serdeA8.serialize(a8) ++ serdeA9.serialize(a9)
    }

    override def _deserialize(rest: ByteString): SerdeResult[Staging[(A0, A1, A2, A3, A4, A5, A6, A7, A8, A9)]] = {
      for {
        pair0 <- serdeA0._deserialize(rest); pair1 <- serdeA1._deserialize(pair0.rest); pair2 <- serdeA2._deserialize(pair1.rest); pair3 <- serdeA3._deserialize(pair2.rest); pair4 <- serdeA4._deserialize(pair3.rest); pair5 <- serdeA5._deserialize(pair4.rest); pair6 <- serdeA6._deserialize(pair5.rest); pair7 <- serdeA7._deserialize(pair6.rest); pair8 <- serdeA8._deserialize(pair7.rest); pair9 <- serdeA9._deserialize(pair8.rest)
      } yield Staging((pair0.value, pair1.value, pair2.value, pair3.value, pair4.value, pair5.value, pair6.value, pair7.value, pair8.value, pair9.value), pair9.rest)
    }
  }
}