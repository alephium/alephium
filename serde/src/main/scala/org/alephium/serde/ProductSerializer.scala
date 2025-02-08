// auto-generated boilerplate
package org.alephium.serde

import akka.util.ByteString

private[serde] trait ProductSerializer {

  final def forProduct1[A0, T](unpack: T => (A0))(implicit
    serdeA0: Serializer[A0]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0) = unpack(input)
      serdeA0.serialize(a0)
    }
  }

  final def tuple1[A0](implicit serdeA0: Serializer[A0]): Serializer[(A0)] = new Serializer[(A0)] {
    override def serialize(input: (A0)): ByteString = {
      val (a0) = input
      serdeA0.serialize(a0)
    }
  }

  final def forProduct2[A0, A1, T](unpack: T => (A0, A1))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1)
    }
  }

  final def tuple2[A0, A1](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1]): Serializer[(A0, A1)] = new Serializer[(A0, A1)] {
    override def serialize(input: (A0, A1)): ByteString = {
      val (a0, a1) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1)
    }
  }

  final def forProduct3[A0, A1, A2, T](unpack: T => (A0, A1, A2))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2)
    }
  }

  final def tuple3[A0, A1, A2](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2]): Serializer[(A0, A1, A2)] = new Serializer[(A0, A1, A2)] {
    override def serialize(input: (A0, A1, A2)): ByteString = {
      val (a0, a1, a2) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2)
    }
  }

  final def forProduct4[A0, A1, A2, A3, T](unpack: T => (A0, A1, A2, A3))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3)
    }
  }

  final def tuple4[A0, A1, A2, A3](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3]): Serializer[(A0, A1, A2, A3)] = new Serializer[(A0, A1, A2, A3)] {
    override def serialize(input: (A0, A1, A2, A3)): ByteString = {
      val (a0, a1, a2, a3) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3)
    }
  }

  final def forProduct5[A0, A1, A2, A3, A4, T](unpack: T => (A0, A1, A2, A3, A4))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4)
    }
  }

  final def tuple5[A0, A1, A2, A3, A4](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4]): Serializer[(A0, A1, A2, A3, A4)] = new Serializer[(A0, A1, A2, A3, A4)] {
    override def serialize(input: (A0, A1, A2, A3, A4)): ByteString = {
      val (a0, a1, a2, a3, a4) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4)
    }
  }

  final def forProduct6[A0, A1, A2, A3, A4, A5, T](unpack: T => (A0, A1, A2, A3, A4, A5))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5)
    }
  }

  final def tuple6[A0, A1, A2, A3, A4, A5](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5]): Serializer[(A0, A1, A2, A3, A4, A5)] = new Serializer[(A0, A1, A2, A3, A4, A5)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5)): ByteString = {
      val (a0, a1, a2, a3, a4, a5) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5)
    }
  }

  final def forProduct7[A0, A1, A2, A3, A4, A5, A6, T](unpack: T => (A0, A1, A2, A3, A4, A5, A6))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5], serdeA6: Serializer[A6]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6)
    }
  }

  final def tuple7[A0, A1, A2, A3, A4, A5, A6](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5], serdeA6: Serializer[A6]): Serializer[(A0, A1, A2, A3, A4, A5, A6)] = new Serializer[(A0, A1, A2, A3, A4, A5, A6)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5, A6)): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6)
    }
  }

  final def forProduct8[A0, A1, A2, A3, A4, A5, A6, A7, T](unpack: T => (A0, A1, A2, A3, A4, A5, A6, A7))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5], serdeA6: Serializer[A6], serdeA7: Serializer[A7]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7)
    }
  }

  final def tuple8[A0, A1, A2, A3, A4, A5, A6, A7](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5], serdeA6: Serializer[A6], serdeA7: Serializer[A7]): Serializer[(A0, A1, A2, A3, A4, A5, A6, A7)] = new Serializer[(A0, A1, A2, A3, A4, A5, A6, A7)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5, A6, A7)): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7)
    }
  }

  final def forProduct9[A0, A1, A2, A3, A4, A5, A6, A7, A8, T](unpack: T => (A0, A1, A2, A3, A4, A5, A6, A7, A8))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5], serdeA6: Serializer[A6], serdeA7: Serializer[A7], serdeA8: Serializer[A8]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7, a8) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7) ++ serdeA8.serialize(a8)
    }
  }

  final def tuple9[A0, A1, A2, A3, A4, A5, A6, A7, A8](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5], serdeA6: Serializer[A6], serdeA7: Serializer[A7], serdeA8: Serializer[A8]): Serializer[(A0, A1, A2, A3, A4, A5, A6, A7, A8)] = new Serializer[(A0, A1, A2, A3, A4, A5, A6, A7, A8)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5, A6, A7, A8)): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7, a8) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7) ++ serdeA8.serialize(a8)
    }
  }

  final def forProduct10[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, T](unpack: T => (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9))(implicit
    serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5], serdeA6: Serializer[A6], serdeA7: Serializer[A7], serdeA8: Serializer[A8], serdeA9: Serializer[A9]
  ): Serializer[T] = new Serializer[T] {
    override def serialize(input: T): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) = unpack(input)
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7) ++ serdeA8.serialize(a8) ++ serdeA9.serialize(a9)
    }
  }

  final def tuple10[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9](implicit serdeA0: Serializer[A0], serdeA1: Serializer[A1], serdeA2: Serializer[A2], serdeA3: Serializer[A3], serdeA4: Serializer[A4], serdeA5: Serializer[A5], serdeA6: Serializer[A6], serdeA7: Serializer[A7], serdeA8: Serializer[A8], serdeA9: Serializer[A9]): Serializer[(A0, A1, A2, A3, A4, A5, A6, A7, A8, A9)] = new Serializer[(A0, A1, A2, A3, A4, A5, A6, A7, A8, A9)] {
    override def serialize(input: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9)): ByteString = {
      val (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) = input
      serdeA0.serialize(a0) ++ serdeA1.serialize(a1) ++ serdeA2.serialize(a2) ++ serdeA3.serialize(a3) ++ serdeA4.serialize(a4) ++ serdeA5.serialize(a5) ++ serdeA6.serialize(a6) ++ serdeA7.serialize(a7) ++ serdeA8.serialize(a8) ++ serdeA9.serialize(a9)
    }
  }
}