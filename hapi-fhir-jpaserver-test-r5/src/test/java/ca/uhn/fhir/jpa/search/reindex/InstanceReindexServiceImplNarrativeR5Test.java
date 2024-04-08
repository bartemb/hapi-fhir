package ca.uhn.fhir.jpa.search.reindex;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.BaseResourceIndex;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedComboStringUnique;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedComboTokenNonUnique;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamNumber;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamQuantity;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamQuantityNormalized;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamString;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamToken;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamUri;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.entity.SearchParamPresentEntity;
import ca.uhn.fhir.jpa.model.search.hash.ResourceIndexHasher;
import ca.uhn.fhir.jpa.searchparam.extractor.ResourceIndexedSearchParams;
import ca.uhn.fhir.test.utilities.HtmlUtil;
import jakarta.annotation.Nonnull;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the narrative generation in {@link InstanceReindexServiceImpl}. This is a separate test
 * from {@literal ReindexDryRunServiceImplTest} because this test doesn't need the JPA
 * infrastructure.
 */
@SuppressWarnings({"unchecked", "SqlDialectInspection"})
public class InstanceReindexServiceImplNarrativeR5Test {
	private static final Logger ourLog = LoggerFactory.getLogger(InstanceReindexServiceImplNarrativeR5Test.class);
	private final FhirContext myCtx = FhirContext.forR4Cached();
	private final InstanceReindexServiceImpl mySvc = new InstanceReindexServiceImpl();
	private final PartitionSettings myPartitionSettings = new PartitionSettings();
	private final JpaStorageSettings myStorageSettings = new JpaStorageSettings();
	private final ResourceTable myEntity = new ResourceTable();

	@Test
	public void testIndexComboNonUnique() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		ResourceIndexedComboTokenNonUnique param = new ResourceIndexedComboTokenNonUnique(myEntity, "Patient?identifier=123");
		calculateHashes(param);
		newParams.myComboTokenNonUnique.add(param);

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("NonUniqueIndexesTable");
		assertEquals("ADD", getBodyCellValue(table, 0, 0));
		assertEquals("ComboTokenNonUnique", getBodyCellValue(table, 0, 1));
		assertEquals("Patient?identifier=123", getBodyCellValue(table, 0, 2));
	}

	@Test
	public void testIndexComboUnique() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		newParams.myComboStringUniques.add(new ResourceIndexedComboStringUnique(myEntity, "Patient?identifier=123", new IdType("Parameter/foo")));

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("UniqueIndexesTable");
		assertEquals("ADD", getBodyCellValue(table, 0, 0));
		assertEquals("ComboStringUnique", getBodyCellValue(table, 0, 1));
		assertEquals("Patient?identifier=123", getBodyCellValue(table, 0, 2));
	}

	@Test
	public void testIndexMissing() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		newParams.myTokenParams.add(new ResourceIndexedSearchParamToken("Observation", "identifier", true));
		SearchParamPresentEntity subject = new SearchParamPresentEntity("subject", false);
		subject.setResource(new ResourceTable());
		calculateHashes(subject);
		newParams.mySearchParamPresentEntities.add(subject);

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("MissingIndexesTable");
		assertEquals("identifier", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("Token", getBodyCellValue(table, 0, 2));
		assertEquals("true", getBodyCellValue(table, 0, 3));
		assertEquals("subject", getBodyCellValue(table, 1, 0));
		assertEquals("ADD", getBodyCellValue(table, 1, 1));
		assertEquals("Reference", getBodyCellValue(table, 1, 2));
		assertEquals("true", getBodyCellValue(table, 1, 3));
	}

	@Test
	public void testIndexNumber() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		ResourceIndexedSearchParamNumber param = new ResourceIndexedSearchParamNumber("Immunization", "dose", BigDecimal.ONE);
		calculateHashes(param);
		newParams.myNumberParams.add(param);

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("NumberIndexesTable");
		assertEquals("dose", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("Number", getBodyCellValue(table, 0, 2));
		assertEquals("1", getBodyCellValue(table, 0, 3));
	}

	@Test
	public void testIndexResourceLink() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		newParams.myLinks.add(ResourceLink.forLocalReference("Observation.subject", myEntity, "Patient", 123L, "123", new Date(), 555L));

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("ResourceLinksTable");
		assertEquals("Observation.subject", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("Reference", getBodyCellValue(table, 0, 2));
		assertEquals("Patient/123", getBodyCellValue(table, 0, 3));
		assertEquals("555", getBodyCellValue(table, 0, 4));
	}

	@Test
	public void testIndexResourceLinkLogical() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		newParams.myLinks.add(ResourceLink.forLogicalReference("Observation.subject", myEntity, "http://foo/base/Patient/456", new Date()));

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("ResourceLinksTable");
		assertEquals("Observation.subject", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("Reference", getBodyCellValue(table, 0, 2));
		assertEquals("", getBodyCellValue(table, 0, 3));
		assertEquals("", getBodyCellValue(table, 0, 4));
		assertEquals("http://foo/base/Patient/456", getBodyCellValue(table, 0, 5));
	}

	@Test
	public void testIndexResourceLinkAbsolute() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		newParams.myLinks.add(ResourceLink.forAbsoluteReference("Observation.subject", myEntity, new IdType("http://foo/base/Patient/123"), new Date()));

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("ResourceLinksTable");
		assertEquals("Observation.subject", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("Reference", getBodyCellValue(table, 0, 2));
		assertEquals("", getBodyCellValue(table, 0, 3));
		assertEquals("", getBodyCellValue(table, 0, 4));
		assertEquals("http://foo/base/Patient/123", getBodyCellValue(table, 0, 5));
	}

	@Test
	public void testIndexQuantity() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		ResourceIndexedSearchParamQuantity param = new ResourceIndexedSearchParamQuantity("Observation", "value-quantity", BigDecimal.valueOf(123), "http://unitsofmeasure.org", "kg");
		calculateHashes(param);
		newParams.myQuantityParams.add(param);

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("QuantityIndexesTable");
		assertEquals("value-quantity", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("Quantity", getBodyCellValue(table, 0, 2));
		assertEquals("123", getBodyCellValue(table, 0, 3));
		assertEquals("http://unitsofmeasure.org", getBodyCellValue(table, 0, 4));
		assertEquals("kg", getBodyCellValue(table, 0, 5));
	}

	@Test
	public void testIndexQuantityNormalized() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		ResourceIndexedSearchParamQuantityNormalized param = new ResourceIndexedSearchParamQuantityNormalized("Observation", "value-quantity", 123.0, "http://unitsofmeasure.org", "kg");
		calculateHashes(param);
		newParams.myQuantityNormalizedParams.add(param);

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("QuantityIndexesTable");
		assertEquals("value-quantity", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("QuantityNormalized", getBodyCellValue(table, 0, 2));
		assertEquals("123.0", getBodyCellValue(table, 0, 3));
		assertEquals("http://unitsofmeasure.org", getBodyCellValue(table, 0, 4));
		assertEquals("kg", getBodyCellValue(table, 0, 5));
	}

	@Test
	public void testIndexString() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		ResourceIndexedSearchParamString param = new ResourceIndexedSearchParamString("Patient", "family", "Simpson", "SIMPSON");
		calculateHashes(param);
		newParams.myStringParams.add(param);

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("StringIndexesTable");
		assertEquals("family", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("String", getBodyCellValue(table, 0, 2));
		assertEquals("Simpson", getBodyCellValue(table, 0, 3));
		assertEquals("SIMPSON", getBodyCellValue(table, 0, 4));
	}

	@Test
	public void testIndexToken() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		ResourceIndexedSearchParamToken param = new ResourceIndexedSearchParamToken("Observation", "identifier", "http://id-system", "id-value");
		calculateHashes(param);
		newParams.myTokenParams.add(param);

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("TokenIndexesTable");
		assertEquals("identifier", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("Token", getBodyCellValue(table, 0, 2));
		assertEquals("http://id-system", getBodyCellValue(table, 0, 3));
		assertEquals("id-value", getBodyCellValue(table, 0, 4));
	}

	@Test
	public void testIndexUrl() throws IOException {
		// Setup
		ResourceIndexedSearchParams newParams = newParams();
		ResourceIndexedSearchParamUri param = new ResourceIndexedSearchParamUri("CodeSystem", "uri", "http://some-codesystem");
		calculateHashes(param);
		newParams.myUriParams.add(param);

		// Test
		Parameters outcome = mySvc.buildIndexResponse(newParams(), newParams, true, Collections.emptyList());
		ourLog.info("Output:\n{}", myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));

		// Verify
		HtmlPage narrativeHtml = extractNarrative(outcome);
		HtmlTable table = (HtmlTable) narrativeHtml.getElementById("UriIndexesTable");
		assertEquals("uri", getBodyCellValue(table, 0, 0));
		assertEquals("ADD", getBodyCellValue(table, 0, 1));
		assertEquals("Uri", getBodyCellValue(table, 0, 2));
		assertEquals("http://some-codesystem", getBodyCellValue(table, 0, 3));
	}

	private void calculateHashes(SearchParamPresentEntity theParam) {
		theParam.calculateHashes(new ResourceIndexHasher(myPartitionSettings, myStorageSettings));
	}

	private void calculateHashes(BaseResourceIndex... theParams) {
		Arrays.stream(theParams).forEach(param -> param.calculateHashes(new ResourceIndexHasher(myPartitionSettings, myStorageSettings)));
	}

	@Nonnull
	private static HtmlPage extractNarrative(Parameters outcome) throws IOException {
		StringType narrative = (StringType) outcome.getParameter().get(0).getValue();
		HtmlPage narrativeHtml = HtmlUtil.parseAsHtml(narrative.getValueAsString());
		ourLog.info("Narrative:\n{}", narrativeHtml.asXml());
		return narrativeHtml;
	}

	private static String getBodyCellValue(HtmlTable table, int theRow, int theCol) {
		return table.getBodies().get(0).getRows().get(theRow).getCell(theCol).asNormalizedText();
	}

	@Nonnull
	private static ResourceIndexedSearchParams newParams() {
		return ResourceIndexedSearchParams.withSets();
	}

}
