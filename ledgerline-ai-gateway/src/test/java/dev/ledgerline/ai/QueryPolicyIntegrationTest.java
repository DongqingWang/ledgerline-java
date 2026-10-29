package dev.ledgerline.ai;

import dev.ledgerline.common.HttpClients;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class QueryPolicyIntegrationTest {
    JdbcTemplate database() {
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE account_balances(account_id VARCHAR,currency VARCHAR,balance_minor BIGINT)");
        db.execute("CREATE TABLE journal_entries(id INT,account_id VARCHAR,direction VARCHAR,amount_minor BIGINT,currency VARCHAR,gl_account VARCHAR,cost_center VARCHAR,posted_at VARCHAR,transfer_dedup_id VARCHAR,raft_index BIGINT)");
        db.update("INSERT INTO account_balances VALUES('alice','USD',123)");
        db.update("INSERT INTO journal_entries VALUES(1,'alice','credit',123,'USD','2100','SECRET','2026-01-01','long-transfer-id',3)");
        return db;
    }
    @Test void configuredPolicyIsUsedByActualDatabaseToolCalls() throws Exception {
        var mapper = HttpClients.mapper();
        var policy = new RedactionPolicy(Map.of("cost_center", new RedactionPolicy.FieldRule("public", "none"),
            "amount_minor", new RedactionPolicy.FieldRule("internal_sensitive", "redact"),
            "balance_minor", new RedactionPolicy.FieldRule("internal_sensitive", "redact")));
        var tools = new QueryTools(database(), mapper, policy);
        var search = mapper.valueToTree(tools.call("search_transactions", mapper.readTree("{\"account_id\":\"alice\",\"limit\":1000}")));
        var entry = search.path("entries").get(0);
        assertEquals("SECRET", entry.path("cost_center").asText());
        assertFalse(entry.has("amount_minor"));
        assertNotEquals("long-transfer-id", entry.path("transfer_dedup_id").asText());
        var balance = mapper.valueToTree(tools.call("get_account_balance", mapper.readTree("{\"account_id\":\"alice\"}")));
        assertFalse(balance.has("balance_minor"));
    }
    @Test void auditsActualRedactionsExactlyOnceWithoutLoggingRawArguments() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("ledgerline.audit");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var mapper = HttpClients.mapper();
            var tools = new QueryTools(database(), mapper, new RedactionPolicy(Map.of()));
            tools.call("search_transactions", mapper.readTree("{\"account_id\":\"alice\",\"limit\":999}"));
            assertEquals(1, appender.list.size());
            var event = mapper.readTree(appender.list.getFirst().getFormattedMessage());
            assertEquals(20, event.path("effective_limit").asInt());
            assertEquals(List.of("cost_center", "transfer_dedup_id"), mapper.convertValue(event.path("redacted_fields"), List.class));
            assertFalse(appender.list.getFirst().getFormattedMessage().contains("alice"));
            assertThrows(IllegalArgumentException.class, () -> tools.call("get_account_balance", mapper.readTree("{}")));
            assertEquals(2, appender.list.size());
            assertEquals("error", mapper.readTree(appender.list.getLast().getFormattedMessage()).path("outcome").asText());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
