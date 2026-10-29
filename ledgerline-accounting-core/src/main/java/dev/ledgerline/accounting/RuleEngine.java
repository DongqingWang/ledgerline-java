package dev.ledgerline.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.ledgerline.common.Accounting.*;
import dev.ledgerline.common.ApiErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class RuleEngine {
    public record Match(String category, String country) {
        boolean matches(Context ctx) { return wildcard(category, ctx.category()) && wildcard(country, ctx.country()); }
        private static boolean wildcard(String pattern, String value) {
            return pattern == null || pattern.isBlank() || "*".equals(pattern) || pattern.equals(value);
        }
    }
    public record Dimensions(String glAccount, String costCenter, String taxCode) {}
    public record Rule(Match match, Dimensions debit, Dimensions credit, Dimensions feeCredit) {}
    public record Rules(List<Rule> rules) {}
    private final List<Rule> rules;

    public RuleEngine(@Value("${ledger.rules:classpath:rules.yaml}") Resource resource) throws IOException {
        var mapper = new ObjectMapper(new YAMLFactory()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try (var input = resource.getInputStream()) {
            rules = List.copyOf(mapper.readValue(input, Rules.class).rules());
        }
        if (rules.isEmpty()) throw new IllegalArgumentException("Empty rule set");
    }

    public Journal compose(Context ctx) {
        ApiErrors.required(ctx.fromAccount(), "from_account");
        ApiErrors.required(ctx.toAccount(), "to_account");
        ApiErrors.required(ctx.currency(), "currency");
        if (ctx.fromAccount().equals(ctx.toAccount())) throw new IllegalArgumentException("Accounts must be distinct");
        if (ctx.amountMinor() <= 0 || ctx.feeMinor() < 0 || ctx.feeMinor() > ctx.amountMinor())
            throw new IllegalArgumentException("Positive amount and fee between zero and amount required");
        Rule rule = rules.stream().filter(r -> r.match() == null || r.match().matches(ctx)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No matching accounting rule"));
        var lines = new ArrayList<Line>();
        lines.add(line(ctx.fromAccount(), "debit", ctx.amountMinor(), rule.debit()));
        lines.add(line(ctx.toAccount(), "credit", ctx.amountMinor() - ctx.feeMinor(), rule.credit()));
        if (ctx.feeMinor() > 0) lines.add(line("platform-fee-revenue", "credit", ctx.feeMinor(), rule.feeCredit()));
        var journal = new Journal(List.copyOf(lines));
        journal.validate();
        return journal;
    }

    private Line line(String account, String direction, long amount, Dimensions dims) {
        if (dims == null || dims.glAccount() == null || dims.glAccount().isBlank())
            throw new IllegalArgumentException("Rule lacks " + direction + " dimensions (including fee_credit for fees)");
        return new Line(account, direction, amount, dims.glAccount(), dims.costCenter(), dims.taxCode());
    }
}
