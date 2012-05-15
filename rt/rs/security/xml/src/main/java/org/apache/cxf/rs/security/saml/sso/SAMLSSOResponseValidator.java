/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.rs.security.saml.sso;

import java.util.List;
import java.util.logging.Logger;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.saml.ext.builder.SAML2Constants;
import org.opensaml.saml2.core.AudienceRestriction;

/**
 * Validate a SAML 2.0 Protocol Response according to the Web SSO profile. The Response
 * should be validated by the SAMLProtocolResponseValidator first.
 * 
 * TODO If an <AuthnStatement> used to establish a security context for the principal contains a
SessionNotOnOrAfter attribute, the security context SHOULD be discarded once this time is
reached

TODO The service provider MUST ensure that bearer assertions are not replayed, by maintaining the set of used
ID values for the length of time for which the assertion would be considered valid based on the
NotOnOrAfter attribute in the <SubjectConfirmationData>.
 */
public class SAMLSSOResponseValidator {
    
    private static final Logger LOG = LogUtils.getL7dLogger(SAMLSSOResponseValidator.class);
    
    private String issuerIDP;
    private String assertionConsumerURL;
    private String clientAddress;
    private String requestId;
    private String spIdentifier;
    
    /**
     * Validate a SAML 2 Protocol Response
     * @param samlResponse
     * @param postBinding
     * @throws WSSecurityException
     */
    public void validateSamlResponse(
        org.opensaml.saml2.core.Response samlResponse,
        boolean postBinding
    ) throws WSSecurityException {
        // Check the Issuer
        validateIssuer(samlResponse.getIssuer());

        // The Response must contain at least one Assertion.
        if (samlResponse.getAssertions() == null || samlResponse.getAssertions().isEmpty()) {
            LOG.fine("The Response must contain at least one Assertion");
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
        // The Response must contain a Destination that matches the assertionConsumerURL if it is
        // signed and received over the POST Binding.
        String destination = samlResponse.getDestination();
        if (postBinding && samlResponse.isSigned()
            && (destination == null || !destination.equals(assertionConsumerURL))) {
            LOG.fine("The Response must contain a destination that matches the assertion consumer URL");
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
        // Validate Assertions
        boolean foundValidSubject = false;
        for (org.opensaml.saml2.core.Assertion assertion : samlResponse.getAssertions()) {
            // Check the Issuer
            if (assertion.getIssuer() == null) {
                LOG.fine("Assertion Issuer must not be null");
                throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
            }
            validateIssuer(assertion.getIssuer());
            
            if (postBinding && assertion.getSignature() == null) {
                LOG.fine("If the HTTP Post binding is used to deliver the Response, "
                         + "the enclosed assertions must be signed");
                throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
            }
            
            // Check for AuthnStatements and validate the Subject accordingly
            if (assertion.getAuthnStatements() != null
                && !assertion.getAuthnStatements().isEmpty()) {
                org.opensaml.saml2.core.Subject subject = assertion.getSubject();
                if (validateAuthenticationSubject(subject)) {
                    validateAudienceRestrictionCondition(assertion.getConditions());
                    foundValidSubject = true;
                }
            }
            
        }
        
        if (!foundValidSubject) {
            LOG.fine("The Response did not contain any Authentication Statement that matched "
                     + "the Subject Confirmation criteria");
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
    }
    
    /**
     * Validate the Issuer (if it exists)
     */
    private void validateIssuer(org.opensaml.saml2.core.Issuer issuer) throws WSSecurityException {
        if (issuer == null) {
            return;
        }
        
        // Issuer value must match Issuer IDP
        if (!issuer.getValue().equals(issuerIDP)) {
            LOG.fine("Issuer value: " + issuer.getValue() + " does not match issuer IDP: " 
                + issuerIDP);
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
        // Format must be nameid-format-entity
        if (issuer.getFormat() != null
            && !SAML2Constants.NAMEID_FORMAT_ENTITY.equals(issuer.getFormat())) {
            LOG.fine("Issuer format is not null and does not equal: " 
                + SAML2Constants.NAMEID_FORMAT_ENTITY);
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
    }
    
    /**
     * Validate the Subject (of an Authentication Statement).
     */
    private boolean validateAuthenticationSubject(
        org.opensaml.saml2.core.Subject subject
    ) throws WSSecurityException {
        if (subject.getSubjectConfirmations() == null) {
            return false;
        }
        // We need to find a Bearer Subject Confirmation method
        for (org.opensaml.saml2.core.SubjectConfirmation subjectConf 
            : subject.getSubjectConfirmations()) {
            if (SAML2Constants.CONF_BEARER.equals(subjectConf.getMethod())) {
                validateSubjectConfirmation(subjectConf.getSubjectConfirmationData());
            }
        }
        
        return true;
    }
    
    /**
     * Validate a (Bearer) Subject Confirmation
     */
    private void validateSubjectConfirmation(
        org.opensaml.saml2.core.SubjectConfirmationData subjectConfData
    ) throws WSSecurityException {
        if (subjectConfData == null) {
            LOG.fine("Subject Confirmation Data of a Bearer Subject Confirmation is null");
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
        // Recipient must match assertion consumer URL
        String recipient = subjectConfData.getRecipient();
        if (recipient == null || !recipient.equals(assertionConsumerURL)) {
            LOG.fine("Recipient " + recipient + " does not match assertion consumer URL "
                + assertionConsumerURL);
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
        // We must have a NotOnOrAfter timestamp
        if (subjectConfData.getNotOnOrAfter() == null
            || subjectConfData.getNotOnOrAfter().isBeforeNow()) {
            LOG.fine("Subject Conf Data does not contain NotOnOrAfter or it has expired");
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
        // Check address
        if (subjectConfData.getAddress() != null
            && !subjectConfData.getAddress().equals(clientAddress)) {
            LOG.fine("Subject Conf Data address " + subjectConfData.getAddress() + " does match"
                     + " client address " + clientAddress);
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
        // It must not contain a NotBefore timestamp
        if (subjectConfData.getNotBefore() != null) {
            LOG.fine("The Subject Conf Data must not contain a NotBefore timestamp");
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
        // InResponseTo must match the AuthnRequest request Id
        if (requestId != null && !requestId.equals(subjectConfData.getInResponseTo())) {
            LOG.fine("The InResponseTo String does match the original request id " + requestId);
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        
    }
    
    private void validateAudienceRestrictionCondition(
        org.opensaml.saml2.core.Conditions conditions
    ) throws WSSecurityException {
        if (conditions == null) {
            LOG.fine("Conditions are null");
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
        List<AudienceRestriction> audienceRestrs = conditions.getAudienceRestrictions();
        if (!matchSaml2AudienceRestriction(spIdentifier, audienceRestrs)) {
            LOG.fine("Assertion does not contain unique subject provider identifier " 
                     + spIdentifier + " in the audience restriction conditions");
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
        }
    }
    
    
    private boolean matchSaml2AudienceRestriction(
        String appliesTo, List<AudienceRestriction> audienceRestrictions
    ) {
        boolean found = false;
        if (audienceRestrictions != null && !audienceRestrictions.isEmpty()) {
            for (AudienceRestriction audienceRestriction : audienceRestrictions) {
                if (audienceRestriction.getAudiences() != null) {
                    for (org.opensaml.saml2.core.Audience audience : audienceRestriction.getAudiences()) {
                        if (appliesTo.equals(audience.getAudienceURI())) {
                            return true;
                        }
                    }
                }
            }
        }

        return found;
    }

    public String getIssuerIDP() {
        return issuerIDP;
    }

    public void setIssuerIDP(String issuerIDP) {
        this.issuerIDP = issuerIDP;
    }

    public String getAssertionConsumerURL() {
        return assertionConsumerURL;
    }

    public void setAssertionConsumerURL(String assertionConsumerURL) {
        this.assertionConsumerURL = assertionConsumerURL;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(String clientAddress) {
        this.clientAddress = clientAddress;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSpIdentifier() {
        return spIdentifier;
    }

    public void setSpIdentifier(String spIdentifier) {
        this.spIdentifier = spIdentifier;
    }
    

}
