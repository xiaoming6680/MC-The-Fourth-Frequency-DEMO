package com.xm.thefourthfrequency.pursuit;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PursuitFormPolicyTest {
	@Test
	void threeFormsHaveDistinctMechanicsAndIncreasingSurvivalWindows() {
		Set<String> ids = new HashSet<>();
		Set<String> counterplay = new HashSet<>();
		int previousDuration = 0;
		for (int form = 1; form <= 3; form++) {
			var policy = PursuitFormPolicy.forForm(form);
			assertEquals(form, policy.number());
			assertTrue(ids.add(policy.id()));
			assertTrue(counterplay.add(policy.counterplay()));
			assertTrue(policy.durationTicks() > previousDuration);
			previousDuration = policy.durationTicks();
		}
	}

	@Test
	void invalidFormInputsClampToTheSupportedRange() {
		assertEquals(1, PursuitFormPolicy.forForm(Integer.MIN_VALUE).number());
		assertEquals(3, PursuitFormPolicy.forForm(Integer.MAX_VALUE).number());
	}
}
