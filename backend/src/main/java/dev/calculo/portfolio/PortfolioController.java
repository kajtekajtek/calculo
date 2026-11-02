package dev.calculo.portfolio;

import dev.calculo.account.Account;
import dev.calculo.portfolio.PortfolioView.PositionDetail;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PortfolioController {
    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    public List<Account> accounts() {
        return service.accounts();
    }

    @GetMapping("/portfolio")
    public PortfolioView portfolio(@RequestParam(defaultValue = "all") String account) {
        return service.view(account);
    }

    @GetMapping("/portfolio/positions/{key}")
    public PositionDetail position(@PathVariable String key, @RequestParam(defaultValue = "all") String account) {
        return service.detail(key, account);
    }
}
