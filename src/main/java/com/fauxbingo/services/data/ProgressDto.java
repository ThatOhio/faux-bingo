package com.fauxbingo.services.data;

import lombok.Data;

@Data
public class ProgressDto
{
	private int current;
	private int required;
	private boolean completed;
}
