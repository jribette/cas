package org.apereo.cas.impl.plans;

import org.apereo.cas.api.AuthenticationRiskContingencyPlan;
import org.apereo.cas.api.AuthenticationRiskContingencyResponse;
import org.apereo.cas.api.AuthenticationRiskScore;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.services.RegisteredService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.servlet.http.HttpServletRequest;

/**
 * This is {@link BaseAuthenticationRiskContingencyPlan}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
public abstract class BaseAuthenticationRiskContingencyPlan implements AuthenticationRiskContingencyPlan {
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());
    
    /**
     * App context.
     */
    @Autowired
    protected ApplicationContext applicationContext;
    
    /**
     * CAS properties.
     */
    @Autowired
    protected CasConfigurationProperties casProperties;

    @Override
    public final AuthenticationRiskContingencyResponse execute(final Authentication authentication,
                                                         final RegisteredService service,
                                                         final AuthenticationRiskScore score,
                                                         final HttpServletRequest request) {
        return executeInternal(authentication, service, score, request);
    }

    /**
     * Execute internal authentication risk contingency.
     *
     * @param authentication the authentication
     * @param service        the service
     * @param score          the score
     * @param request        the request
     * @return the authentication risk contingency response
     */
    protected AuthenticationRiskContingencyResponse executeInternal(final Authentication authentication,
                                                                    final RegisteredService service,
                                                                    final AuthenticationRiskScore score,
                                                                    final HttpServletRequest request) {
        return null;
    }
}
