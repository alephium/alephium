# Contributing to Alephium

As an open source project, everyone is invited to contribute to Alephium.

Nevertheless, following Alephium's good practices regarding coding, styling, git committing etc
will give you higher chance to get reviewed and merged.

## Getting Started

Fork the project, apply your changes in your feature branch and be sure to run:

```
make test-all
```

Before opening your PR (Pull Request), otherwise the CI (Continuous Integration) will probably fail.

Code review can be long and complex, please follow the few rules below to ease everyone's work.

## Git good practices

Small commits are easier to review and should be [atomic](https://en.wikipedia.org/wiki/Atomic_commit#Atomic_commit_convention) as much
as possible.

A good commit message and description helps a lot the reviewers, have a look at
the famous [seven rules of a great Git commit message](https://chris.beams.io/posts/git-commit/#seven-rules)

Ideally each individual commit should work on its own (i.e.`make test-all` should pass)

## Pull Request creation

As the commit messages, a good PR description will ease the reviewing work.
Try to explain the:

 - Why
 - What
 - How

## Pull Request review

During the review it's important to never rebase, squash, force push or more generally apply any action that can change commits' hash or history.
Review is done commit per commit, if one hash changed the all review needs to be redone, to prevent malicious code to be added to approved commits.

In case one of those actions is required, the maintainers will ask for it and the result must be easy to review with the `git range-diff` tool.

Please address PR's comments in some fix commits, respecting all the previous rules.

## Merging Pull Request

The PR can be merged by the maintainers once all the following criteria are reached:

 - No conflicts
 - CI passes
 - 2 approvals
