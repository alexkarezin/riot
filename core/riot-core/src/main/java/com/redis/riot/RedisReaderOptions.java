package com.redis.riot;

import java.util.function.Supplier;

import com.redis.lettucemod.RedisModulesClient;
import com.redis.lettucemod.cluster.RedisModulesClusterClient;
import com.redis.spring.batch.support.RedisItemReaderBuilder;
import com.redis.spring.batch.support.ScanRedisItemReaderBuilder;
import com.redis.spring.batch.support.ScanSizeEstimator;

import io.lettuce.core.AbstractRedisClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Option;

@Slf4j
@Data
public class RedisReaderOptions {

	@Option(names = "--count", description = "SCAN COUNT option (default: ${DEFAULT-VALUE}).", paramLabel = "<int>")
	private long scanCount = ScanRedisItemReaderBuilder.DEFAULT_SCAN_COUNT;
	@Option(names = "--match", description = "SCAN MATCH pattern (default: ${DEFAULT-VALUE}).", paramLabel = "<glob>")
	private String scanMatch = ScanRedisItemReaderBuilder.DEFAULT_SCAN_MATCH;
	@Option(names = "--type", description = "SCAN TYPE option: ${COMPLETION-CANDIDATES}.", paramLabel = "<type>")
	private DataType scanType;
	@Option(names = "--reader-queue", description = "Capacity of the reader queue (default: ${DEFAULT-VALUE}).", paramLabel = "<int>")
	private int queueCapacity = RedisItemReaderBuilder.DEFAULT_QUEUE_CAPACITY;
	@Option(names = "--reader-threads", description = "Number of reader threads (default: ${DEFAULT-VALUE}).", paramLabel = "<int>")
	private int threads = RedisItemReaderBuilder.DEFAULT_THREADS;
	@Option(names = "--reader-batch", description = "Number of reader values to process at once (default: ${DEFAULT-VALUE}).", paramLabel = "<int>")
	private int batchSize = RedisItemReaderBuilder.DEFAULT_CHUNK_SIZE;
	@Option(names = "--sample-size", description = "Number of samples used to estimate dataset size (default: ${DEFAULT-VALUE}).", paramLabel = "<int>", hidden = true)
	private int sampleSize = 100;
	@Option(names = "--reader-pool", description = "Max pool connections for reader process (default: ${DEFAULT-VALUE}).", paramLabel = "<int>")
	private int poolMax = 8;

	@SuppressWarnings("unchecked")
	public <B extends ScanRedisItemReaderBuilder<?, ?>> B configure(B builder) {
		builder.scanMatch(scanMatch);
		builder.scanCount(scanCount);
		if (scanType != null) {
			builder.scanType(scanType.name().toLowerCase());
		}
		return (B) configure((RedisItemReaderBuilder<?, ?, ?>) builder);
	}

	public <B extends RedisItemReaderBuilder<?, ?, ?>> B configure(B builder) {
		builder.threads(threads);
		builder.chunkSize(batchSize);
		builder.queueCapacity(queueCapacity);
		return builder;
	}

	public ScanSizeEstimator.EstimateOptions estimateOptions() {
		ScanSizeEstimator.EstimateOptions.EstimateOptionsBuilder builder = ScanSizeEstimator.EstimateOptions.builder()
				.match(scanMatch).sampleSize(sampleSize);
		if (scanType != null) {
			builder.type(scanType.name().toLowerCase());
		}
		return builder.build();
	}

	public Supplier<Long> initialMaxSupplier(RedisOptions redisOptions) {
		return () -> {
			AbstractRedisClient client = redisOptions.client();
			ScanSizeEstimator.ScanSizeEstimatorBuilder builder = redisOptions.isCluster()
					? ScanSizeEstimator.client((RedisModulesClusterClient) client)
					: ScanSizeEstimator.client((RedisModulesClient) client);
			ScanSizeEstimator estimator = builder.build();
			try {
				return estimator.estimate(estimateOptions());
			} catch (Exception e) {
				log.warn("Could not estimate scan size", e);
				return null;
			}
		};
	}

}
