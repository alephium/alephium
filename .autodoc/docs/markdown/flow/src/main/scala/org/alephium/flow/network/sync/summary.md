[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network/sync)

The code in this folder is responsible for managing the synchronization of blocks and broker status in the Alephium network. It provides functionality for fetching blocks, tracking download progress, and maintaining the state of items that need to be fetched from the network.

`BlockFetcher.scala` defines the `BlockFetcher` trait, which is used to fetch blocks from the network. It has abstract methods for network settings, broker configuration, and block flow, as well as a method for handling block announcements. The `MaxDownloadTimes` constant limits the number of times a block can be downloaded, preventing excessive network traffic.

`BlockFlowSynchronizer.scala` handles the synchronization of blocks between nodes in the network. It manages the download and tracking of blocks, as well as handling announcements of new blocks and broker status updates. The class extends several traits, providing functionalities such as handling IO operations, subscribing to events, tracking block downloads, fetching blocks, tracking broker status, and managing node synchronization status.

`BrokerStatusTracker.scala` is responsible for tracking the status of brokers in the Alephium network. It defines methods for tracking the status of brokers, calculating the number of peers to sample for synchronization, and returning a vector of sampled peers. The `BrokerStatusTracker` trait is used in other parts of the Alephium project to manage the synchronization of data between brokers.

`DownloadTracker.scala` defines the `DownloadTracker` trait, which is used to track the download progress of blocks in the Alephium network. It provides methods to check whether a block needs to be downloaded, download blocks, and clean up the syncing HashMap. The trait is likely used by other actors in the Alephium network to coordinate block downloads and ensure that all nodes have an up-to-date copy of the blockchain.

`FetchState.scala` defines a class for keeping track of the state of items that need to be fetched from a network. It uses a cache to store the state of each item, which includes a timestamp and the number of times the item has been downloaded. The class can be used in the larger project to manage the fetching of items from a network, ensuring that items are not downloaded too frequently or prioritizing the download of items that have not been downloaded recently.

Example usage of `FetchState`:

```scala
val fetchState = FetchState[String](100, Duration.minutes(5), 3)
val item = "example"
val timestamp = TimeStamp.now()
if (fetchState.needToFetch(item, timestamp)) {
  // fetch the item from the network
}
```

Overall, the code in this folder plays a critical role in the Alephium project by ensuring that blocks and broker status are synchronized between nodes in the network. It provides a robust and reliable mechanism for downloading, tracking, and finalizing blocks, as well as handling announcements of new blocks and broker status updates.
