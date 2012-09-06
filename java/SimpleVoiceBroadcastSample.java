import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import com.callfire.api.data.AnsweringMachineConfig;
import com.callfire.api.data.Call;
import com.callfire.api.data.Result;
import com.callfire.api.data.ToNumber;
import com.callfire.api.data.VoiceBroadcastConfig;
import com.callfire.api.service.wsdl.CallServicePortType;
import com.callfire.api.service.wsdl.ServiceFault;
import com.callfire.api.service.wsdl.TextServicePortType;
import com.callfire.api.service.wsdl.http.soap12.CallFireApi;
import com.callfire.api.service.xsd.ActionQuery;
import com.callfire.api.service.xsd.CallQueryResult;
import com.callfire.api.service.xsd.CreateSound;
import com.callfire.api.service.xsd.CreateSound.RecordingCall;
import com.callfire.api.service.xsd.SendCall;

/**
 * Example implementation of using CallFire's APIs to send
 * a simple voice broadcast to multiple contacts.
 * 
 * @author ross
 */
public class SimpleVoiceBroadcastSample {
	private static final Logger LOG = Logger.getLogger(SimpleVoiceBroadcastSample.class.getName());
	
	private static final File TEST_LIVE_SOUND_FILE = new File("../callfire-api-samples/src/resources/test-sound.mp3");
	private static final File TEST_MACHINE_SOUND_FILE = new File("../callfire-api-samples/src/resources/test-sound.mp3");
	
	private final String user;
	private final String password;
	private final int maxWaitMs;
	
	private final CallFireApi callFireApi;
	private final CallServicePortType callService;
	private final PropertiesConfiguration propertiesConfig;
	
	private SimpleVoiceBroadcastSample() throws ConfigurationException {
		propertiesConfig = new PropertiesConfiguration("sample.properties");
		user = propertiesConfig.getString("user");
		password = propertiesConfig.getString("password");
		maxWaitMs = propertiesConfig.getInt("max_wait_ms");
		callFireApi = new CallFireApi(user, password);
		callService = callFireApi.getCallServicePort();
	}
	/**
	 * @param txtMsg
	 * @param toNumbers
	 * @param fromNumber
	 * @throws ServiceFault
	 */
	private long sendVoiceBroadcastUsingCallService(List<Object> toNumbers, String fromNumber) throws ServiceFault {
		long liveSoundId = createSound(TEST_LIVE_SOUND_FILE);
		long machineSoundId = createSound(TEST_MACHINE_SOUND_FILE);

		// send a single-call voice broadcast
		VoiceBroadcastConfig config = new VoiceBroadcastConfig();
		config.setAnsweringMachineConfig(AnsweringMachineConfig.AM_AND_LIVE);
		config.setLiveSoundId(liveSoundId);
		config.setMachineSoundId(machineSoundId);
		config.setFromNumber(fromNumber);

		Long broadcastId = 0L;
		SendCall sendCall = new SendCall();
		sendCall.setBroadcastName("Simple Voice Broadcast");
		sendCall.setVoiceBroadcastConfig(config);
		
		for (Object toNumber : toNumbers) {
			ToNumber toNumberElem = new ToNumber();
			toNumberElem.setValue((String)toNumber);
			sendCall.getToNumber().add(toNumberElem);
		}

		broadcastId = callService.sendCall(sendCall);

		return broadcastId;
	}

	/**
	 * @param broadcastId
	 * @param toNumbers
	 * @return true if Result.SENT is received for each specified toNumber.
	 */
	private boolean pollForResponse(long broadcastId, List<Object> toNumbers) throws ServiceFault {
		LOG.info("polling for call status");
		
		final int sleepInterval = 2000;
		int totalWait = 0;

		ActionQuery callQuery = new ActionQuery();
		callQuery.setBroadcastId(broadcastId);

		while (totalWait < maxWaitMs) {
			try {
				Thread.sleep(sleepInterval);
				totalWait += sleepInterval;
			}
			catch (InterruptedException e) {
				LOG.log(Level.FINE, "interrupted", e);
			}
			
			CallQueryResult result = callService.queryCalls(callQuery);
			List<Call> callList = result.getCall();
			if (callList.isEmpty()) {
				throw new RuntimeException("call query did not return a call");
			}

			for (Call call : callList) {
				Result finalResult = call.getFinalResult();
				String toNumber = call.getToNumber().getValue();
				if (finalResult != null) {
					if (!finalResult.equals(Result.UNDIALED)) {
						toNumbers.remove(toNumber);
						LOG.info("found toNumber: " + toNumber);
						if (toNumbers.isEmpty()) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}
	
	private long createSound(File soundFile) throws ServiceFault {
		CreateSound sound = new CreateSound();
		sound.setName("some sound");
		
		DataSource source = new FileDataSource(soundFile);
		DataHandler dataHandler = new DataHandler(source);
		
		sound.setData(dataHandler);

		long soundId = callService.createSound(sound);
		return soundId;
	}
	
	/**
	 * Exercises sending a simple voice broadcast to multiple contacts.
	 * @throws Exception
	 */
	public void run() throws Exception {
		
		List<Object> toNumbers1 = propertiesConfig.getList("contacts_batch_1");
		
		long broadcastId = sendVoiceBroadcastUsingCallService(toNumbers1, "12132212289");
		
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
			new SimpleVoiceBroadcastSample().run();
		}
		catch (Exception e) {
			LOG.log(Level.SEVERE, "exception running " + SimpleVoiceBroadcastSample.class.getName(), e);
		}
	}
}
