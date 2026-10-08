# Idempotent Background Transaction Processing Service

**Author:** Veeravenkata Manikanta Kakaraparthi ([github.com/manikanta-kvv](https://github.com/manikanta-kvv))

A small Java 17 service that processes transaction requests (CREDIT / DEBIT / REVERSAL) coming from an
input source in **any order**, with **duplicates** and **temporary failures**, and still produces one
correct business result per transaction. Built for the FDE L2 practical assessment.

* Plain Java 17 + Maven. **No runtime dependencies** (JUnit 5 for tests only).
* In-memory persistence behind interfaces, so a database / queue can replace it without touching the business logic.
* 60 automated tests (duplicates, out-of-order, invalid input, retries, recovery, end-to-end sample run). `mvn clean test` passes.

## Run it

```bash
mvn clean test                      # run all tests
mvn -q compile exec:java            # run the demo on src/main/resources/sample-transactions.csv
mvn -q compile exec:java -Dexec.args="path/to/your.csv"
```

The demo runs the background job, prints a processing report, then simulates an **operator recovery**
(retrying FAILED transactions) and prints the report again.

## Project structure

```
txn-service/
├── pom.xml
├── README.md
├── .github/workflows/ci.yml            # runs mvn test on every push
└── src
    ├── main
    │   ├── java/com/example/txn
    │   │   ├── App.java                 # demo runner
    │   │   ├── ingest/                  # TransactionSource, in-memory + CSV adapters
    │   │   ├── validation/              # TransactionValidator, ValidationResult
    │   │   ├── store/                   # idempotency / state store
    │   │   ├── ledger/                  # AccountLedger (atomic, idempotent, compensating)
    │   │   ├── downstream/              # DownstreamPublisher + simulated flaky implementation
    │   │   ├── processing/              # TransactionProcessor, RetryPolicy, exceptions
    │   │   ├── job/                     # BackgroundProcessingJob
    │   │   ├── report/                  # ProcessingSummary, ReportPrinter
    │   │   ├── model/                   # request, transaction, record, status, type
    │   │   └── util/                    # masking, structured logging
    │   └── resources/sample-transactions.csv
    └── test/java/com/example/txn        # idempotency, ordering, retry, validation, ledger, job tests
```

## Architecture

```
 TransactionSource ──► BackgroundProcessingJob ──► TransactionProcessor ──► AccountLedger (balances, atomic)
 (list / CSV / queue)   (polls batches, isolates      │  validate                └► DownstreamPublisher (may fail)
                         errors per record)            │  deduplicate ─► TransactionStateStore
                                                       │  order per account (sequence buffer)
                                                       │  process + retry + compensate
                                                       └► ProcessingSummary / ReportPrinter
```

| Package | Responsibility |
| --- | --- |
| `ingest` | Input port (`TransactionSource`) + list and CSV adapters. Knows nothing about business rules. |
| `validation` | Raw `TransactionRequest` (strings) to typed `Transaction`, or a list of errors. |
| `store` | Idempotency / state store (unique transactionId and requestId). |
| `ledger` | System of record for balances. Idempotent per transactionId, atomic, supports compensation. |
| `downstream` | Output port for the side effect after posting (simulated, can fail transiently). |
| `processing` | `TransactionProcessor` = all business rules, plus retry policy. |
| `job` | Background polling job (synchronous or scheduled). |
| `report`, `util` | Summary, report printer, structured logging, masking. |

## Transaction model

`transactionId`, `requestId` (idempotency key), `accountId`, `transactionType`, `amount`, `currency`,
`sequenceNumber`, `eventTime` (optional, audit only), `originalTransactionId` (only for REVERSAL).

### Status lifecycle

```
                      +--> DUPLICATE (terminal, no effect)
 request --> validate-+--> REJECTED  (invalid input or sequence conflict, terminal, no effect)
                      +--> RECEIVED --> PENDING_SEQUENCE --(earlier sequences done)--+
                             |                                                       |
                             +-------------------------------------------------------+--> PROCESSING
 PROCESSING --> PROCESSED
            --> RETRY_PENDING --> PROCESSING (transient failure, back-off, try again)
            --> FAILED   (retries exhausted; effect compensated; blocks that account until recovered)
            --> REJECTED (business rule: insufficient funds, bad reversal, currency mismatch; sequence is consumed)
```

## Design decisions

### 1. Idempotency
* Every accepted request is registered in `TransactionStateStore` under **transactionId** and **requestId**.
  A later request with either value is marked `DUPLICATE` and has **no** effect. This works wherever the duplicate
  arrives (same batch, later batch, while the original is still waiting or failed).
* Same transactionId but different data is also `DUPLICATE` with reason `DUPLICATE_TRANSACTION_ID_DIFFERENT_PAYLOAD`
  and a warning log, because it needs a human to look at it. The original is never overwritten.
* A second layer sits in the ledger: `post()` is idempotent per transactionId (`ALREADY_POSTED`). This is what makes
  **retries safe** even if the first attempt committed but the acknowledgement was lost.
* Invalid requests are **not** registered, so a corrected re-send with the same transactionId is accepted.

### 2. Out-of-order handling: Option A, sequence based (per account)
* Each account has a sequence starting at 1. The processor keeps a small sorted buffer per account.
* A request with a future sequence becomes `PENDING_SEQUENCE` and is processed automatically when all earlier
  sequences have been processed. Missing sequences are listed in the report.
* A different transaction using an already consumed or already buffered sequence is `REJECTED`
  (`SEQUENCE_ALREADY_CONSUMED`, `SEQUENCE_CONFLICT`).
* **Why:** for money movements, order matters (a debit must see the earlier credit; a reversal needs its original).
  Latest-state-wins (Option C) would be wrong for this domain.
* **Trade-offs:** the sender must supply a gapless per-account sequence; one lost message holds back later messages
  of that account (reported, not hidden). Accounts do not block each other.

### 3. Resilience
* Processing one transaction = `ledger.post` (atomic and idempotent) then `downstream.publish` (can fail).
* Transient failure: retry with exponential back-off (default 3 attempts, 100 ms then 200 ms), status `RETRY_PENDING`.
* After the last attempt: the posting is **compensated** (undone), status `FAILED`, reason `RETRIES_EXHAUSTED`.
  `FAILED` therefore means "no business effect", which is easy to reason about.
* A `FAILED` transaction **blocks later sequences of its own account only** (they show
  `BLOCKED_BY_FAILED_SEQUENCE_n`). `retryFailed()` is the operator recovery: it retries the failed transaction and then
  releases the waiting ones. It is safe to call repeatedly.
* Permanent business errors (`INSUFFICIENT_FUNDS`, `ORIGINAL_NOT_POSTED`, ...) are **not** retried. They become `REJECTED`
  and consume the sequence number so the account keeps moving.
* Unexpected exceptions inside one record never stop the batch (`UNEXPECTED_ERROR_<ExceptionClass>`).

### 4. Data consistency
* `InMemoryAccountLedger.post` runs all checks first and then changes balance, entry and bookkeeping together, so a
  rejected or failed posting never leaves partial state. In a database this block is one local transaction.
* Compensation restores the exact previous balance and is only allowed for the latest entry of an account, which
  per-account sequencing guarantees for a failed transaction.
* Known limitation (dual write): ledger and downstream are two systems. Here it is handled with retry + idempotent
  posting + compensation. In production I would use a **transactional outbox**: write the ledger entry and an outbox event
  in the same DB transaction, and let a relay publish the event with retries.

### 5. Observability
* One structured `key=value` log line per event: `RECEIVED`, `PENDING_SEQUENCE`, `PROCESSED`, `DUPLICATE`,
  `RETRY_SCHEDULED`, `FAILED`, `REJECTED_*`, `RECOVERY_RETRY`, `BATCH_DONE`.
* Every transaction keeps its **status history, attempt count and reason**.
* `ProcessingSummary` + `ReportPrinter`: counts by status, retry statistics, missing sequences, blocked accounts,
  balances, and a per-transaction table.

### 6. Security and data handling
* Account ids are masked in logs and reports (`****1001`). Amounts and payloads are not logged.
* Validation error messages name the field and rule, never the submitted value.
* Identifiers from invalid input are sanitised before logging (prevents log injection).
* Strict validation: id format, positive amount with at most 2 decimals and an upper limit, 3-letter currency,
  sequence at least 1, ISO-8601 time. No secrets or credentials exist in the code.

### 7. Maintainability
* Business rules (`TransactionProcessor`, ledger) are separated from infrastructure (sources, stores, publisher) by interfaces.
* Time (`Sleeper`) and failures (`SimulatedDownstreamPublisher`) are injectable, so tests are fast and deterministic.

## Actual output of the demo

After the background run (18 requests received):

```
Counts by status
  PENDING_SEQUENCE  2     (TXN-3002 blocked by failed TXN-3001, TXN-6003 waiting for missing seq 2)
  PROCESSED         8
  DUPLICATE         2
  REJECTED          5     (4 invalid records + 1 INSUFFICIENT_FUNDS)
  FAILED            1     (TXN-3001, retries exhausted, effect compensated)

Retries: 2 transactions retried, 4 extra attempts
Missing sequence numbers: ****6006 -> [2]
Balances: ****1001 1500.00 | ****2002 650.00 | ****3003 0.00 | ****5005 40.00 | ****6006 10.00
```

After operator recovery (`retryFailed()`): TXN-3001 and TXN-3002 become `PROCESSED`, `FAILED` = 0,
balance of ****3003 = 250.00. Every other balance is unchanged, which shows retries do not create duplicate effects.

## Sample run (bundled data, 18 requests)

| Scenario in the CSV | Result |
| --- | --- |
| ACC-1001: seq 3, 2 arrive before seq 1, then a reversal | Processed in order 1,2,3,4. Balance 1500.00 |
| Same request replayed, same transaction with new requestId | 2 x `DUPLICATE`, no second effect |
| TXN-2002 fails twice downstream | `PROCESSED` on attempt 3, applied once |
| TXN-3001 fails 4 times | `FAILED` after 3 attempts, balance 0.00, TXN-3002 `PENDING_SEQUENCE` (blocked) |
| After operator recovery | TXN-3001 and TXN-3002 `PROCESSED`, balance 250.00 |
| 4 malformed records | 4 x `REJECTED`, batch continues |
| Debit on an empty account | `REJECTED: INSUFFICIENT_FUNDS`, next sequence continues |
| ACC-6006 sequence 2 never arrives | seq 3 stays `PENDING_SEQUENCE`, report shows missing `[2]` |

## Requirement coverage

| ID | Where |
| --- | --- |
| L2-FR-01 replaceable input | `TransactionSource` + `InMemoryTransactionSource`, `CsvTransactionParser` |
| L2-FR-02 validation | `TransactionValidator`, `TransactionValidatorTest` |
| L2-FR-03 / 04 state + lifecycle | `TransactionRecord`, `TransactionStatus` |
| L2-FR-05 / 06 once only, late duplicates | `TransactionProcessor.process`, `IdempotencyTest` |
| L2-FR-07 out-of-order strategy | sequence buffer per account, `OrderingTest` |
| L2-FR-08 / 09 retry without duplicate effect | `execute()`, `SimulatedDownstreamPublisher`, `RetryTest` |
| L2-FR-10 separation | ports and adapters, see Architecture |
| L2-FR-11 summary | `ProcessingSummary`, `ReportPrinter` |
| L2-FR-12 tests | `src/test` (duplicate, out-of-order, invalid, retry, recovery, end-to-end) |

## Assumptions
* Sequence numbers are per account, start at 1 and have no gaps in the normal case.
* One currency per account (set by its first posting).
* A REVERSAL must match the original's account and amount, and a transaction can be reversed once.
* An in-memory store means state is lost on restart (acceptable for this assessment; see next steps).
* CSV input has no quoted fields.

## Production-readiness notes / next steps
1. Replace the in-memory store and ledger with a database: unique constraints on `transaction_id` and `request_id`,
   ledger post and status update in one transaction, `SELECT ... FOR UPDATE` (or optimistic version) per account.
2. Add a transactional outbox for downstream events.
3. Put a real queue (Kafka / SQS) behind `TransactionSource`, partitioned by `accountId` so one account is always handled
   by one worker (keeps ordering and avoids lock contention).
4. Move retries to the queue (delayed retry / dead-letter queue) and add a time limit for `PENDING_SEQUENCE` with an alert.
5. Metrics (Micrometer / Prometheus): counts by status, retry rate, pending age, DLQ size. Trace ids in logs.
6. Secure the operator recovery endpoint, add audit logging for manual actions.

## AI-assisted development note

As allowed by the assessment, I used an AI assistant (Claude) in this work. It helped me turn the problem statement into
a design and produced the first version of the code, tests and this README. I then ran the project locally
(`mvn clean test`, `mvn -q compile exec:java`), checked the output against the requirements, and went through the
design decisions so that I can explain them myself. The AI-generated tests also caught one real bug during development:
transactions arriving behind an already FAILED transaction were not showing why they were blocked. It was fixed and is
now covered by `RetryTest.failedTransactionBlocksLaterSequencesOfThatAccountOnly`.