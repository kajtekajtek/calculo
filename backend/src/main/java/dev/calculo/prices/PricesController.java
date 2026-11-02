package dev.calculo.prices;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prices")
public class PricesController {
    private final PriceSyncService sync;

    public PricesController(PriceSyncService sync) {
        this.sync = sync;
    }

    /** Starts a sync in the background and returns at once. */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() {
        sync.syncAsync();
        return ResponseEntity.accepted().build();
    }
}
