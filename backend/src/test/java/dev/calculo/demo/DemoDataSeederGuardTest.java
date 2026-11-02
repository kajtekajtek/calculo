package dev.calculo.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.calculo.account.Account;
import dev.calculo.account.AccountRepository;
import dev.calculo.bonds.BondRepository;
import dev.calculo.prices.PriceSyncService;
import dev.calculo.xtb.LedgerRepository;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.support.TransactionTemplate;

/** The demo's fake prices must never reach a database that holds anything but demo data. */
class DemoDataSeederGuardTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PriceSyncService sync = mock(PriceSyncService.class);
    private final DemoDataSeeder seeder = new DemoDataSeeder(accounts, mock(LedgerRepository.class),
            mock(BondRepository.class), sync, mock(TransactionTemplate.class), Clock.systemUTC(), "Obligacje");

    private static Account account(String id) {
        return new Account(id, id, "x");
    }

    private void assertRefused(Account... existing) {
        when(accounts.all()).thenReturn(List.of(existing));
        assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("demo");
        verify(sync, never()).sync();
    }

    @Test
    void failsOnAnImportedAccount() {
        assertRefused(account("xtb:IKE"));
    }

    @Test
    void failsOnAnImportedAccountNextToDemoData() {
        assertRefused(account(DemoDataSeeder.IKE), account("xtb:IKE"));
    }

    @Test
    void failsOnBondsAloneBecauseTheyAreNotDemoData() {
        assertRefused(account("bonds"));
    }

    @Test
    void acceptsTheDemoAccounts() {
        when(accounts.all()).thenReturn(List.of(account(DemoDataSeeder.IKE), account(DemoDataSeeder.TRADES),
                account("bonds")));
        seeder.run(new DefaultApplicationArguments());
        verify(sync).sync();
    }
}
