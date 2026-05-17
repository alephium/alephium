# Security Policy

Alephium takes the security of its network and infrastructure seriously. We appreciate the security community's efforts in helping us keep Alephium and its users safe.

## Bug Bounty Program

Alephium operates a Bug Bounty & Responsible Disclosure program that rewards researchers who report vulnerabilities responsibly. The full program rules, eligibility criteria, and reward terms are documented at:

**[Alephium Bug Bounty Program](https://github.com/alephium/community/blob/master/BugBounty.md)**

Please read the program rules in full before reporting.

## Scope

The bug bounty program covers the entire operational Alephium environment, including (but not limited to) the following repositories:

- [alephium/alephium](https://github.com/alephium/alephium) — Full node / protocol
- [alephium/alephium-frontend](https://github.com/alephium/alephium-frontend) — Frontend applications
- [alephium/extension-wallet](https://github.com/alephium/extension-wallet) — Browser extension wallet
- [alephium/wormhole-fork](https://github.com/alephium/wormhole-fork) — Bridge
- [alephium/ledger-alephium](https://github.com/alephium/ledger-alephium) — Ledger integration
- [alephium/alephium-dex](https://github.com/alephium/alephium-dex) — DEX proof of concept
- [alephium/alephium-nft](https://github.com/alephium/alephium-nft) — NFT marketplace proof of concept
- [alephium/alephium-web3](https://github.com/alephium/alephium-web3) — Web3 SDK
- [alephium/explorer-backend](https://github.com/alephium/explorer-backend) — Explorer backend
- [alephium/www](https://github.com/alephium/www) — Website

Bugs found in any other Alephium-related repository that put user funds at risk will also be considered in scope.

**Out of scope:**

- Third-party contracts outside Alephium's direct control
- Bugs in third-party contracts or apps utilizing Alephium code

## How to Report a Vulnerability

Please report vulnerabilities through **one** of the following private channels. Do **not** disclose the issue publicly (issues, discussions, pull requests, social media, etc.) until Alephium has acknowledged, resolved the issue, and authorized public disclosure.

### Option 1 — Email (primary channel)

Send your report to **bugbounty@alephium.org**.

An acknowledgment will be issued within 2 business days.

### Option 2 — GitHub Private Vulnerability Reporting

You may alternatively submit your report through GitHub's Private Vulnerability Reporting feature on this repository:

**[Report a vulnerability](https://github.com/alephium/alephium/security/advisories/new)**

This channel keeps your report private until a fix is published. Once the advisory is published, you can be publicly credited for the discovery — useful for researchers who prefer in-platform recognition. The same disclosure rules apply: do not share the report outside this private channel until the advisory is published.

## What to Include in Your Report

A comprehensive report helps us triage and reward faster. Please include:

- The conditions required to reproduce the vulnerability
- Steps to reproduce, ideally a proof of concept
- Affected component(s), commit hash, or release version
- Potential impact and exploitation scenarios
- Your contact details and whether you wish to be publicly credited

## Disclosure Timeline

- Reports must be submitted **within 24 hours** of discovery, as required by the Bug Bounty program.
- Alephium will acknowledge receipt within **2 business days**.
- Public disclosure may only occur after Alephium has resolved the issue and authorized release.

## Recognition

Researchers who report a unique, previously unreported vulnerability and maintain confidentiality until it is resolved may opt for public recognition. If you submit through GitHub Private Vulnerability Reporting, recognition can be issued directly on the published security advisory.

## Rewards

Rewards are determined by Alephium based on the risk level and exploitability of the bug, as described in the [full program rules](https://github.com/alephium/community/blob/master/BugBounty.md).

---

Thank you for helping keep Alephium and its users safe.
