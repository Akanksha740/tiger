package com.tiger.ingestion.polymarket;

import com.tiger.config.TigerProperties;
import com.tiger.ingestion.polymarket.PolymarketIngestionService.IngestionResult;
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
    private final TigerProperties properties;
    private final ApplicationContext applicationContext;

    public PolymarketIngestionRunner(
            PolymarketIngestionService ingestionService,
            TigerProperties properties,
            ApplicationContext applicationContext) {
        this.ingestionService = ingestionService;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        TigerProperties.PolymarketEvents job = properties.ingestion().polymarketEvents();
        if (!job.enabled()) {
            return;
        }

        IngestionResult result = ingestionService.ingestEventsPage(job.limit(), job.offset());
        log.info(
                "Completed Polymarket ingestion page: events={}, markets={}",
                result.events(),
                result.markets());
        if (properties.ingestion().exitOnComplete()) {
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }
}
