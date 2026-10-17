package com.techcrm.crm.rpa;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.rpa.RpaDtos.QueuedResponse;
import com.techcrm.crm.rpa.RpaDtos.RpaBotRequest;
import com.techcrm.crm.rpa.RpaDtos.RpaBotResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rpa-bots")
public class RpaBotController {

    private final RpaBotService rpaBotService;
    private final com.techcrm.crm.sap.SapBotRegistry botRegistry;

    public RpaBotController(RpaBotService rpaBotService,
                            com.techcrm.crm.sap.SapBotRegistry botRegistry) {
        this.rpaBotService = rpaBotService;
        this.botRegistry = botRegistry;
    }

    /**
     * The automations this CRM starts, and whether each is wired up.
     *
     * Read from configuration rather than the registry table: a bot runs when
     * its definition id is set, so that is what the control room should show.
     * Nothing here is editable — these are deployed in SAP and pointed at from
     * configuration.
     */
    @GetMapping("/automations")
    public List<com.techcrm.crm.sap.SapBotRegistry.BotSummary> automations() {
        return botRegistry.all();
    }

    @GetMapping
    public List<RpaBotResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return rpaBotService.list(caller);
    }

    @PostMapping
    public ResponseEntity<RpaBotResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @Valid @RequestBody RpaBotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rpaBotService.create(caller, request));
    }

    @PutMapping("/{id}")
    public RpaBotResponse update(@AuthenticationPrincipal AuthenticatedUser caller,
                                 @PathVariable Long id,
                                 @Valid @RequestBody RpaBotRequest request) {
        return rpaBotService.update(caller, id, request);
    }

    @PostMapping("/{id}/deploy")
    public RpaBotResponse deploy(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return rpaBotService.deploy(caller, id);
    }

    /** Manual on-demand run — the same path scheduled and event triggers use. */
    @PostMapping("/{id}/run")
    public ResponseEntity<QueuedResponse> run(@AuthenticationPrincipal AuthenticatedUser caller,
                                              @PathVariable Long id) {
        rpaBotService.run(caller, id);
        return ResponseEntity.accepted().body(new QueuedResponse(true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        rpaBotService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
