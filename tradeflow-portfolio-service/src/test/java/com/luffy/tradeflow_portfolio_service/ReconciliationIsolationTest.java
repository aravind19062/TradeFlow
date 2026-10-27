package com.luffy.tradeflow_portfolio_service;

import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * Reconciliation reads several tables in separate queries; without one snapshot a
 * settlement committing mid-check produces a false mismatch. Guards that every entry
 * point, including the scheduled one (which calls checkAll() via self-invocation),
 * runs inside a REPEATABLE READ transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationIsolationTest {

    @Autowired ReconciliationService reconciliation;
    @MockitoSpyBean AccountRepository accounts;

    @Test
    void scheduledAndOnDemandChecksReadFromOneSnapshot() {
        AtomicBoolean inTransaction = new AtomicBoolean();
        AtomicReference<Integer> isolation = new AtomicReference<>();
        doAnswer(inv -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            isolation.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return List.of(); // only the transaction state matters here
        }).when(accounts).findAll();

        reconciliation.scheduledCheck();
        assertThat(inTransaction).isTrue();
        assertThat(isolation.get()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);

        inTransaction.set(false);
        isolation.set(null);
        reconciliation.checkAll();
        assertThat(inTransaction).isTrue();
        assertThat(isolation.get()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }
}
