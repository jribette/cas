package org.apereo.cas.support.pac4j.authentication.clients;

import org.apereo.cas.authentication.CasSSLContext;
import org.apereo.cas.authentication.principal.ClientCustomPropertyConstants;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.support.pac4j.Pac4jBaseClientProperties;
import org.apereo.cas.pac4j.client.DelegatedIdentityProviderFactory;
import org.apereo.cas.util.RandomUtils;
import org.apereo.cas.util.concurrent.CasReentrantLock;
import org.apereo.cas.util.function.FunctionUtils;
import org.apereo.cas.web.flow.CasWebflowConfigurer;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.pac4j.cas.client.CasClient;
import org.pac4j.cas.config.CasConfiguration;
import org.pac4j.cas.config.CasProtocol;
import org.pac4j.core.client.BaseClient;
import org.pac4j.core.client.IndirectClient;
import org.pac4j.core.http.callback.NoParameterCallbackUrlResolver;
import org.pac4j.core.http.callback.PathParameterCallbackUrlResolver;
import org.pac4j.core.http.callback.QueryParameterCallbackUrlResolver;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This is {@link BaseDelegatedIdentityProviderFactory}.
 *
 * @author Misagh Moayyed
 * @since 6.2.0
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseDelegatedIdentityProviderFactory implements DelegatedIdentityProviderFactory {
    private static final Pattern PATTERN_LOGIN_URL = Pattern.compile('/' + CasWebflowConfigurer.FLOW_ID_LOGIN + '$');

    protected final CasConfigurationProperties casProperties;

    private final CasReentrantLock lock = new CasReentrantLock();

    private final Collection<DelegatedClientFactoryCustomizer> customizers;

    private final CasSSLContext casSSLContext;

    private final Cache<String, Collection<BaseClient>> clientsCache;

    private final ConfigurableApplicationContext applicationContext;

    protected abstract Collection<BaseClient> loadIdentityProviders() throws Exception;

    @Override
    public final Collection<BaseClient> build() {
        return lock.tryLock(() -> {
            val core = casProperties.getAuthn().getPac4j().getCore();
            val currentClients = getCachedClients().isEmpty() || !core.isLazyInit() ? loadIdentityProviders() : getCachedClients();
            clientsCache.put(casProperties.getServer().getName(), currentClients);
            return currentClients;
        });
    }

    @Override
    public Collection<BaseClient> rebuild() {
        clientsCache.invalidateAll();
        return build();
    }

    protected Collection<BaseClient> getCachedClients() {
        val cachedClients = clientsCache.getIfPresent(casProperties.getServer().getName());
        return ObjectUtils.defaultIfNull(cachedClients, new ArrayList<>());
    }

    protected BaseClient configureClient(final BaseClient client,
                                         final Pac4jBaseClientProperties clientProperties,
                                         final CasConfigurationProperties givenProperties) {
        if (clientProperties != null) {
            val cname = clientProperties.getClientName();
            if (StringUtils.isNotBlank(cname)) {
                client.setName(cname);
            } else {
                val className = client.getClass().getSimpleName();
                val genName = className.concat(RandomUtils.randomNumeric(4));
                client.setName(genName);
                LOGGER.warn("Client name for [{}] is set to a generated value of [{}]. "
                    + "Consider defining an explicit name for the delegated provider", className, genName);
            }
            val customProperties = client.getCustomProperties();
            customProperties.put(ClientCustomPropertyConstants.CLIENT_CUSTOM_PROPERTY_AUTO_REDIRECT_TYPE, clientProperties.getAutoRedirectType());

            FunctionUtils.doIfNotBlank(clientProperties.getPrincipalIdAttribute(),
                __ -> customProperties.put(ClientCustomPropertyConstants.CLIENT_CUSTOM_PROPERTY_PRINCIPAL_ATTRIBUTE_ID, clientProperties.getPrincipalIdAttribute()));
            FunctionUtils.doIfNotBlank(clientProperties.getCssClass(),
                __ -> customProperties.put(ClientCustomPropertyConstants.CLIENT_CUSTOM_PROPERTY_CSS_CLASS, clientProperties.getCssClass()));
            FunctionUtils.doIfNotBlank(clientProperties.getDisplayName(),
                __ -> customProperties.put(ClientCustomPropertyConstants.CLIENT_CUSTOM_PROPERTY_DISPLAY_NAME, clientProperties.getDisplayName()));
            if (client instanceof final IndirectClient indirectClient) {
                val callbackUrl = StringUtils.defaultIfBlank(clientProperties.getCallbackUrl(), casProperties.getServer().getLoginUrl());
                indirectClient.setCallbackUrl(callbackUrl);
                LOGGER.trace("Client [{}] will use the callback URL [{}]", client.getName(), callbackUrl);
                val resolver = switch (clientProperties.getCallbackUrlType()) {
                    case PATH_PARAMETER -> new PathParameterCallbackUrlResolver();
                    case NONE -> new NoParameterCallbackUrlResolver();
                    case QUERY_PARAMETER -> new QueryParameterCallbackUrlResolver();
                };
                indirectClient.setCallbackUrlResolver(resolver);
            }
        }
        customizers.forEach(customizer -> customizer.customize(client));
        if (!givenProperties.getAuthn().getPac4j().getCore().isLazyInit()) {
            client.init();
        }
        LOGGER.debug("Configured external identity provider [{}]", client.getName());
        return client;
    }
    
    protected Collection<IndirectClient> buildCasIdentityProviders(final CasConfigurationProperties casProperties) {
        val pac4jProperties = casProperties.getAuthn().getPac4j();
        return pac4jProperties
            .getCas()
            .stream()
            .filter(cas -> cas.isEnabled() && StringUtils.isNotBlank(cas.getLoginUrl()))
            .map(cas -> {
                val cfg = new CasConfiguration(cas.getLoginUrl(), CasProtocol.valueOf(cas.getProtocol()));
                val prefix = PATTERN_LOGIN_URL.matcher(cas.getLoginUrl()).replaceFirst("/");
                cfg.setPrefixUrl(StringUtils.appendIfMissing(prefix, "/"));
                cfg.setHostnameVerifier(casSSLContext.getHostnameVerifier());
                cfg.setSslSocketFactory(casSSLContext.getSslContext().getSocketFactory());

                val client = new CasClient(cfg);
                configureClient(client, cas, casProperties);
                LOGGER.debug("Created client [{}]", client);
                return client;
            })
            .collect(Collectors.toList());
    }

    protected Set<BaseClient> buildAllIdentityProviders(final CasConfigurationProperties properties) throws Exception {
        val newClients = new LinkedHashSet<BaseClient>(buildCasIdentityProviders(properties));
        val builders = getDelegatedClientBuilders();
        for (val builder : builders) {
            val builtClients = builder.build(properties);
            LOGGER.debug("Builder [{}] provides [{}] clients", builder.getName(), builtClients.size());
            builtClients.forEach(instance -> {
                val preparedClient = configureClient(instance.getClient(), instance.getProperties(), properties);
                newClients.add(builder.configure(preparedClient, instance.getProperties(), properties));
            });
        }

        return newClients;
    }

    private List<ConfigurableDelegatedClientBuilder> getDelegatedClientBuilders() {
        val builders = new ArrayList<>(applicationContext.getBeansOfType(ConfigurableDelegatedClientBuilder.class).values());
        AnnotationAwareOrderComparator.sort(builders);
        return builders;
    }
}
