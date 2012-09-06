import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import com.callfire.api.data.Result;
import com.callfire.api.data.Text;
import com.callfire.api.data.TextBroadcastConfig;
import com.callfire.api.data.ToNumber;
import com.callfire.api.service.wsdl.ServiceFault;
import com.callfire.api.service.wsdl.TextServicePortType;
import com.callfire.api.service.wsdl.http.soap12.CallFireApi;
import com.callfire.api.service.xsd.ActionQuery;
import com.callfire.api.service.xsd.SendText;
import com.callfire.api.service.xsd.TextQueryResult;

/**
 * Example implementation of using CallFire's APIs to send
 * a simple text broadcast to multiple contacts.
 * 
 * @author ross
 */
public class SimpleTextBroadcastSample {
	private static final Logger LOG = Logger.getLogger(SimpleTextBroadcastSample.class.getName());
	
	private final String user;
	private final String password;
	private final int maxWaitMs;
	
	private final CallFireApi callFireApi;
	private final TextServicePortType textService;
	private final PropertiesConfiguration propertiesConfig;
	
	public SimpleTextBroadcastSample() throws ConfigurationException {
		propertiesConfig = new PropertiesConfiguration("sample.properties");
		user = propertiesConfig.getString("user");
		password = propertiesConfig.getString("password");
		maxWaitMs = propertiesConfig.getInt("max_wait_ms");
		callFireApi = new CallFireApi(user, password);
		textService = callFireApi.getTextServicePort();
	}
	
	/**
	 * @param txtMsg
	 * @param toNumbers
	 * @param fromNumber
	 * @throws ServiceFault 
	 */
	private long sendTextUsingTextService(String txtMsg, List<Object> toNumbers, String fromNumber) throws ServiceFault {
		SendText sendText = new SendText();
		for (Object toNumber : toNumbers) {
			ToNumber toNumberElem = new ToNumber();
			toNumberElem.setValue((String)toNumber);
			sendText.getToNumber().add(toNumberElem);
		}
		
		TextBroadcastConfig config = new TextBroadcastConfig();
		config.setMessage(txtMsg);
		if (fromNumber != null) {
			config.setFromNumber(fromNumber);
		}
		
		sendText.setTextBroadcastConfig(config);

		return textService.sendText(sendText);
	}

	/**
	 * @param broadcastId
	 * @param toNumbers
	 * @return true if Result.SENT is received for each specified toNumber.
	 */
	public boolean pollForResponse(long broadcastId, List<Object> toNumbers) {
		LOG.info("polling for text status");
		
		final int sleepInterval = 3000;
		int totalWait = 0;
		
		ActionQuery textQuery = new ActionQuery();
		textQuery.setBroadcastId(broadcastId);
		
		while (totalWait < maxWaitMs) {
			try {
				Thread.sleep(sleepInterval);
				totalWait += sleepInterval;
			}
			catch (InterruptedException e) {
				LOG.log(Level.FINE, "interrupted", e);
			}
			
			try {
				TextQueryResult result = textService.queryTexts(textQuery);
				List<Text> textList = result.getText();
				if (textList.isEmpty()) {
					throw new RuntimeException("call query did not return a text");
				}
				
				for (Text text : textList) {
					Result finalResult = text.getFinalResult();
					if (finalResult != null) {
						if (finalResult.equals(Result.SENT)) {
							String toNumber = text.getToNumber().getValue();
							toNumbers.remove(toNumber);
							LOG.info("found toNumber: " + toNumber);
							if (toNumbers.isEmpty()) {
								return true;
							}
						}
					} 
				}
			}
			catch (ServiceFault e) {
				throw new RuntimeException("exception querying for texts", e);
			}
		}
		return false;
	}
	
	/**
	 * Exercises sending a simple text broadcast to multiple contacts.
	 * @throws Exception
	 */
	public void run() throws Exception {
		String textMessage = "this is just a test...";

		List<Object> toNumbers1 = propertiesConfig.getList("contacts_batch_1");
		
		long broadcastId = sendTextUsingTextService(textMessage, toNumbers1, null);
		
		boolean response = pollForResponse(broadcastId, toNumbers1);
		
		if (!response) {
			LOG.log(Level.WARNING, "did not receive postive reponse.");
		}
		else {
			LOG.log(Level.INFO, "received postive reponse.");
		}
	}
	
	public static void main(String[] args) {
		try {
			new SimpleTextBroadcastSample().run();
		}
		catch (Exception e) {
			LOG.log(Level.SEVERE, "exception running " + SimpleTextBroadcastSample.class.getName(), e);
		}
	}
}
