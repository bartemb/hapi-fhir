package ca.uhn.fhir.mdm.rules.matcher;

import ca.uhn.fhir.context.phonetic.PhoneticEncoderEnum;
import ca.uhn.fhir.jpa.searchparam.matcher.IMdmFieldMatcher;
import ca.uhn.fhir.mdm.rules.matcher.fieldmatchers.HapiStringMatcher;
import ca.uhn.fhir.mdm.rules.matcher.fieldmatchers.NumericMatcher;
import ca.uhn.fhir.mdm.rules.matcher.fieldmatchers.PhoneticEncoderMatcher;
import ca.uhn.fhir.mdm.rules.matcher.fieldmatchers.SubstringStringMatcher;
import ca.uhn.fhir.mdm.rules.matcher.models.MatchTypeEnum;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumeration;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class StringMatcherR4Test extends BaseMatcherR4Test {
	private static final Logger ourLog = LoggerFactory.getLogger(StringMatcherR4Test.class);
	public static final String LEFT_NAME = "namadega";
	public static final String RIGHT_NAME = "namaedga";

	private @Nonnull IMdmFieldMatcher getFieldMatcher(MatchTypeEnum theMatchTypeEnum) {
		switch (theMatchTypeEnum) {
			case COLOGNE:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.COLOGNE);
			case DOUBLE_METAPHONE:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.DOUBLE_METAPHONE);
			case MATCH_RATING_APPROACH:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.MATCH_RATING_APPROACH);
			case METAPHONE:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.METAPHONE);
			case SOUNDEX:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.SOUNDEX);
			case CAVERPHONE1:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.CAVERPHONE1);
			case CAVERPHONE2:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.CAVERPHONE2);
			case NYSIIS:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.NYSIIS);
			case REFINED_SOUNDEX:
				return new PhoneticEncoderMatcher(PhoneticEncoderEnum.REFINED_SOUNDEX);
			case STRING:
				return new HapiStringMatcher();
			case SUBSTRING:
				return new SubstringStringMatcher();
			case NUMERIC:
				return new NumericMatcher();
			default:
				fail("String matcher " + theMatchTypeEnum.name() + " does not exist");
		}

		// so we don't have null pointer warnings - we'll never hit this point
		return new HapiStringMatcher();
	}

	@Test
	public void testNamadega() {
		String left = LEFT_NAME;
		String right = RIGHT_NAME;
		assertTrue(match(MatchTypeEnum.COLOGNE, left, right));
		assertTrue(match(MatchTypeEnum.DOUBLE_METAPHONE, left, right));
		assertTrue(match(MatchTypeEnum.MATCH_RATING_APPROACH, left, right));
		assertTrue(match(MatchTypeEnum.METAPHONE, left, right));
		assertTrue(match(MatchTypeEnum.SOUNDEX, left, right));

		assertFalse(match(MatchTypeEnum.CAVERPHONE1, left, right));
		assertFalse(match(MatchTypeEnum.CAVERPHONE2, left, right));
		assertFalse(match(MatchTypeEnum.NYSIIS, left, right));
		assertFalse(match(MatchTypeEnum.REFINED_SOUNDEX, left, right));
		assertFalse(match(MatchTypeEnum.STRING, left, right));
		assertFalse(match(MatchTypeEnum.SUBSTRING, left, right));
	}

	@Test
	public void testNumeric() {
		assertTrue(match(MatchTypeEnum.NUMERIC, "4169671111", "(416) 967-1111"));
		assertFalse(match(MatchTypeEnum.NUMERIC, "5169671111", "(416) 967-1111"));
		assertFalse(match(MatchTypeEnum.NUMERIC, "4169671111", "(416) 967-1111x123"));
	}

	@Test
	public void testMetaphone() {
		assertTrue(match(MatchTypeEnum.METAPHONE, "Durie", "dury"));
		assertTrue(match(MatchTypeEnum.METAPHONE, "Balo", "ballo"));
		assertTrue(match(MatchTypeEnum.METAPHONE, "Hans Peter", "Hanspeter"));
		assertTrue(match(MatchTypeEnum.METAPHONE, "Lawson", "Law son"));

		assertFalse(match(MatchTypeEnum.METAPHONE, "Allsop", "Allsob"));
		assertFalse(match(MatchTypeEnum.METAPHONE, "Gevne", "Geve"));
		assertFalse(match(MatchTypeEnum.METAPHONE, "Bruce", "Bruch"));
		assertFalse(match(MatchTypeEnum.METAPHONE, "Smith", "Schmidt"));
		assertFalse(match(MatchTypeEnum.METAPHONE, "Jyothi", "Jyoti"));
	}

	@Test
	public void testDoubleMetaphone() {
		assertTrue(match(MatchTypeEnum.DOUBLE_METAPHONE, "Durie", "dury"));
		assertTrue(match(MatchTypeEnum.DOUBLE_METAPHONE, "Balo", "ballo"));
		assertTrue(match(MatchTypeEnum.DOUBLE_METAPHONE, "Hans Peter", "Hanspeter"));
		assertTrue(match(MatchTypeEnum.DOUBLE_METAPHONE, "Lawson", "Law son"));
		assertTrue(match(MatchTypeEnum.DOUBLE_METAPHONE, "Allsop", "Allsob"));

		assertFalse(match(MatchTypeEnum.DOUBLE_METAPHONE, "Gevne", "Geve"));
		assertFalse(match(MatchTypeEnum.DOUBLE_METAPHONE, "Bruce", "Bruch"));
		assertFalse(match(MatchTypeEnum.DOUBLE_METAPHONE, "Smith", "Schmidt"));
		assertFalse(match(MatchTypeEnum.DOUBLE_METAPHONE, "Jyothi", "Jyoti"));
	}

	@Test
	public void testNormalizeCase() {
		assertTrue(match(MatchTypeEnum.STRING, "joe", "JoE"));
		assertTrue(match(MatchTypeEnum.STRING, "MCTAVISH", "McTavish"));

		assertFalse(match(MatchTypeEnum.STRING, "joey", "joe"));
		assertFalse(match(MatchTypeEnum.STRING, "joe", "joey"));
	}

	@Test
	public void testExactString() {
		myExtraMatchParams.setExactMatch(true);

		assertTrue(getFieldMatcher(MatchTypeEnum.STRING).matches(new StringType("Jilly"), new StringType("Jilly"), myExtraMatchParams));

		assertFalse(getFieldMatcher(MatchTypeEnum.STRING).matches(new StringType("MCTAVISH"), new StringType("McTavish"), myExtraMatchParams));
		assertFalse(getFieldMatcher(MatchTypeEnum.STRING).matches(new StringType("Durie"), new StringType("dury"), myExtraMatchParams));
	}

	@Test
	public void testExactBoolean() {
		myExtraMatchParams.setExactMatch(true);
		assertTrue(getFieldMatcher(MatchTypeEnum.STRING).matches(new BooleanType(true), new BooleanType(true), myExtraMatchParams));

		assertFalse(getFieldMatcher(MatchTypeEnum.STRING).matches(new BooleanType(true), new BooleanType(false), myExtraMatchParams));
		assertFalse(getFieldMatcher(MatchTypeEnum.STRING).matches(new BooleanType(false), new BooleanType(true), myExtraMatchParams));
	}

	@Test
	public void testExactDateString() {
		myExtraMatchParams.setExactMatch(true);

		assertTrue(getFieldMatcher(MatchTypeEnum.STRING).matches(new DateType("1965-08-09"), new DateType("1965-08-09"), myExtraMatchParams));

		assertFalse(getFieldMatcher(MatchTypeEnum.STRING).matches(new DateType("1965-08-09"), new DateType("1965-09-08"), myExtraMatchParams));
	}


	@Test
	public void testExactGender() {
		Enumeration<Enumerations.AdministrativeGender> male = new Enumeration<Enumerations.AdministrativeGender>(new Enumerations.AdministrativeGenderEnumFactory());
		male.setValue(Enumerations.AdministrativeGender.MALE);

		Enumeration<Enumerations.AdministrativeGender> female = new Enumeration<Enumerations.AdministrativeGender>(new Enumerations.AdministrativeGenderEnumFactory());
		female.setValue(Enumerations.AdministrativeGender.FEMALE);

		myExtraMatchParams.setExactMatch(true);

		assertTrue(getFieldMatcher(MatchTypeEnum.STRING).matches(male, male, myExtraMatchParams));

		assertFalse(getFieldMatcher(MatchTypeEnum.STRING).matches(male, female, myExtraMatchParams));
	}

	@Test
	public void testSoundex() {
		assertTrue(match(MatchTypeEnum.SOUNDEX, "Gail", "Gale"));
		assertTrue(match(MatchTypeEnum.SOUNDEX, "John", "Jon"));
		assertTrue(match(MatchTypeEnum.SOUNDEX, "Thom", "Tom"));

		assertFalse(match(MatchTypeEnum.SOUNDEX, "Fred", "Frank"));
		assertFalse(match(MatchTypeEnum.SOUNDEX, "Thomas", "Tom"));
	}


	@Test
	public void testCaverphone1() {
		assertTrue(match(MatchTypeEnum.CAVERPHONE1, "Gail", "Gael"));
		assertTrue(match(MatchTypeEnum.CAVERPHONE1, "John", "Jon"));

		assertFalse(match(MatchTypeEnum.CAVERPHONE1, "Gail", "Gale"));
		assertFalse(match(MatchTypeEnum.CAVERPHONE1, "Fred", "Frank"));
		assertFalse(match(MatchTypeEnum.CAVERPHONE1, "Thomas", "Tom"));
	}

	@Test
	public void testCaverphone2() {
		assertTrue(match(MatchTypeEnum.CAVERPHONE2, "Gail", "Gael"));
		assertTrue(match(MatchTypeEnum.CAVERPHONE2, "John", "Jon"));
		assertTrue(match(MatchTypeEnum.CAVERPHONE2, "Gail", "Gale"));

		assertFalse(match(MatchTypeEnum.CAVERPHONE2, "Fred", "Frank"));
		assertFalse(match(MatchTypeEnum.CAVERPHONE2, "Thomas", "Tom"));
	}

	@Test
	public void testNormalizeSubstring() {
		assertTrue(match(MatchTypeEnum.SUBSTRING, "BILLY", "Bill"));
		assertTrue(match(MatchTypeEnum.SUBSTRING, "Bill", "Billy"));
		assertTrue(match(MatchTypeEnum.SUBSTRING, "FRED", "Frederik"));

		assertFalse(match(MatchTypeEnum.SUBSTRING, "Fred", "Friederik"));
	}

	private boolean match(MatchTypeEnum theMatcher, String theLeft, String theRight) {
		return getFieldMatcher(theMatcher)
			.matches(new StringType(theLeft), new StringType(theRight), myExtraMatchParams);
	}
}
