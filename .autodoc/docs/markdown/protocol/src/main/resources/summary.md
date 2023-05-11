[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/protocol/src/main/resources)

The `.autodoc/docs/json/protocol/src/main/resources` folder contains two data files, `hashrate-inflation.csv` and `time-inflation.csv`, which seem to be related to the Alephium cryptocurrency project.

`hashrate-inflation.csv` contains a list of tuples with three values: an integer, a float, and a large integer. Although the exact purpose is unclear without more context, it appears to be a reference table, possibly for calculations related to transaction fees or mining rewards. For instance, the code snippet below demonstrates how this table might be used to calculate total transaction fees for a block of cryptocurrency transactions:

```python
total_fees = 0
for transaction in block.transactions:
    fee_rate = lookup_fee_rate(transaction.size)
    fee_amount = transaction.size * fee_rate
    total_fees += fee_amount
```

`time-inflation.csv` provides data on the Alephium blockchain, with each line representing a different block. The data includes the block number, block reward (in Alephium coins), and the total supply of Alephium coins at that block. This information can be used for various purposes, such as analyzing the distribution of rewards and the growth of the coin supply over time or creating visualizations to help users understand the Alephium blockchain's history and current state. The following code snippet demonstrates how to visualize this data using Python and the matplotlib library:

```python
import matplotlib.pyplot as plt

with open('alephium_data.txt', 'r') as f:
    data = f.readlines()

block_nums = []
rewards = []
supplies = []
for line in data:
    parts = line.strip().split(',')
    block_nums.append(int(parts[0]))
    rewards.append(float(parts[1]))
    supplies.append(float(parts[2]))

plt.plot(block_nums, rewards)
plt.title('Alephium Block Rewards')
plt.xlabel('Block Number')
plt.ylabel('Reward (ALEPH)')
plt.show()

plt.plot(block_nums, supplies)
plt.title('Alephium Coin Supply')
plt.xlabel('Block Number')
plt.ylabel('Total Supply (ALEPH)')
plt.show()
```

In summary, the files in this folder seem to be related to the financial aspects of the Alephium cryptocurrency project, providing data for calculations and visualizations that can help users and developers better understand the system's inner workings.
