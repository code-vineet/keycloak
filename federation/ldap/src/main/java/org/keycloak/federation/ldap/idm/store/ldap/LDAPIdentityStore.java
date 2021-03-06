package org.keycloak.federation.ldap.idm.store.ldap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.jboss.logging.Logger;
import org.keycloak.federation.ldap.LDAPConfig;
import org.keycloak.federation.ldap.idm.model.LDAPDn;
import org.keycloak.federation.ldap.idm.model.LDAPObject;
import org.keycloak.federation.ldap.idm.query.Condition;
import org.keycloak.federation.ldap.idm.query.QueryParameter;
import org.keycloak.federation.ldap.idm.query.internal.BetweenCondition;
import org.keycloak.federation.ldap.idm.query.internal.LDAPIdentityQuery;
import org.keycloak.federation.ldap.idm.query.internal.EqualCondition;
import org.keycloak.federation.ldap.idm.query.internal.GreaterThanCondition;
import org.keycloak.federation.ldap.idm.query.internal.InCondition;
import org.keycloak.federation.ldap.idm.query.internal.LessThanCondition;
import org.keycloak.federation.ldap.idm.query.internal.OrCondition;
import org.keycloak.federation.ldap.idm.store.IdentityStore;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.ModelException;

/**
 * An IdentityStore implementation backed by an LDAP directory
 *
 * @author Shane Bryzak
 * @author Anil Saldhana
 * @author <a href="mailto:psilva@redhat.com">Pedro Silva</a>
 */
public class LDAPIdentityStore implements IdentityStore {

    private static final Logger logger = Logger.getLogger(LDAPIdentityStore.class);

    private final LDAPConfig config;
    private final LDAPOperationManager operationManager;

    public LDAPIdentityStore(LDAPConfig config) {
        this.config = config;

        try {
            this.operationManager = new LDAPOperationManager(getConfig());
        } catch (NamingException e) {
            throw new ModelException("Couldn't init operation manager", e);
        }
    }

    @Override
    public LDAPConfig getConfig() {
        return this.config;
    }

    @Override
    public void add(LDAPObject ldapObject) {
        // id will be assigned by the ldap server
        if (ldapObject.getUuid() != null) {
            throw new IllegalStateException("Can't add object with already assigned uuid");
        }

        String entryDN = ldapObject.getDn().toString();
        this.operationManager.createSubContext(entryDN, extractAttributes(ldapObject, true));
        ldapObject.setUuid(getEntryIdentifier(ldapObject));

        if (logger.isTraceEnabled()) {
            logger.tracef("Type with identifier [%s] and dn [%s] successfully added to LDAP store.", ldapObject.getUuid(), entryDN);
        }
    }

    @Override
    public void update(LDAPObject ldapObject) {
        BasicAttributes updatedAttributes = extractAttributes(ldapObject, false);
        NamingEnumeration<Attribute> attributes = updatedAttributes.getAll();

        String entryDn = ldapObject.getDn().toString();
        this.operationManager.modifyAttributes(entryDn, attributes);

        if (logger.isTraceEnabled()) {
            logger.tracef("Type with identifier [%s] and DN [%s] successfully updated to LDAP store.", ldapObject.getUuid(), entryDn);
        }
    }

    @Override
    public void remove(LDAPObject ldapObject) {
        this.operationManager.removeEntry(ldapObject.getDn().toString());

        if (logger.isTraceEnabled()) {
            logger.tracef("Type with identifier [%s] and DN [%s] successfully removed from LDAP store.", ldapObject.getUuid(), ldapObject.getDn().toString());
        }
    }


    @Override
    public List<LDAPObject> fetchQueryResults(LDAPIdentityQuery identityQuery) {
        List<LDAPObject> results = new ArrayList<>();

        try {
            if (identityQuery.getSorting() != null && !identityQuery.getSorting().isEmpty()) {
                throw new ModelException("LDAP Identity Store does not yet support sorted queries.");
            }

            // TODO: proper support for search by more DNs
            String baseDN = identityQuery.getSearchDns().iterator().next();

            for (Condition condition : identityQuery.getConditions()) {

                // Check if we are searching by ID
                String uuidAttrName = getConfig().getUuidLDAPAttributeName();
                if (condition.getParameter() != null && condition.getParameter().getName().equals(uuidAttrName)) {
                    if (EqualCondition.class.isInstance(condition)) {
                        EqualCondition equalCondition = (EqualCondition) condition;
                        SearchResult search = this.operationManager
                                .lookupById(baseDN, equalCondition.getValue().toString(), identityQuery.getReturningLdapAttributes());

                        if (search != null) {
                            results.add(populateAttributedType(search, identityQuery.getReturningReadOnlyLdapAttributes()));
                        }
                    }

                    return results;
                }
            }


            StringBuilder filter = createIdentityTypeSearchFilter(identityQuery);

            List<SearchResult> search;
            if (getConfig().isPagination() && identityQuery.getLimit() > 0) {
                search = this.operationManager.searchPaginated(baseDN, filter.toString(), identityQuery);
            } else {
                search = this.operationManager.search(baseDN, filter.toString(), identityQuery.getReturningLdapAttributes(), identityQuery.getSearchScope());
            }

            for (SearchResult result : search) {
                if (!result.getNameInNamespace().equals(baseDN)) {
                    results.add(populateAttributedType(result, identityQuery.getReturningReadOnlyLdapAttributes()));
                }
            }
        } catch (Exception e) {
            throw new ModelException("Querying of LDAP failed " + identityQuery, e);
        }

        return results;
    }

    @Override
    public int countQueryResults(LDAPIdentityQuery identityQuery) {
        int limit = identityQuery.getLimit();
        int offset = identityQuery.getOffset();

        identityQuery.setLimit(0);
        identityQuery.setOffset(0);

        int resultCount = identityQuery.getResultList().size();

        identityQuery.setLimit(limit);
        identityQuery.setOffset(offset);

        return resultCount;
    }

    // *************** CREDENTIALS AND USER SPECIFIC STUFF

    @Override
    public boolean validatePassword(LDAPObject user, String password) {
        String userDN = user.getDn().toString();

        if (logger.isDebugEnabled()) {
            logger.debugf("Using DN [%s] for authentication of user", userDN);
        }

        if (operationManager.authenticate(userDN, password)) {
            return true;
        }

        return false;
    }

    @Override
    public void updatePassword(LDAPObject user, String password) {
        String userDN = user.getDn().toString();

        if (logger.isDebugEnabled()) {
            logger.debugf("Using DN [%s] for updating LDAP password of user", userDN);
        }

        if (getConfig().isActiveDirectory()) {
            updateADPassword(userDN, password);
        } else {
            ModificationItem[] mods = new ModificationItem[1];

            try {
                BasicAttribute mod0 = new BasicAttribute(LDAPConstants.USER_PASSWORD_ATTRIBUTE, password);

                mods[0] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE, mod0);

                operationManager.modifyAttribute(userDN, mod0);
            } catch (Exception e) {
                throw new ModelException("Error updating password.", e);
            }
        }
    }


    private void updateADPassword(String userDN, String password) {
        try {
            // Replace the "unicdodePwd" attribute with a new value
            // Password must be both Unicode and a quoted string
            String newQuotedPassword = "\"" + password + "\"";
            byte[] newUnicodePassword = newQuotedPassword.getBytes("UTF-16LE");

            BasicAttribute unicodePwd = new BasicAttribute("unicodePwd", newUnicodePassword);

            List<ModificationItem> modItems = new ArrayList<ModificationItem>();
            modItems.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, unicodePwd));

            // Used in ActiveDirectory to put account into "enabled" state (aka userAccountControl=512, see http://support.microsoft.com/kb/305144/en ) after password update. If value is -1, it's ignored
            // TODO: Remove and use mapper instead
            if (getConfig().isUserAccountControlsAfterPasswordUpdate()) {
                BasicAttribute userAccountControl = new BasicAttribute("userAccountControl", "512");
                modItems.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, userAccountControl));

                logger.debugf("Attribute userAccountControls will be switched to 512 after password update of user [%s]", userDN);
            }

            operationManager.modifyAttributes(userDN, modItems.toArray(new ModificationItem[] {}));
        } catch (Exception e) {
            throw new ModelException(e);
        }
    }

    // ************ END CREDENTIALS AND USER SPECIFIC STUFF

    protected StringBuilder createIdentityTypeSearchFilter(final LDAPIdentityQuery identityQuery) {
        StringBuilder filter = new StringBuilder();

        for (Condition condition : identityQuery.getConditions()) {
            applyCondition(filter, condition);
        }


        filter.insert(0, "(&");
        filter.append(getObjectClassesFilter(identityQuery.getObjectClasses()));
        filter.append(")");

        logger.infof("Using filter for LDAP search: %s", filter);
        return filter;
    }

    protected void applyCondition(StringBuilder filter, Condition condition) {
        if (OrCondition.class.isInstance(condition)) {
            OrCondition orCondition = (OrCondition) condition;
            filter.append("(|");

            for (Condition innerCondition : orCondition.getInnerConditions()) {
                applyCondition(filter, innerCondition);
            }

            filter.append(")");
            return;
        }

        QueryParameter queryParameter = condition.getParameter();

        if (!getConfig().getUuidLDAPAttributeName().equals(queryParameter.getName())) {
            String attributeName = queryParameter.getName();

            if (attributeName != null) {
                if (EqualCondition.class.isInstance(condition)) {
                    EqualCondition equalCondition = (EqualCondition) condition;
                    Object parameterValue = equalCondition.getValue();

                    if (Date.class.isInstance(parameterValue)) {
                        parameterValue = LDAPUtil.formatDate((Date) parameterValue);
                    }

                    filter.append("(").append(attributeName).append(LDAPConstants.EQUAL).append(parameterValue).append(")");
                } else if (GreaterThanCondition.class.isInstance(condition)) {
                    GreaterThanCondition greaterThanCondition = (GreaterThanCondition) condition;
                    Comparable parameterValue = greaterThanCondition.getValue();

                    if (Date.class.isInstance(parameterValue)) {
                        parameterValue = LDAPUtil.formatDate((Date) parameterValue);
                    }

                    if (greaterThanCondition.isOrEqual()) {
                        filter.append("(").append(attributeName).append(">=").append(parameterValue).append(")");
                    } else {
                        filter.append("(").append(attributeName).append(">").append(parameterValue).append(")");
                    }
                } else if (LessThanCondition.class.isInstance(condition)) {
                    LessThanCondition lessThanCondition = (LessThanCondition) condition;
                    Comparable parameterValue = lessThanCondition.getValue();

                    if (Date.class.isInstance(parameterValue)) {
                        parameterValue = LDAPUtil.formatDate((Date) parameterValue);
                    }

                    if (lessThanCondition.isOrEqual()) {
                        filter.append("(").append(attributeName).append("<=").append(parameterValue).append(")");
                    } else {
                        filter.append("(").append(attributeName).append("<").append(parameterValue).append(")");
                    }
                } else if (BetweenCondition.class.isInstance(condition)) {
                    BetweenCondition betweenCondition = (BetweenCondition) condition;
                    Comparable x = betweenCondition.getX();
                    Comparable y = betweenCondition.getY();

                    if (Date.class.isInstance(x)) {
                        x = LDAPUtil.formatDate((Date) x);
                    }

                    if (Date.class.isInstance(y)) {
                        y = LDAPUtil.formatDate((Date) y);
                    }

                    filter.append("(").append(x).append("<=").append(attributeName).append("<=").append(y).append(")");
                } else if (InCondition.class.isInstance(condition)) {
                    InCondition inCondition = (InCondition) condition;
                    Object[] valuesToCompare = inCondition.getValue();

                    filter.append("(&(");

                    for (int i = 0; i< valuesToCompare.length; i++) {
                        Object value = valuesToCompare[i];

                        filter.append("(").append(attributeName).append(LDAPConstants.EQUAL).append(value).append(")");
                    }

                    filter.append("))");
                } else {
                    throw new ModelException("Unsupported query condition [" + condition + "].");
                }
            }
        }
    }

    private StringBuilder getObjectClassesFilter(Collection<String> objectClasses) {
        StringBuilder builder = new StringBuilder();

        if (!objectClasses.isEmpty()) {
            for (String objectClass : objectClasses) {
                builder.append("(").append(LDAPConstants.OBJECT_CLASS).append(LDAPConstants.EQUAL).append(objectClass).append(")");
            }
        } else {
            builder.append("(").append(LDAPConstants.OBJECT_CLASS).append(LDAPConstants.EQUAL).append("*").append(")");
        }

        return builder;
    }

    private LDAPObject populateAttributedType(SearchResult searchResult, Collection<String> readOnlyAttrNames) {
        try {
            String entryDN = searchResult.getNameInNamespace();
            Attributes attributes = searchResult.getAttributes();

            LDAPObject ldapObject = new LDAPObject();
            LDAPDn dn = LDAPDn.fromString(entryDN);
            ldapObject.setDn(dn);
            ldapObject.setRdnAttributeName(dn.getFirstRdnAttrName());

            if (logger.isTraceEnabled()) {
                logger.tracef("Populating LDAP Object from DN [%s]", entryDN);
            }

            NamingEnumeration<? extends Attribute> ldapAttributes = attributes.getAll();

            // Exact name of attributes might be different
            List<String> uppercasedReadOnlyAttrNames = new ArrayList<>();
            for (String readonlyAttr : readOnlyAttrNames) {
                uppercasedReadOnlyAttrNames.add(readonlyAttr.toUpperCase());
            }

            while (ldapAttributes.hasMore()) {
                Attribute ldapAttribute = ldapAttributes.next();

                try {
                    ldapAttribute.get();
                } catch (NoSuchElementException nsee) {
                    continue;
                }

                String ldapAttributeName = ldapAttribute.getID();

                if (ldapAttributeName.toLowerCase().equals(getConfig().getUuidLDAPAttributeName().toLowerCase())) {
                    Object uuidValue = ldapAttribute.get();
                    ldapObject.setUuid(this.operationManager.decodeEntryUUID(uuidValue));
                } else {
                    Set<String> attrValues = new TreeSet<>();
                    NamingEnumeration<?> enumm = ldapAttribute.getAll();
                    while (enumm.hasMoreElements()) {
                        String attrVal = enumm.next().toString();
                        attrValues.add(attrVal);
                    }

                    if (ldapAttributeName.toLowerCase().equals(LDAPConstants.OBJECT_CLASS)) {
                        ldapObject.setObjectClasses(attrValues);
                    } else {
                        if (logger.isTraceEnabled()) {
                            logger.tracef("Populating ldap attribute [%s] with value [%s] for DN [%s].", ldapAttributeName, attrValues.toString(), entryDN);
                        }
                        if (attrValues.size() == 1) {
                            ldapObject.setAttribute(ldapAttributeName, attrValues.iterator().next());
                        } else {
                            ldapObject.setAttribute(ldapAttributeName, attrValues);
                        }

                        if (uppercasedReadOnlyAttrNames.contains(ldapAttributeName.toUpperCase())) {
                            ldapObject.addReadOnlyAttributeName(ldapAttributeName);
                        }
                    }
                }
            }

            return ldapObject;

            /*LDAPMappingConfiguration entryConfig = getMappingConfig(attributedType.getClass());

            if (mappingConfig.getParentMembershipAttributeName() != null) {
                StringBuilder filter = new StringBuilder("(&");
                String entryBaseDN = entryDN.substring(entryDN.indexOf(LDAPConstants.COMMA) + 1);

                filter
                        .append("(")
                        .append(getObjectClassesFilter(entryConfig))
                        .append(")")
                        .append("(")
                        .append(mappingConfig.getParentMembershipAttributeName())
                        .append(LDAPConstants.EQUAL).append("")
                        .append(getBindingDN(attributedType, false))
                        .append(LDAPConstants.COMMA)
                        .append(entryBaseDN)
                        .append(")");

                filter.append(")");

                if (logger.isTraceEnabled()) {
                    logger.tracef("Searching parent entry for DN [%s] using filter [%s].", entryBaseDN, filter.toString());
                }

                List<SearchResult> search = this.operationManager.search(getConfig().getBaseDN(), filter.toString(), entryConfig);

                if (!search.isEmpty()) {
                    SearchResult next = search.get(0);

                    Property<IdentityType> parentProperty = PropertyQueries
                            .<IdentityType>createQuery(attributedType.getClass())
                            .addCriteria(new TypedPropertyCriteria(attributedType.getClass())).getFirstResult();

                    if (parentProperty != null) {
                        String parentDN = next.getNameInNamespace();
                        String parentBaseDN = parentDN.substring(parentDN.indexOf(",") + 1);
                        Class<? extends IdentityType> baseDNType = getConfig().getSupportedTypeByBaseDN(parentBaseDN, getEntryObjectClasses(attributes));

                        if (parentProperty.getJavaClass().isAssignableFrom(baseDNType)) {
                            if (logger.isTraceEnabled()) {
                                logger.tracef("Found parent [%s] for entry for DN [%s].", parentDN, entryDN);
                            }

                            int hierarchyDepthCount1 = ++hierarchyDepthCount;

                            parentProperty.setValue(attributedType, populateAttributedType(next, null, hierarchyDepthCount1));
                        }
                    }
                } else {
                    if (logger.isTraceEnabled()) {
                        logger.tracef("No parent entry found for DN [%s] using filter [%s].", entryDN, filter.toString());
                    }
                }
            }  */


        } catch (Exception e) {
            throw new ModelException("Could not populate attribute type " + searchResult.getNameInNamespace() + ".", e);
        }
    }

    protected BasicAttributes extractAttributes(LDAPObject ldapObject, boolean isCreate) {
        BasicAttributes entryAttributes = new BasicAttributes();

        for (Map.Entry<String, Object> attrEntry : ldapObject.getAttributes().entrySet()) {
            String attrName = attrEntry.getKey();
            Object attrValue = attrEntry.getValue();
            if (!ldapObject.getReadOnlyAttributeNames().contains(attrName) && (isCreate || !ldapObject.getRdnAttributeName().equals(attrName))) {

                if (String.class.isInstance(attrValue)) {
                    if (attrValue.toString().trim().length() == 0) {
                        attrValue = LDAPConstants.EMPTY_ATTRIBUTE_VALUE;
                    }
                    entryAttributes.put(attrName, attrValue);
                } else if (Collection.class.isInstance(attrValue)) {
                    BasicAttribute attr = new BasicAttribute(attrName);
                    Collection<String> valueCollection = (Collection<String>) attrValue;
                    for (String val : valueCollection) {
                        attr.add(val);
                    }
                    entryAttributes.put(attr);
                } else if (attrValue == null || attrValue.toString().trim().length() == 0) {
                    entryAttributes.put(attrName, LDAPConstants.EMPTY_ATTRIBUTE_VALUE);
                } else {
                    throw new IllegalArgumentException("Unexpected type of value of argument " + attrName + ". Value is " + attrValue);
                }
            }
        }

        // Don't extract object classes for update
        if (isCreate) {
            BasicAttribute objectClassAttribute = new BasicAttribute(LDAPConstants.OBJECT_CLASS);

            for (String objectClassValue : ldapObject.getObjectClasses()) {
                objectClassAttribute.add(objectClassValue);

                if (objectClassValue.equals(LDAPConstants.GROUP_OF_NAMES)
                        || objectClassValue.equals(LDAPConstants.GROUP_OF_ENTRIES)
                        || objectClassValue.equals(LDAPConstants.GROUP_OF_UNIQUE_NAMES)) {
                    entryAttributes.put(LDAPConstants.MEMBER, LDAPConstants.EMPTY_ATTRIBUTE_VALUE);
                }
            }

            entryAttributes.put(objectClassAttribute);
        }

        return entryAttributes;
    }

    /*public String getBindingDN(IdentityType attributedType, boolean appendBaseDN) {
        LDAPMappingConfiguration mappingConfig = getMappingConfig(attributedType.getClass());

        String baseDN;
        if (mappingConfig.getBaseDN() == null || !appendBaseDN) {
            baseDN = "";
        } else {
            baseDN = LDAPConstants.COMMA + getBaseDN(attributedType);
        }

        Property<String> bindingDnAttributeProperty = mappingConfig.getBindingDnProperty();
        String bindingAttributeName = mappingConfig.getMappedAttributes().get(bindingDnAttributeProperty.getName());
        String bindingAttributeValue = mappingConfig.getBindingDnProperty().getValue(attributedType);

        return bindingAttributeName + LDAPConstants.EQUAL + bindingAttributeValue + baseDN;
    }

    private String getBaseDN(IdentityType attributedType) {
        LDAPMappingConfiguration mappingConfig = getMappingConfig(attributedType.getClass());
        String baseDN = mappingConfig.getBaseDN();
        String parentDN = mappingConfig.getParentMapping().get(mappingConfig.getIdProperty().getValue(attributedType));

        if (parentDN != null) {
            baseDN = parentDN;
        } else {
            Property<IdentityType> parentProperty = PropertyQueries
                    .<IdentityType>createQuery(attributedType.getClass())
                    .addCriteria(new TypedPropertyCriteria(attributedType.getClass())).getFirstResult();

            if (parentProperty != null) {
                IdentityType parentType = parentProperty.getValue(attributedType);

                if (parentType != null) {
                    Property<String> parentIdProperty = getMappingConfig(parentType.getClass()).getIdProperty();

                    String parentId = parentIdProperty.getValue(parentType);

                    String parentBaseDN = mappingConfig.getParentMapping().get(parentId);

                    if (parentBaseDN != null) {
                        baseDN = parentBaseDN;
                    } else {
                        baseDN = getBaseDN(parentType);
                    }
                }
            }
        }

        return baseDN;
    }

    protected void addToParentAsMember(final IdentityType attributedType) {
        LDAPMappingConfiguration entryConfig = getMappingConfig(attributedType.getClass());

        if (entryConfig.getParentMembershipAttributeName() != null) {
            Property<IdentityType> parentProperty = PropertyQueries
                    .<IdentityType>createQuery(attributedType.getClass())
                    .addCriteria(new TypedPropertyCriteria(attributedType.getClass()))
                    .getFirstResult();

            if (parentProperty != null) {
                IdentityType parentType = parentProperty.getValue(attributedType);

                if (parentType != null) {
                    Attributes attributes = this.operationManager.getAttributes(parentType.getId(), getBaseDN(parentType), entryConfig);
                    Attribute attribute = attributes.get(entryConfig.getParentMembershipAttributeName());

                    attribute.add(getBindingDN(attributedType, true));

                    this.operationManager.modifyAttribute(getBindingDN(parentType, true), attribute);
                }
            }
        }
    }   */

    protected String getEntryIdentifier(final LDAPObject ldapObject) {
        try {
            // we need this to retrieve the entry's identifier from the ldap server
            String uuidAttrName = getConfig().getUuidLDAPAttributeName();
            List<SearchResult> search = this.operationManager.search(ldapObject.getDn().toString(), "(" + ldapObject.getDn().getFirstRdn() + ")", Arrays.asList(uuidAttrName), SearchControls.OBJECT_SCOPE);
            Attribute id = search.get(0).getAttributes().get(getConfig().getUuidLDAPAttributeName());

            if (id == null) {
                throw new ModelException("Could not retrieve identifier for entry [" + ldapObject.getDn().toString() + "].");
            }

            return this.operationManager.decodeEntryUUID(id.get());
        } catch (NamingException ne) {
            throw new ModelException("Could not retrieve identifier for entry [" + ldapObject.getDn().toString() + "].");
        }
    }
}
