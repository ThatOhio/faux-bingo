package com.fauxbingo.handlers;

public interface EventHandler<T>
{
	void handle(T event);

	Class<T> getEventType();
}
