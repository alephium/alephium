[View code on GitHub](https://github.com/alephium/alephium/blob/master/util/src/main/scala/org/alephium/util/Base58.scala)

The `Base58` object provides methods to encode and decode data using the Base58 encoding scheme. This encoding scheme is commonly used in Bitcoin and other cryptocurrencies to represent data in a compact and human-readable format.

The `Base58` object defines an alphabet of 58 characters that are used to represent data. The alphabet consists of the digits 1-9, the uppercase letters A-Z (excluding I, O), and the lowercase letters a-z (excluding l). The object also defines a lookup table `toBase58` that maps each character in the alphabet to a number between 0 and 57.

The `encode` method takes a `ByteString` as input and returns a Base58-encoded string. The input data is first converted to a `BigInt` and then divided by 58 repeatedly to obtain the Base58 representation. The resulting string is prefixed with the appropriate number of zeros to represent any leading zero bytes in the input data.

The `decode` method takes a Base58-encoded string as input and returns an `Option[ByteString]`. If the input string is not a valid Base58-encoded string, `None` is returned. Otherwise, the input string is decoded to a `BigInt` and then converted to a `ByteString`. The resulting `ByteString` is prefixed with the appropriate number of zero bytes to represent any leading zeros in the input string.

This object can be used in the larger project to encode and decode data in a format that is commonly used in cryptocurrencies. For example, it can be used to encode and decode Bitcoin addresses or transaction data. Here is an example of how to use the `Base58` object to encode and decode a `ByteString`:

```scala
import akka.util.ByteString
import org.alephium.util.Base58

val data = ByteString("hello world")
val encoded = Base58.encode(data) // "StV1DL6CwTryKyV"
val decoded = Base58.decode(encoded) // Some(ByteString("hello world"))
```
## Questions: 
 1. What is the purpose of the `Base58` object?
    
    The `Base58` object provides methods to encode and decode data using the Base58 encoding scheme.

2. What is the significance of the `alphabet` string and `toBase58` array?
    
    The `alphabet` string defines the characters used in the Base58 encoding scheme, while the `toBase58` array maps each character to its corresponding value in Base58.

3. What is the purpose of the `count` method and how is it used in the `encode` and `decode` methods?
    
    The `count` method is used to determine the number of leading zeros in the input data. It is used in the `encode` method to add the appropriate number of prefix characters to the encoded string, and in the `decode` method to create a `ByteString` object with the appropriate number of leading zeros.