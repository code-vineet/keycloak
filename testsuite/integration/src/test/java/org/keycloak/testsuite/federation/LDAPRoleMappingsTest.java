package org.keycloak.testsuite.federation;

import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runners.MethodSorters;
import org.keycloak.federation.ldap.LDAPFederationProvider;
import org.keycloak.federation.ldap.LDAPFederationProviderFactory;
import org.keycloak.federation.ldap.LDAPUtils;
import org.keycloak.federation.ldap.idm.model.LDAPObject;
import org.keycloak.federation.ldap.idm.query.Condition;
import org.keycloak.federation.ldap.idm.query.QueryParameter;
import org.keycloak.federation.ldap.idm.query.internal.LDAPIdentityQuery;
import org.keycloak.federation.ldap.idm.query.internal.LDAPQueryConditionsBuilder;
import org.keycloak.federation.ldap.mappers.RoleLDAPFederationMapper;
import org.keycloak.models.AccountRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserFederationMapperModel;
import org.keycloak.models.UserFederationProvider;
import org.keycloak.models.UserFederationProviderModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.testsuite.OAuthClient;
import org.keycloak.testsuite.pages.AccountPasswordPage;
import org.keycloak.testsuite.pages.AccountUpdateProfilePage;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.pages.RegisterPage;
import org.keycloak.testsuite.rule.KeycloakRule;
import org.keycloak.testsuite.rule.LDAPRule;
import org.keycloak.testsuite.rule.WebResource;
import org.keycloak.testsuite.rule.WebRule;
import org.openqa.selenium.WebDriver;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LDAPRoleMappingsTest {

    private static LDAPRule ldapRule = new LDAPRule();

    private static UserFederationProviderModel ldapModel = null;

    private static KeycloakRule keycloakRule = new KeycloakRule(new KeycloakRule.KeycloakSetup() {

        @Override
        public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
            FederationTestUtils.addLocalUser(manager.getSession(), appRealm, "mary", "mary@test.com", "password-app");

            Map<String,String> ldapConfig = ldapRule.getConfig();
            ldapConfig.put(LDAPConstants.SYNC_REGISTRATIONS, "true");
            ldapConfig.put(LDAPConstants.EDIT_MODE, UserFederationProvider.EditMode.WRITABLE.toString());

            ldapModel = appRealm.addUserFederationProvider(LDAPFederationProviderFactory.PROVIDER_NAME, ldapConfig, 0, "test-ldap", -1, -1, 0);

            // Delete all LDAP users and add some new for testing
            LDAPFederationProvider ldapFedProvider = FederationTestUtils.getLdapProvider(session, ldapModel);
            LDAPUtils.removeAllUsers(ldapFedProvider, appRealm);

            // Add sample application
            ClientModel finance = appRealm.addClient("finance");

            LDAPObject john = FederationTestUtils.addLDAPUser(ldapFedProvider, appRealm, "johnkeycloak", "John", "Doe", "john@email.org", "1234");
            ldapFedProvider.getLdapIdentityStore().updatePassword(john, "Password1");

            LDAPObject mary = FederationTestUtils.addLDAPUser(ldapFedProvider, appRealm, "marykeycloak", "Mary", "Kelly", "mary@email.org", "5678");
            ldapFedProvider.getLdapIdentityStore().updatePassword(mary, "Password1");

            LDAPObject rob = FederationTestUtils.addLDAPUser(ldapFedProvider, appRealm, "robkeycloak", "Rob", "Brown", "rob@email.org", "8910");
            ldapFedProvider.getLdapIdentityStore().updatePassword(rob, "Password1");

        }
    }) {

        @Override
        protected void after() {
            // Need to cleanup some LDAP objects after the test
            update(new KeycloakRule.KeycloakSetup() {

                @Override
                public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
                    RoleLDAPFederationMapper roleMapper = new RoleLDAPFederationMapper();

                    FederationTestUtils.addOrUpdateRoleLDAPMappers(appRealm, ldapModel, RoleLDAPFederationMapper.Mode.LDAP_ONLY);
                    UserFederationMapperModel roleMapperModel = appRealm.getUserFederationMapperByName(ldapModel.getId(), "realmRolesMapper");
                    LDAPFederationProvider ldapProvider = FederationTestUtils.getLdapProvider(session, ldapModel);

                    LDAPObject ldapRole = roleMapper.loadLDAPRoleByName(roleMapperModel, ldapProvider, "realmRole3");
                    if (ldapRole != null) {
                        ldapProvider.getLdapIdentityStore().remove(ldapRole);
                    }
                }

            });

            super.after();
        }

    };

    @ClassRule
    public static TestRule chain = RuleChain
            .outerRule(ldapRule)
            .around(keycloakRule);

    @Rule
    public WebRule webRule = new WebRule(this);

    @WebResource
    protected OAuthClient oauth;

    @WebResource
    protected WebDriver driver;

    @WebResource
    protected AppPage appPage;

    @WebResource
    protected LoginPage loginPage;

    @Test
    public void test01_ldapOnlyRoleMappings() {
        KeycloakSession session = keycloakRule.startSession();
        try {
            RealmModel appRealm = session.realms().getRealmByName("test");

            FederationTestUtils.addOrUpdateRoleLDAPMappers(appRealm, ldapModel, RoleLDAPFederationMapper.Mode.LDAP_ONLY);

            UserModel john = session.users().getUserByUsername("johnkeycloak", appRealm);
            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            // 1 - Grant some roles in LDAP

            // This role should already exists as it was imported from LDAP
            RoleModel realmRole1 = appRealm.getRole("realmRole1");
            john.grantRole(realmRole1);

            // This role should already exists as it was imported from LDAP
            RoleModel realmRole2 = appRealm.getRole("realmRole2");
            mary.grantRole(realmRole2);

            RoleModel realmRole3 = appRealm.addRole("realmRole3");
            john.grantRole(realmRole3);
            mary.grantRole(realmRole3);

            ClientModel accountApp = appRealm.getClientByClientId(Constants.ACCOUNT_MANAGEMENT_CLIENT_ID);
            ClientModel financeApp = appRealm.getClientByClientId("finance");

            RoleModel manageAccountRole = accountApp.getRole(AccountRoles.MANAGE_ACCOUNT);
            RoleModel financeRole1 = financeApp.getRole("financeRole1");
            john.grantRole(financeRole1);

            // 2 - Check that role mappings are not in local Keycloak DB (They are in LDAP).

            UserModel johnDb = session.userStorage().getUserByUsername("johnkeycloak", appRealm);
            Set<RoleModel> johnDbRoles = johnDb.getRoleMappings();
            Assert.assertFalse(johnDbRoles.contains(realmRole1));
            Assert.assertFalse(johnDbRoles.contains(realmRole2));
            Assert.assertFalse(johnDbRoles.contains(realmRole3));
            Assert.assertFalse(johnDbRoles.contains(financeRole1));
            Assert.assertTrue(johnDbRoles.contains(manageAccountRole));

            // 3 - Check that role mappings are in LDAP and hence available through federation

            Set<RoleModel> johnRoles = john.getRoleMappings();
            Assert.assertTrue(johnRoles.contains(realmRole1));
            Assert.assertFalse(johnRoles.contains(realmRole2));
            Assert.assertTrue(johnRoles.contains(realmRole3));
            Assert.assertTrue(johnRoles.contains(financeRole1));
            Assert.assertTrue(johnRoles.contains(manageAccountRole));

            Set<RoleModel> johnRealmRoles = john.getRealmRoleMappings();
            Assert.assertEquals(2, johnRealmRoles.size());
            Assert.assertTrue(johnRealmRoles.contains(realmRole1));
            Assert.assertTrue(johnRealmRoles.contains(realmRole3));

            // account roles are not mapped in LDAP. Those are in Keycloak DB
            Set<RoleModel> johnAccountRoles = john.getClientRoleMappings(accountApp);
            Assert.assertTrue(johnAccountRoles.contains(manageAccountRole));

            Set<RoleModel> johnFinanceRoles = john.getClientRoleMappings(financeApp);
            Assert.assertEquals(1, johnFinanceRoles.size());
            Assert.assertTrue(johnFinanceRoles.contains(financeRole1));

            // 4 - Delete some role mappings and check they are deleted

            john.deleteRoleMapping(realmRole3);
            john.deleteRoleMapping(realmRole1);
            john.deleteRoleMapping(financeRole1);
            john.deleteRoleMapping(manageAccountRole);

            johnRoles = john.getRoleMappings();
            Assert.assertFalse(johnRoles.contains(realmRole1));
            Assert.assertFalse(johnRoles.contains(realmRole2));
            Assert.assertFalse(johnRoles.contains(realmRole3));
            Assert.assertFalse(johnRoles.contains(financeRole1));
            Assert.assertFalse(johnRoles.contains(manageAccountRole));

            // Cleanup
            mary.deleteRoleMapping(realmRole2);
            mary.deleteRoleMapping(realmRole3);
            john.grantRole(manageAccountRole);
        } finally {
            keycloakRule.stopSession(session, false);
        }
    }

    @Test
    public void test02_readOnlyRoleMappings() {
        KeycloakSession session = keycloakRule.startSession();
        try {
            RealmModel appRealm = session.realms().getRealmByName("test");

            FederationTestUtils.addOrUpdateRoleLDAPMappers(appRealm, ldapModel, RoleLDAPFederationMapper.Mode.READ_ONLY);

            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            RoleModel realmRole1 = appRealm.getRole("realmRole1");
            RoleModel realmRole2 = appRealm.getRole("realmRole2");
            RoleModel realmRole3 = appRealm.getRole("realmRole3");
            if (realmRole3 == null) {
                realmRole3 = appRealm.addRole("realmRole3");
            }

            // Add some role mappings directly into LDAP
            RoleLDAPFederationMapper roleMapper = new RoleLDAPFederationMapper();
            UserFederationMapperModel roleMapperModel = appRealm.getUserFederationMapperByName(ldapModel.getId(), "realmRolesMapper");
            LDAPFederationProvider ldapProvider = FederationTestUtils.getLdapProvider(session, ldapModel);
            LDAPObject maryLdap = ldapProvider.loadLDAPUserByUsername(appRealm, "marykeycloak");
            roleMapper.addRoleMappingInLDAP(roleMapperModel, "realmRole1", ldapProvider, maryLdap);
            roleMapper.addRoleMappingInLDAP(roleMapperModel, "realmRole2", ldapProvider, maryLdap);

            // Add some role to model
            mary.grantRole(realmRole3);

            // Assert that mary has both LDAP and DB mapped roles
            Set<RoleModel> maryRoles = mary.getRealmRoleMappings();
            Assert.assertTrue(maryRoles.contains(realmRole1));
            Assert.assertTrue(maryRoles.contains(realmRole2));
            Assert.assertTrue(maryRoles.contains(realmRole3));

            // Assert that access through DB will have just DB mapped role
            UserModel maryDB = session.userStorage().getUserByUsername("marykeycloak", appRealm);
            Set<RoleModel> maryDBRoles = maryDB.getRealmRoleMappings();
            Assert.assertFalse(maryDBRoles.contains(realmRole1));
            Assert.assertFalse(maryDBRoles.contains(realmRole2));
            Assert.assertTrue(maryDBRoles.contains(realmRole3));

            mary.deleteRoleMapping(realmRole3);
            try {
                mary.deleteRoleMapping(realmRole1);
                Assert.fail("It wasn't expected to successfully delete LDAP role mappings in READ_ONLY mode");
            } catch (ModelException expected) {
            }

            // Delete role mappings directly in LDAP
            deleteRoleMappingsInLDAP(roleMapperModel, roleMapper, ldapProvider, maryLdap, "realmRole1");
            deleteRoleMappingsInLDAP(roleMapperModel, roleMapper, ldapProvider, maryLdap, "realmRole2");

            // Assert role mappings is not available
            maryRoles = mary.getRealmRoleMappings();
            Assert.assertFalse(maryRoles.contains(realmRole1));
            Assert.assertFalse(maryRoles.contains(realmRole2));
            Assert.assertFalse(maryRoles.contains(realmRole3));
        } finally {
            keycloakRule.stopSession(session, false);
        }
    }

    @Test
    public void test03_importRoleMappings() {
        KeycloakSession session = keycloakRule.startSession();
        try {
            RealmModel appRealm = session.realms().getRealmByName("test");

            FederationTestUtils.addOrUpdateRoleLDAPMappers(appRealm, ldapModel, RoleLDAPFederationMapper.Mode.IMPORT);

            // Add some role mappings directly in LDAP
            RoleLDAPFederationMapper roleMapper = new RoleLDAPFederationMapper();
            UserFederationMapperModel roleMapperModel = appRealm.getUserFederationMapperByName(ldapModel.getId(), "realmRolesMapper");
            LDAPFederationProvider ldapProvider = FederationTestUtils.getLdapProvider(session, ldapModel);
            LDAPObject robLdap = ldapProvider.loadLDAPUserByUsername(appRealm, "robkeycloak");
            roleMapper.addRoleMappingInLDAP(roleMapperModel, "realmRole1", ldapProvider, robLdap);
            roleMapper.addRoleMappingInLDAP(roleMapperModel, "realmRole2", ldapProvider, robLdap);

            // Get user and check that he has requested roles from LDAP
            UserModel rob = session.users().getUserByUsername("robkeycloak", appRealm);
            RoleModel realmRole1 = appRealm.getRole("realmRole1");
            RoleModel realmRole2 = appRealm.getRole("realmRole2");
            RoleModel realmRole3 = appRealm.getRole("realmRole3");
            if (realmRole3 == null) {
                realmRole3 = appRealm.addRole("realmRole3");
            }
            Set<RoleModel> robRoles = rob.getRealmRoleMappings();
            Assert.assertTrue(robRoles.contains(realmRole1));
            Assert.assertTrue(robRoles.contains(realmRole2));
            Assert.assertFalse(robRoles.contains(realmRole3));

            // Add some role mappings in model and check that user has it
            rob.grantRole(realmRole3);
            robRoles = rob.getRealmRoleMappings();
            Assert.assertTrue(robRoles.contains(realmRole3));

            // Delete some role mappings in LDAP and check that it doesn't have any effect and user still has role
            deleteRoleMappingsInLDAP(roleMapperModel, roleMapper, ldapProvider, robLdap, "realmRole1");
            deleteRoleMappingsInLDAP(roleMapperModel, roleMapper, ldapProvider, robLdap, "realmRole2");
            robRoles = rob.getRealmRoleMappings();
            Assert.assertTrue(robRoles.contains(realmRole1));
            Assert.assertTrue(robRoles.contains(realmRole2));

            // Delete role mappings through model and verifies that user doesn't have them anymore
            rob.deleteRoleMapping(realmRole1);
            rob.deleteRoleMapping(realmRole2);
            rob.deleteRoleMapping(realmRole3);
            robRoles = rob.getRealmRoleMappings();
            Assert.assertFalse(robRoles.contains(realmRole1));
            Assert.assertFalse(robRoles.contains(realmRole2));
            Assert.assertFalse(robRoles.contains(realmRole3));
        } finally {
            keycloakRule.stopSession(session, false);
        }
    }

    private void deleteRoleMappingsInLDAP(UserFederationMapperModel roleMapperModel, RoleLDAPFederationMapper roleMapper, LDAPFederationProvider ldapProvider, LDAPObject ldapUser, String roleName) {
        LDAPIdentityQuery ldapQuery = roleMapper.createRoleQuery(roleMapperModel, ldapProvider);
        LDAPQueryConditionsBuilder conditionsBuilder = new LDAPQueryConditionsBuilder();
        Condition roleNameCondition = conditionsBuilder.equal(new QueryParameter(LDAPConstants.CN), roleName);
        ldapQuery.where(roleNameCondition);
        LDAPObject ldapRole1 = ldapQuery.getFirstResult();
        roleMapper.deleteRoleMappingInLDAP(roleMapperModel, ldapProvider, ldapUser, ldapRole1);
    }
}
