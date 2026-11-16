package io.github.gmshield.shared.data;

import io.github.gmshield.shared.service.OwnerContext;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The owner of every library query (AD-11): Hibernate asks this resolver for the tenant when it opens a
 * session, and filters and fills each {@code @TenantId} column with the answer. The answer is the
 * {@link OwnerContext}'s owner. Without one it throws, so no session opens and no query runs (fail closed);
 * no tenant is ever a root that sees every owner's rows.
 *
 * <p>The rule reaches beyond library queries: the JPA transaction manager is the application's only
 * {@code PlatformTransactionManager}, and every transaction it begins ({@code TransactionTemplate} or
 * {@code @Transactional}) opens a Hibernate session first. Without a bound owner such a transaction fails
 * with {@link OwnerContext.NoOwnerException} before it starts. Work that has no owner, such as the backend's
 * own job writes (Story 1.12), must either bind an owner with {@link OwnerContext#callAs} or run its JDBC
 * writes under a separate JDBC transaction manager on its own connection, as {@code vault.data.JdbcDataKeyStore}
 * does.
 */
public class OwnerTenantResolver implements CurrentTenantIdentifierResolver<UUID> {

    private static final Logger log = LoggerFactory.getLogger(OwnerTenantResolver.class);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        try {
            return OwnerContext.require();
        } catch (OwnerContext.NoOwnerException missing) {
            log.atWarn().log("owner.missing");
            throw missing;
        }
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return false;
    }

    /** Hands the resolver to Hibernate when JPA is configured (never in the unit tests, which have no database). */
    @Configuration(proxyBeanMethods = false)
    static class Registration {

        @Bean
        static HibernatePropertiesCustomizer ownerTenantResolver() {
            OwnerTenantResolver resolver = new OwnerTenantResolver();
            return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
        }
    }
}
