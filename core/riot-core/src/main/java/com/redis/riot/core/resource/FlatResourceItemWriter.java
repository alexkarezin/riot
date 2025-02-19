/*
 * Copyright 2006-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redis.riot.core.resource;

import org.springframework.batch.item.file.transform.LineAggregator;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import java.util.List;

/**
 * This class is an item writer that writes data to a file or stream. The writer
 * also provides restart. The location of the output file is defined by a
 * {@link Resource} and must represent a writable file.<br>
 * 
 * Uses buffered writer to improve performance.<br>
 * 
 * The implementation is <b>not</b> thread-safe.
 * 
 * @author Waseem Malik
 * @author Tomas Slanina
 * @author Robert Kasanicky
 * @author Dave Syer
 * @author Michael Minella
 * @author Mahmoud Ben Hassine
 */
public class FlatResourceItemWriter<T> extends AbstractResourceItemWriter<T> {

	protected LineAggregator<T> lineAggregator;

	public FlatResourceItemWriter() {
		this.setExecutionContextName(ClassUtils.getShortName(FlatResourceItemWriter.class));
	}

	/**
	 * Assert that mandatory properties (lineAggregator) are set.
	 * 
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	@Override
	public void afterPropertiesSet() throws Exception {
		Assert.notNull(lineAggregator, "A LineAggregator must be provided.");
		if (append) {
			shouldDeleteIfExists = false;
		}
	}

	/**
	 * Public setter for the {@link LineAggregator}. This will be used to translate
	 * the item into a line for output.
	 * 
	 * @param lineAggregator the {@link LineAggregator} to set
	 */
	public void setLineAggregator(LineAggregator<T> lineAggregator) {
		this.lineAggregator = lineAggregator;
	}

	@Override
	public String doWrite(List<? extends T> items) {
		StringBuilder lines = new StringBuilder();
		for (T item : items) {
			lines.append(this.lineAggregator.aggregate(item)).append(this.lineSeparator);
		}
		return lines.toString();
	}

}
