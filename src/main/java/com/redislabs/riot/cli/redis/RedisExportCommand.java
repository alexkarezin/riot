package com.redislabs.riot.cli.redis;

import java.util.Map;

import org.springframework.batch.item.ItemWriter;

import com.redislabs.riot.cli.ExportCommand;
import com.redislabs.riot.cli.RedisWriterOptions;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "redis-export", description = "Transfer data from a source Redis db to a target Redis db")
public class RedisExportCommand extends ExportCommand {

	@ArgGroup(exclusive = false, heading = "Target Redis connection options%n", order = 1)
	private RedisConnectionOptions redis = new RedisConnectionOptions();
	@Mixin
	private RedisWriterOptions writer = new RedisWriterOptions();

	@Override
	protected ItemWriter<Map<String, Object>> writer() {
		return writer.writer(redis);
	}

}