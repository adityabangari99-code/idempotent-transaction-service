# Idempotent Background Transaction Processing Service

A minimal, dependency-free **Java 17+** reference solution for the **FDE L2** practical assessment. The processor is an independently callable background-job component; the command-line entry point demonstrates a replaceable batch input adapter. No server, framework, broker, database, or UI is required for this assessment.

## Run

```bash
mkdir -p out
javac -d out src/main/java/com/example/transactions/Main.java
java -cp out com.example.transactions.Main
```

## Run tests

```bash
javac -cp out -d out src/test/java/com/example/transactions/ProcessorTest.java
java -cp out com.example.transactions.ProcessorTest
```

Expected test output: `ALL TESTS PASSED`.

## Architecture and design decisions

- **Input adapter:** `Main` provides sample requests; `Processor.submit(Request)` can be called by a scheduler, file adapter, queue consumer, or API later.
- **Validation:** rejects null/missing identifiers, invalid currency, nonpositive amounts, invalid type, and sequence < 1. Invalid input is returned as `FAILED` and does not stop the batch.
- **Idempotency:** business `transactionId` identifies an accepted transaction. The same business payload is reported as `DUPLICATE`, even when its `requestId` changes. A changed payload for the same transaction ID is rejected. A `requestId` reused for another transaction ID is rejected.
- **Ordering:** account-local contiguous sequence numbers start at **1**. Future sequence numbers are buffered in `PENDING` until earlier sequences succeed. The missing next sequence per account is reported. A single sequence can only be reserved by one transaction. This is intentionally **per account**, not a global sequence; independent accounts progress independently.
- **Retry:** simulated transient failures occur before any balance update. `retryPending()` retries the account's next transaction; after `maxAttempts`, the transaction is `FAILED` and the account sequence is blocked for operator recovery. A retry cannot create duplicate balance effects.
- **Consistency:** one synchronized processor owns all in-memory maps, making state changes and balance updates serialized within this process. There is no external side effect or persistent ledger. This is **not** crash-safe or multi-instance exactly-once processing.
- **Observability:** `Result` gives an outcome for every submission; `summary()` counts current accepted transaction states; `attempts()` exposes retries; `missingSequenceByAccount()` exposes gaps. `DUPLICATE` and rejected `FAILED` submissions are per-request outcomes and **not** included in the state summary because they are not accepted business transactions.
- **Status lifecycle:** `RECEIVED` -> `PENDING` (if out of order) -> `PROCESSING` -> `PROCESSED`; transient failure gives `RETRY_PENDING` then retry; exhausted retries give terminal `FAILED`. Duplicate submissions return `DUPLICATE` without changing the original transaction state.

## Sample input and expected behavior

The demo submits `TXN-2002` (account ACC-201, seq 2, debit 40) before `TXN-2001` (seq 1, credit 100); it buffers the debit, then processes both in order. It also submits a duplicate of `TXN-2002`, `TXN-2003` (credit 25), invalid `TXN-2004` (amount -10), and `TXN-2005` (credit 500) with one simulated transient failure. After one retry, ACC-201 balance is **85** and ACC-203 balance is **500**. No invalid or duplicate request updates balances.

The sample document uses sequence 5 for ACC-203. Because this implementation chooses **account-local** ordering, the demo uses sequence 1 for ACC-203 and ACC-202; otherwise those accounts would correctly wait for their missing sequences 1–4. This is an explicit modeling choice.

## Tests

The standalone test runner checks out-of-order buffering/draining, replay/duplicate idempotency, conflicting payloads, request ID collisions, invalid-input isolation, transient retry success, retry exhaustion, single balance update, and missing-sequence reporting.

## Known limitations and production hardening

1. **Durability:** in-memory state disappears on restart. Use a relational database with unique constraints on business transaction ID, request ID, and `(account_id, sequence_number)`; atomically commit the transaction state and ledger/balance changes.
2. **Concurrency:** `synchronized` protects one JVM only. For horizontal scaling, use account-partitioned queue consumption and database transactions/locking; handle redelivery and worker crashes.
3. **Recovery:** terminal failures block their account's sequence. Add an audited operator requeue/dead-letter workflow and gap timeout/alerts. Avoid silently skipping failed financial transactions.
4. **Financial rules:** the demo permits negative balances and treats currencies as metadata. Production systems must define overdraft policies, per-currency balances, scale/rounding, reversal semantics, and double-entry ledger accounting.
5. **Operational visibility:** export counters for accepted, duplicates, invalid, retry attempts, pending gaps, and exhausted retries; add structured correlation-safe logs and traces without exposing customer data.
6. **Security:** authenticate adapters, authorize account operations, validate request schemas, and store secrets outside source code.

## AI assistance and validation

AI assistance was used to accelerate the first implementation and documentation. The implementation was checked with the included deterministic Java test runner. Reviewers should still inspect the trade-offs above and run the tests independently. No claim is made that this in-memory demo is production-ready.
