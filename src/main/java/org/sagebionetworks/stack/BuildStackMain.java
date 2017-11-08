package org.sagebionetworks.stack;

import org.sagebionetworks.stack.ssl.SSLSetup;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.log4j.Logger;
import org.sagebionetworks.stack.alarms.ElbAlarmSetup;
import org.sagebionetworks.stack.alarms.RdsAlarmSetup;
import org.sagebionetworks.stack.config.InputConfiguration;
import org.sagebionetworks.stack.factory.AmazonClientFactory;
import org.sagebionetworks.stack.factory.AmazonClientFactoryImpl;
import org.sagebionetworks.stack.notifications.EnvironmentInstancesNotificationSetup;
import org.sagebionetworks.stack.notifications.StackInstanceNotificationSetup;
import org.sagebionetworks.stack.ssl.ACMSetup;
import org.sagebionetworks.stack.util.Sleeper;
import org.sagebionetworks.stack.util.SleeperImpl;

/**
 * The main class to start the stack builder
 * @author John
 *
 */
public class BuildStackMain {

	private static Logger log = Logger.getLogger(BuildStackMain.class.getName());
	/**
	 * This is the main script that builds the configuration.
	 * @param args
	 */
	public static void main(String[] args) {
		SleeperImpl sleeper = new SleeperImpl();
		
		try{
			// Log the args.
			logArgs(args);
			// The path to the configuration property file must be provided
			Properties input = null;
			if(args != null && args.length ==1 && args[0] != null && !"".equals(args[0].trim())) {
				String pathConfig = args[0];
				// Load the properties file
				input = loadPropertiesFromPath(pathConfig);
			}else{
				// Load the properties from the System Properties.
				input = System.getProperties();
			}
			// Load the configuration
			buildStack(input, new AmazonClientFactoryImpl(), sleeper);

		}catch(Throwable e){
			log.error("Terminating: ",e);
		}finally{
			log.info("Terminating stack builder\n\n\n");
			System.exit(0);
		}
	}

	/**
	 * @param pathConfig
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	public static GeneratedResources buildStack(Properties inputProps, AmazonClientFactory factory, Sleeper sleeper) throws FileNotFoundException, IOException, InterruptedException {
		// First load the configuration properties.
		InputConfiguration config = new InputConfiguration(inputProps);
		// Set the credentials
		factory.setCredentials(config.getAWSCredentials());
		// Load the default properties used for this stack
		Properties defaultStackProperties =  new StackDefaults(factory.createS3Client(), config).loadStackDefaultsFromS3();
		// Add the default properties to the config
		// Note: This is where all encrypted properties are also added.
		config.addPropertiesWithPlaintext(defaultStackProperties);
		
		// Collect all of the resources generated by this build
		GeneratedResources resources = new GeneratedResources();
		
		// Since the search index can take time to setup, we buid it first.
		new SearchIndexSetup(factory, config, resources, sleeper).setupResources();
		
		// Setup the Route53 CNAMEs
		new Route53Setup(factory, config, resources).setupResources();
		
		// The first step is to setup the stack security
		new EC2SecuritySetup(factory, config, resources).setupResources();
		
		// Setup the notification topic.
		new StackInstanceNotificationSetup(factory, config, resources).setupResources();
		new EnvironmentInstancesNotificationSetup(factory, config, resources).setupResources();
		
		// Setup the Database Parameter group
		new DatabaseParameterGroup(factory, config, resources).setupResources();
		
		// Setup all of the database security groups
		new DatabaseSecuritySetup(factory, config, resources).setupResources();
		
		// We are ready to create the database instances
		new MySqlDatabaseSetup(factory, config, resources, sleeper).setupResources();
		
		// Add all of the the alarms
		new RdsAlarmSetup(factory, config, resources).setupResources();
				
		// Create the configuration file and upload it S3
		new StackConfigurationSetup(factory, config, resources).setupResources();
		
		// Process the artifacts
		new ArtifactProcessing(new DefaultHttpClient(), factory, config, resources).processArtifacts();
		
		// Setup the SSL certificates
		//new SSLSetup(factory, config, resources).setupResources();
		
		// Gather ACM ARNs
		new ACMSetup(factory, config, resources).setupResources();
		
		// Setup all environments
		new ElasticBeanstalkSetup(factory, config, resources).setupResources();
		
		// Setup the alarm for unhealthy instances on load balancer
		new ElbAlarmSetup(factory, config, resources, sleeper).setupResources();
		
		// Return all of the generated objects
		return resources;
	}
	
	/**
	 *  Load the properties from the passed path.
	 * 
	 * @param pathConfig
	 * @return
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private static Properties loadPropertiesFromPath(String pathConfig)
			throws FileNotFoundException, IOException {
		// Load the config file
		File configFile = new File(pathConfig);
		if(!configFile.exists()) throw new IllegalArgumentException("Passed configuration file does not exist: "+configFile.getAbsolutePath());
		FileInputStream in =  new FileInputStream(configFile);
		try{
			Properties props = new Properties();
			props.load(in);
			return props;
		}finally{
			in.close();
		}
	}
	
	/**
	 * Write the arguments to the log.
	 * @param args
	 */
	private static void logArgs(String[] args) {
		log.info("Starting Stack Builder...");
		if(args !=null){
			log.info("args[] length="+args.length);
			for(String arg: args){
				log.info(arg);
			}
		}
	}

}
