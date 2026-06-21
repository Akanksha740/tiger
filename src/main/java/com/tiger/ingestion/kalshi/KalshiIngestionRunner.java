package com.tiger.ingestion.kalshi;

import com.tiger.config.TigerProperties;
import com.tiger.ingestion.kalshi.KalshiIngestionService.IngestionCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(KalshiIngestionService.class)
public class KalshiIngestionRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KalshiIngestionRunner.class);

    private final KalshiIngestionService ingestionService;
    private final TigerProperties properties;
    private final ApplicationContext applicationContext;

    public KalshiIngestionRunner(
            KalshiIngestionService ingestionService,
            TigerProperties properties,
            ApplicationContext applicationContext) {
        this.ingestionService = ingestionService;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean ran = false;

        TigerProperties.KalshiSeries seriesJob = properties.ingestion().kalshiSeries();
        if (seriesJob.enabled()) {
            Long minUpdatedTs = ingestionService.resolveSeriesMinUpdatedTs(seriesJob.minUpdatedTs());
            IngestionCounters result = ingestionService.ingestSeries(minUpdatedTs);
            log.info(
                    "Kalshi series job finished: fetched={} inserted={} updated={} failed={}",
                    result.fetched,
                    result.inserted,
                    result.updated,
                    result.failed);
            ran = true;
        }

        TigerProperties.KalshiCatalog catalogJob = properties.ingestion().kalshiCatalog();
        if (catalogJob.enabled()) {
            KalshiIngestionService.CatalogIngestionResult result =
                    ingestionService.ingestCatalog(catalogJob.refreshSeries());
            if (result.series() != null) {
                log.info(
                        "Kalshi catalog series: fetched={} inserted={} failed={}",
                        result.series().fetched,
                        result.series().inserted,
                        result.series().failed);
            }
            log.info(
                    "Kalshi catalog events: events={} markets={} outcomes~={} failed={}",
                    result.events().events,
                    result.events().markets,
                    result.events().outcomes,
                    result.events().failed);
            log.info(
                    "Kalshi catalog open markets: markets={} outcomes~={} failed={}",
                    result.openMarkets().markets,
                    result.openMarkets().outcomes,
                    result.openMarkets().failed);
            ran = true;
        }

        TigerProperties.KalshiEvents eventsJob = properties.ingestion().kalshiEvents();
        if (eventsJob.enabled()) {
            IngestionCounters result = ingestionService.ingestEvents(eventsJob.withNestedMarkets());
            log.info(
                    "Kalshi events job finished: events={} markets={} outcomes~={} failed={}",
                    result.events,
                    result.markets,
                    result.outcomes,
                    result.failed);
            ran = true;
        }

        TigerProperties.KalshiOpenMarkets marketsJob = properties.ingestion().kalshiOpenMarkets();
        if (marketsJob.enabled()) {
            IngestionCounters result = ingestionService.ingestOpenMarkets();
            log.info(
                    "Kalshi open markets job finished: fetched={} inserted={} failed={}",
                    result.fetched,
                    result.inserted,
                    result.failed);
            ran = true;
        }

        if (ran && properties.ingestion().exitOnComplete()) {
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }
}
