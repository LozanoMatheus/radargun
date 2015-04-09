package org.radargun.service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.Entity;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.SharedCacheMode;

import org.hibernate.FlushMode;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.c3p0.internal.C3P0ConnectionProvider;
import org.hibernate.cache.infinispan.InfinispanRegionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQL94Dialect;
import org.hibernate.hikaricp.internal.HikariCPConnectionProvider;
import org.radargun.Directories;
import org.radargun.Service;
import org.radargun.config.AnnotatedHelper;
import org.radargun.config.DefinitionElement;
import org.radargun.config.DocumentedValue;
import org.radargun.config.Property;
import org.radargun.config.XmlConverter;
import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.traits.JpaProvider;
import org.radargun.traits.Lifecycle;
import org.radargun.traits.ProvidesTrait;
import org.radargun.traits.Transactional;
import org.radargun.utils.ArgsHolder;
import org.radargun.utils.ReflexiveConverters;
import org.radargun.utils.Utils;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Service(doc = "Service for Hibernate ORM 5.x")
public class HibernateOrm5Service implements Lifecycle, JpaProvider {
   private static Log log = LogFactory.getLog(HibernateOrm5Service.class);

   @Property(doc = "Persistence unit to be used. Default is 'default'")
   private String persistenceUnit;

   @Property(doc = "Flush mode. By default not set.")
   private FlushMode flushMode;

   @Property(doc = "HBM -> DDL settings. By default not set.")
   private Hbm2DdlMode hbm2ddlAuto;

   @Property(doc = "Show SQL commands. By default not set.")
   private Boolean showSql;

   @Property(doc = "Additional properties to be passed to the entity manager factory.", complexConverter = Prop.Converter.class)
   private List<Prop> properties = Collections.EMPTY_LIST;

   @Property(doc = "Database", complexConverter = Database.Converter.class, optional = false)
   private Database database;

   @Property(doc = "Connection pool. Default is the native hibernate implementation.", complexConverter = ConnectionPoolConverter.class)
   private ConnectionPool connectionPool = new DefaultConnectionPool();

   @Property(doc = "Second level/query caching settings. By default not set.", complexConverter = Cache.Converter.class)
   private Cache cache;

   @Property(doc = "JTA transaction timeout, in seconds.")
   private int transactionTimeout = -1;

   protected JacamarHelper jacamarHelper = new JacamarHelper();
   protected JndiHelper jndiHelper = new JndiHelper();
   protected JtaHelper jtaHelper = new JtaHelper();

   private volatile boolean running;
   // we can have only one instance, since if Infinispan would be used as cache,
   // two cache managers would use the same JMX domain and fail with default configuration
   private EntityManagerFactory entityManagerFactory;

   @ProvidesTrait
   public Lifecycle getLifecycle() {
      return this;
   }

   @ProvidesTrait
   public JpaProvider getJpaProvider() {
      return this;
   }

   @ProvidesTrait
   public Transactional createTransactional() {
      return new HibernateOrm5Transactional(this, transactionTimeout);
   }

   @Override
   public void start() {
      try {
         if (connectionPool instanceof IronJacamar) {
            String datasourceDefinitions = ((IronJacamar) connectionPool).datasourceDefinitions;
            File datasources = File.createTempFile("datasources-", "-ds.xml");
            datasources.deleteOnExit();
            Files.write(datasources.toPath(), datasourceDefinitions.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            jacamarHelper.start(Collections.EMPTY_LIST, Collections.singletonList(datasources.toURI().toURL()));
         } else {
            jndiHelper.start();
            jtaHelper.start();
         }
         entityManagerFactory = createEntityManagerFactory();
         running = true;
      } catch (Throwable throwable) {
         throw new RuntimeException("Failed to start service", throwable);
      }
   }

   @Override
   public void stop() {
      try {
         if (entityManagerFactory != null && entityManagerFactory.isOpen()) {
            entityManagerFactory.close();
         }
         if (connectionPool instanceof IronJacamar) {
            jacamarHelper.stop();
         } else {
            jtaHelper.stop();
            jndiHelper.stop();
         }
         running = false;
      } catch (Throwable throwable) {
         throw new RuntimeException("Failed to stop service", throwable);
      }
   }

   @Override
   public boolean isRunning() {
      return running;
   }

   @Override
   public EntityManagerFactory getEntityManagerFactory() {
      return entityManagerFactory;
   }

   private EntityManagerFactory createEntityManagerFactory() {
      Map<String, Object> propertyMap = new HashMap<>();
      database.applyProperties(propertyMap);
      connectionPool.applyProperties(propertyMap, database);
      if (hbm2ddlAuto != null)
         propertyMap.put(AvailableSettings.HBM2DDL_AUTO, hbm2ddlAuto.getValue());
      if (showSql != null)
         propertyMap.put(AvailableSettings.SHOW_SQL, showSql.toString());
      if (flushMode != null)
         propertyMap.put("org.hibernate.flushMode", flushMode.toString());
      if (cache != null) {
         cache.applyProperties(propertyMap);
      } else {
         propertyMap.put(AvailableSettings.USE_SECOND_LEVEL_CACHE, Boolean.FALSE.toString());
      }
      for (Prop p : properties) {
         Object previous = propertyMap.put(p.name, p.value);
         if (previous != null && !previous.equals(p.value)) {
            log.warnf("Overriding property '%s' -> '%s' with '%s'", p.name, previous, p.value);
         }
      }
      List<Class<?>> entityClasses = new ArrayList<Class<?>>();
      Path pluginLibDir = Paths.get(Directories.PLUGINS_DIR.toString(), ArgsHolder.getCurrentPlugin(), "lib");
      for (File file : pluginLibDir.toFile().listFiles(new Utils.JarFilenameFilter())) {
         entityClasses.addAll(AnnotatedHelper.getClassesFromJar(file.toString(), Object.class, Entity.class, "org.radargun.jpa.entities."));
      }
      log.debug("Persistence properties: " + propertyMap);
      propertyMap.put(org.hibernate.jpa.AvailableSettings.LOADED_CLASSES, entityClasses);
      return Persistence.createEntityManagerFactory(persistenceUnit, propertyMap);
   }

   protected enum Hbm2DdlMode {
      @DocumentedValue("Validate the schema, make no changes to the database.")
      VALIDATE("validate"),
      @DocumentedValue("Update the schema.")
      UPDATE("update"),
      @DocumentedValue("Create the schema, destroying previous data.")
      CREATE("create"),
      @DocumentedValue("Drop the schema when the SessionFactory is closed.")
      CREATE_DROP("create-drop");

      private final String value;

      private Hbm2DdlMode(String value) {
         this.value = value;
      }

      public String getValue() {
         return value;
      }
   }

   @DefinitionElement(name = "property", doc = "Custom property to be passed to entity manager factory.")
   protected static class Prop {
      @Property(doc = "Name of the property", optional = false)
      public String name;

      @Property(doc = "Value of the property", optional = false)
      public String value;

      private static class Converter extends ReflexiveConverters.ListConverter {
         public Converter() {
            super(new Class[] { Prop.class });
         }
      }
   }

   protected interface Database {
      void applyProperties(Map<String, Object> properties);

      String getDriverClassName();

      String getUrl();

      String getDataSourceClassName();

      Map<String, String> getDataSourceProperties();

      public static class Converter extends ReflexiveConverters.ObjectConverter {
         public Converter() {
            super(Database.class);
         }
      }
   }

   @DefinitionElement(name = "h2", doc = "Connects to H2 database")
   protected static class H2 implements Database {
      @Property(doc = "URL of the database.")
      String url;

      @Override
      public void applyProperties(Map<String, Object> properties) {
         properties.put(AvailableSettings.DIALECT, H2Dialect.class.getName());
      }

      @Override
      public String getDriverClassName() {
         return org.h2.Driver.class.getName();
      }

      @Override
      public String getDataSourceClassName() {
         return org.h2.jdbcx.JdbcDataSource.class.getName();
      }

      @Override
      public Map<String, String> getDataSourceProperties() {
         return Collections.singletonMap("url", url);
      }

      @Override
      public String getUrl() {
         return url;
      }
   }

   protected static abstract class Postgres implements Database {
      @Property(doc = "Server address")
      String serverAddress;

      @Property(doc = "Server port")
      Integer serverPort;

      @Property(doc = "Database name")
      String database;

      public void applyProperties(Map<String, Object> properties) {
      }

      @Override
      public String getDriverClassName() {
         return org.postgresql.Driver.class.getName();
      }

      @Override
      public String getDataSourceClassName() {
         return org.postgresql.ds.PGSimpleDataSource.class.getName();
      }

      @Override
      public Map<String, String> getDataSourceProperties() {
         HashMap<String, String> map = new HashMap<>();
         map.put("ServerName", serverAddress);
         map.put("DatabaseName", database);
         if (serverPort != null) map.put("PortNumber", serverPort.toString());
         return map;
      }

      public String getUrl() {
         return "jdbc:postgresql://" + serverAddress + ':' + serverPort + '/' + database;
      }
   }

   @DefinitionElement(name = "postgres94", doc = "Connect to PostgreSQL database")
   protected static class Postgres94 extends Postgres {
      @Override
      public void applyProperties(Map<String, Object> properties) {
         super.applyProperties(properties);
         properties.put(AvailableSettings.DIALECT, PostgreSQL94Dialect.class.getName());
      }
   }

   protected static class ConnectionPoolConverter extends ReflexiveConverters.ObjectConverter {
      public ConnectionPoolConverter() {
         super(ConnectionPool.class);
      }
   }

   protected interface ConnectionPool {
      void applyProperties(Map<String, Object> properties, Database database);
   }

   @DefinitionElement(name = "default", doc = "Default connection pool, not recommended for production use.")
   protected static class DefaultConnectionPool implements ConnectionPool {
      @Override
      public void applyProperties(Map<String, Object> properties, Database database) {
         properties.put(AvailableSettings.DRIVER, database.getDriverClassName());
         properties.put(AvailableSettings.URL, database.getUrl());
      }
   }

   @DefinitionElement(name = "c3p0", doc = "C3P0 connection pool.")
   protected static class C3P0 implements ConnectionPool {
      @Property(doc = "Minimum pool size.")
      Integer minSize;

      @Property(doc = "Maximum pool size.")
      Integer maxSize;

      @Property(doc = "Timeout")
      Integer timeout;

      @Property(doc = "Maximum number of statements")
      Integer maxStatements;

      @Property(doc = "Idle test period.")
      Integer idleTestPeriod;

      @Override
      public void applyProperties(Map<String, Object> properties, Database database) {
         properties.put(AvailableSettings.CONNECTION_PROVIDER, C3P0ConnectionProvider.class.getName());
         properties.put(AvailableSettings.DRIVER, database.getDriverClassName());
         properties.put(AvailableSettings.URL, database.getUrl());

         if (minSize != null)
            properties.put("hibernate.c3p0.min_size", minSize.toString());
         if (maxSize != null)
            properties.put("hibernate.c3p0.max_size", maxSize.toString());
         if (timeout != null)
            properties.put("hibernate.c3p0.timeout", timeout.toString());
         if (maxStatements != null)
            properties.put("hibernate.c3p0.max_statements", maxStatements.toString());
         if (idleTestPeriod != null)
            properties.put("hibernate.c3p0.idle_test_period", idleTestPeriod.toString());
      }
   }

   @DefinitionElement(name = "hikari-cp", doc = "Hikari Connection Pool")
   protected static class HikariCP implements ConnectionPool {
      @Property(doc = "Test query executed to verify connection.")
      String connectionTestQuery;

      @Property(doc = "Maximum pool size.")
      Integer maxSize;

      @Override
      public void applyProperties(Map<String, Object> properties, Database database) {
         properties.put(AvailableSettings.CONNECTION_PROVIDER, HikariCPConnectionProvider.class.getName());
         properties.put("hibernate.hikari.dataSourceClassName", database.getDataSourceClassName());

         for (Map.Entry<String, String> entry : database.getDataSourceProperties().entrySet()) {
            properties.put("hibernate.hikari.dataSource." + entry.getKey(), entry.getValue());
         }
         if (connectionTestQuery != null)
            properties.put("hibernate.hikari.connectionTestQuery", connectionTestQuery);
         if (maxSize != null)
            properties.put("hibernate.hikari.maximumPoolSize", maxSize.toString());
      }
   }

   @DefinitionElement(name = "iron-jacamar", doc = "Iron Jacamar")
   protected static class IronJacamar implements ConnectionPool {
      @Property(doc = "Data source JNDI name.")
      String dataSourceJndi;

      @Property(doc = "Set if this DS implements JTA.")
      boolean jta;

      @Property(doc = "Definitions of datasources that will be deployed into Fungal.", optional = false,
         complexConverter = XmlConverter.class)
      String datasourceDefinitions;

      @Override
      public void applyProperties(Map<String, Object> properties, Database database) {
         if (jta) {
            properties.put(org.hibernate.jpa.AvailableSettings.JTA_DATASOURCE, dataSourceJndi);
         } else {
            properties.put(org.hibernate.jpa.AvailableSettings.NON_JTA_DATASOURCE, dataSourceJndi);
         }
      }
   }

   protected abstract static class Cache {
      @Property(doc = "Cache query results. By default not set.")
      private Boolean useQueryCache;

      @Property(doc = "Cache concurrency strategy. By default not set.")
      CacheConcurrencyStrategy cacheConcurrencyStrategy;

      @Property(doc = "Shared cache mode. By default not set.")
      private SharedCacheMode sharedCacheMode;

      public void applyProperties(Map<String, Object> properties) {
         properties.put(AvailableSettings.USE_SECOND_LEVEL_CACHE, Boolean.TRUE.toString());
         if (useQueryCache != null) {
            properties.put(AvailableSettings.USE_QUERY_CACHE, useQueryCache.toString());
         }
         if (cacheConcurrencyStrategy != null) {
            properties.put(AvailableSettings.DEFAULT_CACHE_CONCURRENCY_STRATEGY, cacheConcurrencyStrategy.toAccessType().getExternalName());
         }
         if (sharedCacheMode != null)
            properties.put(org.hibernate.jpa.AvailableSettings.SHARED_CACHE_MODE, sharedCacheMode.toString());
      }

      protected static class Converter extends ReflexiveConverters.ObjectConverter {
         public Converter() {
            super(Cache.class);
         }
      }
   }

   @DefinitionElement(name = "infinispan", doc = "Second-level cache implemented by Infinispan")
   protected static class InfinispanCache extends Cache {
      @Property(doc = "Infinispan configuration file.")
      String configuration;

      @Property(doc = "Mapping of classes to specific caches", complexConverter = ClassMapping.Converter.class)
      List<ClassMapping> classMappings;

      @Override
      public void applyProperties(Map<String, Object> properties) {
         super.applyProperties(properties);
         properties.put(AvailableSettings.CACHE_REGION_FACTORY, InfinispanRegionFactory.class.getName());
         if (configuration != null)
            properties.put(InfinispanRegionFactory.INFINISPAN_CONFIG_RESOURCE_PROP, configuration);
         for (ClassMapping mapping : classMappings) {
            properties.put("hibernate.cache.infinispan." + mapping.clazz + ".cfg", mapping.toCache);
         }
      }
   }

   @DefinitionElement(name = "mapping", doc = "Defines mapping specific map to certain cache.")
   protected static class ClassMapping {
      @Property(name = "class", doc = "Class name", optional = false)
      String clazz;

      @Property(doc = "Cache name", optional = false)
      String toCache;

      public static class Converter extends ReflexiveConverters.ListConverter {
         public Converter() {
            super(new Class[] { ClassMapping.class });
         }
      }
   }
}
