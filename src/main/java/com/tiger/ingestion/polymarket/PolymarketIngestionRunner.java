package com.tiger.ingestion.polymarket;

import com.tiger.config.TigerProperties;
import com.tiger.ingestion.polymarket.PolymarketIngestionService.IngestionCounters;
import com.tiger.ingestion.polymarket.PolymarketIngestionService.IngestionResult;
import com.tiger.ingestion.polymarket.PolymarketOrderbookSnapshotService.SnapshotRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class PolymarketIngestionRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PolymarketIngestionRunner.class);

    private final PolymarketIngestionService ingestionService;
    private final PolymarketOrderbookSnapshotService orderbookSnapshotService;
    private final TigerProperties properties;
    private final ApplicationContext applicationContext;

    public PolymarketIngestionRunner(
            PolymarketIngestionService ingestionService,
            PolymarketOrderbookSnapshotService orderbookSnapshotService,
            TigerProperties properties,
            ApplicationContext applicationContext) {
        this.ingestionService = ingestionService;
        this.orderbookSnapshotService = orderbookSnapshotService;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean ran = false;

        TigerProperties.PolymarketEvents job = properties.ingestion().polymarketEvents();
        if (job.enabled()) {
            IngestionResult result = ingestionService.ingestEventsPage(job.limit(), job.offset());
            log.info(
                    "Completed Polymarket ingestion page: events={}, markets={}",
                    result.events(),
                    result.markets());
            ran = true;
        }

        TigerProperties.PolymarketCatalog catalogJob = properties.ingestion().polymarketCatalog();
        if (catalogJob.enabled()) {
            IngestionCounters result =
                    ingestionService.ingestCatalog(
                            catalogJob.pageLimit(), catalogJob.startOffset(), catalogJob.maxPages());
            log.info(
                    "Completed Polymarket catalog ingest: pages={} fetched={} events={} markets={} outcomes={} failed={} nextOffset={} terminal={} interrupted={}",
                    result.pages,
                    result.fetched,
                    result.events,
                    result.markets,
                    result.outcomes,
                    result.failed,
                    result.nextOffset,
                    result.terminalPage,
                    result.interrupted);
            ran = true;
        }

        TigerProperties.PolymarketOrderbookSnapshots snapshotJob =
                properties.ingestion().polymarketOrderbookSnapshots();
        if (snapshotJob.enabled()) {
            SnapshotRunResult result = orderbookSnapshotService.snapshotActiveMarkets(snapshotJob.limit());
            log.info(
                    "Completed Polymarket orderbook snapshots: scanned={} inserted={} skipped={} failed={}",
                    result.marketsScanned(),
                    result.snapshotsInserted(),
                    result.marketsSkipped(),
                    result.failures());
            ran = true;
        }

        if (ran && properties.ingestion().exitOnComplete()) {
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }
}
