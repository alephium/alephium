[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/util/src/main/scala/org)

In the `org.alephium` package, the `json/util` folder contains utility classes and methods for handling JSON data within the Alephium project. These utilities are essential for parsing, encoding, and decoding JSON data, which is a common data format used in various parts of the project, such as API responses and configuration files.

Here is a summary of the files in this folder:

1. `JsonUtils.scala`: This file contains utility methods for working with JSON data. It provides methods for encoding and decoding JSON data using the `circe` library, which is a popular JSON library for Scala. The methods in this file are generic and can be used throughout the project to handle JSON data. For example, you can use the `parseAs` method to parse a JSON string into a case class:

   ```scala
   import org.alephium.json.JsonUtils._

   case class Person(name: String, age: Int)
   val jsonString = """{"name": "Alice", "age": 30}"""
   val person: Either[Error, Person] = parseAs[Person](jsonString)
   ```

2. `JsonSchemas.scala`: This file defines JSON schemas for various data types used in the Alephium project. These schemas are used to validate and generate JSON data for these types. The schemas are defined using the `json-schema` library, which is a Scala library for working with JSON schemas. For example, you can use the `BlockSchema` to validate a JSON string representing a block:

   ```scala
   import org.alephium.json.JsonSchemas._
   import org.alephium.json.JsonUtils._

   val jsonString = """{"header": {...}, "transactions": [...]}"""
   val validationResult: Either[Error, Unit] = validateJson(jsonString, BlockSchema)
   ```

3. `JsonCodecs.scala`: This file defines implicit JSON encoders and decoders for various data types used in the Alephium project. These encoders and decoders are used by the `circe` library to automatically convert between JSON data and Scala objects. For example, you can use the `Block` encoder and decoder to convert a `Block` object to a JSON string and vice versa:

   ```scala
   import org.alephium.json.JsonCodecs._
   import org.alephium.json.JsonUtils._
   import org.alephium.protocol.Block

   val block: Block = ...
   val jsonString: String = writeAsJson(block)
   val decodedBlock: Either[Error, Block] = parseAs[Block](jsonString)
   ```

In summary, the `json/util` folder provides essential utilities for handling JSON data in the Alephium project. These utilities include methods for encoding and decoding JSON data, JSON schemas for validating and generating JSON data, and JSON encoders and decoders for various data types. These utilities are used throughout the project to handle JSON data in various contexts, such as API responses and configuration files.
