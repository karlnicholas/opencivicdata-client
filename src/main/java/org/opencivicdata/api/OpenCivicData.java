package org.opencivicdata.api;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.logging.Logger;

import org.opencivicdata.data.BaseData;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;

/**
 * <pre>
 * OpenStates API. This is a fully static class so that
 * it gets initialized once and then any API class 
 * will not have to initialize it again. This
 * class is not thread-safe and is generally not for public
 * consumption. Acceptable methods are
 * 
 * getCache()
 * setCache()
 * suspendCache()
 * 
 * See below.
 *</pre>
 *
 */
public class OpenCivicData implements OpenCivicDataAPI {
	private static final Logger logger = Logger.getLogger(OpenCivicData.class.getName());
	public static final String apikeyKey = "apikey";
	public static final String cacheKey = "cache";
	public static final String apiServer = "api.opencivicdata.org";
	private static String apiKey;
	private static String cache;
    private static boolean checkCache;
    private static boolean suspendCache;
	private static ObjectMapper mapper;
	public static SimpleDateFormat dateFormat;
//	public static TreeMap<String, TreeNode> pluses;
//	public static TreeMap<String, TreeNode> newFields;

    /**
     * Initialize the API. Called instead of a constructor.
     *
     * @param bundle the bundle
     */
	public OpenCivicData(ResourceBundle bundle) throws OpenCivicDataException {
		// API not needed for testing
		if ( !bundle.containsKey(apikeyKey)) throw new OpenCivicDataException("No apikey found in opencivicdata.properties", null, null, null);
		apiKey = bundle.getString(apikeyKey);
		if ( apiKey == null ) throw new OpenCivicDataException("apikey not set in opencivicdata.properties", null, null, null);
		if ( bundle.containsKey(cacheKey)) {
			cache = bundle.getString(cacheKey);
			if ( cache.lastIndexOf('/') != (cache.length()-1)) cache = cache+"/";
			File cacheFile = new File(cache);
			logger.config("cache directory:" + cacheFile.toString());
			if ( !cacheFile.exists() ) {
				logger.config("Creating directories for cache:" + cacheFile.toString());
				cacheFile.mkdirs();
			}
		}
		checkCache = true;
		suspendCache = false;
		mapper = new ObjectMapper();
		mapper.addHandler(new MyDeserializationProblemHandler() );
		dateFormat = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" );
		mapper.setDateFormat( dateFormat );
		// arbitrary large size (2^20)
	}

	private static class MyDeserializationProblemHandler extends DeserializationProblemHandler {
		public boolean handleUnknownProperty(
			DeserializationContext ctxt,
	        JsonParser jp,
	        JsonDeserializer<?> deserializer,
	        Object beanOrClass,
	        String propertyName) throws IOException, JsonProcessingException 
	    {
			if ( propertyName.charAt(0) == '+' ) {
				if ( beanOrClass instanceof BaseData ) {
					BaseData base = (BaseData)beanOrClass; 
					if ( base.pluses == null ) base.pluses = new TreeMap<String, TreeNode>();
					base.pluses.put(propertyName, jp.readValueAsTree());
				} else {
					throw new RuntimeException("beanOrClass type unknown");
				}
			} else {
				if ( beanOrClass instanceof BaseData ) {
					BaseData base = (BaseData)beanOrClass; 
					if ( base.additionalFields == null ) base.additionalFields = new TreeMap<String, TreeNode>();
					base.additionalFields.put(propertyName, jp.readValueAsTree());
				} else {
					throw new RuntimeException("beanOrClass type unknown");
				}
			}
//			ctxt.getParser().skipChildren();
			return true;
	    }
	}
	
	/**
	 * Don't use unless you really want to change the API key. It should be set in a properties file, 
	 * but, it could be done this way as well.
	 * @param apiKey
	 */
	public static void setAPIKey(String apiKey) {
		OpenCivicData.apiKey = apiKey;
	}
	
	/**
	 * Modify whether or not the cache is first checked for files.
	 * Note that any JSON read will always be written to the
	 * cache.
	 *
	 * @param checkCache the check cache
	 */
	public static void setCache(boolean checkCache) {
		if ( OpenCivicData.checkCache != checkCache ) logger.fine("Changing checkCache setting to:" + checkCache );
		OpenCivicData.checkCache = checkCache;
	}

	/**
	 * Get the current state of the caching flag
	 * 
	 * @return the current state of the caching flag
	 */
	public static boolean getCache() {
		return OpenCivicData.checkCache;
	}
	
	/**
	 * Disable caching for one call, and one call only.
	 * 
	 * This call disables caching for the next call 
	 * regardless of the current state of caching. It 
	 * does not disable the current state of caching.  
	 * 
	 */
	public static void suspendCache() {
		suspendCache = true;
	}

	/**
	 * Handles the actual API calls and caching.
	 * 
	 *  This is part of a static class and therefore is not thread-safe. This method 
	 *  is not for public consumption.
	 *   
	 */
	public <T> T query(MethodMap methodMap, ArgMap argMap, Class<T> responseType ) throws OpenCivicDataException {
 		BufferedReader reader = null;
		HttpURLConnection conn = null;
		String charSet = "utf-8";
		try {
			if ( isCaching(methodMap, argMap) ) {
				File file = getCacheFile(methodMap, argMap);

				long fileLength = file.length(); 
				logger.fine("Length of File in cache:" + fileLength + ": " + file.getName());
				if ( fileLength == 0L ) {
					OpenCivicData.cacheFileFromAPI(methodMap, argMap, responseType, file);
				}
				reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), charSet));
			} else {
				conn = OpenCivicData.getConnectionFromAPI(methodMap, argMap);
			    // better check it first
			    if (conn.getResponseCode() / 100 != 2) {
			    	String msg = conn.getResponseMessage();
			    	conn.disconnect();
			    	throw new OpenCivicDataException(msg, methodMap, argMap, responseType);
			    }
				charSet = getCharset(conn);
				reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), charSet));
			}
			
        	return mapper.readValue( reader, responseType );
		} catch (JsonParseException e) {
			throw new OpenCivicDataException(e, methodMap, argMap, responseType);
		} catch (JsonMappingException e) {
			throw new OpenCivicDataException(e, methodMap, argMap, responseType);
		} catch (URISyntaxException e) {
			throw new OpenCivicDataException(e, methodMap, argMap, responseType);
		} catch (IOException e) {
			throw new OpenCivicDataException(e, methodMap, argMap, responseType);
		} finally {
			suspendCache = false;
			if ( conn != null ) conn.disconnect();
			if ( reader != null ) {
				try {
					reader.close();
				} catch (IOException e) {
					throw new OpenCivicDataException(e, methodMap, argMap, responseType);
				}
			}
		}
	}
	
	private boolean isCaching(MethodMap methodMap, ArgMap argMap) {
		if ( OpenCivicData.cache == null ) return false;
		if ( OpenCivicData.suspendCache == true ) return false;
		if ( OpenCivicData.checkCache == false ) return false;
		return true;
	}
	
	private static File getCacheFile(MethodMap methodMap, ArgMap argMap) throws UnsupportedEncodingException {
		StringBuilder filename = new StringBuilder( OpenCivicData.cache );
		// There is always a method
		for (String key: methodMap )
		{
			filename.append( URLEncoder.encode(key, "UTF-8") );
			filename.append('.');
		}
		// trim off last char
		filename.deleteCharAt(filename.length()-1);
		// do args if any
		if ( argMap!= null ) {
			for (String key: argMap.keySet() )
			{
				String value = argMap.get(key);
				if ( value == null ) continue;
				filename.append('.');
				filename.append( key );
				filename.append('.');
				filename.append( URLEncoder.encode(value, "UTF-8") );
			}
		} 
		filename.append(".json" );

		// return a file object
		return new File(filename.toString());
	}
	
	private static void cacheFileFromAPI(MethodMap methodMap, ArgMap argMap, Class<?> responseType, File file) throws URISyntaxException, IOException, OpenCivicDataException {
		BufferedReader breader = null;
		BufferedWriter bwriter = null;
		HttpURLConnection conn = null;
		try {
		    char[] buffer = new char[262144];
			conn = getConnectionFromAPI(methodMap, argMap);
		    // better check it first
		    if (conn.getResponseCode() / 100 != 2) {
		    	String msg = conn.getResponseMessage();
		    	conn.disconnect();
		    	throw new OpenCivicDataException(msg, methodMap, argMap, responseType);
		    }
			String charSet = getCharset(conn);
			breader = new BufferedReader(new InputStreamReader( conn.getInputStream(), charSet ) );
			bwriter = new BufferedWriter( new OutputStreamWriter( new FileOutputStream(file), Charset.forName("utf-8")) );
			int read;
			while ( (read = breader.read(buffer)) != -1 ) {
				bwriter.write(buffer, 0, read);
			}
		} finally {
			if ( conn != null ) conn.disconnect();
			if ( bwriter != null ) bwriter.close();
			if ( breader != null ) breader.close();
		}
	}
	
	private static HttpURLConnection getConnectionFromAPI(MethodMap methodMap, ArgMap argMap) throws URISyntaxException, IOException, OpenCivicDataException {
	    HttpURLConnection con = null;
		// There is always a method
		StringBuilder method = new StringBuilder("/");
		for (String key: methodMap )
		{
			method.append(key);
			method.append('/');
		}
		// trim off last char
//		method.deleteCharAt(method.length()-1);

		StringBuilder terms = new StringBuilder();
		// Iterate through the keys and their values, both are important
		if ( argMap != null ) {
			for (String key: argMap.keySet() )
			{
				String value = argMap.get(key);
				if ( value == null ) continue;
				terms.append( '&' );
				terms.append( key );
				terms.append('=' );
				terms.append( value);
			}
		}
			
		// construct the URI ..
		URI uri = new URI(
			"http", 
			null,
			apiServer, 
			-1,
			method.toString(), 
			"apikey=" + apiKey + terms.toString(), 
			null
		);
		logger.fine(uri.toString());
		
		con = (HttpURLConnection) uri.toURL().openConnection();
	    con.setRequestMethod("GET");
	    con.setRequestProperty("Accept", "text/json, application/json");
	    con.connect();
	    return con;
	}
	
	private static String getCharset(HttpURLConnection con) throws OpenCivicDataException, IOException {
	    String contentType = con.getHeaderField("Content-Type");
	    String charset = null;
	    for (String param : contentType.replace(" ", "").split(";")) {
	        if (param.startsWith("charset=")) {
	            charset = param.split("=", 2)[1];
	            break;
	        }
	    }
	    if ( charset == null ) {
	    	logger.fine("Defaulting to utf-8 charset");
	    	charset = "utf-8";
	    }
	    return charset;
	}
}