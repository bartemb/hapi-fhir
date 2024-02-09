package ca.uhn.fhir.jpa.dao.dstu3;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValueSetExpansionOptions;
import ca.uhn.fhir.jpa.entity.TermValueSet;
import ca.uhn.fhir.jpa.entity.TermValueSetPreExpansionStatusEnum;
import ca.uhn.fhir.jpa.test.BaseJpaDstu3Test;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.dstu3.model.CodeSystem;
import org.hl7.fhir.dstu3.model.CodeType;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.StringType;
import org.hl7.fhir.dstu3.model.UriType;
import org.hl7.fhir.dstu3.model.ValueSet;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;


public class FhirResourceDaoDstu3ValueSetTest extends BaseJpaDstu3Test {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(FhirResourceDaoDstu3ValueSetTest.class);

	private IIdType myExtensionalVsId;
	@Autowired
	private IValidationSupport myValidationSupport;

	@BeforeEach
	@Transactional
	public void before02() throws IOException {
		ValueSet upload = loadResourceFromClasspath(ValueSet.class, "/extensional-case-3-vs.xml");
		myExtensionalVsId = myValueSetDao.create(upload, mySrd).getId().toUnqualifiedVersionless();

		CodeSystem upload2 = loadResourceFromClasspath(CodeSystem.class, "/extensional-case-3-cs.xml");
		myCodeSystemDao.create(upload2, mySrd).getId().toUnqualifiedVersionless();

	}

	@Test
	public void testExpandValueSetWithIso3166() throws IOException {
		ValueSet vs = loadResourceFromClasspath(ValueSet.class, "/dstu3/nl/LandISOCodelijst-2.16.840.1.113883.2.4.3.11.60.40.2.20.5.2--20171231000000.json");
		myValueSetDao.create(vs);

		runInTransaction(() -> {
			TermValueSet vsEntity = myTermValueSetDao.findByUrl("http://decor.nictiz.nl/fhir/ValueSet/2.16.840.1.113883.2.4.3.11.60.40.2.20.5.2--20171231000000").orElseThrow(() -> new IllegalStateException());
			assertThat(TermValueSetPreExpansionStatusEnum.NOT_EXPANDED).isEqualTo(vsEntity.getExpansionStatus());
		});

		IValidationSupport.CodeValidationResult validationOutcome;
		UriType vsIdentifier = new UriType("http://decor.nictiz.nl/fhir/ValueSet/2.16.840.1.113883.2.4.3.11.60.40.2.20.5.2--20171231000000");
		CodeType code = new CodeType();
		CodeType system = new CodeType("urn:iso:std:iso:3166");

		// Validate good
		code.setValue("NL");
		validationOutcome = myValueSetDao.validateCode(vsIdentifier, null, code, system, null, null, null, mySrd);
		assertThat(validationOutcome.isOk()).isEqualTo(true);

		// Validate bad
		code.setValue("QQ");
		validationOutcome = myValueSetDao.validateCode(vsIdentifier, null, code, system, null, null, null, mySrd);
		assertThat(validationOutcome.isOk()).isEqualTo(false);

		await().until(() -> clearDeferredStorageQueue());
		myTermSvc.preExpandDeferredValueSetsToTerminologyTables();

		runInTransaction(() -> {
			TermValueSet vsEntity = myTermValueSetDao.findByUrl("http://decor.nictiz.nl/fhir/ValueSet/2.16.840.1.113883.2.4.3.11.60.40.2.20.5.2--20171231000000").orElseThrow(() -> new IllegalStateException());
			assertThat(TermValueSetPreExpansionStatusEnum.EXPANDED).isEqualTo(vsEntity.getExpansionStatus());
		});

		// Validate good
		code.setValue("NL");
		validationOutcome = myValueSetDao.validateCode(vsIdentifier, null, code, system, null, null, null, mySrd);
		assertThat(validationOutcome.isOk()).isEqualTo(true);

		// Validate bad
		code.setValue("QQ");
		validationOutcome = myValueSetDao.validateCode(vsIdentifier, null, code, system, null, null, null, mySrd);
		assertThat(validationOutcome.isOk()).isEqualTo(false);

	}

	private boolean clearDeferredStorageQueue() {

		if (!myTerminologyDeferredStorageSvc.isStorageQueueEmpty(true)) {
			myTerminologyDeferredStorageSvc.saveAllDeferred();
			return false;
		} else {
			return true;
		}

	}

	@Test
	@Disabled
	public void testBuiltInValueSetFetchAndExpand() {

		try {
			myValueSetDao.read(new IdType("ValueSet/endpoint-payload-type"));
			fail("");
		} catch (ResourceNotFoundException e) {
			// good
		}

		ValueSet vs = myValidationSupport.fetchResource(ValueSet.class, "http://hl7.org/fhir/ValueSet/endpoint-payload-type");
		myValueSetDao.update(vs);

		vs = myValueSetDao.read(new IdType("ValueSet/endpoint-payload-type"));
		assertThat(vs).isNotNull();
		assertThat(vs.getUrl()).isEqualTo("http://hl7.org/fhir/ValueSet/endpoint-payload-type");

		ValueSet expansion = myValueSetDao.expand(vs.getIdElement(), null, mySrd);
		ourLog.debug(myFhirContext.newXmlParser().setPrettyPrint(true).encodeResourceToString(expansion));
	}

	@Test
	public void testExpandById() {
		String resp;

		ValueSet expanded = myValueSetDao.expand(myExtensionalVsId, null, mySrd);
		resp = myFhirContext.newXmlParser().setPrettyPrint(true).encodeResourceToString(expanded);
		ourLog.info(resp);
		assertThat(resp).contains("<ValueSet xmlns=\"http://hl7.org/fhir\">");
		assertThat(resp).contains("<expansion>");
		assertThat(resp).contains("<contains>");
		assertThat(resp).contains("<system value=\"http://acme.org\"/>");
		assertThat(resp).contains("<code value=\"8450-9\"/>");
		assertThat(resp).contains("<display value=\"Systolic blood pressure--expiration\"/>");
		assertThat(resp).contains("</contains>");
		assertThat(resp).contains("<contains>");
		assertThat(resp).contains("<system value=\"http://acme.org\"/>");
		assertThat(resp).contains("<code value=\"11378-7\"/>");
		assertThat(resp).contains("<display value=\"Systolic blood pressure at First encounter\"/>");
		assertThat(resp).contains("</contains>");
		assertThat(resp).contains("</expansion>");

		/*
		 * Filter with display name
		 */

		expanded = myValueSetDao.expand(myExtensionalVsId, new ValueSetExpansionOptions().setFilter("systolic"), mySrd);
		resp = myFhirContext.newXmlParser().setPrettyPrint(true).encodeResourceToString(expanded);
		ourLog.info(resp);
		//@formatter:off
		assertThat(resp).containsSequence(
			"<code value=\"11378-7\"/>",
			"<display value=\"Systolic blood pressure at First encounter\"/>");
		//@formatter:on

	}

	@Test
	@Disabled
	public void testExpandByIdentifier() {
		ValueSet expanded = myValueSetDao.expandByIdentifier("http://www.healthintersections.com.au/fhir/ValueSet/extensional-case-2", new ValueSetExpansionOptions().setFilter("11378"));
		String resp = myFhirContext.newXmlParser().setPrettyPrint(true).encodeResourceToString(expanded);
		ourLog.info(resp);
		//@formatter:off
		assertThat(resp).containsSequence()
			"<code value=\"11378-7\"/>",
			"<display value=\"Systolic blood pressure at First encounter\"/>"));
		//@formatter:on

		assertThat(resp).doesNotContain("<code value=\"8450-9\"/>");
	}

	/**
	 * This type of expansion doesn't really make sense..
	 */
	@Test
	@Disabled
	public void testExpandByValueSet() throws IOException {
		ValueSet toExpand = loadResourceFromClasspath(ValueSet.class, "/extensional-case-3-vs.xml");
		ValueSet expanded = myValueSetDao.expand(toExpand, new ValueSetExpansionOptions().setFilter("11378"));
		String resp = myFhirContext.newXmlParser().setPrettyPrint(true).encodeResourceToString(expanded);
		ourLog.info(resp);
		//@formatter:off
		assertThat(resp, stringContainsInOrder(
			"<code value=\"11378-7\"/>",
			"<display value=\"Systolic blood pressure at First encounter\"/>"));
		//@formatter:on

		assertThat(resp).doesNotContain("<code value=\"8450-9\"/>");
	}

	@Test
	public void testValidateCodeOperationByIdentifierAndCodeAndSystem() {
		UriType valueSetIdentifier = new UriType("http://www.healthintersections.com.au/fhir/ValueSet/extensional-case-2");
		IdType id = null;
		CodeType code = new CodeType("11378-7");
		UriType system = new UriType("http://acme.org");
		StringType display = null;
		Coding coding = null;
		CodeableConcept codeableConcept = null;
		IValidationSupport.CodeValidationResult result = myValueSetDao.validateCode(valueSetIdentifier, id, code, system, display, coding, codeableConcept, mySrd);
		assertThat(result.isOk()).isTrue();
		assertThat(result.getDisplay()).isEqualTo("Systolic blood pressure at First encounter");
	}

	@Test
	public void testValidateCodeOperationByIdentifierAndCodeAndSystemAndBadDisplay() {
		UriType valueSetIdentifier = new UriType("http://www.healthintersections.com.au/fhir/ValueSet/extensional-case-2");
		IdType id = null;
		CodeType code = new CodeType("11378-7");
		UriType system = new UriType("http://acme.org");
		StringType display = new StringType("Systolic blood pressure at First encounterXXXX");
		Coding coding = null;
		CodeableConcept codeableConcept = null;
		IValidationSupport.CodeValidationResult result = myValueSetDao.validateCode(valueSetIdentifier, id, code, system, display, coding, codeableConcept, mySrd);
		assertThat(result.isOk()).isTrue();
		assertThat(result.getDisplay()).isEqualTo("Systolic blood pressure at First encounter");
	}

	@Test
	public void testValidateCodeOperationByIdentifierAndCodeAndSystemAndGoodDisplay() {
		UriType valueSetIdentifier = new UriType("http://www.healthintersections.com.au/fhir/ValueSet/extensional-case-2");
		IdType id = null;
		CodeType code = new CodeType("11378-7");
		UriType system = new UriType("http://acme.org");
		StringType display = new StringType("Systolic blood pressure at First encounter");
		Coding coding = null;
		CodeableConcept codeableConcept = null;
		IValidationSupport.CodeValidationResult result = myValueSetDao.validateCode(valueSetIdentifier, id, code, system, display, coding, codeableConcept, mySrd);
		assertThat(result.isOk()).isTrue();
		assertThat(result.getDisplay()).isEqualTo("Systolic blood pressure at First encounter");
	}

	@Test
	public void testValidateCodeOperationByResourceIdAndCodeAndSystem() {
		UriType valueSetIdentifier = null;
		IIdType id = myExtensionalVsId;
		CodeType code = new CodeType("11378-7");
		UriType system = new UriType("http://acme.org");
		StringType display = null;
		Coding coding = null;
		CodeableConcept codeableConcept = null;
		IValidationSupport.CodeValidationResult result = myValueSetDao.validateCode(valueSetIdentifier, id, code, system, display, coding, codeableConcept, mySrd);
		assertThat(result.isOk()).isTrue();
		assertThat(result.getDisplay()).isEqualTo("Systolic blood pressure at First encounter");
	}

	@Test
	public void testValidateCodeOperationByResourceIdAndCodeableConcept() {
		UriType valueSetIdentifier = null;
		IIdType id = myExtensionalVsId;
		CodeType code = null;
		UriType system = null;
		StringType display = null;
		Coding coding = null;
		CodeableConcept codeableConcept = new CodeableConcept();
		codeableConcept.addCoding().setSystem("http://acme.org").setCode("11378-7");
		IValidationSupport.CodeValidationResult result = myValueSetDao.validateCode(valueSetIdentifier, id, code, system, display, coding, codeableConcept, mySrd);
		assertThat(result.isOk()).isTrue();
		assertThat(result.getDisplay()).isEqualTo("Systolic blood pressure at First encounter");
	}

	@Test
	public void testValidateCodeAgainstBuiltInValueSetAndCodeSystemWithValidCode() {
		IPrimitiveType<String> display = null;
		Coding coding = null;
		CodeableConcept codeableConcept = null;
		StringType vsIdentifier = new StringType("http://hl7.org/fhir/ValueSet/administrative-gender");
		StringType code = new StringType("male");
		StringType system = new StringType("http://hl7.org/fhir/administrative-gender");
		IValidationSupport.CodeValidationResult result = myValueSetDao.validateCode(vsIdentifier, null, code, system, display, coding, codeableConcept, mySrd);

		ourLog.info(result.getMessage());
		assertThat(result.isOk()).as(result.getMessage()).isTrue();
	}


}

