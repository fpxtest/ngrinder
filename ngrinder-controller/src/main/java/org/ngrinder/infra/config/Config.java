/*
 * Copyright (C) 2012 - 2012 NHN Corporation
 * All rights reserved.
 *
 * This file is part of The nGrinder software distribution. Refer to
 * the file LICENSE which is part of The nGrinder distribution for
 * licensing details. The nGrinder distribution is available on the
 * Internet at http://nhnopensource.org/ngrinder
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngrinder.infra.config;

import static org.ngrinder.common.constant.NGrinderConstants.DOWNLOAD_PATH;
import static org.ngrinder.common.constant.NGrinderConstants.PERF_TEST_PATH;
import static org.ngrinder.common.constant.NGrinderConstants.PLUGIN_PATH;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.annotation.PostConstruct;

import net.grinder.util.NetworkUtil;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.ngrinder.common.constant.NGrinderConstants;
import org.ngrinder.common.exception.ConfigurationException;
import org.ngrinder.common.model.Home;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.logger.CoreLogger;
import org.ngrinder.service.IConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;

/**
 * Spring component which is responsible to get the nGrinder configurations which is stored
 * ${NGRINDER_HOME}.
 * 
 * @author JunHo Yoon
 * @since 3.0
 */
@Component
public class Config implements IConfig {
	private static final String NGRINDER_DEFAULT_FOLDER = ".ngrinder";
	private static final Logger LOG = LoggerFactory.getLogger(Config.class);
	private Home home = null;
	private PropertiesWrapper internalProperties;
	private PropertiesWrapper systemProperties;
	private PropertiesWrapper databaseProperties;
	private static String versionString = "";
	private boolean verbose;
	private String currentIP;

	static final int NGRINDER_DEFAULT_CLUSTER_LISTENER_PORT = 40003;
	private boolean isCluster;
	private String clusterURIs;
	
	private String region;
	public static final String NON_REGION = "NONE";
	
	/**
	 * Make it singleton.
	 */
	Config() {
	}

	/**
	 * Initialize Config. This method mainly perform NGRINDER_HOME resolution and system properties
	 * load. In addition, Logger is initialized and default configuration file is copied into
	 * NGRINDER_HOME if it's the first
	 */
	@PostConstruct
	public void init() {
		try {
			home = resolveHome();
			copyDefaultConfigurationFiles();
			loadIntrenalProperties();
			loadSystemProperties();
			initLogger(isTestMode());
			currentIP = NetworkUtil.getLocalHostAddress("www.github.com", 80);
			CoreLogger.LOGGER.info("NGrinder is starting...");
			loadDatabaseProperties();
			versionString = getVesion();
			
			//check cluster
			//set cluster configuration foe ehcache
			loadClusterConfig();
			loadExtendProperties();

		} catch (IOException e) {
			throw new ConfigurationException("Error while loading NGRINDER_HOME", e);
		}
	}
	
	/**
	 * Initialize cache cluster configuration.
	 * 
	 */
	protected void loadClusterConfig() {
		String clusterUri = getSystemProperties().getProperty(NGrinderConstants.NGRINDER_PROP_CLUSTER_URIS, null);
		int clusterListenerPort = getSystemProperties().getPropertyInt(
				NGrinderConstants.NGRINDER_PROP_CLUSTER_LISTENER_PORT, NGRINDER_DEFAULT_CLUSTER_LISTENER_PORT);

		if (StringUtils.isBlank(clusterUri)) {
			return;
		}
		isCluster = true;
		String currentIP = NetworkUtil.getLocalHostAddress();
		String[] clusterUriList = StringUtils.split(clusterUri, ";");
		StringBuilder urisSB = new StringBuilder();
		for (String peerIP : clusterUriList) {
			// should exclude itself from the peer list
			if (urisSB.length() > 0) {
				urisSB.append("|");
			}
			if (!currentIP.equals(peerIP)) {
				urisSB.append("//").append(peerIP).append(":").append(clusterListenerPort);
				urisSB.append("/").append(NGrinderConstants.CACHE_NAME_DISTRIBUTED_MAP);
			}
		}

		if (StringUtils.isBlank(urisSB.toString())) {
			LOG.error("Invalid configuration for ehcache cluster:{}", clusterUri);
			isCluster = false;
			return;
		}
		clusterURIs = urisSB.toString();
		LOG.info("Cache cluster URIs:{}", clusterURIs);
		return;
		
		/*
		FactoryConfiguration peerProviderConfig = new FactoryConfiguration();
		peerProviderConfig.setClass(RMICacheManagerPeerProviderFactory.class.getName());
		StringBuilder peerPropSB = new StringBuilder("peerDiscovery=manual,rmiUrls=");
		peerPropSB.append(urisSB.toString());
		peerProviderConfig.setProperties(peerPropSB.toString());
		Configuration ehCacheConfig = ehcache.getConfiguration();
		ehCacheConfig.addCacheManagerPeerProviderFactory(peerProviderConfig);

		FactoryConfiguration peerListenerConfig = new FactoryConfiguration();
		peerListenerConfig.setClass(RMICacheManagerPeerListenerFactory.class.getName());
		String listenerPropStr = "port=" + NGRINDER_DEFAULT_CLUSTER_LISTENER_PORT;
		peerListenerConfig.setProperties(listenerPropStr);
		ehCacheConfig.addCacheManagerPeerListenerFactory(peerListenerConfig);
		
		Cache distCache = cacheManager.getCache(NGrinderConstants.CACHE_NAME_DISTRIBUTED_MAP);
		EhCacheCache distEhCache = (EhCacheCache) distCache;
		CacheConfiguration distCacheConfig = distEhCache.getNativeCache().getCacheConfiguration();

		// <bootstrapCacheLoaderFactory
		// class="net.sf.ehcache.distribution.RMIBootstrapCacheLoaderFactory"/>
		// when the cache is initialized, it will synchronize the ache with
		// peer.
		BootstrapCacheLoaderFactoryConfiguration bootConfig = new BootstrapCacheLoaderFactoryConfiguration();
		bootConfig.setClass(RMIBootstrapCacheLoaderFactory.class.getName());
		distCacheConfig.addBootstrapCacheLoaderFactory(bootConfig);

		// <cacheEventListenerFactory
		// class="net.sf.ehcache.distribution.RMICacheReplicatorFactory" />
		// this configuration makes sure the update on this cache will be
		// replecated to other peer
		CacheEventListenerFactoryConfiguration updateConfig = new CacheEventListenerFactoryConfiguration();
		updateConfig.setClass(RMICacheReplicatorFactory.class.getName());
		distCacheConfig.addCacheEventListenerFactory(updateConfig);
		*/
		 
	}
	
	/**
	 * Check whether the cache cluster is set.
	 * @return true is cache cluster set
	 */
	public boolean isCluster() {
		return this.isCluster;
	}
	
	/*
	 * return the cluster URIs in configuration.
	 */
	public String getClusterURIs() {
		return this.clusterURIs;
	}	
	
	protected void loadExtendProperties() {
		InputStream inputStream = null;
		Properties extProp = new Properties();
		try {
			inputStream = new ClassPathResource("/system-ex.conf").getInputStream();
			extProp.load(inputStream);
			String regionStr = extProp.getProperty(NGrinderConstants.NGRINDER_PROP_REGION, NON_REGION);
			region = regionStr.trim();
		} catch (IOException e) {
			LOG.error("Error while load system-ex.conf", e);
			region = NON_REGION;
		} finally {
			IOUtils.closeQuietly(inputStream);
		}
		
	}
	
	public String getRegion() {
		return region;
	}
	
	/**
	 * Initialize Logger.
	 * 
	 * @param forceToVerbose
	 *            force to verbose logging.
	 */
	public void initLogger(boolean forceToVerbose) {
		setupLogger((forceToVerbose) ? true : getSystemProperties().getPropertyBoolean("verbose", false));
	}

	/**
	 * Set up logger.
	 * 
	 * @param verbose
	 *            verbose mode?
	 */
	public void setupLogger(boolean verbose) {
		this.verbose = verbose;
		final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		final JoranConfigurator configurator = new JoranConfigurator();
		configurator.setContext(context);
		context.reset();
		context.putProperty("LOG_LEVEL", verbose ? "DEBUG" : "INFO");
		context.putProperty("LOG_DIRECTORY", getHome().getGloablLogFile().getAbsolutePath());
		try {
			configurator.doConfigure(new ClassPathResource("/logback/logback-ngrinder.xml").getFile());
		} catch (JoranException e) {
			CoreLogger.LOGGER.error(e.getMessage(), e);
		} catch (IOException e) {
			CoreLogger.LOGGER.error(e.getMessage(), e);
		}
	}

	/**
	 * Copy default files.
	 * 
	 * @throws IOException
	 *             occurs when there is no such a files.
	 */
	private void copyDefaultConfigurationFiles() throws IOException {
		checkNotNull(home);
		home.copyFrom(new ClassPathResource("ngrinder_home_template").getFile(), false);
		home.makeSubPath(PLUGIN_PATH);
		home.makeSubPath(PERF_TEST_PATH);
		home.makeSubPath(DOWNLOAD_PATH);
	}

	/**
	 * Resolve nGrinder home path.
	 * 
	 * @return resolved home
	 */
	private Home resolveHome() {
		String userHomeFromEnv = System.getenv("NGRINDER_HOME");
		String userHomeFromProperty = System.getProperty("ngrinder.home");
		if (StringUtils.isNotEmpty(userHomeFromEnv) && !StringUtils.equals(userHomeFromEnv, userHomeFromProperty)) {
			LOG.warn("The path to ngrinder-home is ambiguous:");
			LOG.warn("    System Environment:  NGRINDER_HOME=" + userHomeFromEnv);
			LOG.warn("    Java Sytem Property:  ngrinder.home=" + userHomeFromProperty);
			LOG.warn("    '" + userHomeFromProperty + "' is accepted.");
		}
		String userHome = null;
		userHome = StringUtils.defaultIfEmpty(userHomeFromProperty, userHomeFromEnv);
		File homeDirectory = (StringUtils.isNotEmpty(userHome)) ? new File(userHome) : new File(
						System.getProperty("user.home"), NGRINDER_DEFAULT_FOLDER);

		return new Home(homeDirectory);
	}

	/**
	 * Load internal properties which is not modifiable by user.
	 */
	protected void loadIntrenalProperties() {
		InputStream inputStream = null;
		Properties properties = new Properties();
		try {
			inputStream = new ClassPathResource("/internal.properties").getInputStream();
			properties.load(inputStream);
			internalProperties = new PropertiesWrapper(properties);
		} catch (IOException e) {
			LOG.error("Error while load internal.properties", e);
			internalProperties = new PropertiesWrapper(properties);
		} finally {
			IOUtils.closeQuietly(inputStream);
		}
	}

	/**
	 * Load database related properties. (database.conf)
	 * 
	 */
	protected void loadDatabaseProperties() {
		checkNotNull(home);
		Properties properties = home.getProperties("database.conf");
		properties.put("NGRINDER_HOME", home.getDirectory().getAbsolutePath());
		databaseProperties = new PropertiesWrapper(properties);
	}

	/**
	 * Load system related properties. (system.conf)
	 */
	public void loadSystemProperties() {
		checkNotNull(home);
		Properties properties = home.getProperties("system.conf");
		properties.put("NGRINDER_HOME", home.getDirectory().getAbsolutePath());
		systemProperties = new PropertiesWrapper(properties);
	}

	/**
	 * Get the database properties.
	 * 
	 * @return database properties
	 */
	public PropertiesWrapper getDatabaseProperties() {
		checkNotNull(databaseProperties);
		return databaseProperties;
	}

	/**
	 * Check if it's testmode.
	 * 
	 * @return true if test mode
	 */
	public boolean isTestMode() {
		return getSystemProperties().getPropertyBoolean("testmode", false);
	}

	/**
	 * Check if it's the security enabled mode.
	 * 
	 * @return true if security is enabled.
	 */
	public boolean isSecurityEnabled() {
		return !isTestMode() && getSystemProperties().getPropertyBoolean("security", false);
	}

	/**
	 * Check if plugin support is enabled. The reason why we need this configuration is that it
	 * takes time to initialize plugin system in unit test context.
	 * 
	 * @return true if plugin is supported.
	 */
	public boolean isPluginSupported() {
		return !isTestMode() && (getSystemProperties().getPropertyBoolean("pluginsupport", true));
	}

	/**
	 * Get the resolved home folder.
	 * 
	 * @return home
	 */
	public Home getHome() {
		return this.home;
	}

	/**
	 * Get the system properties.
	 * 
	 * @return {@link PropertiesWrapper} which is loaded from system.conf.
	 */
	public PropertiesWrapper getSystemProperties() {
		checkNotNull(systemProperties);
		return systemProperties;
	}

	/**
	 * Get nGrinder version number.
	 * 
	 * @return nGrinder version number. If not set, return "UNKNOWN"
	 */
	public String getVesion() {
		return getInternalProperties().getProperty("ngrinder.version", "UNKNOWN");
	}

	/**
	 * Policy file which determine the process and thread.
	 */
	private String policyScript = "";

	/**
	 * Get the content of "process_and_thread_policy.js" file.
	 * 
	 * @return file content.
	 */
	public String getProcessAndThreadPolicyScript() {
		if (StringUtils.isEmpty(policyScript)) {
			try {
				policyScript = FileUtils.readFileToString(getHome().getSubFile("process_and_thread_policy.js"));
				return policyScript;
			} catch (IOException e) {
				LOG.error("Error while load process_and_thread_policy.js", e);
				return "";
			}
		} else {
			return policyScript;
		}
	}

	/**
	 * Get the internal properties.
	 * 
	 * @return internal properties
	 */
	public PropertiesWrapper getInternalProperties() {
		return internalProperties;
	}

	/**
	 * Get nGrinder version in static way.
	 * 
	 * @return nGrinder version.
	 */
	public static String getVerionString() {
		return versionString;
	}

	/**
	 * Check if it's verbose logging mode.
	 * 
	 * @return true if verbose
	 */
	public boolean isVerbose() {
		return verbose;
	}

	public String getCurrentIP() {
		return currentIP;
	}

}
