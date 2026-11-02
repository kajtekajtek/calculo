package dev.calculo.xtb;

import dev.calculo.xtb.ParsedExport.CashOperation;
import dev.calculo.xtb.ParsedExport.OpenPosition;
import dev.calculo.xtb.ParsedExport.OperationType;
import dev.calculo.xtb.ParsedExport.ParsedAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Manual sanity check against a real export. Runs only when CALCULO_REAL_EXPORT points at the file. */
@EnabledIfEnvironmentVariable(named = "CALCULO_REAL_EXPORT", matches = ".+")
class XtbRealExportCheck {

    @Test
    void realExportParsesAndMatchesOpenPositions() throws Exception {
        Path path = Path.of(System.getenv("CALCULO_REAL_EXPORT"));
        ParsedExport parsed;
        try (InputStream in = Files.newInputStream(path)) {
            parsed = XtbExportParser.parse(path.getFileName().toString(), in);
        }
        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.accounts()).hasSize(2);
        for (ParsedAccount acc : parsed.accounts()) {
            Map<String, BigDecimal> bought = new HashMap<>();
            for (CashOperation op : acc.operations()) {
                if (op.type() == OperationType.PURCHASE) bought.merge(op.ticker(), op.volume(), BigDecimal::add);
            }
            Map<String, BigDecimal> open = new HashMap<>();
            for (OpenPosition p : acc.openPositions()) open.merge(p.ticker(), p.volume(), BigDecimal::add);
            assertThat(bought.keySet()).isEqualTo(open.keySet());
            for (var e : open.entrySet()) {
                assertThat(bought.get(e.getKey()).subtract(e.getValue()).abs())
                        .isLessThanOrEqualTo(new BigDecimal("0.0001"));
            }
        }
    }
}
