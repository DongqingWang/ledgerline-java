package dev.ledgerline.reporting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class TrialBalance {
    public record Row(String currency, String glAccount, BigDecimal debits, BigDecimal credits) {}
    public record Report(boolean balanced, List<Row> rows, List<Map<String, Object>> unbalancedTransfers,
                         List<Map<String, Object>> balanceMismatches, DateRange period) {}
    private final JdbcTemplate db;
    public TrialBalance(JdbcTemplate db) { this.db = db; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        return run(DateRange.all());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run(DateRange period) {
        var filter = period.filter();
        List<Row> rows = db.query("SELECT currency,COALESCE(gl_account,'') AS gl_account,"
            + "SUM(CASE WHEN direction='debit' THEN amount_minor ELSE 0 END) AS debits,"
            + "SUM(CASE WHEN direction='credit' THEN amount_minor ELSE 0 END) AS credits "
            + "FROM journal_entries" + filter.sql() + " GROUP BY currency,COALESCE(gl_account,'') ORDER BY currency,gl_account",
            (rs, i) -> new Row(rs.getString("currency"), rs.getString("gl_account"), rs.getBigDecimal("debits"), rs.getBigDecimal("credits")),
            filter.parameters().toArray());
        var unbalanced = db.queryForList("SELECT transfer_dedup_id,currency FROM journal_entries" + filter.sql()
            + " GROUP BY transfer_dedup_id,currency HAVING SUM(CASE WHEN direction='debit' THEN amount_minor ELSE -amount_minor END)<>0", filter.parameters().toArray());
        // Current balances represent all history, not just movement within a date window.
        // Keep this check global even for a period-filtered trial balance.
        var mismatches = db.queryForList("WITH expected AS (SELECT account_id,currency,"
            + "SUM(CASE WHEN direction='credit' THEN amount_minor ELSE -amount_minor END) AS balance FROM journal_entries GROUP BY account_id,currency) "
            + "SELECT b.account_id FROM account_balances b LEFT JOIN expected e ON b.account_id=e.account_id AND b.currency=e.currency "
            + "WHERE b.balance_minor<>COALESCE(e.balance,0) UNION SELECT e.account_id FROM expected e LEFT JOIN account_balances b "
            + "ON b.account_id=e.account_id AND b.currency=e.currency WHERE b.account_id IS NULL");
        boolean totals = rows.stream().allMatch(row -> row.debits().compareTo(row.credits()) == 0);
        return new Report(totals && unbalanced.isEmpty() && mismatches.isEmpty(), rows, unbalanced, mismatches, period);
    }
}
