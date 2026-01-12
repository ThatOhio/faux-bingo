package com.fauxbingo;

import com.fauxbingo.handlers.EventHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Central event processing engine that manages and delegates events to registered handlers.
 */
@Slf4j
public class EventProcessor
{
	private final Map<Class<?>, List<EventHandler<?>>> handlers = new HashMap<>();

	public <T> void registerHandler(EventHandler<T> handler)
	{
		Class<T> eventType = handler.getEventType();
		handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);
		log.debug("Registered handler {} for event type {}", handler.getClass().getSimpleName(), eventType.getSimpleName());
	}

	public <T> void unregisterHandler(EventHandler<T> handler)
	{
		Class<T> eventType = handler.getEventType();
		List<EventHandler<?>> handlerList = handlers.get(eventType);
		if (handlerList != null)
		{
			handlerList.remove(handler);
			log.debug("Unregistered handler {} for event type {}", handler.getClass().getSimpleName(), eventType.getSimpleName());
		}
	}

	@SuppressWarnings("unchecked")
	public <T> void processEvent(T event)
	{
		List<EventHandler<?>> handlerList = handlers.get(event.getClass());
		if (handlerList != null && !handlerList.isEmpty())
		{
			for (EventHandler<?> handler : handlerList)
			{
				try
				{
					((EventHandler<T>) handler).handle(event);
				}
				catch (Exception e)
				{
					log.error("Error in handler {} processing event {}", 
						handler.getClass().getSimpleName(), 
						event.getClass().getSimpleName(), e);
				}
			}
		}
	}

	public void clearHandlers()
	{
		handlers.clear();
		log.debug("Cleared all event handlers");
	}
}
