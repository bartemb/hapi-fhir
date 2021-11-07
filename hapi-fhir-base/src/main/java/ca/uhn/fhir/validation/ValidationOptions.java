package ca.uhn.fhir.validation;

/*-
 * #%L
 * HAPI FHIR - Core Library
 * %%
 * Copyright (C) 2014 - 2021 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.apache.commons.lang3.Validate;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class ValidationOptions {

	public static final int DEFAULT_BUNDLE_VALIDATION_THREADCOUNT = 1;
	private static ValidationOptions ourEmpty;
	private Set<String> myProfiles;

	// FIXME KHS make it clear in the docs that bundle structure is not validated when this is true
	private boolean myConcurrentBundleValidation;
	private int myBundleValidationThreadCount = DEFAULT_BUNDLE_VALIDATION_THREADCOUNT;

	public Set<String> getProfiles() {
		return myProfiles != null ? Collections.unmodifiableSet(myProfiles) : Collections.emptySet();
	}

	public ValidationOptions addProfile(String theProfileUri) {
		Validate.notBlank(theProfileUri);

		if (myProfiles == null) {
			myProfiles = new HashSet<>();
		}
		myProfiles.add(theProfileUri);
		return this;
	}

	public ValidationOptions addProfileIfNotBlank(String theProfileUri) {
		if (isNotBlank(theProfileUri)) {
			return addProfile(theProfileUri);
		}
		return this;
	}

	/**
	 * If this is true, bundles will be validated in parallel threads.  The bundle structure itself will not be validated,
	 * only the resources in its entries.
	 */

	public boolean isConcurrentBundleValidation() {
		return myConcurrentBundleValidation;
	}

	/**
	 * If this is true, bundles will be validated in parallel threads.  The bundle structure itself will not be validated,
	 * only the resources in its entries.
	 */
	public ValidationOptions setConcurrentBundleValidation(boolean theConcurrentBundleValidation) {
		myConcurrentBundleValidation = theConcurrentBundleValidation;
		return this;
	}

	/**
	 * The number of threads bundle entries will be validated within.  This is only used when
	 * {@link #isConcurrentBundleValidation} is true.
	 */
	public int getBundleValidationThreadCount() {
		return myBundleValidationThreadCount;
	}

	/**
	 * The number of threads bundle entries will be validated within.  This is only used when
	 * {@link #isConcurrentBundleValidation} is true.
	 */
	public ValidationOptions setBundleValidationThreadCount(int theBundleValidationThreadCount) {
		myBundleValidationThreadCount = theBundleValidationThreadCount;
		return this;
	}

	public static ValidationOptions empty() {
		ValidationOptions retVal = ourEmpty;
		if (retVal == null) {
			retVal = new ValidationOptions();
			retVal.myProfiles = Collections.emptySet();
			ourEmpty = retVal;
		}
		return retVal;
	}

}
