package dev.ledgerline.fx;

import java.math.BigDecimal;
import java.util.Map;

@FunctionalInterface
public interface FxRateSource {
    Map<String, BigDecimal> fetch() throws Exception;
}
