# Wallet System — README

A secure digital wallet backend (like the core of Paytm/PhonePe) built with
Java + Spring Boot. Every user has exactly one wallet. Money never gets
duplicated, lost, or corrupted — even if the same request is sent twice, or
two requests hit the same wallet at the exact same moment.

This document explains, in plain language, **how** it stays correct and
**how to run and test it yourself.**

---

## Table of Contents

1. [What This System Does](#1-what-this-system-does)
2. [Security Implementation](#2-security-implementation)
3. [Idempotency Handling](#3-idempotency-handling)
4. [Concurrency Strategy](#4-concurrency-strategy)
5. [Transaction Boundaries](#5-transaction-boundaries)
6. [How to Run the Application Locally](#6-how-to-run-the-application-locally)
7. [Unit Tests & Coverage](#7-unit-tests--coverage)

---

## 1. What This System Does

- A user registers → gets a wallet automatically, starting at balance 0.
- A user can add money to their own wallet.
- A user can transfer money to another user's wallet.
- A user can see their own wallet balance and transaction history.
- An admin can see every wallet and every transaction in the system.

The two hard problems this project solves:

1. **"What if the same request gets sent twice?"** (e.g. a mobile app retries
   after a network timeout, or a user double-taps "Send"). Money must not be
   added or sent twice.
2. **"What if two requests happen at the exact same time?"** (e.g. two
   transfers touching the same wallet at once). The final balance must
   always be mathematically correct — no money created or destroyed.

The rest of this document explains exactly how each problem is solved, in
the order a request actually experiences them.

---

## 2. Security Implementation

**In plain terms:** nobody can see or touch anyone else's wallet, and every
request has to prove who it is first.

**Step by step, what happens on every request:**

1. **You log in** (`POST /auth/login`) with your email and password. The
   server checks your password against a securely hashed copy (we use
   **BCrypt** — your real password is never stored anywhere, not even by us).
2. If correct, the server gives you a **JWT token** — think of it as a
   signed, tamper-proof ID card that says "this is user #7, role USER,
   valid until this time."
3. **Every request after that** must include this token in the
   `Authorization: Bearer <token>` header.
4. Before any of your business logic runs, a security filter
   (`JwtAuthenticationFilter`) checks the token:
   - Is the signature valid (was it really issued by this server)?
   - Has it expired?
   - If either check fails → the request is rejected immediately with
     `401 Unauthorized`, before it ever reaches your wallet or transfer code.
5. **Role check.** Once the token is verified, the system checks: does this
   role (`USER` or `ADMIN`) have permission for this URL?
   - `/wallet/**` → only `USER`
   - `/admin/**` → only `ADMIN`
   - Wrong role → `403 Forbidden`.
6. **The most important rule: your wallet ID is never taken from what you
   type or send.** Every wallet operation looks up "whose wallet is this?"
   using the **user ID inside your verified token** — never from the request
   body or URL. So even if someone tried to sneak `"walletId": 999` into a
   request, it would simply be ignored; the system only ever acts on the
   wallet belonging to the token holder.

**Where this lives in the code:**
`SecurityConfig.java` (the rules), `JwtUtil.java` (create/verify tokens),
`JwtAuthenticationFilter.java` (runs the check on every request),
`CustomUserDetailsService.java` + `AuthenticatedUser.java` (loads who you are).

---

## 3. Idempotency Handling

**In plain terms:** if the exact same request (same `Idempotency-Key`)
arrives more than once, the second (and third, and fourth...) time returns
the *same result* instead of doing the money operation again.

**Why this matters with a real example:** Your app calls "add ₹500," the
network drops before you get the response, so your app retries the same
call. Without idempotency, you'd end up with ₹1000 added instead of ₹500.

**How we prevent that, step by step:**

1. Every "add money" and "transfer" request **must** include a header:
   `Idempotency-Key: <some-unique-value>` (a UUID works well). Missing it →
   the request is rejected with `400 Bad Request` before anything else happens.
2. When the request comes in, the server **first checks**: "have I already
   processed this exact key before?" It looks in the `transactions` table.
   - **If yes** → it returns that stored result immediately. No balance
     change happens. This handles the normal case of a client retrying.
3. **If no** → the server proceeds to actually add the money / do the
   transfer, and saves a new transaction record tagged with that key.
4. **The real safety net (rare but important):** what if two identical
   requests arrive at the *exact same instant*, both pass step 2 (because
   neither one has been saved yet)? This is where the database itself steps
   in: the `idempotency_key` column has a **UNIQUE constraint**. When both
   requests try to save their transaction record, the database only allows
   ONE of them to succeed — the second one gets rejected automatically. That
   rejected request's entire operation (including any balance change) is
   rolled back, so money is never double-processed even in this edge case.
   The client gets a clear `409 Conflict` telling them to check the result
   instead of blindly retrying with a brand-new key.
5. **Even failures are remembered.** If a transfer fails (say, insufficient
   balance), that failure is also saved under the same idempotency key.
   Retrying with that same key returns the same failure — it won't
   "accidentally succeed" on a later retry just because someone added funds
   in between.

**Where this lives in the code:**
`Transaction.java` (the unique constraint), `WalletService.java` (the
`replayIfExists` check + the save that can trigger the DB constraint),
`GlobalExceptionHandler.java` (turns the rare DB rejection into a clean
`409` response).

---

## 4. Concurrency Strategy

**In plain terms:** if two operations try to touch the same wallet(s) at the
same time, the database makes one of them wait its turn — nothing is ever
allowed to read a balance, and then write based on stale information.

We use **two techniques together**, as required:

### a) Optimistic locking (on every wallet)

Every wallet has a hidden `version` number. Every time the balance is
updated, the system checks: "is this still the version I originally read?"
If someone else changed the wallet in between, the version won't match, the
update is rejected, and the system reports a conflict (`409`) instead of
silently overwriting someone else's change. Think of it like a Google Doc
telling you "someone else edited this — please refresh" instead of letting
your stale edit wipe out theirs.

### b) Pessimistic locking (specifically for transfers)

A transfer touches **two** wallets at once — the sender's and the
receiver's. That needs something stronger than "check version at the end":
we need to **lock both wallets for the whole duration of the transfer**, so
no other operation can touch either wallet mid-transfer. This is done with
a real database row lock (`SELECT ... FOR UPDATE`) — any other operation
trying to touch either wallet simply **waits** until the transfer finishes.

**The tricky part we specifically solved: deadlocks.**
Imagine Alice sends money to Bob at the exact same moment Bob sends money to
Alice. If the code locked "sender first, then receiver," Alice's request
would lock Alice's wallet and wait for Bob's, while Bob's request locks
Bob's wallet and waits for Alice's — both wait forever. This is a
**deadlock**.

Our fix: **always lock wallets in a fixed order** (by their internal wallet
ID, lowest first), no matter which direction the money is flowing. So both
Alice→Bob and Bob→Alice always try to lock the same wallet first. One of
them simply waits a fraction of a second for the other to finish — no
permanent standoff.

**Where this lives in the code:**
`Wallet.java` (`@Version` field = optimistic lock),
`WalletRepository.java` (`findByIdForUpdate` = pessimistic lock query),
`WalletService.java` (`transfer` method — the lock-ordering logic).

---

## 5. Transaction Boundaries

**In plain terms:** "transaction boundary" means exactly how much work
happens as one all-or-nothing unit. Either everything inside it succeeds
and is saved, or none of it is.

**Add money:** one boundary covers: lock wallet → increase balance → save
the transaction record. All three happen together, or none do.

**Transfer money:** one boundary covers: lock both wallets (in safe order)
→ check sender balance → debit sender → credit receiver → save the
transaction record. If any step fails partway (say, a crash), the database
rolls everything back — you will never see money leave one wallet without
arriving in the other.

**One deliberate exception, explained:** when a transfer fails because of
insufficient balance, we *want* to keep a permanent record of that failed
attempt (so a retry with the same key returns the same failure, per §3).
Normally, throwing an error would roll back everything, including that
failure record we just tried to save. We use a specific transaction setting
(`noRollbackFor`) so that this one particular, expected business failure
still lets the failure record commit, while every other unexpected error
still rolls back completely.

**Registration:** creating the user account and creating their wallet also
happen in one boundary — you can never end up with a user that has no
wallet, or a wallet with no owner.

**Where this lives in the code:** the `@Transactional` annotations on
`AuthService.register`, `WalletService.addMoney`, and `WalletService.transfer`
(note the `noRollbackFor` on the transfer method specifically).

---

## 6. How to Run the Application Locally

### What you need first

- **Java 17 or newer** installed (`java -version` to check)
- **Maven** installed (`mvn -version` to check) — or use the Maven built
  into IntelliJ IDEA / Eclipse / VS Code, no separate install needed
- An IDE (IntelliJ IDEA Community Edition is free and recommended) — or just
  a terminal

### Steps

1. **Unzip the project** you received. You should see a `wallet-system`
   folder containing `pom.xml`, `src/`, and this README.
2. **Open a terminal inside that folder** (or open the folder in your IDE).
3. **Run the application:**
   ```bash
   mvn spring-boot:run
   ```
   (Or, in an IDE: open `WalletSystemApplication.java` and click the green
   Run button next to the `main` method.)
4. **Wait for the startup log.** You'll see Spring Boot's banner, then a
   line like `Tomcat started on port(s): 8080`, followed by
   `Started WalletSystemApplication`. The app is now running at
   `http://localhost:8080`.
5. **A default admin account is auto-created** the first time you run it —
   look for a log line like:
   ```
   Seeded default admin account -> email: admin@wallet.system, password: Admin@12345
   ```
   Use these credentials to test the `/admin/**` endpoints.
6. **Try it out.** Using Postman, curl, or any HTTP client:
   ```bash
   # Register a new user (creates their wallet automatically)
   curl -X POST http://localhost:8080/auth/register \
     -H "Content-Type: application/json" \
     -d '{"email":"alice@example.com","password":"SuperSecret1"}'

   # Log in (copy the "token" from the response)
   curl -X POST http://localhost:8080/auth/login \
     -H "Content-Type: application/json" \
     -d '{"email":"alice@example.com","password":"SuperSecret1"}'

   # Add money (replace <TOKEN> with the token you copied)
   curl -X POST http://localhost:8080/wallet/add \
     -H "Authorization: Bearer <TOKEN>" \
     -H "Idempotency-Key: my-unique-key-001" \
     -H "Content-Type: application/json" \
     -d '{"amount": 500}'

   # Check your balance
   curl http://localhost:8080/wallet -H "Authorization: Bearer <TOKEN>"
   ```
7. **Data persists between restarts.** The database is a file
   (`./data/walletdb.mv.db`) created next to wherever you ran the app — stop
   and restart, and your data is still there. Delete that `data/` folder to
   start completely fresh.
8. **(Optional) Browse the database directly:** visit
   `http://localhost:8080/h2-console` in your browser. JDBC URL:
   `jdbc:h2:file:./data/walletdb`, username `sa`, no password.
9. **Port already in use?** Change `server.port=8080` in
   `src/main/resources/application.properties` to a free port, e.g. `8081`.

---

## 7. Unit Tests & Coverage

### What's tested

| Test file | What it proves |
|---|---|
| `JwtUtilTest` | Tokens are generated, parsed, and correctly rejected once expired or mismatched. |
| `AuthServiceTest` | Registration creates a user + wallet together; duplicate emails are rejected; login succeeds/fails correctly. |
| `WalletServiceTest` | Add-money and transfer logic, including: idempotency replay, insufficient-balance handling, self-transfer rejection, and — critically — that **wallet locks are always acquired lowest-ID-first**, proving the deadlock fix actually behaves as designed. |
| `AuthControllerTest` | `/auth/register` and `/auth/login` return the right HTTP status for valid input, bad input, and duplicate/incorrect credentials. |
| `WalletControllerTest` | `/wallet/**` endpoints enforce the `Idempotency-Key` header, validate input, and return the right status for every business outcome (success, not found, insufficient balance). |
| `AdminControllerTest` | `/admin/**` endpoints return the right data shape. |
| `GlobalExceptionHandlerTest` | Every error type maps to the correct, clean HTTP response — including ones rare enough that normal test traffic wouldn't reliably trigger them. |
| `WalletSystemIntegrationTest` | **Full end-to-end proof**, using the real database and real security filters: an unauthenticated request is rejected (401), a `USER` token can't reach admin endpoints (403), a duplicate `Idempotency-Key` credits money exactly once, and two **simultaneous, opposite-direction transfers** between two real wallets finish with mathematically correct balances (no money created or lost). |

### How to run the tests

```bash
mvn test
```

This runs every test above against an isolated in-memory database (separate
from your local `./data/walletdb` file — tests never touch your real data).

### How to check coverage

```bash
mvn verify
```

This runs the tests **and** generates a coverage report via JaCoCo. Open the
report in a browser:

```
target/site/jacoco/index.html
```

You'll see a percentage breakdown per package/class. The build is configured
to **fail if overall line coverage of the business logic drops below 80%**
(controllers, services, security, and exception handling — the parts with
actual logic to get wrong). Plain data classes (DTOs, entities) are excluded
from that threshold since they're just field holders with no branching logic
to test.

### Reading the coverage report

1. Open `target/site/jacoco/index.html` after running `mvn verify`.
2. The top-level table shows overall percentages (instructions, branches, lines).
3. Click into `com.wallet.system.service` or `com.wallet.system.controller`
   to see coverage class-by-class.
4. Green highlighted lines = covered by a test. Red = not covered — useful
   if you add new features later and want to know what still needs a test.
