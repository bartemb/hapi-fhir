package ca.uhn.fhir.jpa.dao.r4;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.jobs.expunge.DeleteExpungeAppCtx;
import ca.uhn.fhir.batch2.jobs.expunge.DeleteExpungeJobParameters;
import ca.uhn.fhir.batch2.jobs.parameters.PartitionedUrl;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.batch2.model.JobInstanceStartRequest;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.IAnonymousInterceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.batch.models.Batch2JobStartResponse;
import ca.uhn.fhir.jpa.dao.BaseHapiFhirDao;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.PartitionablePartitionId;
import ca.uhn.fhir.jpa.model.entity.ResourceHistoryTable;
import ca.uhn.fhir.jpa.model.entity.ResourceHistoryTag;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedComboStringUnique;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamDate;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamString;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamToken;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.entity.ResourceTag;
import ca.uhn.fhir.jpa.model.entity.SearchParamPresentEntity;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.util.SqlQuery;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.DateAndListParam;
import ca.uhn.fhir.rest.param.DateOrListParam;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.HasAndListParam;
import ca.uhn.fhir.rest.param.HasOrListParam;
import ca.uhn.fhir.rest.param.HasParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.TokenParamModifier;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.util.BundleBuilder;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.SearchParameter;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static ca.uhn.fhir.util.TestUtil.sleepAtLeast;
import static org.apache.commons.lang3.StringUtils.countMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"unchecked", "ConstantConditions"})

public class PartitioningSqlR4Test extends BasePartitioningR4Test {
	private static final Logger ourLog = LoggerFactory.getLogger(PartitioningSqlR4Test.class);

	@Autowired
	private IJobCoordinator myJobCoordinator;

	@BeforeEach
	public void disableAdvanceIndexing() {
		myStorageSettings.setAdvancedHSearchIndexing(false);
		// ugh - somewhere the hibernate round trip is mangling LocalDate to h2 date column unless the tz=GMT
		TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
		ourLog.info("Running with Timezone {}", TimeZone.getDefault().getID());
	}

	@BeforeEach
	public void beforeEach() {
		myStorageSettings.setMarkResourcesForReindexingUponSearchParameterChange(false);
	}

	@AfterEach
	public void afterEach() {
		JpaStorageSettings defaults = new JpaStorageSettings();
		myStorageSettings.setMarkResourcesForReindexingUponSearchParameterChange(defaults.isMarkResourcesForReindexingUponSearchParameterChange());
		myStorageSettings.setMatchUrlCacheEnabled(defaults.isMatchUrlCacheEnabled());
	}

	@Test
	public void testCreateSearchParameter_DefaultPartition() {
		addCreateDefaultPartition();
		addReadDefaultPartition(); // one for search param validation
		SearchParameter sp = new SearchParameter();
		sp.addBase("Patient");
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.setType(Enumerations.SearchParamType.REFERENCE);
		sp.setCode("extpatorg");
		sp.setName("extpatorg");
		sp.setExpression("Patient.extension('http://patext').value.as(Reference)");
		Long id = mySearchParameterDao.create(sp, mySrd).getId().getIdPartAsLong();

		runInTransaction(() -> {
			ResourceTable resourceTable = myResourceTableDao.findById(id).orElseThrow(IllegalArgumentException::new);
			assertNull(resourceTable.getPartitionId());
		});
	}

	@Test
	public void testCreate_CrossPartitionReference_ByPid_Allowed() {
		myPartitionSettings.setAllowReferencesAcrossPartitions(PartitionSettings.CrossPartitionReferenceMode.ALLOWED_UNQUALIFIED);
		myStorageSettings.setIndexMissingFields(JpaStorageSettings.IndexEnabledEnum.DISABLED);

		// Create patient in partition 1
		addCreatePartition(myPartitionId, myPartitionDate);
		Patient patient = new Patient();
		patient.setActive(true);
		IIdType patientId = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();

		// Create observation in partition 2
		addCreatePartition(myPartitionId2, myPartitionDate2);
		Observation obs = new Observation();
		obs.getSubject().setReference(patientId.getValue());

		myCaptureQueriesListener.clear();
		IIdType obsId = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

		List<SqlQuery> selectQueries = myCaptureQueriesListener.getSelectQueriesForCurrentThread();
		assertEquals(1, selectQueries.size());
		// Look up the referenced subject/patient
		String sql = selectQueries.get(0).getSql(true, false).toLowerCase();
		assertThat(sql).contains(" from hfj_resource ");
		assertEquals(2, StringUtils.countMatches(selectQueries.get(0).getSql(true, false).toLowerCase(), "partition"));

		runInTransaction(() -> {
			List<ResourceLink> resLinks = myResourceLinkDao.findAll();
			ourLog.info("Resource links:\n{}", resLinks);
			assertEquals(2, resLinks.size());
			assertEquals(obsId.getIdPartAsLong(), resLinks.get(0).getSourceResourcePid());
			assertEquals(patientId.getIdPartAsLong(), resLinks.get(0).getTargetResourcePid());
			assertEquals(myPartitionId, resLinks.get(0).getTargetResourcePartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, resLinks.get(0).getTargetResourcePartitionId().getPartitionDate());
		});
	}

	@Test
	public void testCreate_CrossPartitionReference_ByPid_NotAllowed() {

		// Create patient in partition 1
		addCreatePartition(myPartitionId, myPartitionDate);
		Patient patient = new Patient();
		patient.setActive(true);
		IIdType patientId = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();

		// Create observation in partition 2
		addCreatePartition(myPartitionId2, myPartitionDate2);
		Observation obs = new Observation();
		obs.getSubject().setReference(patientId.getValue());

		try {
			myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();
			fail();
		} catch (InvalidRequestException e) {
			assertThat(e.getMessage()).startsWith(Msg.code(1094) + "Resource Patient/" + patientId.getIdPart() + " not found, specified in path: Observation.subject");
		}

	}

	@Test
	public void testCreate_CrossPartitionReference_ByForcedId_Allowed() {
		myPartitionSettings.setAllowReferencesAcrossPartitions(PartitionSettings.CrossPartitionReferenceMode.ALLOWED_UNQUALIFIED);

		// Create patient in partition 1
		addCreatePartition(myPartitionId, myPartitionDate);
		Patient patient = new Patient();
		patient.setId("ONE");
		patient.setActive(true);
		IIdType patientId = myPatientDao.update(patient, mySrd).getId().toUnqualifiedVersionless();

		// Create observation in partition 2
		addCreatePartition(myPartitionId2, myPartitionDate2);
		Observation obs = new Observation();
		obs.getSubject().setReference(patientId.getValue());
		IIdType obsId = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

		runInTransaction(() -> {
			List<ResourceLink> resLinks = myResourceLinkDao.findAll();
			ourLog.info("Resource links:\n{}", resLinks);
			assertEquals(2, resLinks.size());
			assertEquals(obsId.getIdPartAsLong(), resLinks.get(0).getSourceResourcePid());
			assertEquals(patientId.getIdPart(), resLinks.get(0).getTargetResourceId());
		});
	}

	@Test
	public void testCreate_CrossPartitionReference_ByForcedId_NotAllowed() {

		// Create patient in partition 1
		addCreatePartition(myPartitionId, myPartitionDate);
		Patient patient = new Patient();
		patient.setId("ONE");
		patient.setActive(true);
		IIdType patientId = myPatientDao.update(patient, mySrd).getId().toUnqualifiedVersionless();

		// Create observation in partition 2
		addCreatePartition(myPartitionId2, myPartitionDate2);
		Observation obs = new Observation();
		obs.getSubject().setReference(patientId.getValue());

		try {
			myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();
			fail();
		} catch (InvalidRequestException e) {
			assertThat(e.getMessage()).startsWith(Msg.code(1094) + "Resource Patient/ONE not found, specified in path: Observation.subject");
		}

	}

	@Test
	public void testCreate_SamePartitionReference_DefaultPartition_ByPid() {
		// Create patient in partition NULL
		addCreateDefaultPartition(myPartitionDate);
		Patient patient = new Patient();
		patient.setActive(true);
		IIdType patientId = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();

		// Create observation in partition NULL
		addCreateDefaultPartition(myPartitionDate);
		Observation obs = new Observation();
		obs.getSubject().setReference(patientId.getValue());
		IIdType obsId = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

		runInTransaction(() -> {
			List<ResourceLink> resLinks = myResourceLinkDao.findAll();
			ourLog.info("Resource links:\n{}", resLinks);
			assertEquals(2, resLinks.size());
			assertEquals(obsId.getIdPartAsLong(), resLinks.get(0).getSourceResourcePid());
			assertEquals(patientId.getIdPartAsLong(), resLinks.get(0).getTargetResourcePid());
		});
	}

	@Test
	public void testCreate_SamePartitionReference_DefaultPartition_ByForcedId() {
		// Create patient in partition NULL
		addCreateDefaultPartition(myPartitionDate);
		Patient patient = new Patient();
		patient.setId("ONE");
		patient.setActive(true);
		IIdType patientId = myPatientDao.update(patient, mySrd).getId().toUnqualifiedVersionless();

		// Create observation in partition NULL
		addCreateDefaultPartition(myPartitionDate);
		Observation obs = new Observation();
		obs.getSubject().setReference(patientId.getValue());
		IIdType obsId = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

		runInTransaction(() -> {
			List<ResourceLink> resLinks = myResourceLinkDao.findAll();
			ourLog.info("Resource links:\n{}", resLinks);
			assertEquals(2, resLinks.size());
			assertEquals(obsId.getIdPartAsLong(), resLinks.get(0).getSourceResourcePid());
			assertEquals(patientId.getIdPart(), resLinks.get(0).getTargetResourceId());
		});
	}

	@Test
	public void testCreateSearchParameter_DefaultPartitionWithDate() {
		addCreateDefaultPartition(myPartitionDate);
		addReadDefaultPartition(); // one for search param validation

		SearchParameter sp = new SearchParameter();
		sp.addBase("Patient");
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.setType(Enumerations.SearchParamType.REFERENCE);
		sp.setCode("extpatorg");
		sp.setName("extpatorg");
		sp.setExpression("Patient.extension('http://patext').value.as(Reference)");
		Long id = mySearchParameterDao.create(sp, mySrd).getId().getIdPartAsLong();

		runInTransaction(() -> {
			// HFJ_RESOURCE
			ResourceTable resourceTable = myResourceTableDao.findById(id).orElseThrow(IllegalArgumentException::new);
			PartitionablePartitionId partitionId = resourceTable.getPartitionId();
			assertNotNull(partitionId);
			assertNull(partitionId.getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, partitionId.getPartitionDate());
		});
	}


	@Test
	public void testCreateSearchParameter_NonDefaultPartition() {
		addCreatePartition(myPartitionId, myPartitionDate);

		SearchParameter sp = new SearchParameter();
		sp.addBase("Patient");
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.setType(Enumerations.SearchParamType.REFERENCE);
		sp.setCode("extpatorg");
		sp.setName("extpatorg");
		sp.setExpression("Patient.extension('http://patext').value.as(Reference)");
		try {
			mySearchParameterDao.create(sp, mySrd);
			fail();
		} catch (UnprocessableEntityException e) {
			assertEquals(Msg.code(1318) + "Resource type SearchParameter can not be partitioned", e.getMessage());
		}
	}

	@Test
	public void testCreate_AutoCreatePlaceholderTargets() {
		myStorageSettings.setAutoCreatePlaceholderReferenceTargets(true);

		addCreatePartition(1, null);
		addCreatePartition(1, null);
		IIdType patientId1 = createPatient(withOrganization(new IdType("Organization/FOO")));

		addReadPartition(1);
		IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals(patientId1, gotId1);

		addReadPartition(1);
		IdType gotIdOrg = myOrganizationDao.read(new IdType("Organization/FOO"), mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals("Organization/FOO", gotIdOrg.toUnqualifiedVersionless().getValue());
	}

	@Test
	public void testCreate_UnknownPartition() {
		addCreatePartition(99, null);

		Patient patient = new Patient();
		patient.addIdentifier().setSystem("system").setValue("value");
		patient.setBirthDate(new Date());
		try {
			myPatientDao.create(patient, mySrd);
			fail();
		} catch (ResourceNotFoundException e) {
			assertEquals("No partition exists with ID 99", e.getMessage());
		}

	}

	@Test
	public void testCreate_ServerId_NoPartition() {
		addCreateDefaultPartition();

		Patient patient = new Patient();
		patient.addIdentifier().setSystem("system").setValue("value");
		patient.setBirthDate(new Date());
		Long patientId = myPatientDao.create(patient, mySrd).getId().getIdPartAsLong();

		runInTransaction(() -> {
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertNull(resourceTable.getPartitionId());
		});
	}


	@Test
	public void testCreate_ServerId_WithPartition() {
		createUniqueComboSp();
		createRequestId();

		addCreatePartition(myPartitionId, myPartitionDate);
		Organization org = new Organization();
		org.setName("org");
		IIdType orgId = myOrganizationDao.create(org, mySrd).getId().toUnqualifiedVersionless();

		addCreatePartition(myPartitionId, myPartitionDate);
		Patient p = new Patient();
		p.getMeta().addTag("http://system", "code", "diisplay");
		p.addName().setFamily("FAM");
		p.addIdentifier().setSystem("system").setValue("value");
		p.setGender(Enumerations.AdministrativeGender.MALE);
		p.getManagingOrganization().setReferenceElement(orgId);
		Long patientId = myPatientDao.create(p, mySrd).getId().getIdPartAsLong();

		runInTransaction(() -> {
			// HFJ_RESOURCE
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, resourceTable.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, resourceTable.getPartitionId().getPartitionDate());

			// HFJ_RES_TAG
			List<ResourceTag> tags = myResourceTagDao.findAll();
			assertEquals(1, tags.size());
			assertEquals(myPartitionId, tags.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, tags.get(0).getPartitionId().getPartitionDate());

			// HFJ_RES_VER
			ResourceHistoryTable version = myResourceHistoryTableDao.findForIdAndVersion(patientId, 1L);
			assertEquals(myPartitionId, version.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, version.getPartitionId().getPartitionDate());

			// HFJ_HISTORY_TAG
			List<ResourceHistoryTag> historyTags = myResourceHistoryTagDao.findAll();
			assertEquals(1, historyTags.size());
			assertEquals(myPartitionId, historyTags.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, historyTags.get(0).getPartitionId().getPartitionDate());

			// HFJ_SPIDX_STRING
			List<ResourceIndexedSearchParamString> strings = myResourceIndexedSearchParamStringDao.findAllForResourceId(patientId);
			ourLog.info("\n * {}", strings.stream().map(ResourceIndexedSearchParamString::toString).collect(Collectors.joining("\n * ")));
			assertEquals(9, strings.size());
			assertEquals(myPartitionId, strings.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, strings.get(0).getPartitionId().getPartitionDate());

			// HFJ_SPIDX_DATE
			List<ResourceIndexedSearchParamDate> dates = myResourceIndexedSearchParamDateDao.findAllForResourceId(patientId);
			ourLog.info("\n * {}", dates.stream().map(ResourceIndexedSearchParamDate::toString).collect(Collectors.joining("\n * ")));
			assertEquals(2, dates.size());
			assertEquals(myPartitionId, dates.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, dates.get(0).getPartitionId().getPartitionDate());
			assertEquals(myPartitionId, dates.get(1).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, dates.get(1).getPartitionId().getPartitionDate());

			// HFJ_RES_LINK
			List<ResourceLink> resourceLinks = myResourceLinkDao.findAllForSourceResourceId(patientId);
			assertEquals(1, resourceLinks.size());
			assertEquals(myPartitionId, resourceLinks.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, resourceLinks.get(0).getPartitionId().getPartitionDate());
			assertEquals(myPartitionId, resourceLinks.get(0).getTargetResourcePartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, resourceLinks.get(0).getTargetResourcePartitionId().getPartitionDate());

			// HFJ_RES_PARAM_PRESENT
			List<SearchParamPresentEntity> presents = mySearchParamPresentDao.findAllForResource(resourceTable);
			assertEquals(3, presents.size());
			assertEquals(myPartitionId, presents.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, presents.get(0).getPartitionId().getPartitionDate());

			// HFJ_IDX_CMP_STRING_UNIQ
			List<ResourceIndexedComboStringUnique> uniques = myResourceIndexedComboStringUniqueDao.findAllForResourceIdForUnitTest(patientId);
			assertEquals(1, uniques.size());
			assertEquals(myPartitionId, uniques.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, uniques.get(0).getPartitionId().getPartitionDate());
		});

		myCaptureQueriesListener.clear();


	}

	@Test
	public void testCreate_ServerId_DefaultPartition() {
		createUniqueComboSp();
		createRequestId();

		addCreateDefaultPartition(myPartitionDate);
		Organization org = new Organization();
		org.setName("org");
		IIdType orgId = myOrganizationDao.create(org, mySrd).getId().toUnqualifiedVersionless();

		addCreateDefaultPartition(myPartitionDate);
		Patient p = new Patient();
		p.getMeta().addTag("http://system", "code", "diisplay");
		p.addName().setFamily("FAM");
		p.addIdentifier().setSystem("system").setValue("value");
		p.setGender(Enumerations.AdministrativeGender.MALE);
		p.getManagingOrganization().setReferenceElement(orgId);
		Long patientId = myPatientDao.create(p, mySrd).getId().getIdPartAsLong();

		runInTransaction(() -> {
			// HFJ_RESOURCE
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
            assertNull(resourceTable.getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, resourceTable.getPartitionId().getPartitionDate());

			// HFJ_RES_TAG
			List<ResourceTag> tags = myResourceTagDao.findAll();
			assertEquals(1, tags.size());
            assertNull(tags.get(0).getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, tags.get(0).getPartitionId().getPartitionDate());

			// HFJ_RES_VER
			ResourceHistoryTable version = myResourceHistoryTableDao.findForIdAndVersion(patientId, 1L);
            assertNull(version.getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, version.getPartitionId().getPartitionDate());

			// HFJ_HISTORY_TAG
			List<ResourceHistoryTag> historyTags = myResourceHistoryTagDao.findAll();
			assertEquals(1, historyTags.size());
            assertNull(historyTags.get(0).getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, historyTags.get(0).getPartitionId().getPartitionDate());

			// HFJ_SPIDX_STRING
			List<ResourceIndexedSearchParamString> strings = myResourceIndexedSearchParamStringDao.findAllForResourceId(patientId);
			String stringsDesc = strings.stream().map(ResourceIndexedSearchParamString::toString).sorted().collect(Collectors.joining("\n * "));
			ourLog.info("\n * {}", stringsDesc);
			assertThat(stringsDesc).doesNotContain("_text");
			assertThat(stringsDesc).doesNotContain("_content");
			assertEquals(9, strings.size(), stringsDesc);
            assertNull(strings.get(0).getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, strings.get(0).getPartitionId().getPartitionDate());

			// HFJ_SPIDX_DATE
			List<ResourceIndexedSearchParamDate> dates = myResourceIndexedSearchParamDateDao.findAllForResourceId(patientId);
			ourLog.info("\n * {}", dates.stream().map(ResourceIndexedSearchParamDate::toString).collect(Collectors.joining("\n * ")));
			assertEquals(2, dates.size());
            assertNull(dates.get(0).getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, dates.get(0).getPartitionId().getPartitionDate());
            assertNull(dates.get(1).getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, dates.get(1).getPartitionId().getPartitionDate());

			// HFJ_RES_LINK
			List<ResourceLink> resourceLinks = myResourceLinkDao.findAllForSourceResourceId(patientId);
			assertEquals(1, resourceLinks.size());
            assertNull(resourceLinks.get(0).getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, resourceLinks.get(0).getPartitionId().getPartitionDate());

			// HFJ_RES_PARAM_PRESENT
			List<SearchParamPresentEntity> presents = mySearchParamPresentDao.findAllForResource(resourceTable);
			assertEquals(3, presents.size());
            assertNull(presents.get(0).getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, presents.get(0).getPartitionId().getPartitionDate());

			// HFJ_IDX_CMP_STRING_UNIQ
			List<ResourceIndexedComboStringUnique> uniques = myResourceIndexedComboStringUniqueDao.findAllForResourceIdForUnitTest(patientId);
			assertEquals(1, uniques.size());
            assertNull(uniques.get(0).getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, uniques.get(0).getPartitionId().getPartitionDate());
		});

	}


	@Test
	public void testCreate_ForcedId_WithPartition() {
		addCreatePartition(myPartitionId, myPartitionDate);
		Organization org = new Organization();
		org.setId("org");
		org.setName("org");
		IIdType orgId = myOrganizationDao.update(org, mySrd).getId().toUnqualifiedVersionless();

		addCreatePartition(myPartitionId, myPartitionDate);
		Patient p = new Patient();
		p.setId("pat");
		p.getManagingOrganization().setReferenceElement(orgId);
		myPatientDao.update(p, mySrd);

		runInTransaction(() -> {
			ResourceTable orgResourceTable = myResourceTableDao.findByTypeAndFhirId("Organization", "org").orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, orgResourceTable.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, orgResourceTable.getPartitionId().getPartitionDate());

			ResourceTable patientResourceTable = myResourceTableDao.findByTypeAndFhirId("Patient", "pat").orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, patientResourceTable.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, patientResourceTable.getPartitionId().getPartitionDate());
		});

	}

	@Test
	public void testCreate_ForcedId_NoPartition() {
		addCreateDefaultPartition();
		Organization org = new Organization();
		org.setId("org");
		org.setName("org");
		IIdType orgId = myOrganizationDao.update(org, mySrd).getId().toUnqualifiedVersionless();

		addCreateDefaultPartition();
		Patient p = new Patient();
		p.setId("pat");
		p.getManagingOrganization().setReferenceElement(orgId);
		myPatientDao.update(p, mySrd);

		runInTransaction(() -> {
			ResourceTable orgResourceTable = myResourceTableDao.findByTypeAndFhirId("Organization", "org").orElseThrow(IllegalArgumentException::new);
			assertNull(orgResourceTable.getPartitionId());

			ResourceTable patientResourceTable = myResourceTableDao.findByTypeAndFhirId("Patient", "pat").orElseThrow(IllegalArgumentException::new);
			assertNull(patientResourceTable.getPartitionId());
		});

	}

	@Test
	public void testCreate_ForcedId_DefaultPartition() {
		addCreateDefaultPartition(myPartitionDate);
		Organization org = new Organization();
		org.setId("org");
		org.setName("org");
		IIdType orgId = myOrganizationDao.update(org, mySrd).getId().toUnqualifiedVersionless();

		addCreateDefaultPartition(myPartitionDate);
		Patient p = new Patient();
		p.setId("pat");
		p.getManagingOrganization().setReferenceElement(orgId);
		myPatientDao.update(p, mySrd);

		runInTransaction(() -> {
			ResourceTable orgResourceTable = myResourceTableDao.findByTypeAndFhirId("Organization", "org").orElseThrow(IllegalArgumentException::new);
			assertNull(orgResourceTable.getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, orgResourceTable.getPartitionId().getPartitionDate());

			ResourceTable patientResourceTable = myResourceTableDao.findByTypeAndFhirId("Patient", "pat").orElseThrow(IllegalArgumentException::new);
			assertNull(patientResourceTable.getPartitionId().getPartitionId());
			assertLocalDateFromDbMatches(myPartitionDate, patientResourceTable.getPartitionId().getPartitionDate());
		});

	}


	@Test
	public void testCreateInTransaction_ServerId_WithPartition() {
		createUniqueComboSp();
		createRequestId();

		addCreatePartition(myPartitionId, myPartitionDate);
		addCreatePartition(myPartitionId, myPartitionDate);
		addCreatePartition(myPartitionId, myPartitionDate);
		addCreatePartition(myPartitionId, myPartitionDate);
		addCreatePartition(myPartitionId, myPartitionDate);
		addCreatePartition(myPartitionId, myPartitionDate);

		Bundle input = new Bundle();
		input.setType(Bundle.BundleType.TRANSACTION);

		Organization org = new Organization();
		org.setId(IdType.newRandomUuid());
		org.setName("org");
		input.addEntry()
			.setFullUrl(org.getId())
			.setResource(org)
			.getRequest().setUrl("Organization").setMethod(Bundle.HTTPVerb.POST);

		Patient p = new Patient();
		p.getMeta().addTag("http://system", "code", "display");
		p.addName().setFamily("FAM");
		p.addIdentifier().setSystem("system").setValue("value");
		p.setGender(Enumerations.AdministrativeGender.MALE);
		p.getManagingOrganization().setReference(org.getId());
		input.addEntry()
			.setFullUrl(p.getId())
			.setResource(p)
			.getRequest().setUrl("Patient").setMethod(Bundle.HTTPVerb.POST);
		Bundle output = mySystemDao.transaction(mySrd, input);
		ourLog.debug(myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(output));
		Long patientId = new IdType(output.getEntry().get(1).getResponse().getLocation()).getIdPartAsLong();

		assertPersistedPartitionIdMatches(patientId);
	}


	@Test
	public void testDeleteExpunge_Cascade() {
		myPartitionSettings.setPartitioningEnabled(true);

		addCreatePartition(myPartitionId, myPartitionDate);
		addCreatePartition(myPartitionId, myPartitionDate);
		IIdType p1 = createPatient(withActiveTrue());
		IIdType o1 = createObservation(withSubject(p1));

		addCreatePartition(myPartitionId2, myPartitionDate);
		addCreatePartition(myPartitionId2, myPartitionDate);
		IIdType p2 = createPatient(withActiveTrue());
		IIdType o2 = createObservation(withSubject(p2));

		// validate precondition
		addReadAllPartitions();
		addReadAllPartitions();
		assertEquals(2, myPatientDao.search(SearchParameterMap.newSynchronous(), mySrd).size());
		assertEquals(2, myObservationDao.search(SearchParameterMap.newSynchronous(), mySrd).size());
		addReadPartition(myPartitionId);
		addReadPartition(myPartitionId);
		assertEquals(1, myPatientDao.search(SearchParameterMap.newSynchronous(), mySrd).size());
		assertEquals(1, myObservationDao.search(SearchParameterMap.newSynchronous(), mySrd).size());

		DeleteExpungeJobParameters jobParameters = new DeleteExpungeJobParameters();
		PartitionedUrl partitionedUrl = new PartitionedUrl()
				.setUrl("Patient?_id=" + p1.getIdPart() + "," + p2.getIdPart())
				.setRequestPartitionId(RequestPartitionId.fromPartitionId(myPartitionId));
		jobParameters.addPartitionedUrl(partitionedUrl);
		jobParameters.setCascade(true);

		JobInstanceStartRequest startRequest = new JobInstanceStartRequest();
		startRequest.setParameters(jobParameters);
		startRequest.setJobDefinitionId(DeleteExpungeAppCtx.JOB_DELETE_EXPUNGE);

		// execute
		Batch2JobStartResponse startResponse = myJobCoordinator.startInstance(mySrd, startRequest);

		// Validate
		JobInstance outcome = myBatch2JobHelper.awaitJobCompletion(startResponse);
		assertEquals(2, outcome.getCombinedRecordsProcessed());
		addReadAllPartitions();
		assertDoesntExist(p1);
		addReadAllPartitions();
		assertDoesntExist(o1);
		addReadAllPartitions();
		assertNotGone(p2);
		addReadAllPartitions();
		assertNotGone(o2);
	}

	private void assertPersistedPartitionIdMatches(Long patientId) {
		runInTransaction(() -> {
			// HFJ_RESOURCE
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, resourceTable.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(resourceTable.getPartitionId().getPartitionDate(), myPartitionDate);
		});
	}

	@Test
	public void testUpdateResourceWithPartition() {
		createRequestId();

		// Create a resource
		addCreatePartition(myPartitionId, myPartitionDate);
		Patient patient = new Patient();
		patient.getMeta().addTag("http://system", "code", "display");
		patient.setActive(true);
		Long patientId = myPatientDao.create(patient, mySrd).getId().getIdPartAsLong();
		assertPersistedPartitionIdMatches(patientId);

		// Update that resource
		addCreatePartition(myPartitionId);
		patient = new Patient();
		patient.setId("Patient/" + patientId);
		patient.setActive(false);
		myPatientDao.update(patient, mySrd);

		runInTransaction(() -> {
			// HFJ_RESOURCE
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, resourceTable.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, resourceTable.getPartitionId().getPartitionDate());

			// HFJ_RES_VER
			int version = 2;
			ResourceHistoryTable resVer = myResourceHistoryTableDao.findForIdAndVersion(patientId, version);
			assertEquals(myPartitionId, resVer.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, resVer.getPartitionId().getPartitionDate());

			// HFJ_HISTORY_TAG
			List<ResourceHistoryTag> historyTags = myResourceHistoryTagDao.findAll();
			assertEquals(2, historyTags.size());
			assertEquals(myPartitionId, historyTags.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, historyTags.get(0).getPartitionId().getPartitionDate());
			assertEquals(myPartitionId, historyTags.get(1).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, historyTags.get(1).getPartitionId().getPartitionDate());

			// HFJ_SPIDX_STRING
			List<ResourceIndexedSearchParamString> strings = myResourceIndexedSearchParamStringDao.findAllForResourceId(patientId);
			ourLog.info("\n * {}", strings.stream().map(ResourceIndexedSearchParamString::toString).collect(Collectors.joining("\n * ")));
			assertEquals(9, strings.size());
			assertEquals(myPartitionId, strings.get(0).getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, strings.get(0).getPartitionId().getPartitionDate());

		});

	}

	@Test
	public void testUpdateConditionalInPartition() {
		myStorageSettings.setIndexMissingFields(JpaStorageSettings.IndexEnabledEnum.DISABLED);
		createRequestId();

		// Create a resource
		addCreatePartition(myPartitionId, myPartitionDate);
		addReadPartition(myPartitionId);
		Patient p = new Patient();
		p.setActive(false);
		p.addIdentifier().setValue("12345");
		Long patientId = myPatientDao.update(p, "Patient?identifier=12345", mySrd).getId().getIdPartAsLong();
		runInTransaction(() -> {
			// HFJ_RESOURCE
			assertEquals(1, myResourceTableDao.count());
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, resourceTable.getPartitionId().getPartitionId().intValue());
			LocalDate expected = myPartitionDate;
			LocalDate actual = resourceTable.getPartitionId().getPartitionDate();
			assertLocalDateFromDbMatches(expected, actual);

			// HFJ_SPIDX_TOKEN
			ourLog.info("Tokens:\n * {}", myResourceIndexedSearchParamTokenDao.findAll().stream().map(ResourceIndexedSearchParamToken::toString).collect(Collectors.joining("\n * ")));
			assertEquals(3, myResourceIndexedSearchParamTokenDao.countForResourceId(patientId));
		});

		// Update that resource
		addReadPartition(myPartitionId);
		addCreatePartition(myPartitionId);
		p = new Patient();
		p.setActive(true);
		p.addIdentifier().setValue("12345");
		Long patientId2 = myPatientDao.update(p, "Patient?identifier=12345", mySrd).getId().getIdPartAsLong();

		assertEquals(patientId, patientId2);

		runInTransaction(() -> {
			// HFJ_RESOURCE
			assertEquals(1, myResourceTableDao.count());
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, resourceTable.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, resourceTable.getPartitionId().getPartitionDate());

			// HFJ_SPIDX_TOKEN
			ourLog.info("Tokens:\n * {}", myResourceIndexedSearchParamTokenDao.findAll().stream().map(ResourceIndexedSearchParamToken::toString).collect(Collectors.joining("\n * ")));
			assertEquals(3, myResourceIndexedSearchParamTokenDao.countForResourceId(patientId));

			// HFJ_RES_VER
			int version = 2;
			ResourceHistoryTable resVer = myResourceHistoryTableDao.findForIdAndVersion(patientId, version);
			assertEquals(myPartitionId, resVer.getPartitionId().getPartitionId().intValue());
			assertLocalDateFromDbMatches(myPartitionDate, resVer.getPartitionId().getPartitionDate());

		});

	}

	@Test
	public void testRead_PidId_AllPartitions() {
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue());

		{
			addReadAllPartitions();
			myCaptureQueriesListener.clear();
			IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);

			// Only the read columns should be used, no criteria use partition
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID,"));
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		}
		{
			addReadAllPartitions();
			IdType gotId2 = myPatientDao.read(patientId2, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId2, gotId2);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);

			// Only the read columns should be used, no criteria use partition
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID,"));
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		}
	}

	@Test
	public void testRead_PidId_SpecificPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue());

		// Read in correct Partition
		{
			myCaptureQueriesListener.clear();
			addReadPartition(1);
			IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);

			// Only the read columns should be used, no criteria use partition
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID,"), searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID='1'"), searchSql);
		}

		// Read in null Partition
		{
			addReadPartition(1);
			try {
				myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
				fail();
			} catch (ResourceNotFoundException e) {
				assertThat(e.getMessage()).matches(Msg.code(1996) + "Resource Patient/[0-9]+ is not known");
			}
		}

		// Read in wrong Partition
		{
			addReadPartition(1);
			try {
				myPatientDao.read(patientId2, mySrd).getIdElement().toUnqualifiedVersionless();
				fail();
			} catch (ResourceNotFoundException e) {
				assertThat(e.getMessage()).matches(Msg.code(1996) + "Resource Patient/[0-9]+ is not known");
			}
		}
	}

	@Test
	public void testRead_PidId_MultiplePartitionNames() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		createPatient(withPartition(2), withActiveTrue());
		IIdType patientId3 = createPatient(withPartition(3), withActiveTrue());

		// Two partitions - Found
		{
			myCaptureQueriesListener.clear();
			myPartitionInterceptor.assertNoRemainingIds();
			myPartitionInterceptor.addReadPartition(RequestPartitionId.fromPartitionNames(PARTITION_1, PARTITION_2));
			IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);

			// Only the read columns should be used, but no selectors on partition ID
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID,"), searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID in ("), searchSql);
		}

		// Two partitions including default - Found
		{
			myCaptureQueriesListener.clear();
			myPartitionInterceptor.addReadPartition(RequestPartitionId.fromPartitionNames(PARTITION_1, JpaConstants.DEFAULT_PARTITION_NAME));
			IdType gotId1 = myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientIdNull, gotId1);

			// Only the read columns should be used, but no selectors on partition ID
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID,"), searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID is null"), searchSql);
		}

		// Two partitions - Not Found
		{
			myPartitionInterceptor.addReadPartition(RequestPartitionId.fromPartitionNames(PARTITION_1, PARTITION_2));
			try {
				myPatientDao.read(patientId3, mySrd);
				fail();
			} catch (ResourceNotFoundException e) {
				// good
			}

			myPartitionInterceptor.addReadPartition(RequestPartitionId.fromPartitionNames(PARTITION_1, PARTITION_2));
			try {
				myPatientDao.read(patientIdNull, mySrd);
				fail();
			} catch (ResourceNotFoundException e) {
				// good
			}
		}

	}

	@Test
	public void testRead_PidId_MultiplePartitionIds() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		createPatient(withPartition(2), withActiveTrue());
		IIdType patientId3 = createPatient(withPartition(3), withActiveTrue());

		// Two partitions - Found
		{
			myCaptureQueriesListener.clear();
			myPartitionInterceptor.addReadPartition(RequestPartitionId.fromPartitionIds(1, 2));
			IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);

			// Only the read columns should be used, but no selectors on partition ID
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID,"), searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID in ("), searchSql);
		}

		// Two partitions including default - Found
		{
			myCaptureQueriesListener.clear();
			myPartitionInterceptor.assertNoRemainingIds();
			myPartitionInterceptor.addReadPartition(RequestPartitionId.fromPartitionIds(1, null));
			IdType gotId1 = myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientIdNull, gotId1);

			// Only the read columns should be used, but no selectors on partition ID
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID,"), searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID is null"), searchSql);
		}

		// Two partitions - Not Found
		{
			myPartitionInterceptor.addReadPartition(RequestPartitionId.fromPartitionNames(PARTITION_1, PARTITION_2));
			try {
				myPatientDao.read(patientId3, mySrd);
				fail();
			} catch (ResourceNotFoundException e) {
				// good
			}

			myPartitionInterceptor.addReadPartition(RequestPartitionId.fromPartitionNames(PARTITION_1, PARTITION_2));
			try {
				myPatientDao.read(patientIdNull, mySrd);
				fail();
			} catch (ResourceNotFoundException e) {
				// good
			}
		}

	}

	@Test
	public void testRead_PidId_DefaultPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		createPatient(withPartition(2), withActiveTrue());

		// Read in correct Partition
		{
			myCaptureQueriesListener.clear();
			addReadDefaultPartition();
			IdType gotId1 = myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientIdNull, gotId1);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);

			// Only the read columns should be used, no criteria use partition
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID,"));
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID is null"));
		}

		// Read in wrong Partition
		{
			addReadDefaultPartition();
			try {
				myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
				fail();
			} catch (ResourceNotFoundException e) {
				assertThat(e.getMessage()).matches(Msg.code(1996) + "Resource Patient/[0-9]+ is not known");
			}
		}
	}


	@Test
	public void testRead_PidId_UnknownResourceId() {
		// Read in specific Partition
		{
			addReadPartition(1);
			try {
				myPatientDao.read(new IdType("Patient/1"), mySrd);
				fail();
			} catch (ResourceNotFoundException e) {
				// expected
			}
		}

		// Read in null Partition
		{
			addReadDefaultPartition();
			try {
				myPatientDao.read(new IdType("Patient/1"), mySrd);
				fail();
			} catch (ResourceNotFoundException e) {
				// expected
			}
		}
	}

	@Test
	public void testRead_PidId_ResourceIdOnlyExistsInDifferentPartition() {
		IIdType id = createPatient(withPartition(2), withActiveTrue());
		// Read in specific Partition
		{
			addReadPartition(1);
			try {
				myPatientDao.read(id, mySrd);
				fail();
			} catch (ResourceNotFoundException e) {
				// expected
			}
		}

		// Read in null Partition
		{
			addReadDefaultPartition();
			try {
				myPatientDao.read(id, mySrd);
				fail();
			} catch (ResourceNotFoundException e) {
				// expected
			}
		}
	}

	@Test
	public void testRead_ForcedId_SpecificPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue(), withId("NULL"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withId("ONE"));
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue(), withId("TWO"));

		// Read in correct Partition
		addReadPartition(1);
		IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals(patientId1, gotId1);

		// Read in null Partition
		addReadPartition(1);
		try {
			myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage()).matches(Msg.code(2001) + "Resource Patient/NULL is not known");
		}

		// Read in wrong Partition
		addReadPartition(1);
		try {
			myPatientDao.read(patientId2, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage()).matches(Msg.code(2001) + "Resource Patient/TWO is not known");
		}

		// Read in wrong Partition
		addReadPartition(2);
		try {
			myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage()).matches(Msg.code(2001) + "Resource Patient/ONE is not known");
		}

		// Read in correct Partition
		addReadPartition(2);
		IdType gotId2 = myPatientDao.read(patientId2, mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals(patientId2, gotId2);

	}

	@Test
	public void testRead_ForcedId_DefaultPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue(), withId("NULL"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withId("ONE"));
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue(), withId("TWO"));

		// Read in correct Partition
		addReadDefaultPartition();
		IdType gotId1 = myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals(patientIdNull, gotId1);

		// Read in null Partition
		addReadDefaultPartition();
		try {
			myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage()).matches(Msg.code(2001) + "Resource Patient/ONE is not known");
		}

		// Read in wrong Partition
		addReadDefaultPartition();
		try {
			myPatientDao.read(patientId2, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage()).matches(Msg.code(2001) + "Resource Patient/TWO is not known");
		}
	}

	@Test
	public void testRead_ForcedId_AllPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue(), withId("NULL"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withId("ONE"));
		createPatient(withPartition(2), withActiveTrue(), withId("TWO"));
		{
			addReadAllPartitions();
			IdType gotId1 = myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientIdNull, gotId1);
		}
		{
			addReadAllPartitions();
			IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);
		}
	}

	@Test
	public void testRead_ForcedId_AllPartition_WithDuplicate() {
		dropForcedIdUniqueConstraint();
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue(), withId("FOO"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withId("FOO"));
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue(), withId("FOO"));
		assertEquals(patientIdNull, patientId1);
		assertEquals(patientIdNull, patientId2);

		{
			addReadAllPartitions();
			try {
				myPatientDao.read(patientIdNull, mySrd);
				fail();
			} catch (PreconditionFailedException e) {
				assertEquals(Msg.code(1099) + "Non-unique ID specified, can not process request", e.getMessage());
			}
		}
	}

	@Test
	public void testSearch_IdParamOnly_PidId_SpecificPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue());

		/* *******************************
		 * _id param is only parameter
		 * *******************************/

		// Read in correct Partition
		{
			myCaptureQueriesListener.clear();
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous(IAnyResource.SP_RES_ID, new TokenParam(patientId1.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(1, searchOutcome.size());
			IIdType gotId1 = searchOutcome.getResources(0, 1).get(0).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
			ourLog.info("Search SQL:\n{}", searchSql);

			// Only the read columns should be used, no criteria use partition
			assertThat(searchSql).as(searchSql).contains("PARTITION_ID = '1'");
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(2);
		}

		// Read in null Partition
		{
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous(IAnyResource.SP_RES_ID, new TokenParam(patientIdNull.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(0, searchOutcome.size());
		}

		// Read in wrong Partition
		{
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous(IAnyResource.SP_RES_ID, new TokenParam(patientId2.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(0, searchOutcome.size());
		}

	}


	@Test
	public void testSearch_IdParamSecond_PidId_SpecificPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue());

		/* *******************************
		 * _id param is second parameter
		 * *******************************/

		// Read in correct Partition
		{
			myCaptureQueriesListener.clear();
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous()
				.add(Patient.SP_ACTIVE, new TokenParam("true"))
				.add(IAnyResource.SP_RES_ID, new TokenParam(patientId1.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(1, searchOutcome.size());
			IIdType gotId1 = searchOutcome.getResources(0, 1).get(0).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
			ourLog.info("Search SQL:\n{}", searchSql);

			// Only the read columns should be used, no criteria use partition
			assertThat(searchSql).as(searchSql).contains("PARTITION_ID = '1'");
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(3); // If this switches to 2 that would be fine
		}

		// Read in null Partition
		{
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous()
				.add(Patient.SP_ACTIVE, new TokenParam("true"))
				.add(IAnyResource.SP_RES_ID, new TokenParam(patientIdNull.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(0, searchOutcome.size());
		}

		// Read in wrong Partition
		{
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous()
				.add(Patient.SP_ACTIVE, new TokenParam("true"))
				.add(IAnyResource.SP_RES_ID, new TokenParam(patientId2.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(0, searchOutcome.size());
		}

	}


	@Test
	public void testSearch_IdParamOnly_ForcedId_SpecificPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withId("PT-NULL"), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withId("PT-1"), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withId("PT-2"), withActiveTrue());

		/* *******************************
		 * _id param is only parameter
		 * *******************************/

		// Read in correct Partition
		{
			myCaptureQueriesListener.clear();
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous(IAnyResource.SP_RES_ID, new TokenParam(patientId1.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			myCaptureQueriesListener.logSelectQueriesForCurrentThread();
			assertEquals(1, searchOutcome.size());
			IIdType gotId1 = searchOutcome.getResources(0, 1).get(0).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false).toUpperCase();
			ourLog.info("Search SQL:\n{}", searchSql);

			// Only the read columns should be used, no criteria use partition
			assertThat(searchSql).as(searchSql).contains("PARTITION_ID IN ('1')");
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID,")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_DATE")).as(searchSql).isEqualTo(1);
		}

		// Read in null Partition
		{
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous(IAnyResource.SP_RES_ID, new TokenParam(patientIdNull.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(0, searchOutcome.size());
		}

		// Read in wrong Partition
		{
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous(IAnyResource.SP_RES_ID, new TokenParam(patientId2.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(0, searchOutcome.size());
		}

	}


	@Test
	public void testSearch_IdParamSecond_ForcedId_SpecificPartition() {
		IIdType patientId1 = createPatient(withPartition(1), withId("PT-1"), withActiveTrue());
		IIdType patientIdNull = createPatient(withPartition(null), withId("PT-NULL"), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withId("PT-2"), withActiveTrue());

		logAllTokenIndexes();

		/* *******************************
		 * _id param is second parameter
		 * *******************************/

		// Read in correct Partition
		{
			myCaptureQueriesListener.clear();
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous()
				.add(Patient.SP_ACTIVE, new TokenParam("true"))
				.add(IAnyResource.SP_RES_ID, new TokenParam(patientId1.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(1, searchOutcome.size());
			IIdType gotId1 = searchOutcome.getResources(0, 1).get(0).getIdElement().toUnqualifiedVersionless();
			assertEquals(patientId1, gotId1);

			// First SQL resolves the forced ID
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false).toUpperCase();
			ourLog.info("Search SQL:\n{}", searchSql);
			assertThat(searchSql).as(searchSql).contains("PARTITION_ID IN ('1')");
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID,")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_DATE")).as(searchSql).isEqualTo(1);

			// Second SQL performs the search

			searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(1).getSql(true, false).toUpperCase();
			ourLog.info("Search SQL:\n{}", searchSql);
			assertThat(searchSql).as(searchSql).contains("PARTITION_ID = '1'");
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(3);
		}

		// Read in null Partition
		{
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous()
				.add(Patient.SP_ACTIVE, new TokenParam("true"))
				.add(IAnyResource.SP_RES_ID, new TokenParam(patientIdNull.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(0, searchOutcome.size());
		}

		// Read in wrong Partition
		{
			addReadPartition(1);

			SearchParameterMap map = SearchParameterMap.newSynchronous()
				.add(Patient.SP_ACTIVE, new TokenParam("true"))
				.add(IAnyResource.SP_RES_ID, new TokenParam(patientId2.toUnqualifiedVersionless().getValue()));
			IBundleProvider searchOutcome = myPatientDao.search(map, mySrd);
			assertEquals(0, searchOutcome.size());
		}

	}


	@Test
	public void testSearch_MissingParamString_SearchAllPartitions() {
		myPartitionSettings.setIncludePartitionInSearchHashes(false);

		IIdType patientIdNull = createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		IIdType patientId2 = createPatient(withPartition(2), withFamily("FAMILY"));

		// :missing=true
		{
			addReadAllPartitions();
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_ACTIVE, new StringParam().setMissing(true));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
			assertEquals(1, StringUtils.countMatches(searchSql, "SP_MISSING = 'true'"));
		}

		// :missing=false
		{
			addReadAllPartitions();
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_FAMILY, new StringParam().setMissing(false));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
			assertEquals(1, StringUtils.countMatches(searchSql, "SP_MISSING = 'false'"));
		}
	}


	@Test
	public void testSearch_MissingParamString_SearchOnePartition() {
		createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		// :missing=true
		{
			addReadPartition(1);
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_ACTIVE, new StringParam().setMissing(true));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientId1);

			ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
			assertThat(StringUtils.countMatches(searchSql, "t0.PARTITION_ID = '1'")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "SP_MISSING = 'true'")).as(searchSql).isEqualTo(1);
		}

		// :missing=false
		{
			addReadPartition(1);
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_FAMILY, new StringParam().setMissing(false));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientId1);

			ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
			assertEquals(1, StringUtils.countMatches(searchSql, "t0.PARTITION_ID = '1'"));
			assertEquals(1, StringUtils.countMatches(searchSql, "SP_MISSING = 'false'"));
		}
	}

	@Test
	public void testSearch_MissingParamString_SearchDefaultPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withFamily("FAMILY"));
		createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		// :missing=true
		{
			addReadDefaultPartition();
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_ACTIVE, new StringParam().setMissing(true));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientIdNull);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID IS NULL"));
			assertEquals(1, StringUtils.countMatches(searchSql, "SP_MISSING = 'true'"));
		}

		// :missing=false
		{
			addReadDefaultPartition();
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_FAMILY, new StringParam().setMissing(false));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientIdNull);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID IS NULL"));
			assertEquals(1, StringUtils.countMatches(searchSql, "SP_MISSING = 'false'"));
		}
	}

	@Test
	public void testSearch_MissingParamReference_SearchAllPartitions() {
		myPartitionSettings.setIncludePartitionInSearchHashes(false);

		IIdType patientIdNull = createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		IIdType patientId2 = createPatient(withPartition(2), withFamily("FAMILY"));

		// :missing=true
		{
			addReadAllPartitions();
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_GENERAL_PRACTITIONER, new StringParam().setMissing(true));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);
			assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
			assertEquals(1, StringUtils.countMatches(searchSql, "HFJ_RES_PARAM_PRESENT"));
			assertEquals(1, StringUtils.countMatches(searchSql, "HASH_PRESENCE = '1919227773735728687'"));
		}
	}

	@Test
	public void testSearch_MissingParamReference_SearchOnePartition_IncludePartitionInHashes() {
		myPartitionSettings.setIncludePartitionInSearchHashes(true);

		createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		// :missing=true
		{
			addReadPartition(1);
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_GENERAL_PRACTITIONER, new StringParam().setMissing(true));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientId1);

			ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(2);
			assertThat(StringUtils.countMatches(searchSql, "t0.PARTITION_ID = '1'")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "HFJ_RES_PARAM_PRESENT")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "HASH_PRESENCE = '-3438137196820602023'")).as(searchSql).isEqualTo(1);
		}
	}

	@Test
	public void testSearch_MissingParamReference_SearchOnePartition_DontIncludePartitionInHashes() {
		myPartitionSettings.setIncludePartitionInSearchHashes(false);

		createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		// :missing=true
		{
			addReadPartition(1);
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_GENERAL_PRACTITIONER, new StringParam().setMissing(true));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientId1);

			ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(2);
			assertThat(StringUtils.countMatches(searchSql, "t0.PARTITION_ID = '1'")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "HFJ_RES_PARAM_PRESENT")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "HASH_PRESENCE = '1919227773735728687'")).as(searchSql).isEqualTo(1);
		}
	}

	@Test
	public void testSearch_MissingParamReference_SearchDefaultPartition() {
		IIdType patientIdDefault = createPatient(withPartition(null), withFamily("FAMILY"));
		createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		// :missing=true
		{
			addReadDefaultPartition();
			myCaptureQueriesListener.clear();
			SearchParameterMap map = new SearchParameterMap();
			map.add(Patient.SP_GENERAL_PRACTITIONER, new StringParam().setMissing(true));
			map.setLoadSynchronous(true);
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).containsExactly(patientIdDefault);

			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
			ourLog.info("Search SQL:\n{}", searchSql);
			assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(2);
			assertThat(StringUtils.countMatches(searchSql, "t0.PARTITION_ID IS NULL")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "HFJ_RES_PARAM_PRESENT")).as(searchSql).isEqualTo(1);
			assertThat(StringUtils.countMatches(searchSql, "HASH_PRESENCE = '1919227773735728687'")).as(searchSql).isEqualTo(1);
		}
	}


	@Test
	public void testSearch_NoParams_SearchAllPartitions() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue());

		addReadAllPartitions();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
	}

	@Test
	public void testSearch_NoParams_SearchOnePartition() {
		createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		createPatient(withPartition(2), withActiveTrue());

		addReadPartition(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientId1);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
	}

	@Test
	public void testSearch_NoParams_SearchMultiplePartitionsByName_NoDefault() {
		createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue());
		createPatient(withPartition(3), withActiveTrue());

		addReadPartitions(PARTITION_1, PARTITION_2);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientId1, patientId2);

		ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
		assertThat(searchSql).contains("PARTITION_ID IN ('1','2')");
	}

	@Test
	public void testSearch_NoParams_SearchMultiplePartitionsByName_WithDefault() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		createPatient(withPartition(1), withActiveTrue());
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue());
		createPatient(withPartition(3), withActiveTrue());

		addReadPartitions(JpaConstants.DEFAULT_PARTITION_NAME, PARTITION_2);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).as(ids.toString()).containsExactlyInAnyOrder(patientIdNull, patientId2);

		ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
		String sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
		assertThat(sql).as(sql).contains("PARTITION_ID = '2'");
		assertThat(sql).as(sql).contains("PARTITION_ID IS NULL");
	}

	@Test
	public void testSearch_DateParam_SearchAllPartitions() {
		myPartitionSettings.setIncludePartitionInSearchHashes(false);

		IIdType patientIdNull = createPatient(withPartition(null), withBirthdate("2020-04-20"));
		IIdType patientId1 = createPatient(withPartition(1), withBirthdate("2020-04-20"));
		IIdType patientId2 = createPatient(withPartition(2), withBirthdate("2020-04-20"));
		createPatient(withPartition(null), withBirthdate("2021-04-20"));
		createPatient(withPartition(1), withBirthdate("2021-04-20"));
		createPatient(withPartition(2), withBirthdate("2021-04-20"));

		// Date param

		addReadAllPartitions();
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateParam("2020-04-20"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(2, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));

		// Date OR param

		addReadAllPartitions();
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateOrListParam().addOr(new DateParam("2020-04-20")).addOr(new DateParam("2020-04-22")));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(4, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));

		// Date AND param

		addReadAllPartitions();
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateAndListParam().addAnd(new DateOrListParam().addOr(new DateParam("2020"))).addAnd(new DateOrListParam().addOr(new DateParam("2020-04-20"))));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(4, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));

		// DateRangeParam

		addReadAllPartitions();
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateRangeParam(new DateParam("2020-01-01"), new DateParam("2020-04-25")));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		// NOTE: the query is changed, only one SP_VALUE_LOW and SP_VALUE_HIGH
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_HIGH"));

	}


	@Test
	public void testSearch_DateParam_SearchSpecificPartitions() {
		myPartitionSettings.setIncludePartitionInSearchHashes(false);

		createPatient(withPartition(null), withBirthdate("2020-04-20"));
		IIdType patientId1 = createPatient(withPartition(1), withBirthdate("2020-04-20"));
		createPatient(withPartition(2), withBirthdate("2020-04-20"));
		createPatient(withPartition(null), withBirthdate("2021-04-20"));
		createPatient(withPartition(1), withBirthdate("2021-04-20"));
		createPatient(withPartition(2), withBirthdate("2021-04-20"));

		// Date param

		runInTransaction(() -> {
			ourLog.info("Date indexes:\n * {}", myResourceIndexedSearchParamDateDao.findAll().stream().map(ResourceIndexedSearchParamDate::toString).collect(Collectors.joining("\n * ")));
		});
		addReadPartition(1);
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateParam("2020-04-20"));
		map.setLoadSynchronous(true);
		myCaptureQueriesListener.clear();
		IBundleProvider results = myPatientDao.search(map, mySrd);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientId1);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(2);
		assertThat(StringUtils.countMatches(searchSql, "SP_VALUE_LOW")).as(searchSql).isEqualTo(2);

		// Date OR param

		addReadPartition(1);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateOrListParam().addOr(new DateParam("2020-04-20")).addOr(new DateParam("2020-04-22")));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientId1);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(4, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));

		// Date AND param

		addReadPartition(1);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateAndListParam().addAnd(new DateOrListParam().addOr(new DateParam("2020"))).addAnd(new DateOrListParam().addOr(new DateParam("2020-04-20"))));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientId1);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(4, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));

		// DateRangeParam

		addReadPartition(1);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateRangeParam(new DateParam("2020-01-01"), new DateParam("2020-04-25")));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientId1);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		// NOTE: the query is changed, only one SP_VALUE_LOW and SP_VALUE_HIGH
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_HIGH"));

	}


	@Test
	public void testSearch_DateParam_SearchDefaultPartitions() {
		myPartitionSettings.setIncludePartitionInSearchHashes(false);

		IIdType patientIdNull = createPatient(withPartition(null), withBirthdate("2020-04-20"));
		createPatient(withPartition(1), withBirthdate("2020-04-20"));
		createPatient(withPartition(2), withBirthdate("2020-04-20"));
		createPatient(withPartition(null), withBirthdate("2021-04-20"));
		createPatient(withPartition(1), withBirthdate("2021-04-20"));
		createPatient(withPartition(2), withBirthdate("2021-04-20"));

		// Date param

		addReadDefaultPartition();
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateParam("2020-04-20"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(2, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));

		// Date OR param

		addReadDefaultPartition();
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateOrListParam().addOr(new DateParam("2020-04-20")).addOr(new DateParam("2020-04-22")));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(4, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));

		// Date AND param

		addReadDefaultPartition();
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateAndListParam().addAnd(new DateOrListParam().addOr(new DateParam("2020"))).addAnd(new DateOrListParam().addOr(new DateParam("2020-04-20"))));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(4, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));

		// DateRangeParam

		addReadDefaultPartition();
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateRangeParam(new DateParam("2020-01-01"), new DateParam("2020-04-25")));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		// NOTE: the query is changed, only one SP_VALUE_LOW and SP_VALUE_HIGH
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_HIGH"));

	}

	@Test
	public void testSearch_DateParam_SearchDefaultPartitions_NonNullDefaultPartition() {
		myPartitionSettings.setIncludePartitionInSearchHashes(false);
		myPartitionSettings.setDefaultPartitionId(-1);

		IIdType patientIdNull = createPatient(withPartition(null), withBirthdate("2020-04-20"));
		createPatient(withPartition(1), withBirthdate("2020-04-20"));
		createPatient(withPartition(2), withBirthdate("2020-04-20"));
		createPatient(withPartition(null), withBirthdate("2021-04-20"));
		createPatient(withPartition(1), withBirthdate("2021-04-20"));
		createPatient(withPartition(2), withBirthdate("2021-04-20"));

		// Date param
		addReadDefaultPartition();
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateParam("2020-04-20"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID = '-1'"));
		assertEquals(2, StringUtils.countMatches(searchSql, "SP_VALUE_LOW"));
	}


	@Test
	public void testSearch_HasParam_SearchOnePartition() {
		addCreatePartition(1, null);
		Organization org = new Organization();
		org.setId("ORG");
		org.setName("ORG");
		myOrganizationDao.update(org, mySrd);

		addCreatePartition(1, null);
		Practitioner practitioner = new Practitioner();
		practitioner.setId("PRACT");
		practitioner.addName().setFamily("PRACT");
		myPractitionerDao.update(practitioner, mySrd);

		addCreatePartition(1, null);
		PractitionerRole role = new PractitionerRole();
		role.setId("ROLE");
		role.getPractitioner().setReference("Practitioner/PRACT");
		role.getOrganization().setReference("Organization/ORG");
		myPractitionerRoleDao.update(role, mySrd);

		addReadPartition(1);
		SearchParameterMap params = SearchParameterMap.newSynchronous();
		HasAndListParam value = new HasAndListParam();
		value.addAnd(new HasOrListParam().addOr(new HasParam("PractitionerRole", "practitioner", "_id", "ROLE")));
		params.add("_has", value);
		myCaptureQueriesListener.clear();
		IBundleProvider outcome = myPractitionerDao.search(params, mySrd);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread(1);
		assertThat(outcome.getResources(0, 1)).hasSize(1);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertThat(searchSql).as(searchSql).contains("PARTITION_ID in ('1')");
		assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID,")).as(searchSql).isEqualTo(1);
		assertThat(StringUtils.countMatches(searchSql, "PARTITION_DATE")).as(searchSql).isEqualTo(1);
	}


	@Test
	public void testSearch_StringParam_SearchAllPartitions() {
		myPartitionSettings.setIncludePartitionInSearchHashes(false);

		IIdType patientIdNull = createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		IIdType patientId2 = createPatient(withPartition(2), withFamily("FAMILY"));

		addReadAllPartitions();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_NORMALIZED"));
	}

	@Test
	public void testSearch_StringParam_SearchDefaultPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withFamily("FAMILY"));
		createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		addReadDefaultPartition();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		searchSql = searchSql.toUpperCase();
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID IS NULL"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_NORMALIZED"));
	}

	@Test
	public void testSearch_StringParam_SearchOnePartition() {
		createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		addReadPartition(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).containsExactly(patientId1);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_NORMALIZED"));
	}

	@Test
	public void testSearch_StringParam_SearchMultiplePartitions() {
		IIdType patientIdNull = createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		IIdType patientId2 = createPatient(withPartition(2), withFamily("FAMILY"));
		createPatient(withPartition(3), withFamily("FAMILY"));

		createPatient(withPartition(null), withFamily("BLAH"));
		createPatient(withPartition(1), withFamily("BLAH"));
		createPatient(withPartition(2), withFamily("BLAH"));
		createPatient(withPartition(3), withFamily("BLAH"));


		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);

		// Match two partitions
		{
			addReadPartition(1, 2);

			myCaptureQueriesListener.clear();
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			assertThat(ids).as(ids.toString()).containsExactlyInAnyOrder(patientId1, patientId2);

			ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
			assertThat(searchSql).contains("PARTITION_ID IN ('1','2')");
			assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		}

		// Match two partitions including null
		{
			addReadPartition(1, null);

			myCaptureQueriesListener.clear();
			IBundleProvider results = myPatientDao.search(map, mySrd);
			List<IIdType> ids = toUnqualifiedVersionlessIds(results);
			myCaptureQueriesListener.logSelectQueriesForCurrentThread();
			assertThat(ids).as(ids.toString()).containsExactlyInAnyOrder(patientId1, patientIdNull);

			ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
			String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
			assertThat(searchSql).contains("PARTITION_ID IS NULL");
			assertThat(searchSql).contains("PARTITION_ID = '1'");
			assertEquals(3, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		}
	}

	@Test
	public void testSearch_StringParam_SearchMultiplePartitions_IncludePartitionInHashes() {
		myPartitionSettings.setIncludePartitionInSearchHashes(true);

		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);

		addReadPartition(1, 2);
		try {
			myPatientDao.search(map, mySrd);
			fail();
		} catch (InternalErrorException e) {
			assertEquals(Msg.code(1527) + "Can not search multiple partitions when partitions are included in search hashes", e.getMessage());
		}
	}

	@Test
	public void testSearch_StringParam_SearchAllPartitions_IncludePartitionInHashes() {
		myPartitionSettings.setIncludePartitionInSearchHashes(true);

		addReadAllPartitions();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);
		try {
			IBundleProvider value = myPatientDao.search(map, mySrd);
			value.size();
			fail();
		} catch (PreconditionFailedException e) {
			assertEquals(Msg.code(1220) + "This server is not configured to support search against all partitions", e.getMessage());
		}
	}

	@Test
	public void testSearch_StringParam_SearchDefaultPartition_IncludePartitionInHashes() {
		myPartitionSettings.setIncludePartitionInSearchHashes(true);

		IIdType patientIdNull = createPatient(withPartition(null), withFamily("FAMILY"));
		createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		addReadDefaultPartition();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		searchSql = searchSql.toUpperCase();
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID IS NULL"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_NORMALIZED"));
	}

	@Test
	public void testSearch_StringParam_SearchOnePartition_IncludePartitionInHashes() {
		myPartitionSettings.setIncludePartitionInSearchHashes(true);

		createPatient(withPartition(null), withFamily("FAMILY"));
		IIdType patientId1 = createPatient(withPartition(1), withFamily("FAMILY"));
		createPatient(withPartition(2), withFamily("FAMILY"));

		logAllStringIndexes();

		addReadPartition(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).as(ids.toString()).containsExactly(patientId1);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_NORMALIZED"));
	}

	/*
	 * Should try to get this down at some point
	 */
	@Test
	@Disabled
	public void testSearch_StringParam_SearchOnePartition_AddRevIncludes() {
		addReadPartition(1);
		addCreatePartition(1, null);
		Organization org = new Organization();
		org.setName("FOO");
		org.setId("FOO-ORG");
		myOrganizationDao.update(org, mySrd);

		for (int i = 0; i < 50; i++) {
			addCreatePartition(1, null);
			PractitionerRole pr = new PractitionerRole();
			pr.getOrganization().setReference("Organization/FOO-ORG");
			myPractitionerRoleDao.create(pr, mySrd);
		}

		addReadPartition(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.addRevInclude(PractitionerRole.INCLUDE_ORGANIZATION);
		map.setCount(10);
		IBundleProvider results = myOrganizationDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();

		assertThat(ids.size()).as(() -> ids.toString()).isEqualTo(10);
	}

	@Test
	public void testSearch_TagNotParam_SearchAllPartitions() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"), withIdentifier("http://foo", "bar"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"), withIdentifier("http://foo", "bar"));
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code2"));
		createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code2"));
		createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code2"));

		addReadAllPartitions();
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Constants.PARAM_TAG, new TokenParam("http://system", "code2").setModifier(TokenParamModifier.NOT));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "TAG_SYSTEM = 'http://system'"));

		// And with another param

		addReadAllPartitions();
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Constants.PARAM_TAG, new TokenParam("http://system", "code2").setModifier(TokenParamModifier.NOT));
		map.add(Patient.SP_IDENTIFIER, new TokenParam("http://foo", "bar"));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1);

		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(1);
		assertThat(StringUtils.countMatches(searchSql, "TAG_SYSTEM = 'http://system'")).as(searchSql).isEqualTo(1);
		assertThat(StringUtils.countMatches(searchSql, ".HASH_SYS_AND_VALUE =")).as(searchSql).isEqualTo(1);


	}

	@Test
	public void testSearch_TagNotParam_SearchDefaultPartition() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"));

		addReadDefaultPartition();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Constants.PARAM_TAG, new TokenParam("http://system", "code2").setModifier(TokenParamModifier.NOT));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID IS NULL"));
		assertEquals(1, StringUtils.countMatches(searchSql, "TAG_SYSTEM = 'http://system'"));

		assertThat(ids).as(ids.toString()).containsExactly(patientIdNull);
	}

	@Test
	public void testSearch_TagNotParam_SearchOnePartition() {
		createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code2"));
		createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code2"));
		createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code2"));

		addReadPartition(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Constants.PARAM_TAG, new TokenParam("http://system", "code2").setModifier(TokenParamModifier.NOT));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientId1);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "TAG_SYSTEM = 'http://system'"));
	}

	@Test
	public void testSearch_TagParam_SearchAllPartitions() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"));
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"));

		addReadAllPartitions();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Constants.PARAM_TAG, new TokenParam("http://system", "code"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "TAG_SYSTEM = 'http://system'"));
	}

	@Test
	public void testSearch_TagParam_SearchOnePartition() {
		createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"));

		addReadPartition(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Constants.PARAM_TAG, new TokenParam("http://system", "code"));
		map.setLoadSynchronous(true);
		myCaptureQueriesListener.clear();
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread(0);
		assertThat(ids).containsExactly(patientId1);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);

		assertEquals(2, StringUtils.countMatches(searchSql, "JOIN"));
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "TAG_SYSTEM = 'http://system'"));
	}

	@Test
	public void testSearch_TagParamNot_SearchAllPartitions() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"));
		IIdType patientId2 = createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"), withTag("http://system", "code2"));
		createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"), withTag("http://system", "code2"));
		createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"), withTag("http://system", "code2"));

		addReadAllPartitions();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Constants.PARAM_TAG, new TokenParam("http://system", "code2").setModifier(TokenParamModifier.NOT));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientIdNull, patientId1, patientId2);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "TAG_SYSTEM = 'http://system'"));
	}

	@Test
	public void testSearch_TagParamNot_SearchOnePartition() {
		createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"));
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"));
		createPatient(withPartition(null), withActiveTrue(), withTag("http://system", "code"), withTag("http://system", "code2"));
		createPatient(withPartition(1), withActiveTrue(), withTag("http://system", "code"), withTag("http://system", "code2"));
		createPatient(withPartition(2), withActiveTrue(), withTag("http://system", "code"), withTag("http://system", "code2"));

		addReadPartition(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Constants.PARAM_TAG, new TokenParam("http://system", "code2").setModifier(TokenParamModifier.NOT));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(patientId1);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "TAG_SYSTEM = 'http://system'"));
	}

	@Test
	public void testSearch_UniqueParam_SearchAllPartitions() {
		createUniqueComboSp();

		IIdType id = createPatient(withPartition(1), withGender("male"), withFamily("FAM"));

		addReadAllPartitions();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAM"));
		map.add(Patient.SP_GENDER, new TokenParam(null, "male"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).containsExactly(id);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertThat(searchSql).doesNotContain("PARTITION_ID IN");
		assertThat(searchSql).doesNotContain("PARTITION_ID =");
		assertThat(searchSql).containsOnlyOnce("IDX_STRING = 'Patient?family=FAM&gender=male'");
	}


	@Test
	public void testSearch_UniqueParam_SearchOnePartition() {
		createUniqueComboSp();

		IIdType id = createPatient(withPartition(1), withGender("male"), withFamily("FAM"));

		addReadPartition(1);
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAM"));
		map.add(Patient.SP_GENDER, new TokenParam(null, "male"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).containsExactly(id);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertThat(searchSql).containsOnlyOnce( "PARTITION_ID = '1'");
		assertThat(searchSql).containsOnlyOnce("IDX_STRING = 'Patient?family=FAM&gender=male'");

		// Same query, different partition
		addReadPartition(2);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_GENDER, new TokenParam(null, "male"));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).isEmpty();

	}


	@ParameterizedTest
	@ValueSource(strings = {"ALL", "ONE", "MANY"})
	public void testSearch_NonUniqueComboParam(String theReadPartitions) {
		createNonUniqueComboSp();

		IIdType orgId = createOrganization(withId("A"), withPartition(myPartitionId), withName("My Org")).toUnqualifiedVersionless();
		IIdType orgId2 = createOrganization(withId("B"), withPartition(myPartitionId2), withName("My Org")).toUnqualifiedVersionless();
		// Matching
		IIdType patientId = createPatient(withPartition(myPartitionId), withFamily("FAMILY"), withOrganization(orgId));
		// Non matching
		createPatient(withPartition(myPartitionId), withFamily("WRONG"), withOrganization(orgId));
		createPatient(withPartition(myPartitionId2), withFamily("FAMILY"), withOrganization(orgId2));

		logAllNonUniqueIndexes();

		switch (theReadPartitions) {
			case "ALL":
				addReadAllPartitions();
				break;
			case "ONE":
				addReadPartition(myPartitionId);
				break;
			case "MANY":
				addReadPartition(myPartitionId, myPartitionId4);
				break;
			default:
				throw new IllegalStateException();
		}
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.add(Patient.SP_ORGANIZATION, new ReferenceParam(orgId));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).containsExactly(patientId);

		ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);

		switch (theReadPartitions) {
			case "ALL":
				assertThat(searchSql).doesNotContain("t0.PARTITION_ID =");
				assertThat(searchSql).doesNotContain("t0.PARTITION_ID IN");
				break;
			case "ONE":
				assertThat(searchSql).contains("t0.PARTITION_ID = '1'");
				break;
			case "MANY":
				assertThat(searchSql).contains("t0.PARTITION_ID IN ('1','4')");
				break;
			default:
				throw new IllegalStateException();
		}

		assertThat(searchSql).containsOnlyOnce("t0.HASH_COMPLETE = '-2879121558074554863'");
	}


	@Test
	public void testSearch_RefParam_TargetPid_SearchOnePartition() {
		createUniqueComboSp();

		IIdType patientId = createPatient(withPartition(myPartitionId), withGender("male"));
		IIdType observationId = createObservation(withPartition(myPartitionId), withSubject(patientId));

		addReadPartition(myPartitionId);
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Observation.SP_SUBJECT, new ReferenceParam(patientId));
		map.setLoadSynchronous(true);
		IBundleProvider results = myObservationDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).containsExactly(observationId);

		ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
		assertThat(StringUtils.countMatches(searchSql, "t0.PARTITION_ID = '1'")).as(searchSql).isEqualTo(1);
		assertThat(StringUtils.countMatches(searchSql, "t0.SRC_PATH = 'Observation.subject'")).as(searchSql).isEqualTo(1);
		assertThat(StringUtils.countMatches(searchSql, "t0.TARGET_RESOURCE_ID = '" + patientId.getIdPartAsLong() + "'")).as(searchSql).isEqualTo(1);
		assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(2);

		// Same query, different partition
		addReadPartition(2);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Observation.SP_SUBJECT, new ReferenceParam(patientId));
		map.setLoadSynchronous(true);
		results = myObservationDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).isEmpty();

	}

	@Test
	public void testSearch_RefParam_TargetPid_SearchDefaultPartition() {
		createUniqueComboSp();

		IIdType patientId = createPatient(withPartition(null), withGender("male"));
		IIdType observationId = createObservation(withPartition(null), withSubject(patientId));

		addReadDefaultPartition();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Observation.SP_SUBJECT, new ReferenceParam(patientId));
		map.setLoadSynchronous(true);
		IBundleProvider results = myObservationDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(observationId);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "t0.PARTITION_ID IS NULL"));
		assertEquals(1, StringUtils.countMatches(searchSql, "t0.SRC_PATH = 'Observation.subject'"));
		assertEquals(1, StringUtils.countMatches(searchSql, "t0.TARGET_RESOURCE_ID = '" + patientId.getIdPartAsLong() + "'"));
		assertEquals(2, StringUtils.countMatches(searchSql, "PARTITION_ID"));

		// Same query, different partition
		addReadPartition(2);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Observation.SP_SUBJECT, new ReferenceParam(patientId));
		map.setLoadSynchronous(true);
		results = myObservationDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).isEmpty();

	}

	@Test
	public void testSearch_RefParam_TargetForcedId_SearchOnePartition() {
		createUniqueComboSp();

		IIdType patientId = createPatient(withPartition(myPartitionId), withId("ONE"), withGender("male"));
		IIdType observationId = createObservation(withPartition(myPartitionId), withSubject(patientId));

		addReadPartition(myPartitionId);
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Observation.SP_SUBJECT, new ReferenceParam(patientId));
		map.setLoadSynchronous(true);
		IBundleProvider results = myObservationDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).containsExactly(observationId);

		ourLog.info("Search SQL:\n{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false);
		assertThat(StringUtils.countMatches(searchSql.toUpperCase(Locale.US), "PARTITION_ID = '1'")).as(searchSql).isEqualTo(1);
		assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(2);

		// Same query, different partition
		addReadPartition(2);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Observation.SP_SUBJECT, new ReferenceParam(patientId));
		map.setLoadSynchronous(true);
		results = myObservationDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).isEmpty();

	}


	@Test
	public void testSearch_TokenParam_CodeInValueSet() {

		CodeSystem cs = new CodeSystem();
		cs.setUrl("http://cs");
		cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		cs.setContent(CodeSystem.CodeSystemContentMode.COMPLETE);
		cs.addConcept().setCode("A");
		cs.addConcept().setCode("B");
		myCodeSystemDao.create(cs, new SystemRequestDetails());

		ValueSet vs = new ValueSet();
		vs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		vs.setUrl("http://vs");
		vs.getCompose().addInclude().setSystem("http://cs");
		myValueSetDao.create(vs, new SystemRequestDetails());

		createObservation(withPartition(1), withId("OBS1"), withObservationCode("http://cs", "A"));
		createObservation(withPartition(1), withId("OBS2"), withObservationCode("http://cs", "B"));
		createObservation(withPartition(1), withId("OBS3"), withObservationCode("http://cs", "C"));

		logAllTokenIndexes();

		myCaptureQueriesListener.clear();
		addReadPartitions(PARTITION_1);
		SearchParameterMap map = SearchParameterMap.newSynchronous("code", new TokenParam("http://vs").setModifier(TokenParamModifier.IN));
		IBundleProvider outcome = myObservationDao.search(map, mySrd);
		List<String> actual = toUnqualifiedVersionlessIdValues(outcome);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(actual).containsExactlyInAnyOrder("Observation/OBS1", "Observation/OBS2");

	}


	@Test
	public void testSearch_RefParam_TargetForcedId_SearchDefaultPartition() {
		createUniqueComboSp();

		IIdType patientId = createPatient(withPartition(null), withId("ONE"), withGender("male"));
		IIdType observationId = createObservation(withPartition(null), withSubject(patientId));

		addReadDefaultPartition();

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Observation.SP_SUBJECT, new ReferenceParam(patientId));
		map.setLoadSynchronous(true);
		IBundleProvider results = myObservationDao.search(map, mySrd);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids).containsExactly(observationId);

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertThat(StringUtils.countMatches(searchSql.toUpperCase(Locale.US), "PARTITION_ID IS NULL")).as(searchSql).isEqualTo(1);
		assertThat(StringUtils.countMatches(searchSql, "PARTITION_ID")).as(searchSql).isEqualTo(2);

		// Same query, different partition
		addReadPartition(2);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Observation.SP_SUBJECT, new ReferenceParam(patientId));
		map.setLoadSynchronous(true);
		results = myObservationDao.search(map, mySrd);
		ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids).isEmpty();

	}

	@Test
	public void testTransaction_MultipleConditionalUpdates() {
		myStorageSettings.setIndexMissingFields(JpaStorageSettings.IndexEnabledEnum.DISABLED);

		AtomicInteger counter = new AtomicInteger(0);
		Supplier<Bundle> input = () -> {
			BundleBuilder bb = new BundleBuilder(myFhirContext);

			Patient pt = new Patient();
			pt.setId(IdType.newRandomUuid());
			pt.addIdentifier().setSystem("http://foo").setValue("123");
			bb.addTransactionCreateEntry(pt).conditional("Patient?identifier=http://foo|123");

			Observation obsA = new Observation();
			obsA.getSubject().setReference(pt.getId());
			obsA.getCode().addCoding().setSystem("http://foo").setCode("bar1");
			obsA.setValue(new Quantity(null, 1, "http://unitsofmeasure.org", "kg", "kg"));
			obsA.setEffective(new DateTimeType(new Date()));
			obsA.addNote().setText("Foo " + counter.incrementAndGet()); // changes every time
			bb.addTransactionUpdateEntry(obsA).conditional("Observation?code=http://foo|bar1");

			Observation obsB = new Observation();
			obsB.getSubject().setReference(pt.getId());
			obsB.getCode().addCoding().setSystem("http://foo").setCode("bar2");
			obsB.setValue(new Quantity(null, 1, "http://unitsofmeasure.org", "kg", "kg"));
			obsB.setEffective(new DateTimeType(new Date()));
			obsB.addNote().setText("Foo " + counter.incrementAndGet()); // changes every time
			bb.addTransactionUpdateEntry(obsB).conditional("Observation?code=http://foo|bar2");

			Observation obsC = new Observation();
			obsC.getSubject().setReference(pt.getId());
			obsC.getCode().addCoding().setSystem("http://foo").setCode("bar3");
			obsC.setValue(new Quantity(null, 1, "http://unitsofmeasure.org", "kg", "kg"));
			obsC.setEffective(new DateTimeType(new Date()));
			obsC.addNote().setText("Foo " + counter.incrementAndGet()); // changes every time
			bb.addTransactionUpdateEntry(obsC).conditional("Observation?code=bar3");

			Observation obsD = new Observation();
			obsD.getSubject().setReference(pt.getId());
			obsD.getCode().addCoding().setSystem("http://foo").setCode("bar4");
			obsD.setValue(new Quantity(null, 1, "http://unitsofmeasure.org", "kg", "kg"));
			obsD.setEffective(new DateTimeType(new Date()));
			obsD.addNote().setText("Foo " + counter.incrementAndGet()); // changes every time
			bb.addTransactionUpdateEntry(obsD).conditional("Observation?code=bar4");

			return (Bundle) bb.getBundle();
		};

		ourLog.info("About to start transaction");

		for (int i = 0; i < 60; i++) {
			addCreatePartition(1, null);
		}

		// Pre-fetch the partition ID from the partition lookup table
		createPatient(withPartition(1), withActiveTrue());

		myCaptureQueriesListener.clear();
		Bundle outcome = mySystemDao.transaction(mySrd, input.get());
		ourLog.debug("Resp: {}", myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertEquals(2, myCaptureQueriesListener.countSelectQueriesForCurrentThread());
		assertThat(myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false)).contains("rispt1_0.PARTITION_ID in ('1')");
		assertThat(myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false)).contains("rispt1_0.HASH_SYS_AND_VALUE in ('7432183691485874662','-3772330830566471409','-4132452001562191669')");
		myCaptureQueriesListener.logInsertQueriesForCurrentThread();
		assertEquals(45, myCaptureQueriesListener.countInsertQueriesForCurrentThread());
		myCaptureQueriesListener.logUpdateQueriesForCurrentThread();
		assertEquals(4, myCaptureQueriesListener.countUpdateQueriesForCurrentThread());
		assertEquals(0, myCaptureQueriesListener.countDeleteQueriesForCurrentThread());

		/*
		 * Run a second time
		 */

		myCaptureQueriesListener.clear();
		outcome = mySystemDao.transaction(mySrd, input.get());
		ourLog.debug("Resp: {}", myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertEquals(8, myCaptureQueriesListener.countSelectQueriesForCurrentThread());
		myCaptureQueriesListener.logInsertQueriesForCurrentThread();
		assertEquals(4, myCaptureQueriesListener.countInsertQueriesForCurrentThread());
		myCaptureQueriesListener.logUpdateQueriesForCurrentThread();
		assertEquals(8, myCaptureQueriesListener.countUpdateQueriesForCurrentThread());
		assertEquals(4, myCaptureQueriesListener.countDeleteQueriesForCurrentThread());

		/*
		 * Third time with mass ingestion mode enabled
		 */
		myStorageSettings.setMassIngestionMode(true);
		myStorageSettings.setMatchUrlCache(true);

		myCaptureQueriesListener.clear();
		outcome = mySystemDao.transaction(mySrd, input.get());
		ourLog.debug("Resp: {}", myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertEquals(7, myCaptureQueriesListener.countSelectQueriesForCurrentThread());
		myCaptureQueriesListener.logInsertQueriesForCurrentThread();
		assertEquals(4, myCaptureQueriesListener.countInsertQueriesForCurrentThread());
		myCaptureQueriesListener.logUpdateQueriesForCurrentThread();
		assertEquals(8, myCaptureQueriesListener.countUpdateQueriesForCurrentThread());
		assertEquals(0, myCaptureQueriesListener.countDeleteQueriesForCurrentThread());

		/*
		 * Fourth time with mass ingestion mode enabled
		 */

		myCaptureQueriesListener.clear();
		outcome = mySystemDao.transaction(mySrd, input.get());
		ourLog.debug("Resp: {}", myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertEquals(5, myCaptureQueriesListener.countSelectQueriesForCurrentThread());
		myCaptureQueriesListener.logInsertQueriesForCurrentThread();
		assertEquals(4, myCaptureQueriesListener.countInsertQueriesForCurrentThread());
		myCaptureQueriesListener.logUpdateQueriesForCurrentThread();
		assertEquals(8, myCaptureQueriesListener.countUpdateQueriesForCurrentThread());
		assertEquals(0, myCaptureQueriesListener.countDeleteQueriesForCurrentThread());
	}

	@Test
	public void testTransactionWithManyInlineMatchUrls() throws IOException {
		myStorageSettings.setAutoCreatePlaceholderReferenceTargets(true);
		myStorageSettings.setMatchUrlCacheEnabled(true);
		myStorageSettings.setIndexMissingFields(JpaStorageSettings.IndexEnabledEnum.DISABLED);

		SystemRequestDetails requestDetails = new SystemRequestDetails();
		requestDetails.setRequestPartitionId(RequestPartitionId.fromPartitionId(myPartitionId));

		Bundle input = loadResource(myFhirContext, Bundle.class, "/r4/test-patient-bundle.json");

		myCaptureQueriesListener.clear();
		Bundle output = mySystemDao.transaction(requestDetails, input);
		myCaptureQueriesListener.logSelectQueries();

		assertEquals(17, myCaptureQueriesListener.countSelectQueriesForCurrentThread());
		assertEquals(6189, myCaptureQueriesListener.countInsertQueriesForCurrentThread());
		assertEquals(418, myCaptureQueriesListener.countUpdateQueriesForCurrentThread());
		assertEquals(0, myCaptureQueriesListener.countDeleteQueriesForCurrentThread());
		assertEquals(1, myCaptureQueriesListener.countCommits());
		assertEquals(0, myCaptureQueriesListener.countRollbacks());

		assertThat(output.getEntry()).hasSize(input.getEntry().size());

		runInTransaction(()->{
			assertEquals(437, myResourceTableDao.count());
			assertEquals(437, myResourceHistoryTableDao.count());
		});

		/*
		 * Run a second time
		 */

		requestDetails = new SystemRequestDetails();
		requestDetails.setRequestPartitionId(RequestPartitionId.fromPartitionId(myPartitionId));

		input = loadResource(myFhirContext, Bundle.class, "/r4/test-patient-bundle.json");

		myCaptureQueriesListener.clear();
		output = mySystemDao.transaction(requestDetails, input);
		myCaptureQueriesListener.logSelectQueries();

		assertEquals(26, myCaptureQueriesListener.countSelectQueriesForCurrentThread());
		assertEquals(0, myCaptureQueriesListener.countInsertQueriesForCurrentThread());
		assertEquals(326, myCaptureQueriesListener.countUpdateQueriesForCurrentThread());
		assertEquals(326, myCaptureQueriesListener.countDeleteQueriesForCurrentThread());
		assertEquals(1, myCaptureQueriesListener.countCommits());
		assertEquals(0, myCaptureQueriesListener.countRollbacks());

		assertThat(output.getEntry()).hasSize(input.getEntry().size());

		runInTransaction(()->{
			assertEquals(437, myResourceTableDao.count());
			assertEquals(437, myResourceHistoryTableDao.count());
		});

	}


	/**
	 * JA: I disabled this test - I am not clear on what it was actually trying to test
	 */
	@Test
	@Disabled
	public void testUpdate_ResourcePreExistsInWrongPartition() {
		IIdType patientId = createPatient(withPartition(null), withId("ONE"), withBirthdate("2020-01-01"));

		addCreatePartition(1);

		Patient patient = new Patient();
		patient.setId(patientId.toUnqualifiedVersionless());
		patient.setGender(Enumerations.AdministrativeGender.MALE);
		myPatientDao.update(patient, mySrd);
	}

	@Test
	public void testHistory_Instance_CorrectPartition() {
		IIdType id = createPatient(withPartition(1), withBirthdate("2020-01-01"));

		// Update the patient
		addCreatePartition(myPartitionId);
		Patient patient = new Patient();
		patient.setActive(false);
		patient.setId(id);
		myPatientDao.update(patient, mySrd);

		addReadPartition(1);
		myCaptureQueriesListener.clear();
		IBundleProvider results = myPatientDao.history(id, null, null, null, mySrd);
		assertEquals(2, results.sizeOrThrowNpe());
		List<String> ids = toUnqualifiedIdValues(results);
		assertThat(ids).containsExactly(id.withVersion("2").getValue(), id.withVersion("1").getValue());

		assertThat(myCaptureQueriesListener.getSelectQueriesForCurrentThread()).hasSize(3);

		// Resolve resource
		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("SQL:{}", searchSql);
		assertThat(countMatches(searchSql, "PARTITION_ID=")).as(searchSql).isEqualTo(1);

		// Fetch history resource
		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(1).getSql(true, true);
		ourLog.info("SQL:{}", searchSql);
		assertThat(countMatches(searchSql, "rht1_0.PARTITION_ID in")).as(searchSql).isEqualTo(1);

		// Fetch history resource
		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(2).getSql(true, true);
		ourLog.info("SQL:{}", searchSql);
		assertThat(countMatches(searchSql, "rht1_0.PARTITION_ID in")).as(searchSql.replace(" ", "").toUpperCase()).isEqualTo(1);
	}

	@Test
	public void testHistory_Instance_WrongPartition() {
		IIdType id = createPatient(withPartition(1), withBirthdate("2020-01-01"));

		// Update the patient
		addCreatePartition(myPartitionId);
		Patient p = new Patient();
		p.setActive(false);
		p.setId(id);
		myPatientDao.update(p, mySrd);

		addReadPartition(2);
		try {
			myPatientDao.history(id, null, null, null, mySrd);
			fail();
		} catch (ResourceNotFoundException e) {
			// good
		}
	}

	@Test
	public void testHistory_Instance_DefaultPartition() {
		IIdType id = createPatient(withPartition(null), withBirthdate("2020-01-01"));

		// Update the patient
		addCreateDefaultPartition();
		Patient patient = new Patient();
		patient.setActive(false);
		patient.setId(id);
		myPatientDao.update(patient, mySrd);

		addReadDefaultPartition();
		myCaptureQueriesListener.clear();
		IBundleProvider results = myPatientDao.history(id, null, null, null, mySrd);
		assertEquals(2, results.sizeOrThrowNpe());
		List<String> ids = toUnqualifiedIdValues(results);
		assertThat(ids).containsExactly(id.withVersion("2").getValue(), id.withVersion("1").getValue());

		assertThat(myCaptureQueriesListener.getSelectQueriesForCurrentThread()).hasSize(3);

		// Fetch history resource
		String sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("SQL:{}", sql);
		assertEquals(0, countMatches(sql, "PARTITION_ID="));

		// Fetch history resource
		sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(1).getSql(true, true);
		ourLog.info("SQL:{}", sql);
		assertEquals(0, countMatches(sql, "PARTITION_ID="));

		// Fetch history resource
		sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(2).getSql(true, true);
		ourLog.info("SQL:{}", sql);
		assertEquals(0, countMatches(sql, "PARTITION_ID="));
	}

	@Test
	public void testHistory_Instance_AllPartitions() {
		IIdType id = createPatient(withPartition(1), withBirthdate("2020-01-01"));

		// Update the patient
		addCreatePartition(myPartitionId);
		Patient patient = new Patient();
		patient.setActive(false);
		patient.setId(id);
		myPatientDao.update(patient, mySrd);

		addReadAllPartitions();
		myCaptureQueriesListener.clear();
		IBundleProvider results = myPatientDao.history(id, null, null, null, mySrd);
		assertEquals(2, results.sizeOrThrowNpe());
		List<String> ids = toUnqualifiedIdValues(results);
		assertThat(ids).containsExactly(id.withVersion("2").getValue(), id.withVersion("1").getValue());
	}

	@Test
	public void testHistory_Server() {
		addReadAllPartitions();
		try {
			mySystemDao.history(null, null, null, mySrd).size();
			fail();
		} catch (InvalidRequestException e) {
			assertEquals(Msg.code(953) + "Type- and Server- level history operation not supported across partitions on partitioned server", e.getMessage());
		}
	}

	@Test
	public void testHistory_Server_SpecificPartition() {
		IIdType id1A = createPatient(withPartition(1), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		IIdType id1B = createPatient(withPartition(1), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		createPatient(withPartition(2), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		createPatient(withPartition(2), withBirthdate("2020-01-01"));

		addReadPartition(1);
		myCaptureQueriesListener.clear();
		IBundleProvider results = mySystemDao.history(null, null, null, mySrd);
		assertEquals(2, results.sizeOrThrowNpe());
		List<String> ids = toUnqualifiedIdValues(results);
		assertThat(ids).containsExactly(id1B.withVersion("1").getValue(), id1A.withVersion("1").getValue());

		assertThat(myCaptureQueriesListener.getSelectQueriesForCurrentThread()).hasSize(2);

		// Count
		ourLog.info("SQL:{}", myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true));
		String sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false).toUpperCase();
		assertThat(countMatches(sql, "COUNT(")).as(sql).isEqualTo(1);
		assertThat(countMatches(sql, "RHT1_0.PARTITION_ID IN ('1')")).as(sql).isEqualTo(1);

		// Fetch history
		sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(1).getSql(true, false).toUpperCase();
		ourLog.info("SQL:{}", sql);
		assertThat(countMatches(sql, "RHT1_0.PARTITION_ID IN ('1')")).as(sql).isEqualTo(1);

	}

	@Test
	public void testHistory_Server_DefaultPartition() {
		IIdType id1A = createPatient(withPartition(null), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		IIdType id1B = createPatient(withPartition(null), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		createPatient(withPartition(2), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		createPatient(withPartition(2), withBirthdate("2020-01-01"));

		addReadDefaultPartition();
		myCaptureQueriesListener.clear();
		IBundleProvider results = mySystemDao.history(null, null, null, mySrd);
		assertEquals(2, results.sizeOrThrowNpe());
		List<String> ids = toUnqualifiedIdValues(results);
		assertThat(ids).containsExactly(id1B.withVersion("1").getValue(), id1A.withVersion("1").getValue());

		assertThat(myCaptureQueriesListener.getSelectQueriesForCurrentThread()).hasSize(2);

		// Count
		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("SQL:{}", searchSql);
		assertThat(countMatches(searchSql, "rht1_0.PARTITION_ID is null")).as(searchSql).isEqualTo(1);

		// Fetch history resource
		searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(1).getSql(true, true);
		ourLog.info("SQL:{}", searchSql);
		assertThat(countMatches(searchSql, "PARTITION_ID=")).as(searchSql.replace(" ", "").toUpperCase()).isEqualTo(0);
		assertThat(countMatches(searchSql, "PARTITION_IDIN")).as(searchSql.replace(" ", "").toUpperCase()).isEqualTo(0);
	}

	@Test
	public void testHistory_Server_MultiplePartitions() {
		String idNull1 = createPatient(withPartition(null), withBirthdate("2020-01-01")).toUnqualifiedVersionless().getValue();
		sleepAtLeast(10);
		String idNull2 = createPatient(withPartition(null), withBirthdate("2020-01-01")).toUnqualifiedVersionless().getValue();
		sleepAtLeast(10);
		String id21 = createPatient(withPartition(2), withBirthdate("2020-01-01")).toUnqualifiedVersionless().getValue();
		sleepAtLeast(10);
		String id31 = createPatient(withPartition(3), withBirthdate("2020-01-01")).toUnqualifiedVersionless().getValue();
		sleepAtLeast(10);
		String id22 = createPatient(withPartition(2), withBirthdate("2020-01-01")).toUnqualifiedVersionless().getValue();
		sleepAtLeast(10);
		String id32 = createPatient(withPartition(3), withBirthdate("2020-01-01")).toUnqualifiedVersionless().getValue();

		// Multiple Partitions
		{
			addReadPartition(2, null);
			myCaptureQueriesListener.clear();
			IBundleProvider results = mySystemDao.history(null, null, null, mySrd);
			assertEquals(4, results.sizeOrThrowNpe());
			List<String> ids = toUnqualifiedVersionlessIdValues(results);
			assertThat(ids).containsExactly(id22, id21, idNull2, idNull1);
		}

		// Multiple Partitions With Null
		{
			addReadPartition(2, 3);
			myCaptureQueriesListener.clear();
			IBundleProvider results = mySystemDao.history(null, null, null, mySrd);
			assertEquals(4, results.sizeOrThrowNpe());
			List<String> ids = toUnqualifiedVersionlessIdValues(results);
			assertThat(ids).containsExactly(id32, id22, id31, id21);
		}

	}

	@Test
	public void testHistory_Type_AllPartitions() {
		addReadAllPartitions();
		try {
			myPatientDao.history(null, null, null, mySrd).size();
			fail();
		} catch (InvalidRequestException e) {
			assertEquals(Msg.code(953) + "Type- and Server- level history operation not supported across partitions on partitioned server", e.getMessage());
		}
	}

	@Test
	public void testHistory_Type_SpecificPartition() {
		IIdType id1A = createPatient(withPartition(1), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		IIdType id1B = createPatient(withPartition(1), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		createPatient(withPartition(2), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		createPatient(withPartition(2), withBirthdate("2020-01-01"));

		addReadPartition(1);
		myCaptureQueriesListener.clear();
		IBundleProvider results = myPatientDao.history(null, null, null, mySrd);
		assertEquals(2, results.sizeOrThrowNpe());
		List<String> ids = toUnqualifiedIdValues(results);
		assertThat(ids).containsExactly(id1B.withVersion("1").getValue(), id1A.withVersion("1").getValue());

		assertThat(myCaptureQueriesListener.getSelectQueriesForCurrentThread()).hasSize(2);

		// Count
		String sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, false).toUpperCase();
		ourLog.info("SQL:{}", sql);
		assertThat(countMatches(sql, "COUNT(")).as(sql).isEqualTo(1);
		assertThat(countMatches(sql, "RHT1_0.PARTITION_ID IN ('1')")).as(sql).isEqualTo(1);

		// History
		sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(1).getSql(true, false).toUpperCase();
		ourLog.info("SQL:{}", sql);
		assertThat(countMatches(sql, "RHT1_0.PARTITION_ID IN ('1')")).as(sql).isEqualTo(1);
	}


	@Test
	public void testHistory_Type_DefaultPartition() {
		IIdType id1A = createPatient(withPartition(null), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		IIdType id1B = createPatient(withPartition(null), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		createPatient(withPartition(2), withBirthdate("2020-01-01"));
		sleepAtLeast(10);
		createPatient(withPartition(2), withBirthdate("2020-01-01"));

		addReadDefaultPartition();
		myCaptureQueriesListener.clear();
		IBundleProvider results = myPatientDao.history(null, null, null, mySrd);
		assertEquals(2, results.sizeOrThrowNpe());
		List<String> ids = toUnqualifiedIdValues(results);
		assertThat(ids).containsExactly(id1B.withVersion("1").getValue(), id1A.withVersion("1").getValue());

		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(myCaptureQueriesListener.getSelectQueriesForCurrentThread()).hasSize(2);

		// Resolve resource
		String sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true).toUpperCase();
		assertThat(countMatches(sql, "RHT1_0.PARTITION_ID IS NULL")).as(sql).isEqualTo(1);
		assertEquals(1, countMatches(sql, "PARTITION_ID"));

		// Fetch history resource
		sql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(1).getSql(true, true).toUpperCase();
		assertThat(countMatches(sql, "RHT1_0.PARTITION_ID IS NULL")).as(sql).isEqualTo(1);

	}

	@Test
	public void testReindexPartitionedServer() {
		IIdType patientIdNull = createPatient(withPartition(null), withActiveTrue());
		IIdType patientId1 = createPatient(withPartition(1), withActiveTrue());

		myResourceReindexingSvc.markAllResourcesForReindexing();
		myResourceReindexingSvc.forceReindexingPass();

		runInTransaction(() -> {
			assertNotEquals(BaseHapiFhirDao.INDEX_STATUS_INDEXING_FAILED, myResourceTableDao.findById(patientIdNull.getIdPartAsLong()).get().getIndexStatus());
			assertNotEquals(BaseHapiFhirDao.INDEX_STATUS_INDEXING_FAILED, myResourceTableDao.findById(patientId1.getIdPartAsLong()).get().getIndexStatus());
		});
	}

	@Test
	public void testPartitionNotify() {
		IAnonymousInterceptor interceptor = mock(IAnonymousInterceptor.class);
		myInterceptorRegistry.registerAnonymousInterceptor(Pointcut.STORAGE_PARTITION_SELECTED, interceptor);
		try {
			createPatient(withPartition(1), withBirthdate("2020-01-01"));

			ArgumentCaptor<HookParams> captor = ArgumentCaptor.forClass(HookParams.class);
			verify(interceptor, times(1)).invoke(eq(Pointcut.STORAGE_PARTITION_SELECTED), captor.capture());

			RequestPartitionId partitionId = captor.getValue().get(RequestPartitionId.class);
			assertEquals(1, partitionId.getPartitionIds().get(0).intValue());
			assertEquals("PART-1", partitionId.getPartitionNames().get(0));
			assertEquals("Patient", captor.getValue().get(RuntimeResourceDefinition.class).getName());

		} finally {
			myInterceptorRegistry.unregisterInterceptor(interceptor);
		}
	}

	/**
	 * H2 gets a bit tripped up by our changing the system TZ during test
	 * execution, so we can have an off by one error.. but as long as it's close
	 * this is definitely working
	 */
	public static void assertLocalDateFromDbMatches(LocalDate theExpected, LocalDate theActual) {
		LocalDate expMinus1 = theExpected.minusDays(1);
		LocalDate expMinus2 = theExpected.minusDays(2);
		LocalDate expPlus1 = theExpected.plusDays(1);
		assertThat(theActual)
			.satisfiesAnyOf(
				arg -> assertThat(arg).isEqualTo(theExpected),
				arg -> assertThat(arg).isEqualTo(expMinus1),
				arg -> assertThat(arg).isEqualTo(expMinus2),
				arg -> assertThat(arg).isEqualTo(expPlus1)
			);
	}

}
