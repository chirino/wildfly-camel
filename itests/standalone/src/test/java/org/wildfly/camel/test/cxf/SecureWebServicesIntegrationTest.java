/*
 * #%L
 * Wildfly Camel :: Testsuite
 * %%
 * Copyright (C) 2013 - 2014 RedHat
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
package org.wildfly.camel.test.cxf;

import org.apache.camel.CamelContext;
import org.apache.camel.PollingConsumer;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ContainerResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.camel.test.cxf.subB.Endpoint;
import org.wildfly.camel.test.cxf.subB.EndpointImpl;

import java.io.IOException;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ALLOW_RESOURCE_SERVICE_RESTART;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;

@RunWith(Arquillian.class)
public class SecureWebServicesIntegrationTest {

    static final String SIMPLE_WAR = "simple.war";

    @ArquillianResource
    Deployer deployer;

    @ContainerResource
    ManagementClient managementClient;

    @Before
    public void setUp() throws IOException {
        // Set up a security domain for our tests to authenticate against
        ModelNode securityDomainOpAdd = createOpNode("subsystem=security/security-domain=cxf-security-domain", "add");
        ModelNode securityDomainContent = createOpNode("subsystem=security/security-domain=cxf-security-domain/authentication=classic", "add");

        ModelNode loginModules = securityDomainContent.get("login-modules");

        ModelNode userRoles = new ModelNode();
        userRoles.get("code").set("UsersRoles");
        userRoles.get("flag").set("required");

        ModelNode moduleOptions = userRoles.get("module-options");
        moduleOptions.get("usersProperties").set("cxf-users.properties");
        moduleOptions.get("rolesProperties").set("cxf-roles.properties");
        loginModules.add(userRoles);

        ModelNode result = managementClient.getControllerClient().execute(createCompositeNode(new ModelNode[] {securityDomainOpAdd, securityDomainContent}));

        // Make sure the commands worked before proceeding
        Assert.assertEquals("success", result.get("outcome").asString());
    }

    @After
    public void tearDown() throws IOException {
        // Remove the test security domain after each test
        ModelNode securityDomainOpRemove = createOpNode("subsystem=security/security-domain=cxf-security-domain", "remove");
        securityDomainOpRemove.get(OPERATION_HEADERS, ALLOW_RESOURCE_SERVICE_RESTART).set(true);

        ModelNode result = managementClient.getControllerClient().execute(securityDomainOpRemove);
        Assert.assertEquals("success", result.get("outcome").asString());
    }

    @Test
    public void testEndpointRouteWithValidCredentials() throws Exception {
        deployer.deploy(SIMPLE_WAR);
        try {
            CamelContext camelctx = new DefaultCamelContext();
            camelctx.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from("direct:start")
                            .to("cxf://" + getEndpointAddress("/simple", "cxfuser", "cxfpassword"));
                }
            });

            camelctx.start();

            ProducerTemplate producer = camelctx.createProducerTemplate();
            String result = producer.requestBody("direct:start", "Kermit", String.class);
            Assert.assertEquals("Hello Kermit", result);
        } finally {
            deployer.undeploy(SIMPLE_WAR);
        }
    }

    @Test
    public void testEndpointRouteWithInvalidCredentials() throws Exception {
        deployer.deploy(SIMPLE_WAR);
        try {
            CamelContext camelctx = new DefaultCamelContext();
            camelctx.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from("direct:start")
                            .doTry()
                            .to("cxf://" + getEndpointAddress("/simple", "baduser", "badpassword"))
                            .doCatch(Exception.class)
                            .setBody(exceptionMessage())
                            .to("direct:error")
                            .end();
                }
            });

            PollingConsumer pollingConsumer = camelctx.getEndpoint("direct:error").createPollingConsumer();
            pollingConsumer.start();

            camelctx.start();

            ProducerTemplate producer = camelctx.createProducerTemplate();
            producer.requestBody("direct:start", "Kermit", String.class);

            String result = pollingConsumer.receive(5000L).getIn().getBody(String.class);

            Assert.assertTrue(result.contains("401: Unauthorized"));
        } finally {
            deployer.undeploy(SIMPLE_WAR);
        }
    }

    private String getEndpointAddress(final String contextPath, final String username, final String password) {
        final StringBuilder builder = new StringBuilder();
        builder.append(managementClient.getWebUri())
            .append(contextPath)
            .append("/EndpointService?username=")
            .append(username)
            .append("&password=")
            .append(password)
            .append("&serviceClass=")
            .append(Endpoint.class.getName());
        return builder.toString();
    }

    @Deployment(name = SIMPLE_WAR, managed = false)
    public static Archive<?> getSimpleWar() {
        final WebArchive archive = ShrinkWrap.create(WebArchive.class, SIMPLE_WAR);
        final StringAsset jbossWebAsset = new StringAsset("<jboss-web><security-domain>" +
                "cxf-security-domain</security-domain></jboss-web>");

        archive.addClasses(Endpoint.class, EndpointImpl.class);
        archive.addAsResource("cxf/secure/cxf-roles.properties", "cxf-roles.properties");
        archive.addAsResource("cxf/secure/cxf-users.properties", "cxf-users.properties");
        archive.addAsWebInfResource(jbossWebAsset, "jboss-web.xml");
        return archive;
    }

    private ModelNode createOpNode(String address, String operation) {
        ModelNode op = new ModelNode();

        ModelNode list = op.get("address").setEmptyList();
        if (address != null) {
            String[] pathSegments = address.split("/");
            for (String segment : pathSegments) {
                String[] elements = segment.split("=");
                list.add(elements[0], elements[1]);
            }
        }
        op.get("operation").set(operation);
        return op;
    }

    private ModelNode createCompositeNode(ModelNode[] steps) {
        ModelNode comp = new ModelNode();
        comp.get("operation").set("composite");
        for (ModelNode step : steps) {
            comp.get("steps").add(step);
        }
        return comp;
    }
}
