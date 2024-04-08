/*
 * #%L
 * HAPI FHIR JPA Model
 * %%
 * Copyright (C) 2014 - 2024 Smile CDR, Inc.
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
package ca.uhn.fhir.jpa.model.entity;

import ca.uhn.fhir.model.api.IQueryParameterType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

@Embeddable
@Entity
@Table(
		name = "HFJ_SPIDX_COORDS",
		indexes = {
			@Index(
					name = "IDX_SP_COORDS_HASH_V2",
					columnList = "HASH_IDENTITY,SP_LATITUDE,SP_LONGITUDE,RES_ID,PARTITION_ID"),
			@Index(name = "IDX_SP_COORDS_UPDATED", columnList = "SP_UPDATED"),
			@Index(name = "IDX_SP_COORDS_RESID", columnList = "RES_ID")
		})
public class ResourceIndexedSearchParamCoords extends BaseResourceIndexedSearchParam {

	public static final int MAX_LENGTH = 100;

	private static final long serialVersionUID = 1L;

	@Column(name = "SP_LATITUDE")
	// @FullTextField
	public double myLatitude;

	@Column(name = "SP_LONGITUDE")
	// @FullTextField
	public double myLongitude;

	@Id
	@SequenceGenerator(name = "SEQ_SPIDX_COORDS", sequenceName = "SEQ_SPIDX_COORDS")
	@GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_SPIDX_COORDS")
	@Column(name = "SP_ID")
	private Long myId;

	@ManyToOne(
			optional = false,
			fetch = FetchType.LAZY,
			cascade = {})
	@JoinColumn(
			foreignKey = @ForeignKey(name = "FKC97MPK37OKWU8QVTCEG2NH9VN"),
			name = "RES_ID",
			referencedColumnName = "RES_ID",
			nullable = false)
	private ResourceTable myResource;

	public ResourceIndexedSearchParamCoords() {}

	public ResourceIndexedSearchParamCoords(
			String theResourceType, String theParamName, double theLatitude, double theLongitude) {
		setResourceType(theResourceType);
		setParamName(theParamName);
		setLatitude(theLatitude);
		setLongitude(theLongitude);
	}

	@Override
	public boolean equals(Object theObj) {
		if (this == theObj) {
			return true;
		}
		if (theObj == null) {
			return false;
		}
		if (!(theObj instanceof ResourceIndexedSearchParamCoords)) {
			return false;
		}
		ResourceIndexedSearchParamCoords obj = (ResourceIndexedSearchParamCoords) theObj;
		EqualsBuilder b = new EqualsBuilder();
		b.append(getResourceType(), obj.getResourceType());
		b.append(getParamName(), obj.getParamName());
		b.append(isMissing(), obj.isMissing());
		b.append(getContainedOrd(), obj.getContainedOrd());
		b.append(getPartitionId(), obj.getPartitionId());
		b.append(getLatitude(), obj.getLatitude());
		b.append(getLongitude(), obj.getLongitude());
		return b.isEquals();
	}

	@Override
	public <T extends BaseResourceIndex> void copyMutableValuesFrom(T theSource) {
		super.copyMutableValuesFrom(theSource);

		ResourceIndexedSearchParamCoords source = (ResourceIndexedSearchParamCoords) theSource;
		myLatitude = source.getLatitude();
		myLongitude = source.getLongitude();
	}

	@Override
	public Long getId() {
		return myId;
	}

	@Override
	public void setId(Long theId) {
		myId = theId;
	}

	public double getLatitude() {
		return myLatitude;
	}

	public ResourceIndexedSearchParamCoords setLatitude(double theLatitude) {
		myLatitude = theLatitude;
		return this;
	}

	public double getLongitude() {
		return myLongitude;
	}

	public ResourceIndexedSearchParamCoords setLongitude(double theLongitude) {
		myLongitude = theLongitude;
		return this;
	}

	@Override
	public int hashCode() {
		HashCodeBuilder b = new HashCodeBuilder();
		b.append(getResourceType());
		b.append(getParamName());
		b.append(isMissing());
		b.append(getContainedOrd());
		b.append(getPartitionId());
		b.append(getLatitude());
		b.append(getLongitude());
		return b.toHashCode();
	}

	@Override
	public IQueryParameterType toQueryParameterType() {
		return null;
	}

	@Override
	public String toString() {
		ToStringBuilder b = new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE);
		b.append("resourceId", getResourcePid());
		b.append("resourceType", getResourceType());
		b.append("paramName", getParamName());
		b.append("missing", isMissing());
		b.append("containedOrd", getContainedOrd());
		b.append("partitionId", getPartitionId());
		b.append("lat", getLatitude());
		b.append("lon", getLongitude());
		return b.build();
	}

	@Override
	public ResourceTable getResource() {
		return myResource;
	}

	@Override
	public BaseResourceIndexedSearchParam setResource(ResourceTable theResource) {
		myResource = theResource;
		setResourceType(theResource.getResourceType());
		return this;
	}
}
