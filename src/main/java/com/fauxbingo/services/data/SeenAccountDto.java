package com.fauxbingo.services.data;

import lombok.Data;

@Data
public class SeenAccountDto
{
	private String accountHash;
	private String displayName;
	private String accountType;
	private String firstSeenAt;
	private String lastSeenAt;
}
