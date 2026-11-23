# ADR 001 — Balance transfers in PostgreSQL

## Context

A successful retry must identify the original transfer. Two requests racing to debit the same account must spend only available funds. Readers must never observe half a transfer.

## Decision

Store each transfer once with its debit account, credit account, currency and positive decimal amount. A view produces both signed journal entries from that row. Foreign keys enforce source ownership and matching currencies. A trigger rejects changes to posted transfers.

Claim the actor and request key through a unique row before locking accounts. The claim and posting commit together. A replay compares the persisted accounts, currency and numeric amount. Rollbacks release the claim, so a previously unsuccessful request can be retried.

Acquire row locks on both accounts in UUID order and read the balance in a subsequent statement at PostgreSQL READ COMMITTED. A competing writer must wait for those locks. Once acquired, the balance query can see the prior writer's committed journal row.

## Consequences

An interrupted transaction commits both entries or neither. Deterministic lock order prevents reversed account pairs from creating a lock cycle. Computing balances from indexed journal entries avoids a second writable balance field. Read cost grows with account history. A later projection can reduce that cost if measurements justify it.

An opening balance acts as a fixed fixture in this increment. Future corrections should post compensating entries, and production operation would need distinct migration and application database roles, including protected account opening data.
