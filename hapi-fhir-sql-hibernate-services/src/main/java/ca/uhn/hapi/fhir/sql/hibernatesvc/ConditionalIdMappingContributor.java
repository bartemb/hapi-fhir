package ca.uhn.hapi.fhir.sql.hibernatesvc;

import org.hibernate.boot.ResourceStreamLocator;
import org.hibernate.boot.spi.AdditionalMappingContributions;
import org.hibernate.boot.spi.InFlightMetadataCollector;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.internal.util.collections.IdentitySet;
import org.hibernate.mapping.BasicValue;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Component;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.PrimaryKey;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.Table;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConditionalIdMappingContributor implements org.hibernate.boot.spi.AdditionalMappingContributor {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ConditionalIdMappingContributor.class);

	/**
	 * Constructor
	 */
	public ConditionalIdMappingContributor() {
		super();
	}

	@Override
	public String getContributorName() {
		return "PkCleaningMappingContributor";
	}

	@Override
	public void contribute(
		AdditionalMappingContributions theContributions,
		InFlightMetadataCollector theMetadata,
		ResourceStreamLocator theResourceStreamLocator,
		MetadataBuildingContext theBuildingContext) {

		HapiHibernateDialectSettingsService hapiSettingsSvc = theMetadata.getBootstrapContext().getServiceRegistry().getService(HapiHibernateDialectSettingsService.class);
		assert hapiSettingsSvc != null;
		if (!hapiSettingsSvc.isTrimConditionalIdsFromPrimaryKeys()) {
			return;
		}

		removeConditionalIdProperties(theMetadata);

		for (var nextEntry : theMetadata.getEntityBindingMap().entrySet()) {
			PersistentClass persistentClass = nextEntry.getValue();
			Table table = persistentClass.getTable();
			for (ForeignKey foreignKey : table.getForeignKeys().values()) {
				foreignKey.getColumns().removeIf(t -> t.getName().equals("PARENT_PARTITION_ID"));
//				List<Column> referencedColumns = foreignKey.getReferencedColumns();
//				for (int i = 0; i < referencedColumns.size(); i++) {
//					Column referencedColumn = referencedColumns.get(i);
//					if (myIdRemovedColumns.contains(referencedColumn)) {
//						referencedColumns.remove(i);
//						i--;
//					}
//				}
			}
		}


	}

	private void removeConditionalIdProperties(InFlightMetadataCollector theMetadata) {
		for (var nextEntry : theMetadata.getEntityBindingMap().entrySet()) {
			IdentitySet<Column> idRemovedColumns = new IdentitySet<>();
			Set<String> idRemovedProperties = new HashSet<>();

			Class<?> nextType;
			try {
				nextType = Class.forName(nextEntry.getKey());
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}

			PersistentClass next = nextEntry.getValue();
			if (next.getIdentifier() instanceof BasicValue) {
				continue;
			}

			Component identifier = (Component) next.getIdentifier();
			List<Property> properties = identifier.getProperties();
			for (int i = 0; i < properties.size(); i++) {
				Property property = properties.get(i);
				String fieldName = property.getName();
				Field field;
				try {
					field = nextType.getDeclaredField(fieldName);
				} catch (NoSuchFieldException e) {
					throw new IllegalStateException(e);
				}

				ConditionalIdProperty remove = field.getAnnotation(ConditionalIdProperty.class);
				if (remove != null) {
					Property removedProperty = properties.remove(i);
					idRemovedColumns.addAll(removedProperty.getColumns());
					idRemovedProperties.add(removedProperty.getName());
					i--;
				}
			}

			if (idRemovedColumns.isEmpty()) {
				return;
			}

			next.getIdentifierMapper().getProperties().removeIf(t -> idRemovedProperties.contains(t.getName()));

			Table table = next.getTable();
			PrimaryKey pk = table.getPrimaryKey();
			List<Column> pkColumns = pk.getColumns();
			pkColumns.removeIf(t -> idRemovedColumns.contains(t));

		}
	}
}
