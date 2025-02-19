package com.redis.riot.core.convert;

import java.util.function.Function;

public class ObjectToStringConverter implements Function<Object, String> {

	@Override
	public String apply(Object source) {
		if (source == null) {
			return null;
		}
		return source.toString();
	}

}
