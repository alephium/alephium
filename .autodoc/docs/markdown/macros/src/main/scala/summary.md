[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/macros/src/main/scala)

The `.autodoc/docs/json/macros/src/main/scala` folder contains Scala macros and annotations that help generate boilerplate code and improve code efficiency in the Alephium project. These macros and annotations are used to automatically generate code for serialization, hashing, enumeration of sealed traits, and C-style for loops.

1. **ByteCode.scala**: This file defines a `ByteCode` macro annotation that adds a `code` method to case classes, returning a `Byte` value from the companion object. This can be useful when you need to associate a unique byte code with each case class instance.

   Example usage:
   ```scala
   @ByteCode
   final case class MyClass(a: Int, b: String)
   object MyClass {
     val code: Byte = 0x01
   }
   ```

2. **EnumerationMacros.scala**: This file defines a `sealedInstancesOf` method that generates a `TreeSet` of instances of a sealed trait or class at compile time. This can be useful when you need to perform operations on all instances of a sealed trait or class.

   Example usage:
   ```scala
   sealed trait Fruit
   case class Apple() extends Fruit
   case class Orange() extends Fruit

   val fruits = EnumerationMacros.sealedInstancesOf[Fruit]
   ```

3. **Gas.scala**: This file defines a `Gas` macro annotation that adds a `gas()` method to a trait and its companion object, returning a `GasBox` object. This can be useful when you need to associate a gas cost with certain operations in the Alephium project.

   Example usage:
   ```scala
   @Gas
   trait MyTrait
   object MyTrait {
     val gas: GasBox = GasBox(100)
   }
   ```

4. **HPC.scala**: This file defines a `cfor` method that creates a C-style for loop in Scala, improving loop efficiency. This can be useful when you need to perform efficient looping over a range of values.

   Example usage:
   ```scala
   import org.alephium.macros.HPC._

   cfor(0)(_ < 10, _ + 1) { i =>
     println(i)
   }
   ```

5. **HashSerde.scala**: This file defines a `HashSerde` macro annotation that generates boilerplate code for classes that need to be serialized and hashed. The generated code adds a `bytes` field, a `hash` field, and a `shortHex` method to the annotated class and its companion object.

   Example usage:
   ```scala
   import org.alephium.macros.HashSerde

   @HashSerde
   case class Person(name: String, age: Int)

   val person = Person("Alice", 30)
   val bytes = person.bytes
   val hash = person.hash
   val shortHex = person.shortHex
   ```

These macros and annotations help reduce boilerplate code, improve code efficiency, and simplify the development process in the Alephium project. They can be used in various parts of the project where serialization, hashing, enumeration, and efficient looping are required.
