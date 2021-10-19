package com.redis.riot.file;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.json.JsonItemReader;
import org.springframework.batch.item.support.AbstractItemStreamItemReader;
import org.springframework.batch.item.xml.XmlItemReader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

import com.redis.riot.AbstractTransferCommand;
import com.redis.riot.RedisWriterOptions;
import com.redis.riot.RiotStepBuilder;
import com.redis.spring.batch.RedisItemWriter;
import com.redis.spring.batch.support.DataStructure;
import com.redis.spring.batch.support.job.JobFactory;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@Command(name = "import-dump", description = "Import Redis data files into Redis")
public class DumpFileImportCommand extends AbstractTransferCommand {

	@CommandLine.Parameters(arity = "0..*", description = "One ore more files or URLs", paramLabel = "FILE")
	private List<String> files;
	@CommandLine.Mixin
	private DumpFileImportOptions options = new DumpFileImportOptions();
	@ArgGroup(exclusive = false, heading = "Writer options%n")
	private RedisWriterOptions writerOptions = new RedisWriterOptions();

	@Override
	protected Flow flow(JobFactory jobFactory) throws Exception {
		Assert.isTrue(!ObjectUtils.isEmpty(files), "No file specified");
		List<String> expandedFiles = FileUtils.expand(files);
		if (ObjectUtils.isEmpty(expandedFiles)) {
			throw new FileNotFoundException("File not found: " + String.join(", ", files));
		}
		List<Step> steps = new ArrayList<>();
		DataStructureItemProcessor processor = new DataStructureItemProcessor();
		for (String file : expandedFiles) {
			DumpFileType fileType = fileType(file);
			Resource resource = options.inputResource(file);
			AbstractItemStreamItemReader<DataStructure<String>> reader = reader(fileType, resource);
			reader.setName(file + "-reader");
			StepBuilder stepBuilder = jobFactory.step(file + "-dump-file-import-step");
			RiotStepBuilder<DataStructure<String>, DataStructure<String>> step = riotStep(stepBuilder,
					"Importing " + file);
			steps.add(step.reader(reader).processor(processor).writer(writer()).build().build());
		}
		return flow("dump-file-import-flow", steps.toArray(new Step[0]));
	}

	private DumpFileType fileType(String file) {
		if (options.getType() == null) {
			return DumpFileType.of(file);
		}
		return options.getType();
	}

	private ItemWriter<DataStructure<String>> writer() {
		return writerOptions.configure(RedisItemWriter.dataStructure(getRedisOptions().client())
				.poolConfig(poolConfig(writerOptions.getPoolMax()))).build();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected AbstractItemStreamItemReader<DataStructure<String>> reader(DumpFileType fileType, Resource resource) {
		if (fileType == DumpFileType.XML) {
			log.debug("Creating XML data structure reader for file {}", resource);
			return (XmlItemReader) FileUtils.xmlReader(resource, DataStructure.class);
		}
		log.debug("Creating JSON data structure reader for file {}", resource);
		return (JsonItemReader) FileUtils.jsonReader(resource, DataStructure.class);
	}

}
