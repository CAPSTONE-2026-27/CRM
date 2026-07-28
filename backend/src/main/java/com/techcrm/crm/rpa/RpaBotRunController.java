package com.techcrm.crm.rpa;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.rpa.RpaDtos.RpaBotRunResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rpa-bot-runs")
public class RpaBotRunController {

    private final RpaBotService rpaBotService;

    public RpaBotRunController(RpaBotService rpaBotService) {
        this.rpaBotService = rpaBotService;
    }

    @GetMapping
    public List<RpaBotRunResponse> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @RequestParam(required = false) Long botId) {
        return rpaBotService.runs(caller, botId);
    }
}
