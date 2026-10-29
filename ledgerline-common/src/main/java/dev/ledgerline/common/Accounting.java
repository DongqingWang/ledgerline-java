package dev.ledgerline.common;

import java.util.List;

public final class Accounting {
    private Accounting() {}
    public record Context(String fromAccount, String toAccount, long amountMinor, long feeMinor,
                          String currency, String category, String country) {}
    public record Line(String accountId, String direction, long amountMinor, String glAccount,
                       String costCenter, String taxCode) {}
    public record Journal(List<Line> lines) {
        public void validate() {
            if (lines == null || lines.size() < 2) throw new IllegalArgumentException("At least two journal lines required");
            long debit = 0, credit = 0;
            for (Line line : lines) {
                ApiErrors.required(line.accountId(), "account_id");
                if (line.amountMinor() < 0) throw new IllegalArgumentException("Negative journal amount");
                if ("debit".equals(line.direction())) debit = Math.addExact(debit, line.amountMinor());
                else if ("credit".equals(line.direction())) credit = Math.addExact(credit, line.amountMinor());
                else throw new IllegalArgumentException("Unknown journal direction");
            }
            if (debit <= 0 || debit != credit) throw new IllegalArgumentException("Unbalanced journal");
        }
    }
}
