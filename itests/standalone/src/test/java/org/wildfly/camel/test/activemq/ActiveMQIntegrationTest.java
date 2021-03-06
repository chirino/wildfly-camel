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

package org.wildfly.camel.test.activemq;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.camel.component.ActiveMQComponent;
import org.apache.camel.CamelContext;
import org.apache.camel.PollingConsumer;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.gravia.resource.ManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.jms.*;
import javax.naming.InitialContext;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(Arquillian.class)
public class ActiveMQIntegrationTest {

    static final String QUEUE_NAME = "testQueue";

    @ArquillianResource
    InitialContext initialctx;

    @ArquillianResource
    Deployer deployer;

    @Deployment
    public static WebArchive createdeployment() {
        File[] amqDependencies = Maven.configureResolverViaPlugin().
                resolve("org.apache.activemq:activemq-all").
                withTransitivity().
                asFile();

        final WebArchive archive = ShrinkWrap.create(WebArchive.class, "camel-activemq-tests.war");
        archive.addAsLibraries(amqDependencies);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                ManifestBuilder builder = new ManifestBuilder();
                builder.addManifestHeader("Dependencies", "org.jboss.as.controller-client,javax.jms.api");
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testSendMessage() throws Exception {
        // Create the CamelContext
        CamelContext camelctx = new DefaultCamelContext();

        ConnectionFactory connectionFactory = createConnectionFactory();

        ActiveMQComponent activeMQComponent = new ActiveMQComponent();
        activeMQComponent.setConnectionFactory(connectionFactory);
        camelctx.addComponent("activemq", activeMQComponent);

        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("activemq:queue:" + QUEUE_NAME).
                        transform(body().prepend("Hello ")).
                        to("direct:end");
            }
        });

        PollingConsumer pollingConsumer = camelctx.getEndpoint("direct:end").createPollingConsumer();
        pollingConsumer.start();

        camelctx.start();

        // Send a message to the queue
        Connection connection = connectionFactory.createConnection();

        sendMessage(connection, "Kermit");

        String result = pollingConsumer.receive(5000L).getIn().getBody(String.class);

        Assert.assertEquals("Hello Kermit", result);

        connection.close();
        camelctx.stop();
    }

    @Test
    public void testReceiveMessage() throws Exception {
        // Create the CamelContext
        CamelContext camelctx = new DefaultCamelContext();

        ConnectionFactory connectionFactory = createConnectionFactory();

        ActiveMQComponent activeMQComponent = new ActiveMQComponent();
        activeMQComponent.setConnectionFactory(connectionFactory);
        camelctx.addComponent("activemq", activeMQComponent);

        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:start").
                        transform(body().prepend("Hello ")).
                        to("activemq:queue:" + QUEUE_NAME);
            }
        });
        camelctx.start();

        final StringBuffer result = new StringBuffer();
        final CountDownLatch latch = new CountDownLatch(1);

        // Get the message from the queue
        Connection connection = connectionFactory.createConnection();
        receiveMessage(connection, new MessageListener() {
            @Override
            public void onMessage(Message message) {
                TextMessage text = (TextMessage) message;
                try {
                    result.append(text.getText());
                } catch (JMSException ex) {
                    result.append(ex.getMessage());
                }
                latch.countDown();
            }
        });

        ProducerTemplate producer = camelctx.createProducerTemplate();
        producer.asyncSendBody("direct:start", "Kermit");

        Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
        Assert.assertEquals("Hello Kermit", result.toString());

        connection.close();
        camelctx.stop();
    }

    private ActiveMQConnectionFactory createConnectionFactory() {
        return new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false&broker.useJmx=false");
    }

    private void sendMessage(Connection connection, String message) throws Exception {
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination destination = session.createQueue(QUEUE_NAME);
        MessageProducer producer = session.createProducer(destination);
        TextMessage msg = session.createTextMessage(message);
        producer.send(msg);
        connection.start();
    }

    private void receiveMessage(Connection connection, MessageListener listener) throws Exception {
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination destination = session.createQueue(QUEUE_NAME);
        MessageConsumer consumer = session.createConsumer(destination);
        consumer.setMessageListener(listener);
        connection.start();
    }
}
