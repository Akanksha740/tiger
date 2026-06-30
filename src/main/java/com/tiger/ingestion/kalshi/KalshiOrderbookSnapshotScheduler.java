package com.tiger.ingestion.kalshi;

import com.tiger.config.TigerProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "tiger.ingestion.kalshi-orderbook-snapshots.scheduler-enabled",
        havingValue = "true")
public class KalshiOrderbookSnapshotScheduler {
    private static final Logger log = LoggerFactory.getLogger(KalshiOrderbookSnapshotScheduler.class);

    private final KalshiOrderbookSnapshotService snapshotService;
    private final TigerProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public KalshiOrderbookSnapshotScheduler(
            KalshiOrderbookSnapshotService snapshotService, TigerProperties properties) {
        this.snapshotService = snapshotService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${tiger.ingestion.kalshi-orderbook-snapshots.fixed-delay-ms:60000}")
    public void snapshotActiveMarkets() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping Kalshi orderbook snapshot tick because the previous tick is still running");
            return;
        }
        try {
            TigerProperties.KalshiOrderbookSnapshots job = properties.ingestion().kalshiOrderbookSnapshots();
            snapshotService.pollActiveMarkets(job.limit(), job.samples(), job.sampleIntervalMs());
        } finally {
            running.set(false);
        }
    }
}
