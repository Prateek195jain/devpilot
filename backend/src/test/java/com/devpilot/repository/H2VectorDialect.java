package com.devpilot.repository;

import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.ArrayJdbcType;
import org.hibernate.type.descriptor.jdbc.FloatJdbcType;

/** H2-only storage stand-in. Actual pgvector behavior is tested against PostgreSQL. */
public class H2VectorDialect extends H2Dialect {
    @Override
    public void contributeTypes(TypeContributions contributions, ServiceRegistry services) {
        super.contributeTypes(contributions, services);
        contributions.getTypeConfiguration().getJdbcTypeRegistry().addDescriptor(
                SqlTypes.VECTOR, new ArrayJdbcType(FloatJdbcType.INSTANCE));
    }
}
