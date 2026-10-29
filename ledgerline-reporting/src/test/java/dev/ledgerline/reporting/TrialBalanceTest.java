package dev.ledgerline.reporting;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TrialBalanceTest {
    JdbcTemplate database() {
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE journal_entries(transfer_dedup_id VARCHAR,account_id VARCHAR,direction VARCHAR,amount_minor BIGINT,currency VARCHAR,gl_account VARCHAR,"
            + "posted_at TIMESTAMP WITH TIME ZONE DEFAULT TIMESTAMP WITH TIME ZONE '2026-01-01 10:00:00+00')");
        db.execute("CREATE TABLE account_balances(account_id VARCHAR,currency VARCHAR,balance_minor BIGINT)");
        db.update("INSERT INTO journal_entries(transfer_dedup_id,account_id,direction,amount_minor,currency,gl_account) VALUES('txn','bank','debit',100,'USD','1000'),('txn','alice','credit',100,'USD','1000')");
        db.update("INSERT INTO account_balances VALUES('bank','USD',-100),('alice','USD',100),('empty','USD',0)");
        return db;
    }
    @Test void reconcilesJournalAndProjection() {
        var report = new TrialBalance(database()).run();
        assertTrue(report.balanced());
        assertEquals(1, report.rows().size());
        assertTrue(report.balanceMismatches().isEmpty());
    }
    @Test void detectsCorruptBalanceEvenWhenBooksBalance() {
        var db = database();
        db.update("UPDATE account_balances SET balance_minor=101 WHERE account_id='alice'");
        var report = new TrialBalance(db).run();
        assertFalse(report.balanced());
        assertEquals(1, report.balanceMismatches().size());
        assertTrue(report.unbalancedTransfers().isEmpty());
    }
    @Test void doesNotNetDifferentCurrenciesAgainstEachOther() {
        var db = database();
        db.update("INSERT INTO journal_entries(transfer_dedup_id,account_id,direction,amount_minor,currency,gl_account) VALUES('bad-usd','bank','debit',50,'USD','1000'),('bad-eur','bank','credit',50,'EUR','1000')");
        var report = new TrialBalance(db).run();
        assertFalse(report.balanced());
        assertEquals(2, report.unbalancedTransfers().size());
    }
    @Test void missingProjectedAccountIsAnError() {
        var db = database();
        db.update("DELETE FROM account_balances WHERE account_id='alice'");
        assertFalse(new TrialBalance(db).run().balanced());
    }

    void transfer(JdbcTemplate db, String id, String timestamp, long amount) {
        for (String account : new String[]{"bank", "alice"}) {
            String direction = account.equals("bank") ? "debit" : "credit";
            db.update("INSERT INTO journal_entries VALUES(?,?,?,?,?,?,?)", id, account, direction, amount, "USD", "1000", java.time.OffsetDateTime.parse(timestamp));
            db.update("UPDATE account_balances SET balance_minor=balance_minor+? WHERE account_id=?", account.equals("bank") ? -amount : amount, account);
        }
    }
    @Test void inclusiveEndDateIncludesLastMicrosecondButNotNextDayMidnight() {
        var db = database();
        transfer(db, "last-in-day", "2026-01-02T23:59:59.999999Z", 50);
        transfer(db, "next-day", "2026-01-03T00:00:00Z", 25);
        var result = new TrialBalance(db).run(DateRange.parse("2026-01-02", "2026-01-02"));
        assertTrue(result.balanced());
        assertEquals(1, result.rows().size());
        assertEquals(0, new java.math.BigDecimal("50").compareTo(result.rows().getFirst().debits()));
        assertTrue(result.balanceMismatches().isEmpty()); // Current balance is 175, not period movement of 50.
    }
    @Test void filteredReportStillDetectsGlobalProjectionCorruption() {
        var db = database();
        transfer(db, "later", "2026-02-01T00:00:00Z", 50);
        db.update("UPDATE account_balances SET balance_minor=999 WHERE account_id='alice'");
        var result = new TrialBalance(db).run(DateRange.parse("2026-01-01", "2026-01-01"));
        assertFalse(result.balanced());
        assertEquals(1, result.balanceMismatches().size());
    }
    @Test void boundsAreOptionalAndUseUtcRatherThanDatabaseTimezone() {
        var db = database();
        transfer(db, "utc-next-day", "2026-01-01T23:30:00-02:00", 50);
        var service = new TrialBalance(db);
        assertEquals(0, new java.math.BigDecimal("100").compareTo(service.run(DateRange.parse(null, "2026-01-01")).rows().getFirst().debits()));
        assertEquals(0, new java.math.BigDecimal("50").compareTo(service.run(DateRange.parse("2026-01-02", null)).rows().getFirst().debits()));
        assertTrue(service.run(DateRange.parse("2025-01-01", "2025-01-02")).rows().isEmpty());
    }
}
