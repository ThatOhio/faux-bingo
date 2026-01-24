package com.fauxbingo.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class LootMatcher
{
	public static boolean matches(String itemName, String configItem)
	{
		if (itemName == null || configItem == null)
		{
			return false;
		}

		Set<String> itemCandidates = buildCandidates(itemName);
		Set<String> configCandidates = buildCandidates(configItem);

		for (String configCandidate : configCandidates)
		{
			if (configCandidate.contains("*"))
			{
				for (String itemCandidate : itemCandidates)
				{
					if (matchesWildcard(itemCandidate, configCandidate))
					{
						return true;
					}
				}
				continue;
			}

			for (String itemCandidate : itemCandidates)
			{
				if (matchesWithPluralRules(itemCandidate, configCandidate))
				{
					return true;
				}
			}
		}

		for (String configCandidate : configCandidates)
		{
			if (configCandidate.contains("*"))
			{
				continue;
			}
			for (String itemCandidate : itemCandidates)
			{
				if (isFuzzyMatch(itemCandidate, configCandidate))
				{
					return true;
				}
			}
		}

		return false;
	}

	public static boolean matchesAny(String itemName, List<String> configItems)
	{
		if (itemName == null || configItems == null)
		{
			return false;
		}

		for (String configItem : configItems)
		{
			if (matches(itemName, configItem))
			{
				return true;
			}
		}
		return false;
	}

	private static Set<String> buildCandidates(String input)
	{
		Set<String> candidates = new HashSet<>();
		String normalized = normalize(input);
		candidates.add(normalized);
		candidates.add(normalizePossessive(normalized));
		return candidates;
	}

	private static String normalize(String input)
	{
		return input == null ? "" : input.toLowerCase().trim();
	}

	private static String normalizePossessive(String input)
	{
		String withoutPluralPossessive = input.replaceAll("s'(?=\\s|$)", "s");
		return withoutPluralPossessive.replaceAll("'s(?=\\s|$)", "");
	}

	private static boolean matchesWithPluralRules(String item, String config)
	{
		if (item.equals(config))
		{
			return true;
		}

		String configPlural = pluralize(config);
		if (!configPlural.isEmpty() && item.equals(configPlural))
		{
			return true;
		}

		String itemPlural = pluralize(item);
		return !itemPlural.isEmpty() && config.equals(itemPlural);
	}

	private static String pluralize(String input)
	{
		if (input.isEmpty())
		{
			return input;
		}

		int lastSpace = input.lastIndexOf(' ');
		String prefix = lastSpace >= 0 ? input.substring(0, lastSpace + 1) : "";
		String word = lastSpace >= 0 ? input.substring(lastSpace + 1) : input;

		String pluralWord = pluralizeWord(word);
		return pluralWord.isEmpty() ? input : prefix + pluralWord;
	}

	private static String pluralizeWord(String word)
	{
		int length = word.length();
		if (length == 0)
		{
			return word;
		}

		if (word.endsWith("y") && length > 1 && !isVowel(word.charAt(length - 2)))
		{
			return word.substring(0, length - 1) + "ies";
		}

		if (word.endsWith("s") || word.endsWith("x") || word.endsWith("z") || word.endsWith("ch") || word.endsWith("sh"))
		{
			return word + "es";
		}

		if (word.endsWith("fe") && length > 2)
		{
			return word.substring(0, length - 2) + "ves";
		}

		if (word.endsWith("f") && length > 1)
		{
			return word.substring(0, length - 1) + "ves";
		}

		return word + "s";
	}

	private static boolean isVowel(char letter)
	{
		return letter == 'a' || letter == 'e' || letter == 'i' || letter == 'o' || letter == 'u';
	}

	private static boolean matchesWildcard(String input, String wildcard)
	{
		String regex = wildcardToRegex(wildcard);
		return Pattern.matches(regex, input);
	}

	private static String wildcardToRegex(String wildcard)
	{
		String[] parts = wildcard.split("\\*", -1);
		StringBuilder regex = new StringBuilder("^");
		for (int i = 0; i < parts.length; i++)
		{
			regex.append(Pattern.quote(parts[i]));
			if (i < parts.length - 1)
			{
				regex.append(".*");
			}
		}
		regex.append("$");
		return regex.toString();
	}

	private static boolean isFuzzyMatch(String item, String config)
	{
		if (item.length() < 4 || config.length() < 4)
		{
			return false;
		}
		return isEditDistanceAtMostOne(item, config);
	}

	private static boolean isEditDistanceAtMostOne(String left, String right)
	{
		int lengthDiff = Math.abs(left.length() - right.length());
		if (lengthDiff > 1)
		{
			return false;
		}

		int leftIndex = 0;
		int rightIndex = 0;
		int edits = 0;

		while (leftIndex < left.length() && rightIndex < right.length())
		{
			if (left.charAt(leftIndex) == right.charAt(rightIndex))
			{
				leftIndex++;
				rightIndex++;
				continue;
			}

			edits++;
			if (edits > 1)
			{
				return false;
			}

			if (left.length() > right.length())
			{
				leftIndex++;
			}
			else if (right.length() > left.length())
			{
				rightIndex++;
			}
			else
			{
				leftIndex++;
				rightIndex++;
			}
		}

		if (leftIndex < left.length() || rightIndex < right.length())
		{
			edits++;
		}

		return edits <= 1;
	}
}
