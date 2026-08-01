package com.fauxbingo.services.data;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * The `actor` block on a v1 event envelope (docs/v1-api.md, actor). accountHash is a string on
 * the wire, never a JSON number, since it can exceed Number.MAX_SAFE_INTEGER.
 */
@Data
@Builder
public class ActorDto
{
	private String accountHash;
	private String displayName;
	private String accountType;
	private int world;
	private List<String> worldTypes;
}
