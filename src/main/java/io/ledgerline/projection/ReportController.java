package io.ledgerline.projection;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.ledgerline.projection.BalanceQueryService.TrialBalanceView;
import io.ledgerline.web.TenantId;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final BalanceQueryService balanceQueryService;

    public ReportController(BalanceQueryService balanceQueryService) {
        this.balanceQueryService = balanceQueryService;
    }

    @GetMapping("/trial-balance")
    public TrialBalanceView trialBalance(TenantId tenant, @RequestParam String currency) {
        return balanceQueryService.trialBalance(tenant.value(), currency);
    }
}
