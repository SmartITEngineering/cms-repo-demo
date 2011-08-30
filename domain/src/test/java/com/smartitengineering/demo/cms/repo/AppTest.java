package com.smartitengineering.demo.cms.repo;

import com.embarcadero.edn.Customer;
import com.embarcadero.edn.MasterModule;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.smartitengineering.cms.api.common.MediaType;
import com.smartitengineering.cms.api.factory.SmartContentAPI;
import com.smartitengineering.cms.api.factory.type.ContentTypeLoader;
import com.smartitengineering.cms.api.factory.type.WritableContentType;
import com.smartitengineering.cms.api.workspace.Workspace;
import com.smartitengineering.cms.api.workspace.WorkspaceId;
import com.smartitengineering.cms.binder.guice.Initializer;
import com.smartitengineering.dao.common.CommonDao;
import com.smartitengineering.dao.hbase.ddl.HBaseTableGenerator;
import com.smartitengineering.dao.hbase.ddl.config.json.ConfigurationJsonParser;
import com.smartitengineering.util.bean.guice.GuiceUtil;
import com.smartitengineering.util.rest.client.ApplicationWideClientFactoryImpl;
import com.smartitengineering.util.rest.client.ConnectionConfig;
import com.smartitengineering.util.rest.client.jersey.cache.CacheableClient;
import com.sun.jersey.api.client.Client;
import java.util.Collection;
import java.util.Properties;
import javax.ws.rs.core.HttpHeaders;
import junit.framework.Assert;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit test for simple App.
 */
public class AppTest {

  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static final Logger LOGGER = LoggerFactory.getLogger(AppTest.class);
  private static final int SLEEP_DURATION = 1500;
  private static final int PORT = 10080;
  private static final String TEST = "workspace";
  private static final String TEST_NS = "sample";
  private static Server jettyServer;

  @BeforeClass
  public static void globalSetup() throws Exception {
    /*
     * Start HBase and initialize tables
     */
    //-Djavax.xml.parsers.DocumentBuilderFactory=com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl
    System.setProperty("javax.xml.parsers.DocumentBuilderFactory",
                       "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");
    try {
      TEST_UTIL.startMiniCluster();
    }
    catch (Exception ex) {
      LOGGER.error(ex.getMessage(), ex);
    }
    /**
     * Generate the HBase tables if necessary, skips if table exists.
     * Initialize guice modules as specified in the configuration properties
     * In a web app these can reside within a context listener
     */
    new HBaseTableGenerator(ConfigurationJsonParser.getConfigurations(AppTest.class.getClassLoader().
        getResourceAsStream("com/smartitengineering/cms/spi/impl/schema.json")), TEST_UTIL.getConfiguration(), true).
        generateTables();

    /*
     * The following additional DI is to provide the test HBase connection so that it does not search for
     * connection configuration in classpath
     */
    Properties properties = new Properties();
    properties.setProperty(GuiceUtil.CONTEXT_NAME_PROP,
                           "com.smartitengineering.dao.impl.hbase,com.smartitengineering.user.client");
    properties.setProperty(GuiceUtil.IGNORE_MISSING_DEP_PROP, Boolean.TRUE.toString());
    properties.setProperty(GuiceUtil.MODULES_LIST_PROP, ConfigurationModule.class.getName());
    GuiceUtil.getInstance(properties).register();
    Initializer.init();

    /*
     * Start web application container
     */
    jettyServer = new Server(PORT);
    HandlerList handlerList = new HandlerList();
    /*
     * The following is for solr for later, when this is to be used it
     */
    System.setProperty("solr.solr.home", "./target/sample-conf/");
    Handler solr = new WebAppContext("./target/solr/", "/solr");
    handlerList.addHandler(solr);
    jettyServer.setHandler(handlerList);
    jettyServer.setSendDateHeader(true);
    jettyServer.start();

    /*
     * Setup client properties
     */
    System.setProperty(ApplicationWideClientFactoryImpl.TRACE, "true");

    Client client = CacheableClient.create();
    client.resource("http://localhost:7090/api/channels/test").header(HttpHeaders.CONTENT_TYPE,
                                                                      MediaType.APPLICATION_JSON).put(
        "{\"name\":\"test\"}");
    LOGGER.info("Created test channel!");
    /*
     * Setup workspaces
     */
    {
      final WorkspaceId wId;
      try {
        wId = SmartContentAPI.getInstance().getWorkspaceApi().createWorkspace(TEST_NS, TEST);
      }
      catch (Exception ex) {
        LOGGER.info("Exception getting feed resoruce", ex);
        throw new RuntimeException(ex);
      }
      boolean valid = false;
      try {
        Workspace workspace = wId.getWorkspae();
        final ContentTypeLoader contentTypeLoader = SmartContentAPI.getInstance().getContentTypeLoader();
        final Collection<WritableContentType> types;
        types = contentTypeLoader.parseContentTypes(workspace.getId(), AppTest.class.getClassLoader().
            getResourceAsStream("content-type-example.xml"),
                                                    com.smartitengineering.cms.api.common.MediaType.APPLICATION_XML);
        for (WritableContentType type : types) {
          type.put();
        }
        valid = true;
      }
      catch (Exception ex) {
        LOGGER.error("Error creating test workspace for templates", ex);
      }

      Assert.assertTrue(valid);
    }
  }

  @AfterClass
  public static void globalTearDown() throws Exception {
    jettyServer.stop();
    TEST_UTIL.shutdownMiniCluster();
  }

  public static class ConfigurationModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(Configuration.class).toInstance(TEST_UTIL.getConfiguration());
      ConnectionConfig config = new ConnectionConfig();
      config.setBasicUri("");
      config.setContextPath("/");
      config.setHost("localhost");
      config.setPort(PORT);
      bind(ConnectionConfig.class).toInstance(config);
    }
  }

  @Test
  public void testPersistCustomer() throws InterruptedException {
    Customer customer = new Customer();
    final String id = "customer1@testdomain.com";
    //Setting ID is optional; if not set then a UUID will be generated
    //Set ID when it is predictable, for example, for Person - Social Security ID, for Employee - Employee Code etc.
    customer.setId(id);
    customer.setAddress("Test address");
    customer.setName("Test Customer 1");
    Injector injector = Guice.createInjector(new MasterModule());
    CommonDao<Customer, String> dao = injector.getInstance(Key.get(new TypeLiteral<CommonDao<Customer, String>>() {
    }));
    Assert.assertNotNull(dao);
    dao.save(customer);
    Customer readCustomer = dao.getById(id);
    Assert.assertNotNull(readCustomer);
    //This is to ensure that the indexing takes place of the just saved customer
    Thread.sleep(SLEEP_DURATION);
  }
}
