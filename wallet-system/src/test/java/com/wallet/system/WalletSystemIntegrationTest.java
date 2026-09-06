package com.wallet.system;

import com.wallet.system.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests that boot the full Spring context (real security filter
 * chain, real H2 database, real HTTP layer) to prove the three hardest
 * requirements actually hold together, not just in isolated unit tests:
 *   1. JWT + role security is enforced at the HTTP boundary.
 *   2. A duplicate Idempotency-Key never double-processes money.
 *   3. Two concurrent transfers never lose an update (no money created/destroyed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletSystemIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String register(String email, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setEmail(email);
        req.setPassword(password);
        ResponseEntity<AuthResponse> resp = restTemplate.postForEntity(baseUrl() + "/auth/register", req, AuthResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().getToken();
    }

    private String login(String email, String password) {
        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        ResponseEntity<AuthResponse> resp = restTemplate.postForEntity(baseUrl() + "/auth/login", req, AuthResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().getToken();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // -----------------------------------------------------------------
    // Security
    // -----------------------------------------------------------------

    @Test
    void wallet_withoutToken_returns401() {
        ResponseEntity<String> resp = restTemplate.getForEntity(baseUrl() + "/wallet", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminEndpoint_withUserToken_returns403() {
        String email = "user_" + UUID.randomUUID() + "@example.com";
        String token = register(email, "SuperSecret1");

        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(token));
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl() + "/admin/wallets", HttpMethod.GET, entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminEndpoint_withSeededAdminToken_returns200() {
        String adminToken = login("admin@wallet.system", "Admin@12345");

        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(adminToken));
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl() + "/admin/wallets", HttpMethod.GET, entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -----------------------------------------------------------------
    // Idempotency
    // -----------------------------------------------------------------

    @Test
    void addMoney_repeatedWithSameIdempotencyKey_creditsOnlyOnce() {
        String email = "idem_" + UUID.randomUUID() + "@example.com";
        String token = register(email, "SuperSecret1");
        String idempotencyKey = UUID.randomUUID().toString();

        AddMoneyRequest request = new AddMoneyRequest();
        request.setAmount(new BigDecimal("500.00"));

        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", idempotencyKey);
        HttpEntity<AddMoneyRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<TransactionResponse> first = restTemplate.postForEntity(
                baseUrl() + "/wallet/add", entity, TransactionResponse.class);
        ResponseEntity<TransactionResponse> second = restTemplate.postForEntity(
                baseUrl() + "/wallet/add", entity, TransactionResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Same transaction id returned both times -> proves it wasn't reprocessed.
        assertThat(second.getBody().getId()).isEqualTo(first.getBody().getId());

        HttpEntity<Void> getEntity = new HttpEntity<>(authHeaders(token));
        ResponseEntity<WalletResponse> wallet = restTemplate.exchange(
                baseUrl() + "/wallet", HttpMethod.GET, getEntity, WalletResponse.class);

        // Balance credited exactly once, not twice.
        assertThat(wallet.getBody().getBalance()).isEqualByComparingTo("500.00");
    }

    // -----------------------------------------------------------------
    // Concurrency
    // -----------------------------------------------------------------

    @Test
    void concurrentOppositeTransfers_neverLoseAnUpdate() throws Exception {
        String emailA = "concA_" + UUID.randomUUID() + "@example.com";
        String emailB = "concB_" + UUID.randomUUID() + "@example.com";
        String tokenA = register(emailA, "SuperSecret1");
        String tokenB = register(emailB, "SuperSecret1");

        // Fund both wallets so both directions of transfer are affordable.
        fundWallet(tokenA, new BigDecimal("1000.00"));
        fundWallet(tokenB, new BigDecimal("1000.00"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<HttpStatusCode> statusA = new AtomicReference<>();
        AtomicReference<HttpStatusCode> statusB = new AtomicReference<>();

        Runnable transferAtoB = () -> {
            try {
                startLatch.await();
                statusA.set(transfer(tokenA, emailB, new BigDecimal("200.00"), UUID.randomUUID().toString()));
            } catch (InterruptedException ignored) {
            }
        };
        Runnable transferBtoA = () -> {
            try {
                startLatch.await();
                statusB.set(transfer(tokenB, emailA, new BigDecimal("150.00"), UUID.randomUUID().toString()));
            } catch (InterruptedException ignored) {
            }
        };

        executor.submit(transferAtoB);
        executor.submit(transferBtoA);
        startLatch.countDown(); // release both threads at the same instant
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(statusA.get()).isEqualTo(HttpStatus.CREATED);
        assertThat(statusB.get()).isEqualTo(HttpStatus.CREATED);

        BigDecimal balanceA = getBalance(tokenA);
        BigDecimal balanceB = getBalance(tokenB);

        // Net effect: A sent 200, received 150 -> -50. B sent 150, received 200 -> +50.
        assertThat(balanceA).isEqualByComparingTo("950.00");
        assertThat(balanceB).isEqualByComparingTo("1050.00");
        // Total money in the system must be conserved — no update was lost.
        assertThat(balanceA.add(balanceB)).isEqualByComparingTo("2000.00");
    }

    private void fundWallet(String token, BigDecimal amount) {
        AddMoneyRequest request = new AddMoneyRequest();
        request.setAmount(amount);
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        HttpEntity<AddMoneyRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TransactionResponse> resp = restTemplate.postForEntity(
                baseUrl() + "/wallet/add", entity, TransactionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private HttpStatusCode transfer(String token, String toEmail, BigDecimal amount, String idempotencyKey) {
        TransferRequest request = new TransferRequest();
        request.setToEmail(toEmail);
        request.setAmount(amount);
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", idempotencyKey);
        HttpEntity<TransferRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TransactionResponse> resp = restTemplate.postForEntity(
                baseUrl() + "/wallet/transfer", entity, TransactionResponse.class);
        return resp.getStatusCode();
    }

    private BigDecimal getBalance(String token) {
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(token));
        ResponseEntity<WalletResponse> resp = restTemplate.exchange(
                baseUrl() + "/wallet", HttpMethod.GET, entity, WalletResponse.class);
        return resp.getBody().getBalance();
    }
}
