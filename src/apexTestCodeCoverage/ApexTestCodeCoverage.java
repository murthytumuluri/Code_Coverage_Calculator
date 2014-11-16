/**
 * This application uses the SFDC tooling API to retrieve 
 * class code coverage settings in a CSV format which can
 * easily be imported in tools such as Excel
 * 
 * The application uses the Partner WSDL to login to SFDC and then
 * uses the Rest Tooling API to retrieve information.
 * 
 * Leon de Beer
 * Senior Technical Solutions Architect
 * March 2014
 * (c) Salesforce.com
 */
package apexTestCodeCoverage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.text.*;
import java.util.Date;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectorConfig;
import com.sforce.ws.ConnectionException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.nio.charset.Charset;
import java.util.*;

/**
 * @author ldebeer
 *
 */
public class ApexTestCodeCoverage {

	static 	String	userIdEndpoint	=	"";		//	Where to authenticate
	static 	String	accessToken = "";			//	SessionID
	static 	String	instance_url = "";			//	Instance URL 
	static 	String	toolingRestURL = "";		//	Rest URL to be used
	static	int		debugLevel=0;
	
	static	boolean	moreRestBatchRecords	=	false;	//	Use for batched replies where not all records fit in one reply
	static	String	nextRestBatchURL		=	"";		//	see moreRestBatchRecords
	
	static	Map<String, String> classNameMap = new HashMap<String, String>();	//	Map Class ID to Name
	static	Map<String, String> classIDMap   = new HashMap<String, String>();	//	Map Class Name to ID
	
	static	String	userName="";				//	Username from command Line
	static	String	password="";				//	Password from command line
	static 	String	authEndPoint="";			//	Auth Point from command line
	
	static	String	applicationName		=	"ApexTestCodeCoverage";	//	Used for help and how-to
	static	String	applicationVersion	=	"0.1";					//	Used for help and how-to
	
	static	String	outputPath			=	"./";
	static	String	csvFile				=	"";	//	File we are creating
	static	String	argClassNameList	=	"";	//	List of classes we found on the command line
	
	static	String	runDate				=	"";
	static	boolean	firstCSVRecord		=	true;
	static	boolean	buildErd			=	false;
	
	static	PartnerConnection partnerConnection = null;	//	
	
	/**
	 * @param args
	 * 
	 * Main program entry. Expects to find a few parameters to make sure
	 * we can connect to the correct org.
	 * 
	 */
	public static void main(String[] args)  {
		
		//	Analyse cmd parameters
		
		boolean	showUsage = false;
		for (int i=0;i<args.length;i++) {
			String	thisArg = args[i];
			if (thisArg.length() < 2) {	//	we need at least "-?"
				showUsage = true;
				continue;
			}
			if (!thisArg.substring(0,1).equals("-")) {
				showUsage = true;
				continue;
			}
			
			char	thisOption	=	thisArg.toLowerCase().charAt(1);
			/*
			 * -v Version
			 * -d Debug level (0=none)
			 * -u User Name
			 * -p Password
			 * -o Output Path
			 * -a Authentication URL
			 * -c List of Class names (if specified specific information on classes rather than overal code coverage is provided)
			 * -h help
			*/
			String	thisArgValue	=	"";
			if (thisArg.length() > 2) {
				thisArgValue	=	thisArg.substring(2);
			}
			switch (thisOption) {
			
				case('v'):	System.out.println(applicationName + " Version: " + applicationVersion);
							System.exit(0);
							break;
							
				case('d'):	debugLevel	=	Integer.valueOf(thisArgValue);
							break;
				
				case('u'):	userName	=	thisArgValue;
							break;
				
				case('p'):	password	=	thisArgValue;
							break;
				
				case('a'):	authEndPoint =	thisArgValue;
							break;
							
				case('o'):	outputPath = thisArgValue;
							break;
				
				case('c'):	argClassNameList	=	thisArgValue;
							break;
				
				case('h'):	showUsage = true;
							break;

				case('e'):	buildErd = true;
							break;
							
				default:	showUsage = true;
							break;
			
			}			
		}
		
		if (showUsage) {
			ShowUsage();
			System.exit(0);
		}
		
		//	Login using Soap
		
		if (!soapLogin()) {
			System.err.println("Login '" + userName + "' endpoint: '" + authEndPoint + "' failed");
			System.exit(1);
		}
		
		/*
		 * The oAuth login in this application has been disabled and the code remains for sample
		 * purposes only. Advantage of using Soap is to avoid having to configure the org with
		 * a connected app first. Soap will work on any org with configuration changes.
		 * 
		try {
			oAuthLogin(authEndPoint,
							userName,
							password, 
							"",
							"");
			//	salesforceLogin("http://login.salesforce.com","","","","");
		} catch (IOException e) {}
		*/

		/*
		 * We'll create a filename which consists of the login and date so we know which
		 * org this data belongs to and we never loose and overwrite it.
		 */
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
		Date date 	= 	new Date();
		runDate		=	dateFormat.format(date);

		//	execAnonymousApex("System.debug('Leon')");
		//	execAnonymousApex("System.debug(Schema.getGlobalDescribe())");
		//	describeSalesforceObject("");		
		//	describeSalesforceObject("Account");
		
		if (buildErd) {
			csvFile		=	outputPath + File.separatorChar +"ERD-" + userName + "-" + runDate + ".csv";
			listDataModel();			
		}
		else {
			csvFile		=	outputPath + File.separatorChar + userName + "-" + runDate + ".csv";
		
			/*
			 * Populate a map with class and trigger names. Used for display purposes.
			 */
			
			populateClassMap(false);
			populateClassMap(true);		
			
			//	Obtain code coverage results
			
			
			/*
			 * if argClassNameList is set it will contain a list of all classes
			 * for which we want to see detailed test information. That means 
			 * a list with which test classes it's called from and how much that 
			 * test class contributes to the overall test code percentage.
			 * 
			 * If that's not set - we just get the aggregated results
			 */
			
			if (argClassNameList.length()  > 0) {
				csvFile = "detail-" + csvFile;
				getDetailedCodeCoverage();
			}
			else {
				/*
				 * This call just gets the aggregated results out and sticks them in a CSV file
				 */
				getAggregateCodeCoverage();
			}
		}
		
		System.out.println("Done - Created file: " + csvFile);		
		System.exit(0);
	}

	/*
	 * Show how this program should be used
	 */
	public static	void ShowUsage()
	{
		System.out.println(applicationName + " Version: " + applicationVersion);
		System.out.println("Usage: " + applicationName + " -h<help> -c<ClassName,ClassName> -a<authEndPoint> -u<UserName> -p<Password> -v<Version> -o<Output Path>");
		System.out.println("Example: " + applicationName + " -ahttps://login.salesforce.com -uuser@domain.com -pmysecret");
	}
	/*
	 * Login to Salesforce using oAuth
	 * Check this out: http://www.mkyong.com/java/apache-httpclient-examples/
	 * https://www.salesforce.com/us/developer/docs/api_streaming/Content/code_sample_auth_oauth.htm
	 * 
	 * Requires an oAuth connected application to be configured first
	 */
	
	public static	boolean oAuthLogin(	String loginHost, String username,
	        							String password, String clientId, String secret) throws IOException 
	{
		
		//	First get the oAuth Token using the parameters passed on this URL
		
		HttpClient client  = HttpClientBuilder.create().build();
		loginHost += "/services/oauth2/token";
		showDebugMessage(3,"loginHost: " + loginHost);
		HttpPost post = new HttpPost(loginHost);		 
 
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("grant_type", "password"));
		urlParameters.add(new BasicNameValuePair("username", username));
		urlParameters.add(new BasicNameValuePair("password", password));
		urlParameters.add(new BasicNameValuePair("client_id", clientId));
		urlParameters.add(new BasicNameValuePair("client_secret", secret));
	    //	oauthPost.setEntity(new UrlEncodedFormEntity(parametersBody, HTTP.UTF_8));

        boolean	loginOK = true;
        
		post.setEntity(new UrlEncodedFormEntity(urlParameters));
	 
		HttpResponse response = client.execute(post);
	    int code = response.getStatusLine().getStatusCode();
	    
	    if (code != 200) {	//	ToDo - Need to check this
	    	loginOK = false;
	    	return loginOK;
	    }
	    showDebugMessage(5,"OAuth login response code: " + code);
	    
        JSONObject oauthLoginResponse = null;
        
		try {
			oauthLoginResponse = (JSONObject) JSONValue.parseWithException(EntityUtils.toString(response.getEntity()));
			dumpJSONObject("OAuth response: ", oauthLoginResponse);
		} catch (org.apache.http.ParseException e) {
			loginOK = false;
			e.printStackTrace();
		} catch (ParseException e) {
			loginOK = false;
			e.printStackTrace();
		}
				
		//	Setup the global parameters for future use
		
		try {	//	Catch any exceptions and regard them as a failure
		    userIdEndpoint = (String) oauthLoginResponse.get("id");
		    accessToken = (String) oauthLoginResponse.get("access_token");
		    instance_url = (String) oauthLoginResponse.get("instance_url");
		} catch (Exception e) {
			loginOK = false;
		}
	    
		//	Get the user info out - just because it's fun
	    
		List<NameValuePair> userInfoParameters = new ArrayList<NameValuePair>();
		userInfoParameters.add(new BasicNameValuePair("oauth_token", accessToken));

		Charset utf8charset = Charset.forName("UTF-8");
	    String queryString = URLEncodedUtils.format(userInfoParameters, utf8charset);	    
	    
	    HttpGet userInfoRequest = new HttpGet(userIdEndpoint + "?" + queryString);
	    HttpResponse userInfoResponse = client.execute(userInfoRequest);
	    code = userInfoResponse.getStatusLine().getStatusCode();
	    System.out.println("UserInfo reasonse code: " + code);
	    
        JSONObject userInfoJSONResponse = null;
		try {
			userInfoJSONResponse = (JSONObject) JSONValue.parseWithException(EntityUtils.toString(userInfoResponse.getEntity()));
			// dumpJSONObject("UserInfo response: ", (JSONObject) userInfoJSONResponse.get("status"));
			dumpJSONObject("UserInfo response: ", userInfoJSONResponse);
		} catch (org.apache.http.ParseException e) {
			e.printStackTrace();
			System.err.print("Invalid JSON object received on oAuth authentication");
			System.exit(1);
		} catch (ParseException e) {
			e.printStackTrace();
			System.err.print("Invalid JSON object received on oAuth authentication");
			System.exit(1);
		}
	    
	    return loginOK;
		
	}
	
	/*
	 * By using the Soap Login - we have no need to make any configuration changes to the target Or
	 * The Bearer token uses in our REST Api accepts both the oAuth authentication token and Soap the
	 * Soap Session ID so they are interchangeable.
	 * 
	 * To get this to work you need to ensure that the a partner wsdl and wcl jar files are linked in 
	 * to this project. The function simply returns true or false based on the login result
	 * 
	 */
	private static boolean soapLogin() {
		
		boolean loginOK = false;
		try {
			ConnectorConfig config = new ConnectorConfig();
			config.setUsername(userName);
			config.setPassword(password);
			config.setAuthEndpoint(authEndPoint + "/services/Soap/u/29.0");
			if (debugLevel > 1) {
				config.setTraceFile("traceLogs.txt");
				config.setTraceMessage(true);
				config.setPrettyPrintXml(true);
			}
			else {
				config.setTraceMessage(false);
			}
			partnerConnection = new PartnerConnection(config);
			loginOK = true;
			
			accessToken	=	config.getSessionId();
			
			//	We need to strip the getServiceEndPoint down now to remove all the soap specific
			//	stuff so we can use it later to make REST calls.
			
			String	tmpString		=	config.getServiceEndpoint();
			instance_url			=	tmpString.substring(0,tmpString.indexOf("/services"));
			
		} catch (ConnectionException ce) {
			loginOK = false;
			ce.printStackTrace();
		} catch (FileNotFoundException fnfe) {
			loginOK = false;
			fnfe.printStackTrace();
		}
		//	System.out.println("Success: " + success + " - PartnerConnection: " + partnerConnection);
		return loginOK;
	}
	
	/*
	 * Get ApexCodeCoverageAggregate code coverage
	 * Showing the code coverage per object on an agregated level as opose to using the detail returned by ApexCodeCoverage
	 */
	
	public static void getAggregateCodeCoverage() {
		
		//	This code has been tested with API version 29. Do not try to use
		//	anything earlier as the ApexCodeCoverage objects aren't supported!
		
		toolingRestURL 	= instance_url + "/services/data/29.0/tooling/";
		String	restURL			=	"";		
		String	query			=	"";
		int		classCounter	=	0;
		
		//	Read the Apex test coverage results
		
		query 	= "Select+ApexClassorTriggerId,NumLinesCovered,NumLinesUncovered,Coverage+from+ApexCodeCoverageAggregate";
		restURL = instance_url + "/services/data/v29.0/tooling/query/?q=" + query; 
		
		do {
			
			if (moreRestBatchRecords) {
				restURL		=	instance_url + nextRestBatchURL;
				moreRestBatchRecords = false;
			}
			
			showDebugMessage(5,"Using URL: " + restURL + "\n");
				
			JSONObject toolingAPIResponse = salesforceRestCall(restURL);
			//	String recordCount = toolingAPIResponse.get("size").toString();					
			JSONArray recordObject = (JSONArray) toolingAPIResponse.get("records");				
			for (int i = 0; i < recordObject.size(); ++i) {
				classCounter++;
				//	The object below is one record from the ApexCodeCoverage object
			    JSONObject rec = (JSONObject) recordObject.get(i);
			    
			    int coveredLines 		=	rec.get("NumLinesCovered") != null ? Integer.valueOf((String) rec.get("NumLinesCovered").toString()) : 0;
			    int unCoveredLines 		=	rec.get("NumLinesUncovered") != null ? Integer.valueOf((String) rec.get("NumLinesUncovered").toString()) : 0;
			    String apexTestClassID 	=	(String) rec.get("ApexClassOrTriggerId").toString();	
			    writeCSVFile(classCounter + ",'" + apexTestClassID + "','" + getApexClassName(apexTestClassID) + "'," + coveredLines + "," + unCoveredLines + "," + (coveredLines+unCoveredLines) );
			}

		}
		while (moreRestBatchRecords);
	}
	
	/*
	 * Call the Tooling API e.g.
	 * http://na1.salesforce.com/services/data/v28.0/tooling/sobjects/
	 * 
	 * http://www.mkyong.com/webservices/jax-rs/restful-java-client-with-apache-httpclient/
	 */
	public static void getDetailedCodeCoverage() {
		
		String[] classArray 	=	argClassNameList.split(",");
		String	whereClause		=	"";
		String	whereString		=	"";
		for (int i=0;i<classArray.length;i++) {
			if (classIDMap.containsKey(classArray[i])) {
				whereString = "";
				if (whereClause.length() > 0) {
					whereString = "%2C";
				}
				whereString +=	"'" + classIDMap.get(classArray[i]) + "'";
				whereClause +=	whereString;
			}
		}
		if (whereClause.length() == 0) {
			ShowUsage();
			System.out.println("No valid classnames found in : " + argClassNameList);
			System.exit(1);
		}
		
		
		
		//	This code has been tested with API version 29. Do not try to use
		//	anything earlier as the ApexCodeCoverage objects aren't supported!
		
		toolingRestURL 	= instance_url + "/services/data/29.0/tooling/";
		String	restURL			=	"";
		String	query			=	"";
		int		classCounter	=	0;
		
		//	Read the Apex test coverage results
		
		query 	= "Select+ApexClassOrTriggerId,ApexTestClassId,NumLinesCovered,NumLinesUncovered,Coverage+from+ApexCodeCoverage+" +
				  "where+ApexClassorTriggerId+in+(" + whereClause + ")";
		restURL = instance_url + "/services/data/v29.0/tooling/query/?q=" + query;	
		
		do {
			
			if (moreRestBatchRecords) {
				restURL		=	instance_url + nextRestBatchURL;
				moreRestBatchRecords = false;
			}
			JSONObject toolingAPIResponse = salesforceRestCall(restURL);
			JSONArray recordObject = (JSONArray) toolingAPIResponse.get("records");				
			for (int i = 0; i < recordObject.size(); ++i) {
				classCounter++;
				//	The object below is one record from the ApexCodeCoverage object
			    JSONObject rec = (JSONObject) recordObject.get(i);
			    
			    int coveredLines 			=	Integer.valueOf((String) rec.get("NumLinesCovered").toString());
			    int unCoveredLines 			=	Integer.valueOf((String) rec.get("NumLinesUncovered").toString());
			    String apexTestClassID 		=	(String) rec.get("ApexTestClassId").toString();	
			    String ApexClassorTriggerId	=	(String) rec.get("ApexClassOrTriggerId").toString();
			    
			    writeCSVFile(	classCounter + ",'" + ApexClassorTriggerId + "','" + getApexClassName(ApexClassorTriggerId) + "','" + 
			    				apexTestClassID + "','" + getApexClassName(apexTestClassID) + "'," + 
			    				coveredLines + "," + unCoveredLines + "," + (coveredLines+unCoveredLines) );
			}
						
		}
		while (moreRestBatchRecords);
	}
	
	/*
	 * Populate a list with Triggers and Class Names
	 * We actually create 2 maps one which allows you to look up a Name from an ID (classNameMap)
	 * the other to lookup an ID from a Name (classIDMap)
	 */
	public static void populateClassMap(boolean readTriggers)
	{
		
		String	query 	= "SELECT+ID,Name+FROM+ApexClass";
		String	prefix 	= " (Class)";
		
		//	We use the same class to read 2 objects..
		
		if (readTriggers){
			query 	= "SELECT+ID,Name+FROM+ApexTrigger"; 
			prefix  = " (Trigger)";
		}
		
		String	restURL = instance_url + "/services/data/v29.0/tooling/query/?q=" + query;
		
		showDebugMessage(5,"Using URL: " + restURL + "\n");
		
		do {

			if (moreRestBatchRecords) {
				restURL		=	instance_url + nextRestBatchURL;
			}
			moreRestBatchRecords = false;

			JSONObject toolingAPIResponse = salesforceRestCall(restURL);			
			int recordCount = Integer.valueOf(toolingAPIResponse.get("size").toString());
			if (recordCount > 0) {
				JSONArray recordObject = (JSONArray) toolingAPIResponse.get("records");				
				for (int i = 0; i < recordObject.size(); ++i) {
					//	The object below is one record from the ApexCodeCoverage object
				    JSONObject rec = (JSONObject) recordObject.get(i);
				    String	ID		=	rec.get("Id").toString();
				    String	Name	=	rec.get("Name").toString() + prefix;
				    classNameMap.put(ID, Name);
				    classIDMap.put(rec.get("Name").toString(),ID);
				    //	System.out.println("Added: " + ID + " - " + Name);
				}
			}
								
		}	while (moreRestBatchRecords);
		
		return;
	}
	/*
	 * Get Class Name from the our Array
	 */
	public static String getApexClassName(String classID)
	{
		String	className = "Unknown";
		if (classNameMap.containsKey(classID)) {
			className = classNameMap.get(classID);
		}
		return className;
	}
	/*
	 * Make Rest Call using a generic method which does all the error handling
	 * this method will also set / unset the moreRecords variable and URL 
	 * used to point to further (batched) records
	 */
	public static JSONObject	salesforceRestCall(String restURL) {
		
		showDebugMessage(1,"Get call to: "+ restURL);
		
        JSONObject returnJSONObject = null;
		try {
			HttpClient httpClient  = HttpClientBuilder.create().build();
			HttpGet getRequest = new HttpGet(restURL);
			getRequest.addHeader("accept", "application/json"); 
			getRequest.addHeader("Authorization", "Bearer "+ accessToken); 
			HttpResponse response = httpClient.execute(getRequest);
			
			if (response.getStatusLine().getStatusCode() != 200) {
				System.out.println("Invalid status code received on SFDC Request on " + restURL + "\n" + response);
				System.exit(1);
			}
			
			try {
				returnJSONObject = (JSONObject) JSONValue.parseWithException(EntityUtils.toString(response.getEntity()));
								
				moreRestBatchRecords		=	false;
				
				if (returnJSONObject.containsKey("nextRecordsUrl")) {
					nextRestBatchURL			= 	returnJSONObject.get("nextRecordsUrl").toString();
					moreRestBatchRecords		=	true;
				}

			} catch (org.apache.http.ParseException e) {
				e.printStackTrace();
				System.err.println("Invalid JSON block recieved");
				System.exit(1);
			} catch (ParseException e) {
				System.err.println("Invalid JSON block recieved");
				e.printStackTrace();
				System.exit(1);
			}
				
		} catch (ClientProtocolException e) {			  
			System.err.println("Protocol exception");
			e.printStackTrace();		 
			System.exit(1);
		} catch (IOException e) {		 
			System.err.println("IO Exception");
		  	e.printStackTrace();
			System.exit(1);
		}	
		
		if (returnJSONObject == null) {
			System.err.println("Invalid JSON data received");
			System.exit(1);
		}
		return	returnJSONObject;
	}
	
	/*
	 * Display JSON Object by key / value pairs
	 */
	public static void dumpJSONObject(String description,JSONObject jObject)
	{
		
		return;	//	If objects are too big - it kills your JVM
		
		/*
		if (debugLevel < 3) {
			return;
		}
		
		System.out.println("dumpJSONObject - " + description);
		System.out.println("jsonObject: " + jObject);
	    Set<String> keys = jObject.keySet();
	    Iterator<String> a = keys.iterator();
	    while(a.hasNext()) {
	        String key = (String)a.next();
	        System.out.print("key : "+key + ": " + jObject.get(key) + "\n");
	    }	
		return;
		*/
		
	}
	
	/*
	 * Show Debug Messages
	 */

	public static void showDebugMessage(int requiredDebugLevel,String message)
	{
		if (debugLevel < requiredDebugLevel) {
			return;
		}
		System.out.println(message);
	}
	/*
	 * Write record to the CVS file
	 */
	public static void writeCSVFile(String record)
	{		
		System.out.println(record);
        try {
            //	File file = new File(csvFile);
            BufferedWriter output = new BufferedWriter(new FileWriter(csvFile,true));
            if (firstCSVRecord) {
            	output.write(runDate + "\n");
            }
            output.write(record + "\n");
            output.close();
            firstCSVRecord = false;
          } catch ( IOException e ) {
             e.printStackTrace();
          }
	}
	/*
	 * The tooling API allows us to run anonymous Apex on an org which can be very useful for all sorts of things
	 * This procedure does just that.
	 */
	
	public	static	String	execAnonymousApex(String apexStatements)
	{
		toolingRestURL 	= instance_url + "/services/data/v29.0/tooling/executeAnonymous/?anonymousBody=" + apexStatements + "%3B";
		JSONObject	x = salesforceRestCall(toolingRestURL);
		System.out.println("Anonymous: " + x);
		return "";
	}
	
	/*
	 * Create a printable version of the ERD
	 */
	public static	void 	listDataModel()
	{
		
		Map<String, JSONObject> objectMap = new HashMap<String, JSONObject>();
		
		JSONObject objectArray = describeSalesforceObject("");
		JSONArray recordObject = (JSONArray) objectArray.get("sobjects");		
		if (recordObject == null) {
			return;
		}
		
	    boolean	showAndStop	=	false;
	    
		for (int i = 0; i < recordObject.size(); ++i) {
		    JSONObject thisObject = (JSONObject) recordObject.get(i);
		    String	objectName		=	(String) thisObject.get("name").toString();
		    String	objectLabel		=	(String) thisObject.get("label").toString();
		    boolean updateable 		= 	stringToBoolean((String) thisObject.get("updateable").toString());
		    boolean triggerable		=	stringToBoolean((String) thisObject.get("triggerable").toString());
		    boolean customSetting 	= 	stringToBoolean((String) thisObject.get("custom").toString());
		    
		    if (objectName.contains("__Share")) {
		    	//	showAndStop = true;
		    }
		    
		    if (showAndStop) {
		    	System.out.println("showAndStop - thisObject: " + thisObject);
		    	System.exit(1);
		    }
		    
		    if ((triggerable) && (!customSetting)) {
		    	//	System.out.println("Object: "  + objectName + " queryable " + queryable + " custom " + customSetting);
		    	
		    	//	System.out.println("**** OBJECT: " + objectName);
		    	
				JSONObject thisObjectStructure = describeSalesforceObject(objectName);
				if (thisObjectStructure != null) {
					objectMap.put(objectName, thisObjectStructure);
			    	//	System.out.println("thisObject: "  + thisObjectStructure);
					JSONArray fieldObject = (JSONArray) thisObjectStructure.get("fields");		
					if (fieldObject == null) {
						// System.out.println("No fields");
						continue;
					}
					
					for (int f = 0; f < fieldObject.size(); ++f) {
					    JSONObject thisFieldObject = (JSONObject) fieldObject.get(f);
					    String	fieldName		=	(String) thisFieldObject.get("name").toString();
					    String  fieldLabel		=	(String) thisFieldObject.get("label").toString();
					    String  fieldType		=	(String) thisFieldObject.get("type").toString();
					    String 	referenceTo		=	"";
					    
					    if (fieldType.equalsIgnoreCase("reference")) {
					    	referenceTo			=	"'" + (String) thisFieldObject.get("referenceTo").toString() + "'";
					    }
					    
					    boolean	fieldUpdateable	=	stringToBoolean((String) thisFieldObject.get("updateable").toString());
					    boolean	customField		=	stringToBoolean((String) thisFieldObject.get("custom").toString());
					    
					    //	Clean the reference to field
					    /*
					    referenceTo		=	referenceTo.replaceAll("[", "");
					    referenceTo		=	referenceTo.replaceAll("]", "");
					    referenceTo		=	referenceTo.replaceAll("\"", "");
					    */
					    
					    String	csvRecord		=	"";
					    csvRecord +=	"\"" + objectName + "\",";
					    csvRecord +=	"\"" + objectLabel + "\",";
					    csvRecord +=	"\"" + fieldName + "\",";
					    csvRecord +=	"\"" + fieldLabel + "\",";
					    csvRecord +=	"\"" + fieldType + "\",";
					    csvRecord +=	"\"" + referenceTo + "\"";
					    
					    writeCSVFile(csvRecord);
					    
					    //	if 9
					    
					    if (showAndStop) {
					    	System.out.println("showAndStop - thisFieldObject: " + thisFieldObject);
					    	System.exit(1);
					    }
					    
					    /*
					    // if (fieldUpdateable) {
					    	System.out.print(fieldName + ", " + fieldType);
					    	if (customField) {
					    		System.out.print(" (" + fieldLabel + ")");
					    	}
					    	System.out.print("\n");
					    //
					     */
					}
			    	
				}
		    }
		}
	}
	/*
	 * Describe an object using the Rest API. If it's called without a parameter
	 * It will show return a list of objects
	 */
	public 	static	JSONObject describeSalesforceObject(String objectName) {

		toolingRestURL 	= instance_url + "/services/data/v29.0/sobjects/";
		if (objectName.length() > 0) {
			toolingRestURL += objectName + "/describe/";		
		}
		JSONObject	returnValue = salesforceRestCall(toolingRestURL);
		//	System.out.println("DescribeObject " + objectName + "\n" + returnValue);
		return	returnValue;
	}
	
	/*
	 * Convert a string to a Boolean
	 */
	public 	static boolean stringToBoolean (String stringValue) {
		if (stringValue.equalsIgnoreCase("true")) {
			return true;
		}
		return false;
	}
}
 