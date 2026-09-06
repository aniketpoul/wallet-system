# wallet-system
wallet system backend in java 

######## Below Problems Solved in this project#############
Security — login → JWT → per-request filter check → role check → wallet ID always derived from the token, never the request
Idempotency — the check-first fast path, the DB unique-constraint safety net for true races, and why even failures get remembered
Concurrency — optimistic locking (@Version) vs. pessimistic locking (SELECT...FOR UPDATE), explained with the Alice/Bob deadlock example and the fixed lock-ordering fix
Transaction boundaries — what's all-or-nothing in add-money vs. transfer, and the deliberate noRollbackFor exception

############# Step for run application in local system ################
****port number change according to your availability
Watch the terminal or IDE console. You'll see a Spring Boot startup banner, followed by log lines, ending with something like 'Tomcat started on port(s): 8080' and 'Started WalletSystemApplication'. This means the server is live and listening at http://localhost:8080. 

The first run also creates a default admin login, shown in the logs as email admin@wallet.system, password Admin@12345.
Open Postman (or any API tool) and try these in order: 1) POST http://localhost:8080/auth/register with body 
{"email":"alice@example.com","password":"SuperSecret1"} to create an account. 

2) POST http://localhost:8080/auth/login with the same email/password to get a login token. 

3) Copy that token, then POST http://localhost:8080/wallet/add with header Authorization: Bearer <token> and Idempotency-Key: test-001, and body {"amount": 500} to add money. 

4) GET http://localhost:8080/wallet with the same Authorization header to see the updated balance.
