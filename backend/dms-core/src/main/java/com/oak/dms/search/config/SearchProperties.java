package com.oak.dms.search.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Binds {@code dms.search.*} (Document Search &amp; Retrieval). PostgreSQL stays the
 * source of truth; everything here configures derived data (the OpenSearch index).
 */
@Component
@ConfigurationProperties(prefix = "dms.search")
@Getter
@Setter
public class SearchProperties {

    /** Global switch. Off means every search uses the title-search fallback and nothing is indexed. */
    private boolean enabled = true;

    private OpenSearch opensearch = new OpenSearch();
    private Index index = new Index();
    private Indexer indexer = new Indexer();
    private Extraction extraction = new Extraction();
    private Reconcile reconcile = new Reconcile();
    private Query query = new Query();

    /** HMAC key for search cursors. Blank means a random per-boot key (cursors die on restart). */
    private String cursorSecret = "";

    @Getter
    @Setter
    public static class OpenSearch {
        private String url = "http://localhost:9200";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);
        /** Bulk and delete-by-query are allowed longer than a user query. */
        private Duration bulkTimeout = Duration.ofSeconds(60);
    }

    @Getter
    @Setter
    public static class Index {
        /** Prefix for every index and alias; tests use a unique one. */
        private String prefix = "dms";
        private int shards = 1;
        private int replicas = 0;
        /** Production only: spread one large tenant over several shards. Unset in dev (1 shard). */
        private Integer routingPartitionSize;
    }

    @Getter
    @Setter
    public static class Indexer {
        /** Per-instance switch for the queue poller (off in tests, which drive it directly). */
        private boolean enabled = true;
        private Duration pollInterval = Duration.ofSeconds(1);
        private Duration idlePoll = Duration.ofSeconds(2);
        private int batchSize = 50;
        private Duration lease = Duration.ofMinutes(2);
        private int maxAttempts = 8;
        private Duration baseBackoff = Duration.ofSeconds(5);
        private Duration maxBackoff = Duration.ofMinutes(15);
        /** Concurrent extraction workers (PDF parsing is the expensive part). */
        private int workers = 2;
    }

    @Getter
    @Setter
    public static class Extraction {
        private long maxFileBytes = 100L * 1024 * 1024;
        private int maxPages = 1000;
        private int maxChars = 2_000_000;
        private Duration timeout = Duration.ofSeconds(60);
        /** MIME types read directly as text. */
        private List<String> plainTextTypes = List.of(
                "text/plain", "text/csv", "text/markdown", "text/html", "text/xml",
                "application/json", "application/xml");
    }

    @Getter
    @Setter
    public static class Reconcile {
        private boolean enabled = true;
        /** Daily, 03:30 server time. */
        private String cron = "0 30 3 * * *";
    }

    @Getter
    @Setter
    public static class Query {
        private int defaultPageSize = 20;
        private int maxPageSize = 50;
        private int overfetchFactor = 3;
        /** The largest candidate window asked of the engine in one round (ids only, so cheap). */
        private int maxBatch = 500;
        /** Rounds and candidates are the caps on the over-fetch loop; the time budget is the hard stop. */
        private int maxRounds = 6;
        private int maxCandidates = 2000;
        private Duration timeBudget = Duration.ofMillis(2500);
        private int breakerFailures = 5;
        private Duration breakerOpen = Duration.ofSeconds(30);
        /** Ranking: title and file name above metadata above body text. Tunable without code. */
        private float titleBoost = 6;
        private float titleStemBoost = 3;
        private float fileNameBoost = 5;
        private float metadataBoost = 2;
        private float bodyBoost = 1;
        private float titlePhraseBoost = 10;
        private float fileNamePhraseBoost = 8;
        /** Highlight snippets: how many and how long. */
        private int highlightFragments = 2;
        private int highlightFragmentSize = 150;
    }
}
