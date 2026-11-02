package dev.calculo.account;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Account> all() {
        return jdbc.sql("select id, name, broker from account order by id")
                .query((rs, i) -> new Account(rs.getString("id"), rs.getString("name"), rs.getString("broker")))
                .list();
    }

    /** Creates the account if missing; an existing account keeps its name. */
    public void createIfMissing(Account account) {
        jdbc.sql("insert into account (id, name, broker) values (:id, :name, :broker) on conflict (id) do nothing")
                .param("id", account.id()).param("name", account.name()).param("broker", account.broker())
                .update();
    }
}
