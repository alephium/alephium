[View code on GitHub](https://github.com/alephium/alephium/blob/master/util/src/main/scala/org/alephium/util/Cache.scala)

The `Cache` object in the `org.alephium.util` package provides a set of methods to create different types of caches. Caches are used to store frequently accessed data in memory to improve performance. The cache implementation is based on a `LinkedHashMap` data structure, which provides a predictable iteration order based on the order in which the entries were added to the map. 

The `Cache` object provides four methods to create caches with different eviction policies: `lru`, `fifo`, `lruSafe`, and `fifoSafe`. The `lru` and `lruSafe` methods create caches that use a Least Recently Used (LRU) eviction policy, which removes the least recently used entries when the cache reaches its maximum capacity. The `fifo` and `fifoSafe` methods create caches that use a First In First Out (FIFO) eviction policy, which removes the oldest entries when the cache reaches its maximum capacity. The `Safe` methods create thread-safe caches, while the non-Safe methods are not thread-safe.

The `Cache` object also provides two additional methods to create custom caches. The `fifo` method with three arguments creates a cache that uses a FIFO eviction policy and also removes entries that have expired based on a given duration. The `fifo` method with a single argument creates a cache that uses a FIFO eviction policy and allows the caller to provide a custom removal function.

The `Cache` object uses the `Inner` class to implement the cache. The `Inner` class extends `LinkedHashMap` and overrides the `removeEldestEntry` method to implement the eviction policy. The `Cache` trait provides a simple interface to interact with the cache, including methods to get, put, and remove entries. The `Cache` trait also provides thread-safety by using a `Lock` trait to synchronize access to the cache.

Example usage:

```
val cache = Cache.lru[String, Int](100)
cache.put("key1", 1)
cache.put("key2", 2)
val value1 = cache.get("key1") // Some(1)
val value2 = cache.get("key2") // Some(2)
cache.remove("key1")
val value3 = cache.get("key1") // None
```
## Questions: 
 1. What is the purpose of the `Cache` object and what types of caches does it provide?
- The `Cache` object provides different types of caches, including LRU and FIFO, with or without thread safety and with or without expiration time.
2. What is the difference between `threadUnsafe` and `threadSafe` cache implementations?
- `threadUnsafe` caches are not thread-safe and can be accessed concurrently by multiple threads, while `threadSafe` caches are thread-safe and use a read-write lock to ensure thread safety.
3. What is the purpose of the `Inner` class and how is it used in the `Cache` object?
- The `Inner` class is a subclass of `LinkedHashMap` that is used to implement the cache functionality. It overrides the `removeEldestEntry` method to remove the least recently used entry from the cache and calls the `removeEldest` function provided by the `Cache` object.