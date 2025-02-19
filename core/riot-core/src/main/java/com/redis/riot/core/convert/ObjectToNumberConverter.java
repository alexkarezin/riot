package com.redis.riot.core.convert;

import java.util.function.Function;

import org.springframework.util.NumberUtils;

public class ObjectToNumberConverter<T extends Number> implements Function<Object, T> {

	private final Class<T> targetType;

	public ObjectToNumberConverter(Class<T> targetType) {
		this.targetType = targetType;
	}

	@Override
	public T apply(Object source) {
		if (source == null) {
			return null;
		}
		if (source instanceof Number) {
			return NumberUtils.convertNumberToTargetClass((Number) source, targetType);
		}
		if (source instanceof String) {
			String string = (String) source;
			if (string.isEmpty()) {
				return null;
			}
			return NumberUtils.parseNumber(string, targetType);
		}
		return null;
	}

}
