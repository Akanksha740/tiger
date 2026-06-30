package com.tiger.ingestion.polymarket;

import com.tiger.config.TigerProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "tiger.ingestion.polymarket-orderbook-snapshots.scheduler-enabled",
        havingValue = "true")
public class PolymarketOrderbookSnapshotScheduler {
    private static final Logger log = LoggerFactory.getLogger(PolymarketOrderbookSnapshotScheduler.class);

    private final PolymarketOrderbookSnapshotService snapshotService;
    private final TigerProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PolymarketOrderbookSnapshotScheduler(
            PolymarketOrderbookSnapshotService snapshotService, TigerProperties properties) {
        this.snapshotService = snapshotService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${tiger.ingestion.polymarket-orderbook-snapshots.fixed-delay-ms:60000}")
    public void snapshotActiveMarkets() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping Polymarket orderbook snapshot tick because the previous tick is still running");
            return;
        }
        try {
            snapshotService.snapshotActiveMarkets(properties.ingestion().polymarketOrderbookSnapshots().limit());
        } finally {
            running.set(false);
        }
    }
}
